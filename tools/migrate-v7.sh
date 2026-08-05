#!/bin/sh
set -eu

apk=${1:-DAAK-NODE.apk}
serial=${DAAK_DEVICE_SERIAL:-}
remove_legacy=${REMOVE_LEGACY_PACKAGE:-0}

[ -f "$apk" ] || { printf 'APK not found: %s\n' "$apk" >&2; exit 1; }
command -v adb >/dev/null 2>&1 || { printf 'adb is required.\n' >&2; exit 1; }

scrcpy_server=${SCRCPY_SERVER_JAR:-}
if [ -z "$scrcpy_server" ]; then
    for candidate in \
        /opt/homebrew/opt/scrcpy/share/scrcpy/scrcpy-server \
        /usr/local/opt/scrcpy/share/scrcpy/scrcpy-server
    do
        if [ -f "$candidate" ]; then scrcpy_server=$candidate; break; fi
    done
fi
[ -f "$scrcpy_server" ] || {
    printf 'scrcpy 4.1 server not found. Install scrcpy or set SCRCPY_SERVER_JAR.\n' >&2
    exit 1
}

if [ -z "$serial" ]; then
    serial=$(adb devices | awk '$2 == "device" && $1 !~ /:5555$/ {print $1; exit}')
fi
[ -n "$serial" ] || { printf 'No authorized USB Android device found.\n' >&2; exit 1; }

adb_device="adb -s $serial"
$adb_device shell "su -c 'id'" | grep -q 'uid=0(root)' || {
    printf 'Root access is required for the one-time private-state migration.\n' >&2
    exit 1
}

legacy_present=0
if $adb_device shell pm path com.firat.node >/dev/null 2>&1; then legacy_present=1; fi

$adb_device install -r "$apk"
$adb_device shell am start -n com.daak.node/.MainActivity -a android.intent.action.MAIN >/dev/null
sleep 2

if [ "$legacy_present" -eq 1 ]; then
    $adb_device shell am force-stop com.firat.node
    $adb_device shell am force-stop com.daak.node
    $adb_device shell "su -c '
        backup=\"/sdcard/Documents/DAAK-Vault/System Backups\"
        mkdir -p \"\$backup\"
        cp /data/user/0/com.firat.node/shared_prefs/firat_node_private.xml \"\$backup/daak-node-private-v6-legacy.xml\"
        cp /data/user/0/com.firat.node/files/config.properties \"\$backup/daak-node-private-config-v6-legacy.properties\"
        cp /data/user/0/com.firat.node/shared_prefs/firat_node_private.xml /data/user/0/com.daak.node/shared_prefs/daak_node_private.xml
        cp /data/user/0/com.firat.node/files/config.properties /data/user/0/com.daak.node/files/config.properties
        uid=\$(stat -c %u /data/user/0/com.daak.node)
        chown -R \"\$uid:\$uid\" /data/user/0/com.daak.node
        restorecon -RF /data/user/0/com.daak.node
    '"
fi

for permission in \
    android.permission.READ_CALENDAR \
    android.permission.READ_EXTERNAL_STORAGE \
    android.permission.WRITE_EXTERNAL_STORAGE \
    android.permission.ACCESS_COARSE_LOCATION \
    com.termux.permission.RUN_COMMAND
do
    $adb_device shell pm grant com.daak.node "$permission"
done
$adb_device shell appops set com.daak.node android:write_settings allow
$adb_device shell settings put global policy_control 'immersive.navigation=*'
$adb_device shell cmd notification allow_listener com.daak.node/com.daak.node.MailNotificationListener
service=com.daak.node/.NodeControlAccessibilityService
enabled=$($adb_device shell settings get secure enabled_accessibility_services | tr -d '\r')
case ":$enabled:" in
    *":$service:"*)
        enabled=$(printf '%s' "$enabled" |
            sed -e "s#$service:##" -e "s#:$service##" -e "s#^$service\$##")
        ;;
esac
# One UI may reject a newly installed service when it is appended behind an
# already-bound mapper. Bind DAAK first, then restore the other services.
$adb_device shell settings put secure accessibility_enabled 0
sleep 1
$adb_device shell settings put secure enabled_accessibility_services "$service"
$adb_device shell settings put secure accessibility_enabled 1
sleep 1
case "$enabled" in
    null|'') ;;
    *) $adb_device shell settings put secure enabled_accessibility_services "$service:$enabled" ;;
esac
$adb_device shell cmd package set-home-activity com.daak.node/.MainActivity
$adb_device shell am start -n com.daak.node/.MainActivity -a android.intent.action.MAIN >/dev/null

# Install the narrow root queue and the pinned scrcpy virtual-display runtime.
# scrcpy's server stays outside the APK and is used only for the short-lived,
# off-screen Keenetic task; the official app retains its own OAuth material.
$adb_device push companion/termux/keenetic-wol /data/local/tmp/daak-keenetic-wol >/dev/null
$adb_device push companion/magisk/daak-app-cleaner.sh /data/local/tmp/daak-app-cleaner.sh >/dev/null
$adb_device push "$scrcpy_server" /data/local/tmp/daak-scrcpy-server.jar >/dev/null
$adb_device shell "su -c '
    cp /data/local/tmp/daak-keenetic-wol /data/adb/daak-keenetic-wol
    cp /data/local/tmp/daak-app-cleaner.sh /data/adb/daak-app-cleaner.sh
    cp /data/local/tmp/daak-scrcpy-server.jar /data/adb/daak-scrcpy-server.jar
    chmod 700 /data/adb/daak-keenetic-wol /data/adb/daak-app-cleaner.sh
    chmod 644 /data/adb/daak-scrcpy-server.jar
    old=\$(cat /data/adb/daak-app-cleaner.pid 2>/dev/null || printf 0)
    [ -n \"\$old\" ] || old=0
    case \"\$old\" in *[!0-9]*) old=0 ;; esac
    [ \"\$old\" -le 1 ] || kill \"\$old\" 2>/dev/null || true
    /data/adb/daak-app-cleaner.sh
'"

$adb_device shell dumpsys package com.daak.node | grep -q 'versionName=7.0.0'
$adb_device shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
    -c android.intent.category.HOME | grep -q 'com.daak.node/.MainActivity'
$adb_device shell settings get secure enabled_accessibility_services | \
    grep -q 'com.daak.node/.NodeControlAccessibilityService'
$adb_device shell "su -c 'test -r /data/adb/daak-scrcpy-server.jar'"

if [ "$legacy_present" -eq 1 ] && [ "$remove_legacy" = 1 ]; then
    $adb_device shell cmd notification disallow_listener \
        com.firat.node/com.firat.node.MailNotificationListener >/dev/null 2>&1 || true
    $adb_device uninstall com.firat.node
fi

printf 'DAAK NODE v7 migration verified on %s.\n' "$serial"
if [ "$legacy_present" -eq 1 ] && [ "$remove_legacy" != 1 ]; then
    printf 'Legacy package retained for rollback. Re-run with REMOVE_LEGACY_PACKAGE=1 after validation.\n'
fi

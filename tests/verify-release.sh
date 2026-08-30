#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$root"

pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1" >&2; exit 1; }

git diff --check
pass "git diff hygiene"

sh -n build-apk.sh tools/migrate-v7.sh tools/install-daak-find.sh companion/macos/DAAK\ Find.command companion/macos/daak-phone companion/macos/daak-find companion/magisk/daak-sshd-firewall.sh companion/magisk/daak-app-cleaner.sh companion/magisk/daak-gateway-watchdog.sh companion/magisk/daak-power-reserve.sh companion/magisk/daak-location-privacy.sh companion/magisk/daak-location-privacy-boot.sh companion/magisk/install-daak-gateway.sh companion/magisk/install-bixby-codex.sh companion/termux/daak-find companion/termux/daak-find-boot companion/termux/daak-selftest companion/termux/keenetic-wol companion/termux/rm-os-sync-daemon companion/termux/rm-os-sync-boot tests/device-app-sweep.sh tests/device-ui-sweep.sh tests/power-reserve-policy.sh
pass "shell syntax"

tests/power-reserve-policy.sh >/dev/null
pass "Power Reserve safety transitions"

python3 tests/daak-find-policy.py >/dev/null
pass "Google-free DAAK Find location policy"

python3 tests/mya-disk-policy.py >/dev/null
pass "MYA disk path and key-only SSH policy"

grep -q 'android:canRequestFilterKeyEvents="true"' res/xml/node_control_accessibility.xml || fail "Bixby key event capability"
grep -q 'KEYCODE_SAMSUNG_BIXBY = 1082' src/com/daak/node/NodeControlAccessibilityService.java || fail "Samsung Bixby key mapping"
grep -q 'EXTRA_CODEX_STANDALONE' src/com/daak/node/MainActivity.java || fail "projectless Codex intent"
pass "Bixby key opens projectless Codex through the biometric gate"

grep -q 'com.andrerinas.openheadunit.ACTION_START_SELF_MODE' src/com/daak/node/MainActivity.java || \
    fail "Android Auto Self Mode action"
grep -q 'androidAutoButton' src/com/daak/node/MainActivity.java || fail "Android Auto logo button"
grep -q 'ANDROID_AUTO' src/com/daak/node/MainActivity.java || fail "Android Auto touch action"
grep -q 'com.andrerinas.openheadunit.ACTION_DISCONNECT' \
    src/com/daak/node/NodeControlAccessibilityService.java || \
    fail "Android Auto rotation disconnect action"
grep -q 'armAndroidAutoRotation' src/com/daak/node/MainActivity.java || \
    fail "Android Auto orientation monitor arm"
grep -q 'restartAndroidAutoAfterRotation' src/com/daak/node/NodeControlAccessibilityService.java || \
    fail "background Android Auto orientation reconnect"
grep -q 'ANDROID_AUTO_ROTATION_DEBOUNCE_MS' src/com/daak/node/NodeControlAccessibilityService.java || \
    fail "Android Auto rotation debounce"
grep -q 'getDefaultDisplay().getRotation()' src/com/daak/node/NodeControlAccessibilityService.java || \
    fail "Android Auto rotation monitor uses physical display rotation"
pass "home Android Auto button uses direct Self Mode and background-safe rotation reconnect"

grep -q 'MotionEvent.ACTION_MOVE' src/com/daak/node/NodeControlAccessibilityService.java || \
    fail "bottom-edge gesture handles movement before system cancellation"
grep -q 'WindowManager.LayoutParams.MATCH_PARENT, dp(24)' \
    src/com/daak/node/NodeControlAccessibilityService.java || \
    fail "bottom-edge gesture capture height"
pass "bottom-edge gesture is cancellation-safe"

python3 -c 'import ast, pathlib; [ast.parse(pathlib.Path(path).read_text()) for path in ("companion/termux/lolile-preview", "companion/termux/lolile-books-sync", "companion/termux/mya-list", "companion/termux/mya-preview", "companion/termux/mya-fetch", "companion/termux/daak-airplay", "companion/termux/rm-os-sync-once")]'
pass "private disk and AirPlay bridge syntax"

grep -q 'MYA_DISK_ROOT = "sftp://mya-l11/ServerShare"' src/com/daak/node/MainActivity.java || \
    fail "MYA disk root"
grep -q 'DISK_SOURCE' src/com/daak/node/MainActivity.java || fail "private disk source selector"
grep -q 'DAAK-MYA' src/com/daak/node/KurekFileProvider.java || fail "MYA download provider"
pass "dual private-disk UI integration"

grep -q '"LIVE", "DAAK YAYIN", "BROADCAST"' src/com/daak/node/MainActivity.java || \
    fail "broadcast control tile"
grep -q 'kai@kais-macbook-pro.*remoteCommand' src/com/daak/node/MainActivity.java || \
    fail "fixed rootless broadcast bridge"
grep -q 'StrictHostKeyChecking=yes' src/com/daak/node/MainActivity.java || \
    fail "pinned M3 host-key enforcement"
grep -q 'ssh-keygen -q -t ed25519' src/com/daak/node/MainActivity.java || \
    fail "rootless S9 control-key bootstrap"
if rg -q 'daak-broadcast " \+ command' src/com/daak/node/MainActivity.java; then
    fail "legacy broadcast shortcut dependency"
fi
pass "S9 broadcast deck exposes four fixed rootless M3 actions"

if rg -qi 'airpipe' AndroidManifest.xml src README.md companion; then
    fail "paid AirPipe dependency removed"
fi
pass "paid AirPipe dependency absent"

if rg -n 'RememberBridge\.(pushOrQueue|flushPending|sendSelected)' \
        src/com/daak/node/MailNotificationListener.java >/dev/null; then
    fail "notification capture attempted automatic daakREMEMBER export"
fi
rg -q 'clearLegacyAutomaticQueue' src/com/daak/node/MainActivity.java || \
    fail "legacy private notification queue cleanup"
rg -q 'AĞA GÖNDER' src/com/daak/node/MainActivity.java || \
    fail "explicit per-item network share UI"
pass "mail and WhatsApp remain phone-local until explicit per-item share"

rg -q '"DISK_SOURCE_MYA"' src/com/daak/node/MainActivity.java || \
    fail "visible Intel Mac disk source"
rg -q 'INTEL MAC.*MYA-L11' src/com/daak/node/MainActivity.java || \
    fail "Intel Mac identity on home and disk UI"
rg -q 'intelMacServices' src/com/daak/node/MainActivity.java || \
    fail "live Intel Mac service badges"
rg -Fq 'services.optBoolean("mail")' src/com/daak/node/MainActivity.java || \
    fail "mail service badge is status-backed"
rg -q 'MAIL SERVICE @ MYA' src/com/daak/node/MainActivity.java || \
    fail "mail stack is a separate service entity from Intel Mac"
rg -q 'readIntelMailServices' src/com/daak/node/MainActivity.java || \
    fail "mail stack has independent live status"
pass "Intel Mac, its hosted mail stack, and private disks are separate live entities"

rg -q 'simPinSubmitted' src/com/daak/node/NodeControlAccessibilityService.java || \
    fail "single-submit SIM PIN guard"
rg -q 'com.android.settings.Settings\$IccLockSettingsActivity' \
    src/com/daak/node/NodeControlAccessibilityService.java || fail "direct SIM lock settings"
pass "SIM PIN disable flow is scoped and single-submit"

./build-apk.sh >/dev/null
pass "clean APK build"

aapt2_bin=${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/35.0.1/aapt2
"$aapt2_bin" dump badging DAAK-NODE.apk | grep -q "versionCode='49'.*versionName='7.6.6'" || fail "APK version"
pass "APK version 7.6.6 (49)"

remote_test_dir=$(mktemp -d)
javac --release 8 -d "$remote_test_dir" src/com/daak/node/RemoteRouting.java tests/RemoteRoutingTest.java
java -cp "$remote_test_dir" com.daak.node.RemoteRoutingTest || fail "direct CRD route tests"
grep -q 'foundation.e.browser' src/com/daak/node/MainActivity.java || \
    fail "direct CRD route uses the installed /e/OS Chromium browser"
grep -q 'https://remotedesktop.google.com/access' src/com/daak/node/MainActivity.java || \
    fail "CRD device hub uses the web client when Google Chrome is absent"
rm -rf "$remote_test_dir"
pass "direct CRD routing uses validated phone-local host IDs and installed browser"
"$aapt2_bin" dump xmltree DAAK-NODE.apk --file AndroidManifest.xml | grep -q 'OledPlayerActivity' || fail "OLED player activity"
pass "secure OLED lock player manifest"

for tone in daak_pulse deep_node terminal_tick; do
    unzip -l DAAK-NODE.apk | grep -q "res/raw/$tone.ogg" || fail "embedded tone $tone"
done
pass "embedded high-volume tones"

if command -v ffmpeg >/dev/null 2>&1; then
    for tone in res/raw/*.ogg; do
        peak=$(ffmpeg -hide_banner -i "$tone" -af volumedetect -f null - 2>&1 | sed -n 's/.*max_volume: \([-0-9.]*\) dB.*/\1/p' | tail -n 1)
        [ -n "$peak" ] || fail "audio analysis $tone"
        awk -v value="$peak" 'BEGIN { exit !(value >= -6.0 && value <= 0.0) }' || fail "audio peak $tone ($peak dB)"
    done
    pass "tone peaks between -6 and 0 dBFS"
fi

if command -v adb >/dev/null 2>&1; then
    device=${DAAK_DEVICE_SERIAL:-$(adb devices | awk '$2 == "device" {print $1; exit}')}
    if [ -n "$device" ]; then
        adb -s "$device" shell dumpsys package com.daak.node | grep -q 'versionName=7.6.6' || fail "installed DAAK version"
        adb -s "$device" shell "su -c 'id'" | grep -q 'uid=0(root)' || fail "root"
        adb -s "$device" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep -q 'com.daak.node/.MainActivity' || fail "default launcher"
        adb -s "$device" shell settings get secure enabled_accessibility_services | grep -q 'com.daak.node/.NodeControlAccessibilityService' || fail "system-wide DAAK Home gesture"
        screen_size=$(adb -s "$device" shell wm size | sed -n 's/.*: \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | tail -n 1)
        screen_width=${screen_size%% *}
        screen_height=${screen_size##* }
        adb -s "$device" shell input keyevent KEYCODE_WAKEUP
        adb -s "$device" shell wm dismiss-keyguard
        sleep 1
        wakefulness=$(adb -s "$device" shell dumpsys power | grep -m1 'mWakefulness=' || true)
        keyguard=$(adb -s "$device" shell dumpsys window | grep -m1 'mDreamingLockscreen=' || true)
        if echo "$wakefulness" | grep -q 'Awake' && echo "$keyguard" | grep -q 'mDreamingLockscreen=false'; then
            adb -s "$device" shell am start -W -a android.settings.SETTINGS >/dev/null
            adb -s "$device" shell input swipe $((screen_width / 2)) $((screen_height - 10)) \
                $((screen_width / 2)) $((screen_height / 2)) 420
            gesture_home_ok=false
            gesture_attempts=0
            while [ "$gesture_attempts" -lt 5 ]; do
                resumed=$(adb -s "$device" shell dumpsys activity activities | \
                    grep -E -m1 'mResumedActivity|ResumedActivity' || true)
                case "$resumed" in
                    *com.daak.node/.MainActivity*) gesture_home_ok=true; break ;;
                esac
                gesture_attempts=$((gesture_attempts + 1))
                sleep 1
            done
            [ "$gesture_home_ok" = true ] || fail "bottom-edge swipe returns to DAAK Home"
        else
            adb -s "$device" shell dumpsys window windows | \
                grep -q 'DAAK control surface' || fail "bottom-edge gesture overlay armed while locked"
        fi
        adb -s "$device" shell pm path com.google.chromeremotedesktop >/dev/null || fail "Chrome Remote Desktop"
        ! adb -s "$device" shell pm list packages | grep -qi rustdesk || fail "RustDesk absent"
        adb -s "$device" shell cmd appops get com.bitchat.droid RUN_IN_BACKGROUND | grep -q 'ignore' || fail "Bitchat background restriction"
        adb -s "$device" shell cmd appops get com.bitchat.droid RUN_ANY_IN_BACKGROUND | grep -q 'ignore' || fail "Bitchat any-background restriction"
        ! adb -s "$device" shell dumpsys activity services com.bitchat.droid | grep -q 'isForeground=true' || fail "Bitchat foreground mesh stopped"
        adb -s "$device" shell "su -c 'iptables -S INPUT'" | grep -q -- '--dport 8022 -j DAAK_SSHD_INPUT' || fail "SSH firewall"
        adb -s "$device" shell "su -c 'iptables -S INPUT'" | grep -q -- '--dport 5555 -j DAAK_SSHD_INPUT' || fail "remote ADB firewall"
        adb -s "$device" shell "su -c 'pid=\$(cat /data/adb/daak-app-cleaner.pid); kill -0 \"\$pid\"'" || fail "app cleaner service"
        adb -s "$device" shell "su -c 'pid=\$(cat /data/adb/daak-power-reserve.pid); kill -0 \"\$pid\"'" || fail "Power Reserve service"
        adb -s "$device" shell "su -c 'test ! -e /data/adb/daak-power-reserve.enabled'" || fail "Power Reserve safely disarmed"
        adb -s "$device" shell "su -c 'grep -q "state=disabled" /data/adb/daak-power-reserve/status'" || fail "Power Reserve disabled status"
        adb -s "$device" shell "su -c 'test -x /data/adb/daak-keenetic-wol'" || fail "root-constrained Keenetic WOL bridge"
        adb -s "$device" shell "su -c 'test -r /data/adb/daak-scrcpy-server.jar'" || fail "headless WOL virtual-display runtime"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/lolile-preview'" || fail "Kurek preview bridge"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/mya-list'" || fail "MYA disk list bridge"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/mya-preview'" || fail "MYA preview bridge"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/mya-fetch'" || fail "MYA download bridge"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/lolile-books-sync'" || fail "book backup bridge"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/daak-airplay'" || fail "free AirPlay bridge"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/keenetic-wol'" || fail "Keenetic Cloud WOL bridge"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/rm-os-sync-once'" || fail "RM-OS one-shot sync"
        adb -s "$device" shell pm path com.buildwithparallel.crosstalk >/dev/null || fail "Crosstalk Android companion"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/usr/bin/crosstalk-android-server'" || fail "Crosstalk Termux controller"
        adb -s "$device" shell "su -c '/data/data/com.termux/files/usr/bin/curl -fsS --max-time 2 http://127.0.0.1:8000/api/v1/status >/dev/null'" || fail "Crosstalk localhost backend"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.local/bin/rm-os-sync-daemon'" || fail "RM-OS sync daemon"
        adb -s "$device" shell "su -c 'pid=\$(cat /data/data/com.termux/files/home/.cache/rm-os-sync-daemon.pid); kill -0 \"\$pid\" && tr \"\\000\" \" \" < \"/proc/\$pid/cmdline\" | grep -q rm-os-sync-daemon'" || fail "RM-OS daemon running"
        adb -s "$device" shell "su -c 'grep -q \"Durum: ONLINE\" \"/sdcard/Documents/DAAK-Vault/RM-OS Sync Status.md\"'" || fail "RM-OS online status"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/usr/bin/ffmpeg'" || fail "AirPlay ffmpeg runtime"
        adb -s "$device" shell dumpsys jobscheduler | grep -q 'com.daak.node/.BookBackupJobService' || fail "book backup scheduler"
        adb -s "$device" shell "su -c 'test -f \"/sdcard/Documents/DAAK-Vault/Kitap Backup Status.md\"'" || fail "book backup status"
        adb -s "$device" shell "su -c 'grep -q \"Durum: HAZIR\" \"/sdcard/Documents/DAAK-Vault/Kitap Backup Status.md\"'" || fail "book backup completion"
        adb -s "$device" shell dumpsys package com.foobnix.pro.pdf.reader | grep -q 'versionName=9.4.21-fdroid' || fail "Librera F-Droid reader"
        book_count=$(adb -s "$device" shell "su -c 'find \"/sdcard/Documents/DAAK-Vault/Kitap Meraklısına\" -type f | wc -l'" | tr -d '\r ')
        [ "${book_count:-0}" -ge 547 ] || fail "book backup file count"
        book_kib=$(adb -s "$device" shell "su -c 'du -sk \"/sdcard/Documents/DAAK-Vault/Kitap Meraklısına\"'" | awk '{print $1}')
        [ "${book_kib:-0}" -ge 2929386 ] || fail "book backup size"
        adb -s "$device" shell pm path org.mozilla.fennec_fdroid >/dev/null || fail "Fennec inline preview"
        [ "$(adb -s "$device" shell getprop service.adb.tcp.port | tr -d '\r')" = 5555 ] || fail "remote ADB service"
        adb -s "$device" shell dumpsys notification --noredact | grep -q 'daak_summary_loud_v1_' || fail "DAAK loud channel"
        capture_test=$(adb -s "$device" shell am broadcast -a com.daak.node.DIAGNOSTIC_NOTIFICATION_CAPTURE -n com.daak.node/.NodeDiagnosticsReceiver)
        printf '%s\n' "$capture_test" | grep -q 'data="PASS .*mail_storage=true whatsapp_storage=true"' || fail "mail/WhatsApp capture self-test"
        airplay_guard=$(adb -s "$device" shell am broadcast -a com.daak.node.DIAGNOSTIC_AIRPLAY_SEND -n com.daak.node/.NodeDiagnosticsReceiver --es path /sdcard/Download/not-allowed.wav)
        printf '%s\n' "$airplay_guard" | grep -q 'data="FAIL invalid_airplay_path"' || fail "AirPlay diagnostic path guard"
        adb -s "$device" shell logcat -c
        wol_guard=$(adb -s "$device" shell am broadcast -a com.daak.node.DIAGNOSTIC_KEENETIC_WOL -p com.daak.node)
        printf '%s\n' "$wol_guard" | grep -q 'data="PASS diagnostic_keenetic_wol=headless_and_queued"' || fail "Keenetic WOL diagnostic"
        sleep 1
        adb -s "$device" shell logcat -d -v brief | grep -q 'DAAK_CONTROL.*Headless WOL status shown' || fail "Keenetic WOL status capsule"
        pass "device integration checks"
    fi
fi

printf 'DAAK NODE v7.6.6 verification complete.\n'

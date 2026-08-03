#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$root"

pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1" >&2; exit 1; }

git diff --check
pass "git diff hygiene"

sh -n build-apk.sh tools/migrate-v7.sh companion/macos/daak-phone companion/magisk/daak-sshd-firewall.sh companion/magisk/daak-app-cleaner.sh companion/termux/daak-selftest companion/termux/keenetic-wol companion/termux/rm-os-sync-daemon companion/termux/rm-os-sync-boot tests/device-app-sweep.sh tests/device-ui-sweep.sh
pass "shell syntax"

python3 -c 'import ast, pathlib; [ast.parse(pathlib.Path(path).read_text()) for path in ("companion/termux/lolile-preview", "companion/termux/lolile-books-sync", "companion/termux/daak-airplay", "companion/termux/rm-os-sync-once")]'
pass "Kurek and AirPlay bridge syntax"

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

./build-apk.sh >/dev/null
pass "clean APK build"

aapt2_bin=${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/35.0.1/aapt2
"$aapt2_bin" dump badging DAAK-NODE.apk | grep -q "versionCode='30'.*versionName='7.0.0'" || fail "APK version"
pass "APK version 7.0.0 (30)"

remote_test_dir=$(mktemp -d)
javac --release 8 -d "$remote_test_dir" src/com/daak/node/RemoteRouting.java tests/RemoteRoutingTest.java
java -cp "$remote_test_dir" com.daak.node.RemoteRoutingTest || fail "direct CRD route tests"
rm -rf "$remote_test_dir"
pass "direct CRD routing uses validated phone-local host IDs"
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
        adb -s "$device" shell dumpsys package com.daak.node | grep -q 'versionName=7.0.0' || fail "installed DAAK version"
        adb -s "$device" shell "su -c 'id'" | grep -q 'uid=0(root)' || fail "root"
        adb -s "$device" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep -q 'com.daak.node/.MainActivity' || fail "default launcher"
        adb -s "$device" shell settings get secure enabled_accessibility_services | grep -q 'com.daak.node/.NodeControlAccessibilityService' || fail "system-wide DAAK Home gesture"
        screen_size=$(adb -s "$device" shell wm size | sed -n 's/.*: \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | tail -n 1)
        screen_width=${screen_size%% *}
        screen_height=${screen_size##* }
        adb -s "$device" shell am start -W -a android.settings.SETTINGS >/dev/null
        adb -s "$device" shell input swipe $((screen_width / 2)) $((screen_height - 10)) \
            $((screen_width / 2)) $((screen_height / 2)) 420
        sleep 1
        adb -s "$device" shell dumpsys activity activities | grep -m1 'mResumedActivity' | \
            grep -q 'com.daak.node/.MainActivity' || fail "bottom-edge swipe returns to DAAK Home"
        adb -s "$device" shell pm path com.google.chromeremotedesktop >/dev/null || fail "Chrome Remote Desktop"
        ! adb -s "$device" shell pm list packages | grep -qi rustdesk || fail "RustDesk absent"
        adb -s "$device" shell dumpsys deviceidle whitelist | grep -q 'com.bitchat.droid' || fail "Bitchat Doze exemption"
        adb -s "$device" shell dumpsys package com.bitchat.droid | grep -q 'ACCESS_BACKGROUND_LOCATION: granted=true' || fail "Bitchat background location"
        adb -s "$device" shell dumpsys activity services com.bitchat.droid | grep -q 'isForeground=true' || fail "Bitchat foreground mesh"
        adb -s "$device" shell "su -c 'iptables -S INPUT'" | grep -q -- '--dport 8022 -j DAAK_SSHD_INPUT' || fail "SSH firewall"
        adb -s "$device" shell "su -c 'iptables -S INPUT'" | grep -q -- '--dport 5555 -j DAAK_SSHD_INPUT' || fail "remote ADB firewall"
        adb -s "$device" shell "su -c 'pid=\$(cat /data/adb/daak-app-cleaner.pid); kill -0 \"\$pid\"'" || fail "app cleaner service"
        adb -s "$device" shell "su -c 'test -x /data/adb/daak-keenetic-wol'" || fail "root-constrained Keenetic WOL bridge"
        adb -s "$device" shell "su -c 'test -r /data/adb/daak-scrcpy-server.jar'" || fail "headless WOL virtual-display runtime"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/lolile-preview'" || fail "Kurek preview bridge"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/lolile-books-sync'" || fail "book backup bridge"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/daak-airplay'" || fail "free AirPlay bridge"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/keenetic-wol'" || fail "Keenetic Cloud WOL bridge"
        adb -s "$device" shell "su -c 'test -x /data/data/com.termux/files/home/.shortcuts/rm-os-sync-once'" || fail "RM-OS one-shot sync"
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

printf 'DAAK NODE v7.0.0 verification complete.\n'

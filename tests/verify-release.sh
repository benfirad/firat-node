#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$root"

pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1" >&2; exit 1; }

git diff --check
pass "git diff hygiene"

sh -n build-apk.sh companion/macos/daak-phone companion/magisk/daak-sshd-firewall.sh companion/termux/daak-selftest tests/device-app-sweep.sh
pass "shell syntax"

./build-apk.sh >/dev/null
pass "clean APK build"

aapt2_bin=${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/35.0.1/aapt2
"$aapt2_bin" dump badging DAAK-NODE.apk | grep -q "versionCode='20'.*versionName='6.8.2'" || fail "APK version"
pass "APK version 6.8.2 (20)"

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
        adb -s "$device" shell dumpsys package com.firat.node | grep -q 'versionName=6.8.2' || fail "installed DAAK version"
        adb -s "$device" shell "su -c 'id'" | grep -q 'uid=0(root)' || fail "root"
        adb -s "$device" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep -q 'com.firat.node/.MainActivity' || fail "default launcher"
        adb -s "$device" shell pm path com.google.chromeremotedesktop >/dev/null || fail "Chrome Remote Desktop"
        ! adb -s "$device" shell pm list packages | grep -qi rustdesk || fail "RustDesk absent"
        adb -s "$device" shell dumpsys deviceidle whitelist | grep -q 'com.bitchat.droid' || fail "Bitchat Doze exemption"
        adb -s "$device" shell dumpsys package com.bitchat.droid | grep -q 'ACCESS_BACKGROUND_LOCATION: granted=true' || fail "Bitchat background location"
        adb -s "$device" shell dumpsys activity services com.bitchat.droid | grep -q 'isForeground=true' || fail "Bitchat foreground mesh"
        adb -s "$device" shell "su -c 'iptables -S INPUT'" | grep -q -- '--dport 8022 -j DAAK_SSHD_INPUT' || fail "SSH firewall"
        adb -s "$device" shell "su -c 'iptables -S INPUT'" | grep -q -- '--dport 5555 -j DAAK_SSHD_INPUT' || fail "remote ADB firewall"
        [ "$(adb -s "$device" shell getprop service.adb.tcp.port | tr -d '\r')" = 5555 ] || fail "remote ADB service"
        adb -s "$device" shell dumpsys notification --noredact | grep -q 'daak_summary_loud_v1_' || fail "DAAK loud channel"
        pass "device integration checks"
    fi
fi

printf 'DAAK NODE v6.8.2 verification complete.\n'

#!/bin/sh
set -u

adb_bin=${ADB:-adb}
device=${1:-${DAAK_DEVICE_SERIAL:-}}
if [ -z "$device" ]; then
    device=$($adb_bin devices | awk '$2 == "device" && $1 !~ /:5555$/ {print $1; exit}')
fi
[ -n "$device" ] || { printf 'No Android device found.\n' >&2; exit 1; }

root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
report="$root/build/ui-sweep.tsv"
mkdir -p "$root/build"
printf 'orientation\tarea\texpected\tresult\tresponse_ms\tattempts\tforeground\n' > "$report"
failures=0
orientation=portrait
restore_rotation() {
    $adb_bin -s "$device" shell wm set-fix-to-user-rotation disabled >/dev/null 2>&1 || true
    $adb_bin -s "$device" shell wm set-user-rotation free >/dev/null 2>&1 || true
}
trap restore_rotation EXIT
trap 'restore_rotation; exit 130' INT TERM
$adb_bin -s "$device" shell wm set-fix-to-user-rotation enabled
$adb_bin -s "$device" shell wm set-user-rotation lock 0
sleep 3

foreground() {
    $adb_bin -s "$device" shell dumpsys activity activities 2>/dev/null |
        sed -n 's/.*mResumedActivity:.* u[0-9][0-9]* \([^ /}]*\).*/\1/p' | head -n 1 | tr -d '\r'
}

home() {
    # Close delayed biometric/device-credential and app dialogs from the prior
    # test before restoring the launcher. Protected actions are never approved.
    $adb_bin -s "$device" shell input keyevent BACK >/dev/null 2>&1 || true
    sleep 0.15
    $adb_bin -s "$device" shell input keyevent BACK >/dev/null 2>&1 || true
    sleep 0.25
    if [ "$orientation" = portrait ]; then
        $adb_bin -s "$device" shell wm set-user-rotation lock 0 >/dev/null 2>&1
    else
        $adb_bin -s "$device" shell wm set-user-rotation lock 1 >/dev/null 2>&1
    fi
    $adb_bin -s "$device" shell am start -n com.daak.node/.MainActivity \
        -a android.intent.action.MAIN >/dev/null 2>&1
    sleep 0.8
}

back() {
    $adb_bin -s "$device" shell input keyevent BACK >/dev/null
    sleep 0.35
}

tap_expect() {
    area=$1; expected=$2; x=$3; y=$4
    $adb_bin -s "$device" logcat -c >/dev/null 2>&1 || true
    $adb_bin -s "$device" shell input tap "$x" "$y" >/dev/null
    line=""
    tries=1
    attempt=0
    while [ "$attempt" -lt 5 ]; do
        line=$($adb_bin -s "$device" logcat -d -v brief 2>/dev/null |
            grep -F "DAAK_UI" | grep -F "action=$expected " | tail -n 1 || true)
        [ -n "$line" ] && break
        attempt=$((attempt + 1)); sleep 0.12
    done
    if [ -z "$line" ]; then
        tries=2
        $adb_bin -s "$device" shell input tap "$x" "$y" >/dev/null
        attempt=0
        while [ "$attempt" -lt 5 ]; do
            line=$($adb_bin -s "$device" logcat -d -v brief 2>/dev/null |
                grep -F "DAAK_UI" | grep -F "action=$expected " | tail -n 1 || true)
            [ -n "$line" ] && break
            attempt=$((attempt + 1)); sleep 0.12
        done
    fi
    response=$(printf '%s\n' "$line" | sed -n 's/.*response_ms=\([0-9][0-9]*\).*/\1/p')
    [ -n "$response" ] || response=-1
    result=PASS
    if [ -z "$line" ]; then result=FAIL_MISSING_ACTION
    elif [ "$tries" -eq 2 ]; then result=PASS_RETRY
    fi
    if [ "$response" -gt 180 ] 2>/dev/null; then result=FAIL_SLOW; fi
    crash=$($adb_bin -s "$device" logcat -b crash -d -v brief 2>/dev/null | grep -E 'FATAL EXCEPTION|ANR in ' || true)
    if [ -n "$crash" ]; then result=FAIL_CRASH; fi
    current=$(foreground); [ -n "$current" ] || current=none
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$orientation" "$area" "$expected" "$result" "$response" "$tries" "$current" >> "$report"
    printf '%-9s %-18s %-42s %-10s %4sms try=%s\n' "$orientation" "$area" "$expected" "$result" "$response" "$tries"
    case "$result" in FAIL_*) failures=$((failures + 1)) ;; esac
    sleep 0.3
}

$adb_bin -s "$device" logcat -c >/dev/null 2>&1 || true

home_action() { home; tap_expect HOME "$1" "$2" "$3"; home; }
control_action() { home; tap_expect NAV CONTROL 1360 98; tap_expect CONTROL "$1" "$2" "$3"; home; }
remote_action() { home; tap_expect HOME REMOTE 360 1018; tap_expect REMOTE "$1" "$2" "$3"; home; }
codex_action() { home; tap_expect DOCK CODEX 720 2840; tap_expect CODEX "$1" "$2" "$3"; back; home; }
help_action() { home; tap_expect DOCK HELP 1268 2840; tap_expect HELP "$1" "$2" "$3"; home; }
panel_action() {
    panel_opener=$1; panel_open_x=$2; panel_open_y=$3; panel_expected=$4; panel_action_x=$5
    home
    tap_expect HOME "$panel_opener" "$panel_open_x" "$panel_open_y"
    tap_expect PANEL "$panel_expected" "$panel_action_x" 2585
    back; home
}

# Portrait: every dock cell and every home card/button.
home_action HOME 172 2840
home_action APPS 446 2840
home_action CODEX 720 2840
home_action DISK 994 2840; back
home_action HELP 1268 2840
home_action VAULT 1232 98; back
home_action CONTROL 1360 98
home_action CODEX 360 809
home_action DISK 1080 809; back
home_action REMOTE 360 1018
home_action LOCAL 1080 1018; back
home_action MAIL 360 1302
home_action WHATSAPP 1080 1302
home_action REMEMBER 360 1505
home_action WEATHER 1080 1505; back
home_action CALENDAR 720 1708
home_action MUSIC 258 2095
home_action PKG:com.sec.android.app.camera 720 2095
home_action PKG:io.github.yahiaangelo.filmsimulator.android 1182 2095
home_action BOOKS 258 2497
home_action REMEMBER 720 2497
home_action RMOS 1182 2497; back

# Portrait control centre: all 12 tiles plus refresh.
control_action PKG:com.tailscale.ipn 360 535
control_action LOLILE_HUB 1080 535; back
control_action PINS 360 766; back
control_action SET:INPUT 1080 766
control_action SET:SECURITY 360 997
control_action WHATSAPP 1080 997
control_action CHECK_UPDATE 360 1228; back
control_action DICTATE 1080 1228; back
control_action PKG:org.fossify.clock 360 1459
control_action PKG:hu.vmiklos.plees_tracker 1080 1459
control_action SOUND 360 1690; back
control_action SET:MAILACCESS 1080 1690
control_action REFRESH 720 1942

# Remote page, including both safe power panels. Shutdown confirmations are cancelled.
remote_action REMOTE_CONNECT:0 360 595
remote_action REMOTE_CONNECT:1 1080 595
remote_action REMOTE_CONNECT:2 360 945
remote_action REMOTE_CONNECT:3 1080 945
remote_action REMOTE_CONFIG 720 1263
remote_action POWER_LOLILE 360 1498
remote_action POWER_MAC 1080 1498

# Codex routes stop at the biometric gate; custom path opens and is cancelled.
codex_action CODEX_RUN:standalone 720 578
codex_action CODEX_RUN:hub 720 893
codex_action CODEX_RUN:phone 720 1208
codex_action CODEX_CUSTOM 720 1523

# Portrait help and app search.
help_action SET:SECURITY 360 1963
help_action SET:SYSTEM 1080 1963
help_action PKG:com.tailscale.ipn 360 2194
help_action PKG:io.github.sds100.keymapper 1080 2194
home; tap_expect DOCK APPS 446 2840; tap_expect APPS SEARCH 720 478; back; home

# Intelligence-panel action rows.
panel_action MAIL 360 1302 PANEL_PRIMARY 245
panel_action MAIL 360 1302 PANEL_SECONDARY 720
panel_action MAIL 360 1302 PANEL_CLOSE 1195
panel_action WHATSAPP 1080 1302 PANEL_PRIMARY 245
panel_action WHATSAPP 1080 1302 PANEL_SECONDARY 720
panel_action WHATSAPP 1080 1302 PANEL_CLOSE 1195
panel_action REMEMBER 360 1505 PANEL_PRIMARY 245
panel_action REMEMBER 360 1505 PANEL_SECONDARY 720
panel_action REMEMBER 360 1505 PANEL_CLOSE 1195
panel_action MUSIC 258 2095 PANEL_PRIMARY 245
panel_action MUSIC 258 2095 PANEL_SECONDARY 720
panel_action MUSIC 258 2095 PANEL_TERTIARY 1195

# Landscape: hit-test every adaptive dock cell and all eight newly interactive Help cards.
$adb_bin -s "$device" shell wm set-user-rotation lock 1
sleep 2
orientation=landscape
home
sleep 3
tap_expect DOCK HOME 324 1360
tap_expect DOCK APPS 902 1360
tap_expect DOCK CODEX 1480 1360
tap_expect DOCK DISK 2058 1360; back
tap_expect DOCK HELP 2636 1360
tap_expect HELP CODEX 402 478; home
tap_expect DOCK HELP 2636 1360; tap_expect HELP DISK 1121 478; back; home
tap_expect DOCK HELP 2636 1360; tap_expect HELP REMEMBER 1840 478; home
tap_expect DOCK HELP 2636 1360; tap_expect HELP RMOS 2559 478; back; home
tap_expect DOCK HELP 2636 1360; tap_expect HELP SET:SECURITY 402 994; home
tap_expect DOCK HELP 2636 1360; tap_expect HELP SET:SYSTEM 1121 994; home
tap_expect DOCK HELP 2636 1360; tap_expect HELP PKG:com.tailscale.ipn 1840 994; home
tap_expect DOCK HELP 2636 1360; tap_expect HELP PKG:io.github.sds100.keymapper 2559 994; home

$adb_bin -s "$device" shell wm set-user-rotation lock 0
sleep 2
home

passes=$(awk -F '\t' 'NR > 1 && $4 ~ /^PASS/ {count++} END {print count+0}' "$report")
printf 'UI_SWEEP_DONE pass=%s fail=%s report=%s\n' "$passes" "$failures" "$report"
[ "$failures" -eq 0 ]

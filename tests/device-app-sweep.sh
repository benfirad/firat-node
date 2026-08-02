#!/bin/sh
set -u

adb_bin=${ADB:-adb}
device=${1:-${DAAK_DEVICE_SERIAL:-}}
if [ -z "$device" ]; then
    device=$($adb_bin devices | awk '$2 == "device" && $1 !~ /:5555$/ {print $1; exit}')
fi
[ -n "$device" ] || { printf 'No Android device found.\n' >&2; exit 1; }

root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
report_dir="$root/build"
report="$report_dir/app-sweep.tsv"
crash_log="$report_dir/app-sweep-crashes.log"
component_list="$report_dir/app-sweep-components.txt"
mkdir -p "$report_dir"
: > "$crash_log"
printf 'component\tresult\tforeground\tstart_ms\n' > "$report"

cleanup() {
    $adb_bin -s "$device" shell input keyevent HOME >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

$adb_bin -s "$device" shell cmd package query-activities --brief \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | tr -d '\r' |
    sed -n 's/^[[:space:]]*\([^[:space:]]*\/[^[:space:]]*\)[[:space:]]*$/\1/p' | sort -u > "$component_list"
total=$(sed '/^$/d' "$component_list" | wc -l | tr -d ' ')
[ "$total" -gt 0 ] || { printf 'No launcher activities found.\n' >&2; exit 1; }

index=0
failures=0
redirects=0
printf 'APP_SWEEP_START total=%s\n' "$total"
while IFS= read -r component <&3; do
    [ -n "$component" ] || continue
    index=$((index + 1))
    package=${component%%/*}
    $adb_bin -s "$device" logcat -c >/dev/null 2>&1 || true
    started=$($adb_bin -s "$device" shell am start -W -n "$component" 2>&1 | tr -d '\r')
    sleep 2
    foreground=$($adb_bin -s "$device" shell dumpsys activity activities 2>/dev/null |
        sed -n 's/.*mResumedActivity:.* u[0-9][0-9]* \([^ /}]*\).*/\1/p' | head -n 1 | tr -d '\r')
    start_ms=$(printf '%s\n' "$started" | sed -n 's/^[[:space:]]*TotalTime: //p' | tail -n 1)
    [ -n "$start_ms" ] || start_ms=0
    crash=$($adb_bin -s "$device" logcat -b crash -d -v brief 2>/dev/null | tr -d '\r')
    anr=$($adb_bin -s "$device" logcat -d -v brief 2>/dev/null |
        grep -E 'ANR in |FATAL EXCEPTION|am_crash|am_anr' | tr -d '\r' || true)

    result=PASS
    if printf '%s\n' "$started" | grep -Eq 'Error:|Exception|SecurityException'; then
        result=FAIL_START
    elif [ -n "$crash$anr" ]; then
        result=FAIL_CRASH
    elif [ "$foreground" = "$package" ]; then
        result=PASS
    elif [ "$package" = com.google.chromeremotedesktop ] && [ "$foreground" = com.android.chrome ]; then
        result=PASS_WRAPPED
    elif [ "$foreground" = com.google.android.permissioncontroller ]; then
        result=PASS_PERMISSION
    elif [ "$package" = com.termux.boot ] || [ "$package" = com.termux.widget ]; then
        result=PASS_FINISHED
    else
        result=REDIRECT
    fi

    case "$result" in
        FAIL_*) failures=$((failures + 1)) ;;
        REDIRECT) redirects=$((redirects + 1)) ;;
    esac
    if [ -n "$crash$anr" ]; then
        {
            printf 'COMPONENT %s\n' "$component"
            printf '%s\n%s\n' "$crash" "$anr"
        } >> "$crash_log"
    fi
    printf '%s\t%s\t%s\t%s\n' "$component" "$result" "${foreground:-none}" "$start_ms" >> "$report"
    printf 'APP %02d/%02d %-14s %s\n' "$index" "$total" "$result" "$component"

    $adb_bin -s "$device" shell input keyevent HOME >/dev/null
    sleep 1
    home=$($adb_bin -s "$device" shell dumpsys activity activities 2>/dev/null |
        sed -n 's/.*mResumedActivity:.* u[0-9][0-9]* \([^ /}]*\).*/\1/p' | head -n 1 | tr -d '\r')
    if [ "$home" != com.firat.node ]; then
        printf 'HOME_RECOVERY %s\n' "${home:-none}"
        $adb_bin -s "$device" shell am start -n com.firat.node/.MainActivity >/dev/null 2>&1 || true
        sleep 1
    fi
    case "$package" in
        ru.tech.imageresizershrinker|me.zhanghai.android.files|com.google.android.apps.photos)
            $adb_bin -s "$device" shell am force-stop "$package" >/dev/null 2>&1 || true
            printf 'HOT_CLEANUP %s\n' "$package"
            ;;
    esac
done 3< "$component_list"

failures=$(awk -F '\t' 'NR > 1 && $2 ~ /^FAIL_/ {count++} END {print count+0}' "$report")
redirects=$(awk -F '\t' 'NR > 1 && $2 == "REDIRECT" {count++} END {print count+0}' "$report")
passes=$(awk -F '\t' 'NR > 1 && $2 ~ /^PASS/ {count++} END {print count+0}' "$report")
printf 'APP_SWEEP_DONE pass=%s redirect=%s fail=%s report=%s\n' "$passes" "$redirects" "$failures" "$report"
[ "$failures" -eq 0 ] && [ "$redirects" -eq 0 ]

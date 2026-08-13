#!/system/bin/sh
set -eu

profile=/data/adb/daak-location-privacy.sh
mode=/data/adb/daak-location-privacy/mode

attempt=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$attempt" -lt 60 ]; do
    sleep 2
    attempt=$((attempt + 1))
done

[ -x "$profile" ] || exit 0
[ "$(cat "$mode" 2>/dev/null || printf disabled)" = enabled ] || exit 0
/system/bin/sh "$profile" enable

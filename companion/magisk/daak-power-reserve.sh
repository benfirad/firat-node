#!/system/bin/sh

# DAAK Power Reserve keeps Android alive as a BLE beacon host when the visible
# battery reaches 5%. It never arms itself: both the explicit enable flag and a
# fresh DAAK Find advertiser heartbeat are required before radios are reduced.

enter_level=5
exit_level=10

decision() {
    armed=$1
    beacon_ready=$2
    active=$3
    powered=$4
    battery=$5

    if [ "$active" -eq 1 ]; then
        [ "$armed" -eq 1 ] || { printf '%s\n' exit-disarmed; return; }
        [ "$powered" -eq 0 ] || { printf '%s\n' exit-powered; return; }
        [ "$beacon_ready" -eq 1 ] || { printf '%s\n' exit-beacon-lost; return; }
        [ "$battery" -lt "$exit_level" ] || { printf '%s\n' exit-recovered; return; }
        printf '%s\n' reserve
        return
    fi

    [ "$armed" -eq 1 ] || { printf '%s\n' disabled; return; }
    [ "$powered" -eq 0 ] || { printf '%s\n' charging; return; }
    [ "$battery" -le "$enter_level" ] || { printf '%s\n' standby; return; }
    [ "$beacon_ready" -eq 1 ] || { printf '%s\n' waiting-for-beacon; return; }
    printf '%s\n' enter
}

if [ "${1:-}" = --decision ]; then
    shift
    [ "$#" -eq 5 ] || {
        echo "usage: --decision ARMED BEACON_READY ACTIVE POWERED BATTERY" >&2
        exit 64
    }
    decision "$@"
    exit
fi

pidfile=/data/adb/daak-power-reserve.pid
old_pid=$(cat "$pidfile" 2>/dev/null || printf 0)
case "$old_pid" in ''|*[!0-9]*) old_pid=0 ;; esac
if [ "$old_pid" -gt 1 ] && kill -0 "$old_pid" 2>/dev/null; then
    old_command=$(tr '\000' ' ' < "/proc/$old_pid/cmdline" 2>/dev/null)
    case "$old_command" in
        *daak-power-reserve.sh*) exit 0 ;;
    esac
fi

(
    reserve_dir=/data/adb/daak-power-reserve
    enabled_flag=/data/adb/daak-power-reserve.enabled
    active_flag="$reserve_dir/active"
    snapshot="$reserve_dir/snapshot"
    status_file="$reserve_dir/status"
    beacon_heartbeat=/data/adb/daak-find/advertiser.ready
    gateway_controller=/data/adb/daak-gateway/daak-gateway-control.jar
    low_samples=0

    mkdir -p "$reserve_dir"
    chmod 700 "$reserve_dir"

    number_or() {
        value=$1
        fallback=$2
        case "$value" in ''|*[!0-9]*) printf '%s' "$fallback" ;; *) printf '%s' "$value" ;; esac
    }

    setting() {
        namespace=$1
        key=$2
        fallback=$3
        value=$(settings get "$namespace" "$key" 2>/dev/null)
        number_or "$value" "$fallback"
    }

    snapshot_state() {
        wifi_on=$(setting global wifi_on 0)
        mobile_data=$(setting global mobile_data 0)
        location_mode=$(setting secure location_mode 0)
        brightness=$(setting system screen_brightness 20)
        timeout=$(setting system screen_off_timeout 30000)
        {
            printf 'wifi_on=%s\n' "$wifi_on"
            printf 'mobile_data=%s\n' "$mobile_data"
            printf 'location_mode=%s\n' "$location_mode"
            printf 'screen_brightness=%s\n' "$brightness"
            printf 'screen_off_timeout=%s\n' "$timeout"
        } > "$snapshot.tmp" && mv -f "$snapshot.tmp" "$snapshot"
        chmod 600 "$snapshot"
    }

    saved() {
        key=$1
        fallback=$2
        value=$(sed -n "s/^${key}=//p" "$snapshot" 2>/dev/null | head -n 1)
        number_or "$value" "$fallback"
    }

    hotspot_stop() {
        [ -f "$gateway_controller" ] || return 0
        state=$(CLASSPATH="$gateway_controller" app_process /system/bin \
            com.daak.node.tools.GatewayControl status 2>/dev/null | head -n 1)
        [ "$state" = active ] || return 0
        CLASSPATH="$gateway_controller" app_process /system/bin \
            com.daak.node.tools.GatewayControl stop >/dev/null 2>&1 || true
    }

    enter_reserve() {
        snapshot_state || return 1
        hotspot_stop
        svc wifi disable >/dev/null 2>&1 || true
        svc data disable >/dev/null 2>&1 || true
        settings put secure location_mode 0 >/dev/null 2>&1 || true
        settings put system screen_brightness 0 >/dev/null 2>&1 || true
        settings put system screen_off_timeout 15000 >/dev/null 2>&1 || true
        am kill-all >/dev/null 2>&1 || true
        input keyevent 223 >/dev/null 2>&1 || true
        : > "$active_flag"
        chmod 600 "$active_flag"
        log -t DAAK-RESERVE "entered at ${battery}%" >/dev/null 2>&1 || true
    }

    exit_reserve() {
        wifi_on=$(saved wifi_on 0)
        mobile_data=$(saved mobile_data 0)
        location_mode=$(saved location_mode 0)
        brightness=$(saved screen_brightness 20)
        timeout=$(saved screen_off_timeout 30000)

        settings put secure location_mode "$location_mode" >/dev/null 2>&1 || true
        settings put system screen_brightness "$brightness" >/dev/null 2>&1 || true
        settings put system screen_off_timeout "$timeout" >/dev/null 2>&1 || true
        [ "$wifi_on" -eq 0 ] || svc wifi enable >/dev/null 2>&1 || true
        [ "$mobile_data" -eq 0 ] || svc data enable >/dev/null 2>&1 || true
        rm -f "$active_flag" "$snapshot"
        log -t DAAK-RESERVE "exited at ${battery}%" >/dev/null 2>&1 || true
    }

    beacon_is_ready() {
        now=$1
        updated=$(cat "$beacon_heartbeat" 2>/dev/null || printf 0)
        case "$updated" in ''|*[!0-9]*) return 1 ;; esac
        age=$((now - updated))
        [ "$age" -ge 0 ] && [ "$age" -le 180 ]
    }

    publish_status() {
        now=$1
        state=$2
        printf 'state=%s\nbattery_percent=%s\nexternally_powered=%s\nbeacon_ready=%s\nupdated_at=%s\n' \
            "$state" "$battery" "$powered" "$beacon_ready" "$now" > "$status_file.tmp" &&
            mv -f "$status_file.tmp" "$status_file"
        chmod 600 "$status_file"
    }

    while true; do
        now=$(date +%s)
        battery_dump=$(dumpsys battery)
        battery=$(printf '%s\n' "$battery_dump" | awk '/level:/{print $2; exit}')
        battery=$(number_or "$battery" 100)

        powered=0
        printf '%s\n' "$battery_dump" | grep -Eq \
            'AC powered: true|USB powered: true|Wireless powered: true' && powered=1
        armed=0
        [ -f "$enabled_flag" ] && armed=1
        active=0
        [ -f "$active_flag" ] && active=1
        beacon_ready=0
        beacon_is_ready "$now" && beacon_ready=1

        state=$(decision "$armed" "$beacon_ready" "$active" "$powered" "$battery")
        if [ "$state" = enter ]; then
            low_samples=$((low_samples + 1))
            if [ "$low_samples" -lt 2 ]; then
                state=arming-reserve
            elif enter_reserve; then
                state=reserve
                low_samples=0
            else
                state=enter-failed
            fi
        else
            low_samples=0
            case "$state" in
                exit-*)
                    exit_reserve
                    state=standby
                    ;;
            esac
        fi

        publish_status "$now" "$state"
        sleep 30
    done
) </dev/null >/dev/null 2>&1 &
echo $! > "$pidfile"

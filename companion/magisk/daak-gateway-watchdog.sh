#!/system/bin/sh

# Root-side, screenless Wi-Fi hotspot watchdog for DAAK Node.
# It is inert until /data/adb/daak-gateway.enabled exists. This lets the files
# ship before the gateway SIM is installed without disrupting the current Wi-Fi
# uplink or remote access.

pidfile=/data/adb/daak-gateway-watchdog.pid
old_pid=$(cat "$pidfile" 2>/dev/null || printf 0)
case "$old_pid" in ''|*[!0-9]*) old_pid=0 ;; esac
if [ "$old_pid" -gt 1 ] && kill -0 "$old_pid" 2>/dev/null; then
    old_command=$(tr '\000' ' ' < "/proc/$old_pid/cmdline" 2>/dev/null)
    case "$old_command" in
        *daak-gateway-watchdog.sh*) exit 0 ;;
    esac
fi

(
    gateway_dir=/data/adb/daak-gateway
    controller="$gateway_dir/daak-gateway-control.jar"
    enabled_flag=/data/adb/daak-gateway.enabled
    thermal_lock="$gateway_dir/thermal.lock"
    battery_lock="$gateway_dir/battery.lock"
    status_file="$gateway_dir/status"
    stop_temp=460
    resume_temp=410
    stop_battery=15
    resume_battery=25

    mkdir -p "$gateway_dir"
    chmod 700 "$gateway_dir"

    control() {
        CLASSPATH="$controller" app_process /system/bin \
            com.daak.node.tools.GatewayControl "$1" 2>/dev/null
    }

    publish_status() {
        now=$(date +%s)
        printf 'state=%s\ntemperature_tenths_c=%s\nbattery_percent=%s\nupdated_at=%s\n' \
            "$1" "$2" "$3" "$now" > "$status_file.tmp" &&
            mv -f "$status_file.tmp" "$status_file"
        chmod 600 "$status_file"
    }

    while true; do
        battery_dump=$(dumpsys battery)
        temperature=$(printf '%s\n' "$battery_dump" | awk '/temperature:/{print $2; exit}')
        battery=$(printf '%s\n' "$battery_dump" | awk '/level:/{print $2; exit}')
        usb_power=$(printf '%s\n' "$battery_dump" | awk '/USB powered:/{print $3; exit}')
        ac_power=$(printf '%s\n' "$battery_dump" | awk '/AC powered:/{print $3; exit}')
        wireless_power=$(printf '%s\n' "$battery_dump" | awk '/Wireless powered:/{print $3; exit}')
        case "$temperature" in ''|*[!0-9]*) temperature=0 ;; esac
        case "$battery" in ''|*[!0-9]*) battery=0 ;; esac

        if [ ! -f "$enabled_flag" ]; then
            publish_status disabled "$temperature" "$battery"
            sleep 60
            continue
        fi

        active=$(control status | awk 'NR==1{print; exit}')
        [ "$active" = active ] || active=inactive

        if [ "$temperature" -ge "$stop_temp" ]; then
            : > "$thermal_lock"
        elif [ -f "$thermal_lock" ] && [ "$temperature" -le "$resume_temp" ]; then
            rm -f "$thermal_lock"
        fi

        externally_powered=0
        if [ "$usb_power" = true ] || [ "$ac_power" = true ] || [ "$wireless_power" = true ]; then
            externally_powered=1
            rm -f "$battery_lock"
        elif [ "$battery" -le "$stop_battery" ]; then
            : > "$battery_lock"
        elif [ -f "$battery_lock" ] && [ "$battery" -ge "$resume_battery" ]; then
            rm -f "$battery_lock"
        fi

        if [ -f "$thermal_lock" ]; then
            [ "$active" = active ] && control stop >/dev/null
            publish_status thermal-pause "$temperature" "$battery"
        elif [ -f "$battery_lock" ] && [ "$externally_powered" -eq 0 ]; then
            [ "$active" = active ] && control stop >/dev/null
            publish_status low-battery-pause "$temperature" "$battery"
        elif ! dumpsys telephony.registry 2>/dev/null | grep -q 'mDataRegState=0(IN_SERVICE)'; then
            publish_status waiting-for-cellular "$temperature" "$battery"
        elif [ "$active" = active ]; then
            publish_status active "$temperature" "$battery"
        elif control start >/dev/null; then
            publish_status starting "$temperature" "$battery"
        else
            publish_status start-failed "$temperature" "$battery"
        fi

        sleep 60
    done
) </dev/null >/dev/null 2>&1 &
echo $! > "$pidfile"

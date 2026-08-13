#!/system/bin/sh
set -eu

staging_dir=${1:-/data/local/tmp}
gateway_dir=/data/adb/daak-gateway
service_dir=/data/adb/service.d

mkdir -p "$gateway_dir" "$service_dir"
cp "$staging_dir/daak-gateway-control.jar" \
    "$gateway_dir/daak-gateway-control.jar"
cp "$staging_dir/daak-gateway-watchdog.sh" \
    "$service_dir/daak-gateway-watchdog.sh"
cp "$staging_dir/daak-sshd-firewall.sh" \
    "$service_dir/daak-sshd-firewall.sh"
cp "$staging_dir/daak-power-reserve.sh" \
    "$service_dir/daak-power-reserve.sh"
if [ -f "$staging_dir/daak-location-privacy.sh" ] &&
   [ -f "$staging_dir/daak-location-privacy-boot.sh" ]; then
    cp "$staging_dir/daak-location-privacy.sh" \
        /data/adb/daak-location-privacy.sh
    cp "$staging_dir/daak-location-privacy-boot.sh" \
        "$service_dir/daak-location-privacy.sh"
    chmod 700 /data/adb/daak-location-privacy.sh \
        "$service_dir/daak-location-privacy.sh"
    sh -n /data/adb/daak-location-privacy.sh
    sh -n "$service_dir/daak-location-privacy.sh"
fi
chmod 700 "$gateway_dir" "$service_dir/daak-gateway-watchdog.sh" \
    "$service_dir/daak-sshd-firewall.sh" "$service_dir/daak-power-reserve.sh"
chmod 600 "$gateway_dir/daak-gateway-control.jar"

# Enable flags are intentionally never created or removed by the installer.
# This preserves explicit user state while keeping every first install inert.
sh -n "$service_dir/daak-gateway-watchdog.sh"
sh -n "$service_dir/daak-sshd-firewall.sh"
sh -n "$service_dir/daak-power-reserve.sh"
sh "$service_dir/daak-gateway-watchdog.sh" </dev/null >/dev/null 2>&1
sh "$service_dir/daak-power-reserve.sh" </dev/null >/dev/null 2>&1

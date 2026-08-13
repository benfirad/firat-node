#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
phone_host=${DAAK_FIND_HOST:-100.95.86.32}
phone_port=${DAAK_FIND_PORT:-8022}
ssh_args="-p $phone_port -o BatchMode=yes -o ConnectTimeout=8 -o StrictHostKeyChecking=yes"
scp_args="-P $phone_port -o BatchMode=yes -o ConnectTimeout=8 -o StrictHostKeyChecking=yes"

ssh $ssh_args "$phone_host" 'mkdir -p ~/.local/bin ~/.local/lib/daak-find ~/.local/state/daak-find ~/.termux/boot; chmod 700 ~/.local/state/daak-find'
scp $scp_args "$repo/companion/termux/daak-find.py" "$phone_host:.local/lib/daak-find/daak-find.py"
scp $scp_args "$repo/companion/termux/daak-find" "$phone_host:.local/bin/daak-find"
scp $scp_args "$repo/companion/termux/daak-find-boot" "$phone_host:.termux/boot/40-daak-find"
ssh $ssh_args "$phone_host" 'chmod 700 ~/.local/lib/daak-find/daak-find.py ~/.local/bin/daak-find ~/.termux/boot/40-daak-find; ~/.termux/boot/40-daak-find'

if command -v adb >/dev/null 2>&1; then
    adb_device=${DAAK_DEVICE_SERIAL:-$(adb devices | awk '$2 == "device" {print $1; exit}')}
    if [ -n "$adb_device" ]; then
        adb -s "$adb_device" shell settings put secure location_mode 3
        adb -s "$adb_device" shell settings put secure always_on_vpn_app com.tailscale.ipn
        adb -s "$adb_device" shell settings put secure always_on_vpn_lockdown 0
        adb -s "$adb_device" shell settings put global mobile_data 1
        adb -s "$adb_device" shell cmd appops set com.tailscale.ipn RUN_IN_BACKGROUND allow
        adb -s "$adb_device" shell cmd appops set com.tailscale.ipn RUN_ANY_IN_BACKGROUND allow
        adb -s "$adb_device" shell cmd appops set com.termux.api FINE_LOCATION allow
        adb -s "$adb_device" shell cmd appops set com.termux.api COARSE_LOCATION allow
        adb -s "$adb_device" shell cmd appops set com.termux.api RUN_IN_BACKGROUND allow
        adb -s "$adb_device" shell cmd appops set com.termux.api RUN_ANY_IN_BACKGROUND allow
        adb -s "$adb_device" shell dumpsys deviceidle whitelist +com.tailscale.ipn >/dev/null
        adb -s "$adb_device" shell dumpsys deviceidle whitelist +com.termux >/dev/null
        adb -s "$adb_device" shell dumpsys deviceidle whitelist +com.termux.api >/dev/null
    fi
fi

install -d -m 700 "$HOME/.local/bin" "$HOME/Applications" "$HOME/Library/Application Support/DAAK Find" "$HOME/Library/LaunchAgents"
install -m 700 "$repo/companion/macos/daak-find" "$HOME/.local/bin/daak-find"
install -m 700 "$repo/companion/macos/DAAK Find.command" "$HOME/Applications/DAAK Find.command"

plist="$HOME/Library/LaunchAgents/com.daak.find.poll.plist"
sed -e "s|__DAAK_FIND_BIN__|$HOME/.local/bin/daak-find|g" \
    -e "s|__DAAK_FIND_LOG__|$HOME/Library/Application Support/DAAK Find/poll.log|g" \
    "$repo/companion/macos/com.daak.find.poll.plist" >"$plist"
chmod 600 "$plist"
plutil -lint "$plist" >/dev/null

launchctl bootout "gui/$(id -u)/com.daak.find.poll" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$plist"
launchctl kickstart -k "gui/$(id -u)/com.daak.find.poll"

echo "DAAK Find installed. Run: $HOME/.local/bin/daak-find open"
echo "Finder shortcut: $HOME/Applications/DAAK Find.command"

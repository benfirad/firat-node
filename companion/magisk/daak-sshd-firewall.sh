#!/system/bin/sh

# Magisk late_start service: keep Termux sshd private to loopback + Tailscale.
# The loop is intentionally tiny and repairs rules after Android network resets.
pidfile=/data/adb/daak-sshd-firewall.pid
old_pid=$(cat "$pidfile" 2>/dev/null || printf 0)
case "$old_pid" in ''|*[!0-9]*) old_pid=0 ;; esac
if [ "$old_pid" -gt 1 ] && kill -0 "$old_pid" 2>/dev/null; then
    old_command=$(tr '\000' ' ' < "/proc/$old_pid/cmdline" 2>/dev/null)
    case "$old_command" in
        *daak-sshd-firewall.sh*) exit 0 ;;
    esac
fi

(
    chain=DAAK_SSHD_INPUT
    termux_home=/data/data/com.termux/files/home
    heartbeat="$termux_home/.config/daak/sshd-firewall.ready"
    while true; do
        healthy=1
        saw_tool=0
        for tool in /system/bin/iptables /system/bin/ip6tables; do
            [ -x "$tool" ] || continue
            saw_tool=1
            "$tool" -w -N "$chain" 2>/dev/null || true
            "$tool" -w -F "$chain" || healthy=0
            "$tool" -w -A "$chain" -i lo -j ACCEPT || healthy=0
            "$tool" -w -A "$chain" -i tun+ -j ACCEPT || healthy=0
            "$tool" -w -A "$chain" -j DROP || healthy=0
            "$tool" -w -C INPUT -p tcp --dport 8022 -j "$chain" 2>/dev/null ||
                "$tool" -w -I INPUT 1 -p tcp --dport 8022 -j "$chain" || healthy=0
        done
        [ "$saw_tool" -eq 1 ] || healthy=0
        mkdir -p "$termux_home/.config/daak"
        if [ "$healthy" -eq 1 ]; then
            if date +%s > "$heartbeat.tmp" && mv -f "$heartbeat.tmp" "$heartbeat"; then
                termux_uid=$(stat -c %u /data/data/com.termux)
                termux_gid=$(stat -c %g /data/data/com.termux)
                chown "$termux_uid:$termux_gid" "$heartbeat"
                chmod 600 "$heartbeat"
            else
                healthy=0
            fi
        fi
        if [ "$healthy" -ne 1 ]; then
            rm -f "$heartbeat" "$heartbeat.tmp"
            pkill -x sshd 2>/dev/null || true
        else
            rm -f /sdcard/Download/daak-node/sshd-firewall.ready
        fi
        sleep 60
    done
) &
echo $! > "$pidfile"

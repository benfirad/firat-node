#!/system/bin/sh

# Magisk late_start service for a narrow DAAK NODE cleanup queue. The Android
# launcher can only request one of the explicitly allowed high-CPU packages;
# arbitrary commands and arbitrary package names are rejected.
request_dir=/data/user/0/com.firat.node/files/daak-node
request_file="$request_dir/cleanup.request"
heartbeat=/data/adb/daak-app-cleaner.ready
pidfile=/data/adb/daak-app-cleaner.pid
busybox=/data/adb/magisk/busybox

handle_request() {
    [ -f "$request_file" ] || return 0
    package=$(tr -d '\r\n' < "$request_file" 2>/dev/null)
    rm -f "$request_file"
    case "$package" in
        ru.tech.imageresizershrinker|me.zhanghai.android.files|com.google.android.apps.photos)
            if [ "$package" = me.zhanghai.android.files ]; then
                # Firebase session telemetry can immediately recreate the file
                # manager after force-stop. It is unrelated to file browsing.
                /system/bin/pm disable --user 0 \
                    me.zhanghai.android.files/com.google.firebase.sessions.SessionLifecycleService \
                    >/dev/null 2>&1 || true
            fi
            /system/bin/am force-stop --user 0 "$package" >/dev/null 2>&1 || return 1
            date +%s > "$heartbeat.tmp" && mv -f "$heartbeat.tmp" "$heartbeat"
            ;;
        *)
            return 1
            ;;
    esac
}

# BusyBox inotifyd invokes this same file with event arguments.
if [ "$#" -gt 0 ]; then
    handle_request
    exit $?
fi

old_pid=$(cat "$pidfile" 2>/dev/null || printf 0)
case "$old_pid" in ''|*[!0-9]*) old_pid=0 ;; esac
if [ "$old_pid" -gt 1 ] && kill -0 "$old_pid" 2>/dev/null; then
    old_command=$(tr '\000' ' ' < "/proc/$old_pid/cmdline" 2>/dev/null)
    case "$old_command" in
        *daak-app-cleaner.sh*) exit 0 ;;
    esac
fi

/system/bin/pm disable --user 0 \
    me.zhanghai.android.files/com.google.firebase.sessions.SessionLifecycleService \
    >/dev/null 2>&1 || true
(
    while true; do
        if [ ! -d "$request_dir" ]; then
            sleep 2
            continue
        fi
        rm -f "$request_file.tmp"
        handle_request || true
        "$busybox" inotifyd "$0" "$request_dir:nyw"
        sleep 2
    done
) &
echo $! > "$pidfile"

#!/system/bin/sh
# Privacy profile for Android 10:
# - exact GPS remains available to Termux:API / DAAK Find and GPSTest diagnostics
# - apps that already had location receive coarse foreground access only
# - apps that had no location permission are not granted new access
# - Google Play services location AppOps are denied
# No mock provider is installed and no coordinate leaves the phone here.
set -eu

state_dir=/data/adb/daak-location-privacy
backup_file="$state_dir/permissions.tsv"
targets_file="$state_dir/targets.txt"
trusted_exact='com.termux shared UID / DAAK Find, GPSTest (foreground diagnostics)'

mkdir -p "$state_dir"
chmod 700 "$state_dir"

package_exists() {
    pm path "$1" >/dev/null 2>&1
}

is_trusted() {
    case "$1" in
        com.termux|com.termux.*|com.android.gpstest.osmdroid) return 0 ;;
        *) return 1 ;;
    esac
}

declares_fine_location() {
    dumpsys package "$1" 2>/dev/null | grep -q 'android.permission.ACCESS_FINE_LOCATION'
}

permission_granted() {
    package=$1
    permission=$2
    dumpsys package "$package" 2>/dev/null |
        grep -q "$permission: granted=true"
}

permission_state() {
    permission_granted "$1" "$2" && printf '1\n' || printf '0\n'
}

set_runtime_permission() {
    package=$1
    permission=$2
    desired=$3
    if [ "$desired" = 1 ]; then
        su 2000 -c "pm grant --user 0 $package $permission" >/dev/null 2>&1 || true
    else
        su 2000 -c "pm revoke --user 0 $package $permission" >/dev/null 2>&1 || true
    fi
}

set_coarse_appop() {
    package=$1
    mode=$2
    cmd appops set --user 0 --uid "$package" COARSE_LOCATION "$mode" >/dev/null 2>&1 || true
    cmd appops set --user 0 "$package" COARSE_LOCATION "$mode" >/dev/null 2>&1 || true
}

discover_targets() {
    temporary="$targets_file.$$"
    : >"$temporary"
    for package in $(pm list packages -3 | cut -d: -f2); do
        is_trusted "$package" && continue
        declares_fine_location "$package" && printf '%s\n' "$package" >>"$temporary"
    done
    for package in com.google.android.apps.maps; do
        package_exists "$package" &&
            declares_fine_location "$package" &&
            printf '%s\n' "$package" >>"$temporary"
    done
    sort -u "$temporary" >"$targets_file"
    rm -f "$temporary"
    chmod 600 "$targets_file"
}

save_original_permissions() {
    touch "$backup_file"
    chmod 600 "$backup_file"
    while IFS= read -r package; do
        awk -F '\t' -v expected="$package" '$1 == expected { found=1 } END { exit !found }' \
            "$backup_file" && continue
        printf '%s\t%s\t%s\n' "$package" \
            "$(permission_state "$package" android.permission.ACCESS_FINE_LOCATION)" \
            "$(permission_state "$package" android.permission.ACCESS_COARSE_LOCATION)" >>"$backup_file"
    done <"$targets_file"
}

restrict_package() {
    package=$1
    had_location=$2
    set_runtime_permission "$package" android.permission.ACCESS_FINE_LOCATION 0
    if [ "$had_location" = 1 ]; then
        set_runtime_permission "$package" android.permission.ACCESS_COARSE_LOCATION 1
        set_coarse_appop "$package" foreground
    else
        set_runtime_permission "$package" android.permission.ACCESS_COARSE_LOCATION 0
        set_coarse_appop "$package" default
    fi
}

enable_profile() {
    discover_targets
    save_original_permissions
    while IFS="$(printf '\t')" read -r package fine coarse; do
        package_exists "$package" || continue
        had_location=0
        if [ "$fine" = 1 ] || [ "$coarse" = 1 ]; then
            had_location=1
        fi
        restrict_package "$package" "$had_location"
    done <"$backup_file"

    set_runtime_permission com.termux.api android.permission.ACCESS_FINE_LOCATION 1
    set_runtime_permission com.termux.api android.permission.ACCESS_COARSE_LOCATION 1
    set_coarse_appop com.termux.api allow

    # GMS has a system-fixed precise permission on this Samsung build. AppOps
    # blocks its effective location access without mutating fixed permissions.
    set_coarse_appop com.google.android.gms ignore

    printf 'enabled\n' >"$state_dir/mode"
    chmod 600 "$state_dir/mode"
}

disable_profile() {
    [ -s "$backup_file" ] || exit 0
    while IFS="$(printf '\t')" read -r package fine coarse; do
        package_exists "$package" || continue
        set_runtime_permission "$package" android.permission.ACCESS_FINE_LOCATION "${fine:-0}"
        set_runtime_permission "$package" android.permission.ACCESS_COARSE_LOCATION "${coarse:-0}"
        if [ "${fine:-0}" = 1 ] || [ "${coarse:-0}" = 1 ]; then
            set_coarse_appop "$package" foreground
        else
            set_coarse_appop "$package" default
        fi
    done <"$backup_file"
    set_coarse_appop com.google.android.gms allow
    printf 'disabled\n' >"$state_dir/mode"
    chmod 600 "$state_dir/mode"
}

set_package_mode() {
    action=$1
    package=${2:-}
    [ -n "$package" ] && package_exists "$package" || {
        echo "unknown package" >&2
        exit 64
    }
    is_trusted "$package" && {
        echo "DAAK Find exact-location package is already trusted" >&2
        exit 65
    }
    if [ "$action" = allow-exact ]; then
        set_runtime_permission "$package" android.permission.ACCESS_COARSE_LOCATION 1
        set_runtime_permission "$package" android.permission.ACCESS_FINE_LOCATION 1
        set_coarse_appop "$package" foreground
    else
        restrict_package "$package" 1
    fi
}

show_status() {
    mode=$(cat "$state_dir/mode" 2>/dev/null || printf 'disabled')
    printf 'mode=%s\n' "$mode"
    printf 'exact_private=%s\n' "$trusted_exact"
    printf 'google_location_appop=%s\n' \
        "$(cmd appops get --user 0 com.google.android.gms COARSE_LOCATION 2>/dev/null |
            sed -n 's/.*COARSE_LOCATION: \([^; ]*\).*/\1/p' | head -n 1)"
    if [ -s "$targets_file" ]; then
        while IFS= read -r package; do
            printf '%s fine_granted=%s coarse_granted=%s\n' "$package" \
                "$(permission_state "$package" android.permission.ACCESS_FINE_LOCATION)" \
                "$(permission_state "$package" android.permission.ACCESS_COARSE_LOCATION)"
        done <"$targets_file"
    fi
}

case "${1:-status}" in
    enable) enable_profile ;;
    disable) disable_profile ;;
    restrict) set_package_mode restrict "${2:-}" ;;
    allow-exact) set_package_mode allow-exact "${2:-}" ;;
    status) show_status ;;
    *) echo "usage: daak-location-privacy.sh [enable|disable|status|restrict PACKAGE|allow-exact PACKAGE]" >&2; exit 64 ;;
esac

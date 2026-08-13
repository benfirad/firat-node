#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
service="$repo_dir/companion/magisk/daak-power-reserve.sh"

check() {
    expected=$1
    shift
    actual=$(sh "$service" --decision "$@")
    if [ "$actual" != "$expected" ]; then
        echo "expected $expected, got $actual for: $*" >&2
        exit 1
    fi
}

# ARMED BEACON_READY ACTIVE POWERED BATTERY
check disabled            0 0 0 0 80
check charging            1 1 0 1 5
check standby             1 1 0 0 6
check waiting-for-beacon  1 0 0 0 5
check enter               1 1 0 0 5
check reserve             1 1 1 0 5
check exit-disarmed       0 1 1 0 5
check exit-powered        1 1 1 1 5
check exit-beacon-lost    1 0 1 0 5
check exit-recovered      1 1 1 0 10

echo "DAAK Power Reserve policy OK"

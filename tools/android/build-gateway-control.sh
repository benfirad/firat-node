#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
sdk_dir=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}
android_jar="$sdk_dir/platforms/android-29/android.jar"
d8="$sdk_dir/build-tools/35.0.1/d8"
build_dir="$repo_dir/build/gateway-control"
source_file="$script_dir/com/daak/node/tools/GatewayControl.java"
output_jar="$build_dir/daak-gateway-control.jar"

if [ ! -f "$android_jar" ] || [ ! -x "$d8" ]; then
    echo "Android SDK platform 29 and build-tools 35.0.1 are required" >&2
    exit 1
fi

rm -rf "$build_dir"
mkdir -p "$build_dir/classes" "$build_dir/dex"
javac -source 8 -target 8 -bootclasspath "$android_jar" \
    -d "$build_dir/classes" "$source_file"
"$d8" --min-api 29 --output "$build_dir/dex" \
    "$build_dir/classes/com/daak/node/tools/GatewayControl.class" \
    "$build_dir/classes/com/daak/node/tools/GatewayControl\$1.class"
(CDPATH= cd -- "$build_dir/dex" && zip -q "$output_jar" classes.dex)
printf '%s\n' "$output_jar"

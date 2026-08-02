#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_apk=${OUTPUT_APK:-"$project_dir/DAAK-NODE.apk"}
sdk_dir=${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}
android_jar="$sdk_dir/platforms/android-29/android.jar"
build_tools="$sdk_dir/build-tools/35.0.1"
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM

mkdir -p "$build_dir/classes" "$build_dir/dex" "$build_dir/generated"
resource_args=""
if [ -d "$project_dir/res" ]; then
  "$build_tools/aapt2" compile --dir "$project_dir/res" -o "$build_dir/resources.zip"
  resource_args="-R $build_dir/resources.zip --java $build_dir/generated"
fi
"$build_tools/aapt2" link -o "$build_dir/unsigned.apk" -I "$android_jar" \
  --manifest "$project_dir/AndroidManifest.xml" --min-sdk-version 28 --target-sdk-version 29 \
  $resource_args
find "$project_dir/src" "$build_dir/generated" -name '*.java' -print0 | \
  xargs -0 javac --release 8 -classpath "$android_jar" -d "$build_dir/classes"
"$build_tools/d8" --lib "$android_jar" --min-api 28 --output "$build_dir/dex" \
  $(find "$build_dir/classes" -name '*.class' -print)
zip -q -j "$build_dir/unsigned.apk" "$build_dir/dex/classes.dex"
"$build_tools/zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
"$build_tools/apksigner" sign --ks "$HOME/.android/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$output_apk" "$build_dir/aligned.apk"
"$build_tools/apksigner" verify "$output_apk"
echo "$output_apk"

#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_apk=${OUTPUT_APK:-"$project_dir/FIRAT-NODE.apk"}
sdk_dir=${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}
android_jar="$sdk_dir/platforms/android-29/android.jar"
build_tools="$sdk_dir/build-tools/35.0.1"
build_dir=$(mktemp -d)

mkdir -p "$build_dir/classes" "$build_dir/dex"
find "$project_dir/src" -name '*.java' -print0 | \
  xargs -0 javac --release 8 -classpath "$android_jar" -d "$build_dir/classes"
"$build_tools/d8" --lib "$android_jar" --min-api 28 --output "$build_dir/dex" \
  $(find "$build_dir/classes" -name '*.class' -print)
"$build_tools/aapt2" link -o "$build_dir/unsigned.apk" -I "$android_jar" \
  --manifest "$project_dir/AndroidManifest.xml" --min-sdk-version 28 --target-sdk-version 29
zip -q -j "$build_dir/unsigned.apk" "$build_dir/dex/classes.dex"
"$build_tools/zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
"$build_tools/apksigner" sign --ks "$HOME/.android/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$output_apk" "$build_dir/aligned.apk"
"$build_tools/apksigner" verify "$output_apk"
echo "$output_apk"

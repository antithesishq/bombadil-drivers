#!/usr/bin/env bash
# Packages the helper's compiled classes and the Kotlin standard library
# into a signed APK with the Android SDK's build tools (no Android Gradle
# plugin needed). R8 drops the parts of the standard library the helper
# doesn't use (see rules.pro):
#
#   build.sh <work dir> <output apk> <classes dir or jar>...
#
# Called by Gradle's `helperApk` task; needs ANDROID_HOME.
set -euo pipefail

work=$1 apk=$2
shift 2
here=$(cd "$(dirname "$0")" && pwd)
tools=$ANDROID_HOME/build-tools/36.0.0
android_jar=$ANDROID_HOME/platforms/android-35/android.jar

inputs=()
for input in "$@"; do
  if [[ -d $input ]]; then
    while IFS= read -r -d '' class; do inputs+=("$class"); done < <(find "$input" -name '*.class' -print0)
  elif [[ -f $input ]]; then
    inputs+=("$input")
  fi
done

rm -rf "$work/dex" "$work/unsigned.apk" "$work/aligned.apk"
mkdir -p "$work/dex" "$(dirname "$apk")"
java -cp "$tools/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 26 --lib "$android_jar" \
  --pg-conf "$here/rules.pro" --output "$work/dex" "${inputs[@]}"
"$tools/aapt2" link -I "$android_jar" --manifest "$here/AndroidManifest.xml" -o "$work/unsigned.apk"
jar --update --no-manifest --file "$work/unsigned.apk" -C "$work/dex" classes.dex
"$tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"

# A key per build directory; the driver reinstalls the helper if a new key
# makes the update fail.
keystore=$work/debug.keystore
if [[ ! -f $keystore ]]; then
  keytool -genkeypair -keystore "$keystore" -storepass android -keypass android -alias debug \
    -keyalg RSA -keysize 2048 -validity 10000 -dname CN=bombadil-helper >/dev/null 2>&1
fi
"$tools/apksigner" sign --ks "$keystore" --ks-pass pass:android --out "$apk" "$work/aligned.apk"
rm -f "$apk.idsig"

#!/usr/bin/env bash
# Packages the helper's compiled classes into a signed APK with the
# Android SDK's build tools (no Android Gradle plugin needed):
#
#   build.sh <classes dir> <work dir> <output apk>
#
# Called by Gradle's `helperApk` task; needs ANDROID_HOME.
set -euo pipefail

classes=$1 work=$2 apk=$3
here=$(cd "$(dirname "$0")" && pwd)
tools=$ANDROID_HOME/build-tools/36.0.0
android_jar=$ANDROID_HOME/platforms/android-35/android.jar

rm -rf "$work/dex" "$work/unsigned.apk" "$work/aligned.apk"
mkdir -p "$work/dex" "$(dirname "$apk")"
"$tools/d8" --release --min-api 26 --lib "$android_jar" --output "$work/dex" $(find "$classes" -name '*.class')
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

#!/usr/bin/env bash
# Packages the in-process agent for one app: an instrumentation APK whose
# target is that app, signed with the app's key (Android only lets an
# instrumentation into an app's process if both share a signature):
#
#   package.sh <agent dex dir> <app package> <keystore> <output apk> [alias] [password]
#
# For a debug build the key is usually ~/.android/debug.keystore, alias
# androiddebugkey, password android. Needs ANDROID_HOME. The dex comes
# from `gradle agentDex` (build/agent/dex).
set -euo pipefail

dex=$1 app=$2 keystore=$3 apk=$4 alias=${5:-androiddebugkey} pass=${6:-android}
here=$(cd "$(dirname "$0")" && pwd)
tools=$ANDROID_HOME/build-tools/36.0.0
android_jar=$ANDROID_HOME/platforms/android-35/android.jar
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$(dirname "$apk")"
"$tools/aapt2" link -I "$android_jar" --manifest "$here/AndroidManifest.xml" \
  --rename-manifest-package "$app.bombadil" --rename-instrumentation-target-package "$app" \
  -o "$work/unsigned.apk"
jar --update --no-manifest --file "$work/unsigned.apk" -C "$dex" classes.dex
"$tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias "$alias" --ks-pass "pass:$pass" --key-pass "pass:$pass" \
  --out "$apk" "$work/aligned.apk"
rm -f "$apk.idsig"

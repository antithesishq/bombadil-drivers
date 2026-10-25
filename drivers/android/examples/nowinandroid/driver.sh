#!/usr/bin/env bash
# The driver Bombadil runs for each test run: the adb driver for Now in
# Android, on the first running emulator that no other driver is using.
# Run setup.sh first, and Bombadil inside `nix develop`.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
source "$here/env.sh"

if [[ -z ${BOMBADIL_ANDROID_SERIALS:-} ]]; then
  BOMBADIL_ANDROID_SERIALS=$(adb devices | awk '/^emulator-.*\tdevice$/ {print $1}' | paste -sd, -)
  export BOMBADIL_ANDROID_SERIALS
fi

exec "$repo/build/install/bombadil-compose/bin/bombadil-android" \
  --package "$NIA_PACKAGE" \
  --apk "$NIA_APK" \
  --grant android.permission.POST_NOTIFICATIONS

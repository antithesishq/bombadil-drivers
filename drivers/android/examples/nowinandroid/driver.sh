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

# NIA_NO_AGENT=1: drive the app from outside only, through the helper.
agent=(--agent "$NIA_AGENT")
if [[ -n ${NIA_NO_AGENT:-} ]]; then agent=(); fi
# NIA_ALLOW_EXTERNAL=1: let the app open other apps (the browser for an
# article) instead of the agent blocking them.
if [[ -n ${NIA_ALLOW_EXTERNAL:-} ]]; then agent+=(--allow-external-activities); fi

exec "$repo/build/install/bombadil-compose/bin/bombadil-android" \
  --package "$NIA_PACKAGE" \
  --apk "$NIA_APK" \
  "${agent[@]}" \
  --grant android.permission.POST_NOTIFICATIONS

#!/usr/bin/env bash
# Gets everything ready for testing Now in Android with Bombadil:
# clones and pins the app, builds its demoDebug APK, builds the driver,
# and boots EMULATORS headless emulators (default 1), reusing any that
# are already running. Idempotent. Run it inside `nix develop`.
#
#   examples/nowinandroid/setup.sh          # one emulator
#   EMULATORS=3 examples/nowinandroid/setup.sh
#   examples/nowinandroid/setup.sh stop     # kill the emulators
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
source "$here/env.sh"

if [[ ${1:-} == stop ]]; then
  for serial in $(adb devices | awk '/^emulator-/ {print $1}'); do
    echo "stopping $serial" >&2
    adb -s "$serial" emu kill || true
  done
  exit 0
fi

if [[ ! -d $NIA_DIR/.git ]]; then
  mkdir -p "$(dirname "$NIA_DIR")"
  git clone https://github.com/android/nowinandroid "$NIA_DIR"
fi
if [[ $(git -C "$NIA_DIR" rev-parse HEAD) != "$NIA_COMMIT" ]]; then
  git -C "$NIA_DIR" fetch origin "$NIA_COMMIT"
  git -C "$NIA_DIR" checkout --detach "$NIA_COMMIT"
fi

if [[ ! -f $NIA_APK || -n $(find "$NIA_DIR" -newer "$NIA_APK" -name '*.kt' -not -path '*/build/*' -print -quit) ]]; then
  # Nixpkgs' Gradle is older than the 9.x that nowinandroid's AGP needs,
  # so use its wrapper (with the dev shell's JDK and Android SDK).
  (cd "$NIA_DIR" && ./gradlew --console=plain :app:assembleDemoDebug)
fi

(cd "$repo" && gradle --console=plain -q installDist)

mkdir -p "$ANDROID_AVD_HOME"
if [[ ! -d $ANDROID_AVD_HOME/$AVD_NAME.avd ]]; then
  echo no | avdmanager create avd --force -n "$AVD_NAME" -k "$AVD_IMAGE" -d pixel_6
fi

adb start-server
for ((i = 0; i < ${EMULATORS:-1}; i++)); do
  port=$((5554 + 2 * i))
  serial=emulator-$port
  if ! adb devices | grep -q "^$serial"; then
    echo "booting $serial" >&2
    # Every instance is read-only, so several can share the AVD.
    nohup emulator -avd "$AVD_NAME" -port "$port" -read-only -no-snapshot \
      -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
      -memory 4096 -cores 4 >"$ANDROID_AVD_HOME/$serial.log" 2>&1 &
  fi
done
for ((i = 0; i < ${EMULATORS:-1}; i++)); do
  serial=emulator-$((5554 + 2 * i))
  start=$SECONDS
  adb -s "$serial" wait-for-device
  until [[ $(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r') == 1 ]]; do sleep 1; done
  adb -s "$serial" install -r -g "$NIA_APK" >/dev/null
  echo "$serial ready ($((SECONDS - start))s)" >&2
done

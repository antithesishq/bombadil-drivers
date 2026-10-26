#!/usr/bin/env bash
# Gets everything ready for testing Now in Android with Bombadil:
# clones and pins the app, builds its APK (NIA_VARIANT, see env.sh), builds
# the driver, and boots EMULATORS headless emulators (default 1), reusing
# any that are already running. Idempotent. Run it inside `nix develop`.
#
#   examples/nowinandroid/setup.sh          # one emulator
#   EMULATORS=3 examples/nowinandroid/setup.sh
#   NIA_GPU=swiftshader examples/nowinandroid/setup.sh
#   examples/nowinandroid/setup.sh stop     # kill the emulators
#
# The emulators render on the host's GPU when there is one (NIA_GPU=auto,
# the default): see boot() below. NIA_GPU=swiftshader forces software
# rendering, NIA_GPU=host requires the GPU.
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

if [[ ! -f $NIA_APK || -n $(find "$NIA_DIR" "$here/agent-keep.pro" -newer "$NIA_APK" \( -name '*.kt' -o -name '*.pro' \) -not -path '*/build/*' -print -quit) ]]; then
  # Nixpkgs' Gradle is older than the 9.x that nowinandroid's AGP needs,
  # so use its wrapper (with the dev shell's JDK and Android SDK).
  (cd "$NIA_DIR" && ./gradlew --console=plain --init-script "$here/agent-keep.init.gradle" "$NIA_TASK")
fi

(cd "$repo" && gradle --console=plain -q installDist agentDex)
# The agent runs inside the app, so it must carry the app's signature.
"$repo/android-agent/package.sh" "$repo/build/agent/dex" "$NIA_PACKAGE" "$NIA_KEYSTORE" "$NIA_AGENT"

mkdir -p "$ANDROID_AVD_HOME"
if [[ ! -d $ANDROID_AVD_HOME/$AVD_NAME.avd ]]; then
  echo no | avdmanager create avd --force -n "$AVD_NAME" -k "$AVD_IMAGE" -d pixel_6
fi

# Rendering: on the host's GPU if it has one, else SwiftShader on the CPU.
# `emulator -gpu host` doesn't work headless here: its host renderer uses
# GLX, which needs an X server, and the launcher puts its bundled
# SwiftShader GL first on LD_LIBRARY_PATH. So qemu is started directly,
# with ANDROID_EGL_ON_EGL=1 (the renderer uses EGL, which the GPU drivers
# offer without a display) and the host's drivers (NIA_GL_DRIVERS, NixOS's
# /run/opengl-driver by default) found through libglvnd (GLVND_LIB, from
# the dev shell), which picks NVIDIA's EGL before Mesa's.
gl=${NIA_GL_DRIVERS:-/run/opengl-driver}
gpu=${NIA_GPU:-auto}
if [[ $gpu == auto ]]; then
  gpu=swiftshader
  if ls "$gl"/lib/libEGL_*.so.0 /dev/dri/renderD* >/dev/null 2>&1; then gpu=host; fi
fi
emu=$(dirname "$(readlink -f "$(command -v emulator)")")

# boot <port> <gpu>: starts an emulator in the background. Every instance
# is read-only, so several can share the AVD.
boot() {
  local args=(-avd "$AVD_NAME" -port "$1" -read-only -no-snapshot -no-window -no-audio -no-boot-anim -memory 4096 -cores 4)
  local log=$ANDROID_AVD_HOME/emulator-$1.log
  if [[ $2 == host ]]; then
    ANDROID_EMULATOR_LAUNCHER_DIR=$emu ANDROID_EGL_ON_EGL=1 \
      LD_LIBRARY_PATH=$emu/lib64:$emu/lib64/vulkan:${GLVND_LIB:?run inside nix develop}:$gl/lib \
      VK_ADD_DRIVER_FILES=${VK_ADD_DRIVER_FILES-$gl/share/vulkan/icd.d} \
      nohup "$emu/qemu/linux-x86_64/qemu-system-x86_64-headless" "${args[@]}" -gpu host >"$log" 2>&1 &
  else
    nohup emulator "${args[@]}" -gpu swiftshader_indirect >"$log" 2>&1 &
  fi
  pids[emulator-$1]=$!
  modes[emulator-$1]=$2
}

# The adb server outlives this script: keep it off our output, or a caller
# reading it (a pipe) would wait for the server to exit.
adb start-server </dev/null >/dev/null 2>&1
declare -A pids modes
for ((i = 0; i < ${EMULATORS:-1}; i++)); do
  port=$((5554 + 2 * i))
  if ! adb devices | grep -q "^emulator-$port"; then
    echo "booting emulator-$port ($gpu)" >&2
    boot "$port" "$gpu"
  fi
done
for ((i = 0; i < ${EMULATORS:-1}; i++)); do
  port=$((5554 + 2 * i))
  serial=emulator-$port
  start=$SECONDS
  until [[ $(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do
    if [[ -n ${pids[$serial]:-} ]] && ! kill -0 "${pids[$serial]}" 2>/dev/null; then
      echo "$serial exited; see $ANDROID_AVD_HOME/$serial.log" >&2
      tail -3 "$ANDROID_AVD_HOME/$serial.log" >&2
      if [[ ${NIA_GPU:-auto} == auto && ${modes[$serial]:-} == host ]]; then
        echo "booting $serial again with SwiftShader" >&2
        boot "$port" swiftshader
        continue
      fi
      exit 1
    fi
    sleep 1
  done
  adb -s "$serial" install -r -g "$NIA_APK" >/dev/null
  renderer=$(adb -s "$serial" shell dumpsys SurfaceFlinger | sed -n 's/^GLES: //p' | cut -d, -f2)
  echo "$serial ready ($((SECONDS - start))s), rendering on$renderer" >&2
done

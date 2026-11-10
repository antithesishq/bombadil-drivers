#!/usr/bin/env bash
# Bombadil's driver command for compass_app; run setup.sh first.
#
# flutter_tester, the headless engine that `flutter test` uses, runs the
# app's kernel with the driver in it, on the app's assets. Dart's print
# and the engine's logs go to stdout, so the protocol goes out on file
# descriptor 3, which is Bombadil's stdout, and stdout goes to stderr.
here=$(cd "$(dirname "$0")" && pwd)
export BOMBADIL_FLUTTER_PROTOCOL_FD=3
export BOMBADIL_FLUTTER_FONTS="$here/out/fonts"

# Each run gets its own files (shared preferences, the image cache), so
# that runs, including parallel ones, don't see each other's.
data=$(mktemp -d "${TMPDIR:-/tmp}/compass_app.XXXXXX")
trap 'rm -rf "$data"' EXIT
export XDG_DATA_HOME="$data/data" XDG_CACHE_HOME="$data/cache" XDG_CONFIG_HOME="$data/config"

"$here/out/flutter_tester" \
  --icu-data-file-path="$here/out/icudtl.dat" \
  --disable-vm-service \
  --enable-checked-mode \
  --verify-entry-points \
  --enable-software-rendering \
  --non-interactive \
  --flutter-assets-dir="$here/out/flutter_assets" \
  "$here/out/kernel_blob.bin" \
  <&0 3>&1 1>&2 &
app=$!
trap 'kill "$app" 2>/dev/null' TERM INT
wait "$app"
status=$?
# A trap interrupted the wait: wait for the app to exit.
if kill -0 "$app" 2>/dev/null; then
  wait "$app"
  status=$?
fi
exit "$status"

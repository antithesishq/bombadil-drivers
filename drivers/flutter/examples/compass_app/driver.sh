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
exec "$here/out/flutter_tester" \
  --icu-data-file-path="$here/out/icudtl.dat" \
  --disable-vm-service \
  --enable-checked-mode \
  --verify-entry-points \
  --enable-software-rendering \
  --non-interactive \
  --flutter-assets-dir="$here/out/flutter_assets" \
  "$here/out/kernel_blob.bin" \
  3>&1 1>&2

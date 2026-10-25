#!/usr/bin/env bash
# Bombadil's driver command for compass_app; run setup.sh first.
here=$(cd "$(dirname "$0")" && pwd)
exec "$here/out/bombadil-flutter-driver" \
  --name compass_app \
  --assets "$here/out/flutter_assets" \
  --icu-data "$here/out/icudtl.dat" \
  --fonts "$here/out/fonts" \
  --size 1024x768 \
  "$@"

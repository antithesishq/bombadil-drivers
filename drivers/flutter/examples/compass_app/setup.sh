#!/usr/bin/env bash
# Prepares the compass_app experiment: clones flutter/samples at a pinned
# commit, builds compass_app's debug asset bundle and the Bombadil
# entrypoint's kernel, and links what driver.sh needs into
# examples/compass_app/out/.
#
# From drivers/flutter:
#   nix develop ../..#flutter -c examples/compass_app/setup.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
samples=${FLUTTER_SAMPLES:-$HOME/repositories/github.com/flutter/samples}
commit=21c71902bb2c7da3cce328ba5946cba2f35a660a

if [ ! -d "$samples/.git" ]; then
  git clone --filter=blob:none https://github.com/flutter/samples.git "$samples"
fi
if [ "$(git -C "$samples" rev-parse HEAD)" != "$commit" ]; then
  git -C "$samples" fetch origin "$commit"
  git -C "$samples" checkout --detach "$commit"
fi

mkdir -p "$here/out"
ln -sfn "$samples/compass_app/app" "$here/out/app"

# The app's assets, from the unchanged app.
(cd "$here/out/app" && flutter build bundle --debug --target-platform linux-x64)

# The app's code with the driver: a debug (JIT) kernel of bombadil_main.dart.
(cd "$here" && flutter pub get &&
  flutter build bundle --debug --target-platform linux-x64 -t bombadil_main.dart)

engine=$(dirname "$(readlink -f "$(command -v flutter)")")/cache/artifacts/engine/linux-x64
ln -sfn "$engine/flutter_tester" "$here/out/flutter_tester"
ln -sfn "$engine/icudtl.dat" "$here/out/icudtl.dat"
ln -sfn "$samples/compass_app/app/build/flutter_assets" "$here/out/flutter_assets"
ln -sfn "$here/build/flutter_assets/kernel_blob.bin" "$here/out/kernel_blob.bin"
ln -sfn "$BOMBADIL_FLUTTER_FONTS" "$here/out/fonts"
echo "ready: $here/driver.sh"

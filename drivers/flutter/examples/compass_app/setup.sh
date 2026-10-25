#!/usr/bin/env bash
# Prepares the compass_app experiment: clones flutter/samples at a pinned
# commit, builds compass_app's debug bundle, builds the driver, and links
# everything the launcher needs into examples/compass_app/out/.
#
# Run it inside the dev shell: nix develop -c examples/compass_app/setup.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
samples=${FLUTTER_SAMPLES:-$HOME/repositories/github.com/flutter/samples}
commit=21c71902bb2c7da3cce328ba5946cba2f35a660a

if [ ! -d "$samples/.git" ]; then
  git clone --filter=blob:none https://github.com/flutter/samples.git "$samples"
fi
if [ "$(git -C "$samples" rev-parse HEAD)" != "$commit" ]; then
  git -C "$samples" fetch origin "$commit"
  git -C "$samples" checkout --detach "$commit"
fi

# The app runs unchanged: a plain debug (JIT) bundle of lib/main.dart.
(cd "$samples/compass_app/app" &&
  flutter build bundle --debug --target-platform linux-x64)

(cd "$repo/embedder" && cargo build --release)

icu=$(dirname "$(readlink -f "$(command -v flutter)")")/cache/artifacts/engine/linux-x64/icudtl.dat
mkdir -p "$here/out"
ln -sfn "$samples/compass_app/app/build/flutter_assets" "$here/out/flutter_assets"
ln -sfn "$icu" "$here/out/icudtl.dat"
ln -sfn "$BOMBADIL_FLUTTER_FONTS" "$here/out/fonts"
ln -sfn "$repo/embedder/target/release/bombadil-flutter-driver" "$here/out/bombadil-flutter-driver"
echo "ready: $here/driver.sh"

# Get started

Use Bombadil's **RPC branch**, `uzaaft/dev/external-drivers`. The drivers need
the `external` commands from that branch, not a regular Bombadil release.

**This is experimental.** The protocol, driver APIs, and specifications can
change together. Use disposable app data and dedicated test devices: a campaign
can perform real actions, change settings, and write files.

The instructions below use installed toolchains. Nix is optional; see
[Optional Nix shells](#optional-nix-shells) if you prefer a development shell.

## Build Bombadil

Install [Rust](https://www.rust-lang.org/tools/install) with Cargo and rustup,
Git, and a native C/C++ compiler. On macOS, install the Xcode command-line
tools. Use a current stable Rust toolchain, not an older distribution package.

The CLI also builds the inspector UI. Install
[Binaryen](https://github.com/WebAssembly/binaryen) (`wasm-opt`) through your
package manager, and prepare its Rust tools:

```sh
rustup update stable
rustup default stable
rustup target add wasm32-unknown-unknown
cargo install --locked trunk
cargo install --locked wasm-bindgen-cli --version 0.2.121
```

The `wasm-bindgen-cli` version must match Bombadil's `Cargo.lock`; update that
command if the RPC branch changes its dependency version.

Clone and build the RPC branch in a separate checkout:

```sh
git clone --branch uzaaft/dev/external-drivers \
  https://github.com/antithesishq/bombadil.git bombadil-rpc
cd bombadil-rpc
cargo fetch --locked
cargo build --release -p bombadil-cli --no-default-features --features external
export BOMBADIL="$PWD/target/release/bombadil"
"$BOMBADIL" external --help
```

Fetching first prepares the inspector build, which runs Cargo offline. The
feature selection builds external-driver support without the terminal backend;
it does not require the terminal backend's Zig toolchain.

Keep `BOMBADIL` set to this absolute path. In a new terminal, export it again
with the path to your build. See the
[Bombadil repository](https://github.com/antithesishq/bombadil/tree/uzaaft/dev/external-drivers)
for its build requirements.

In another terminal, clone this repository alongside the Bombadil checkout,
or use an existing driver checkout. Export `BOMBADIL` there before continuing.
The following commands start from the driver repository root unless stated
otherwise.

```sh
git clone https://github.com/antithesishq/bombadil-drivers.git
cd bombadil-drivers
mkdir -p runs
```

## How a campaign works

Bombadil starts the driver as a child process. The driver reports state and
applies actions. A TypeScript specification extracts values from that state,
generates actions, and checks properties. The state and action shapes belong
to the driver, not to the target application's language.

Pass engine options before `--` and the driver command after it. Start with
`--parallelism 1`, especially when a driver controls a shared desktop or device.
`--time-limit-fuzz` bounds the campaign; `--time-limit-run` bounds each run.

Bombadil resolves specification imports from `@antithesishq/bombadil` and
`@antithesishq/bombadil/external` itself. A separate npm SDK installation is
not part of this setup.

## Rust: Rune

The [Rune driver](https://github.com/antithesishq/bombadil-drivers/tree/main/drivers/rune)
runs inside an opt-in test build. It reads Rune's element tree and sends input
through Rune, without a Linux platform driver or desktop session.

On Linux, install Rust, a C/C++ compiler, `pkg-config`, and the development
libraries for Wayland, xkbcommon, and Fontconfig. Install a Vulkan loader,
a Vulkan driver, and fonts for rendering. For example, on Debian/Ubuntu:

```sh
sudo apt-get install build-essential pkg-config libwayland-dev \
  libxkbcommon-dev libfontconfig1-dev libvulkan1 mesa-vulkan-drivers \
  fonts-dejavu-core
```

Then build the counter and run its specification:

```sh
cargo build --locked --manifest-path drivers/rune/Cargo.toml --examples
"$BOMBADIL" external fuzz \
  --specification drivers/rune/examples/counter.spec.ts \
  --parallelism 1 --time-limit-fuzz 5m --time-limit-run 10s \
  --output-path runs/counter.bin \
  -- drivers/rune/target/debug/examples/counter --bombadil
```

The property deliberately fails when the counter reaches four. This gives you
a violation to inspect, shrink, and replay. Add `--screenshots` after
`--bombadil` for PNG observations; these need Vulkan and fonts, including when
using a software renderer such as Mesa lavapipe.

For your own app, use the same Rune crate instance for the app and driver.
Keep normal startup intact and enable the driver only in a test build. See
the Rune README for the runtime callback, dependency override, and settling
contract. Headless execution does not test native OS input or the compositor.

## Dart: Flutter

The [Flutter driver](https://github.com/antithesishq/bombadil-drivers/tree/main/drivers/flutter)
uses an instrumented entrypoint and Flutter's headless engine. The Compass
example needs x86_64 Linux, Bash, Git, and a
[Flutter SDK](https://docs.flutter.dev/install) on `PATH`. The driver requires
Flutter 3.38 or later with Dart 3.10 or later. Follow Flutter's Linux setup
instructions for the engine's native libraries, then check your installation:

```sh
flutter doctor
flutter precache --linux
```

Install Fontconfig's `fc-match` command and the Roboto and DejaVu Sans font
families. The setup script needs `BOMBADIL_FLUTTER_FONTS` to point to a directory
with one subdirectory per family. For example, after installing the fonts:

```sh
export BOMBADIL_FLUTTER_FONTS="$HOME/.cache/bombadil/flutter-fonts"
for family in Roboto "DejaVu Sans"; do
  mkdir -p "$BOMBADIL_FLUTTER_FONTS/$family"
  for style in Regular Bold Italic "Bold Italic"; do
    ln -sf "$(fc-match -f '%{file}' "$family:style=$style")" \
      "$BOMBADIL_FLUTTER_FONTS/$family/$style.ttf"
  done
done
```

Prepare a dedicated copy of the sample:

```sh
cd drivers/flutter
mkdir -p "$HOME/.cache/bombadil"
export FLUTTER_SAMPLES="$HOME/.cache/bombadil/flutter-samples"
examples/compass_app/setup.sh
```

The setup script checks out a pinned Flutter samples revision, so do not point
`FLUTTER_SAMPLES` at a checkout containing your work. The runner isolates XDG
data, cache, and configuration for each run.

Run from `drivers/flutter`:

```sh
"$BOMBADIL" external fuzz \
  --specification examples/compass_app/spec.ts \
  --parallelism 1 --time-limit-fuzz 5m --time-limit-run 10s \
  --output-path ../../runs/compass.bin \
  -- examples/compass_app/driver.sh
```

## Kotlin: Android

The [Android driver](https://github.com/antithesishq/bombadil-drivers/tree/main/drivers/android)
uses ADB and UiAutomation. Its Compose agent is optional. The Now in Android
example needs x86_64 Linux, access to KVM, and a dedicated emulator environment.
Install JDK 21, Gradle 8.14.x, Bash, Git, `jq`, and util-linux (`setsid`). The
target app uses its own Gradle wrapper; the driver uses the installed `gradle`.

Install the [Android SDK command-line tools](https://developer.android.com/tools)
and set `JAVA_HOME` to your JDK 21 directory. Point `ANDROID_HOME` at your SDK
(the example below uses Android Studio's usual Linux location), then install
the packages used by the helper, agent, target app, and emulator:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
sdkmanager --licenses
sdkmanager "platform-tools" "emulator" \
  "platforms;android-35" "platforms;android-37.0" \
  "build-tools;36.0.0" "build-tools;37.0.0" \
  "system-images;android-35;default;x86_64"
java -version
gradle --version
adb version
```

Keep both build-tools versions: the helper and agent scripts use 36.0.0;
the target app uses the newer SDK. Use SwiftShader to avoid the setup script's
Nix-specific host-GPU library configuration:

```sh
cd drivers/android
export NIA_DIR="$HOME/.cache/bombadil/nowinandroid"
export NIA_GPU=swiftshader
examples/nowinandroid/setup.sh
```

The script prepares a pinned app checkout, builds the test components, and
starts an emulator. Keep its clone separate from your own work. To use only
external UiAutomation, set `NIA_NO_AGENT=1` for setup and the campaign. Keep
variant and emulator settings consistent between both commands.

Run from `drivers/android`:

```sh
"$BOMBADIL" external fuzz \
  --specification examples/nowinandroid/spec.ts \
  --parallelism 1 --time-limit-fuzz 5m --time-limit-run 10s \
  --output-path ../../runs/nowinandroid.bin \
  -- examples/nowinandroid/driver.sh
```

The setup script's `stop` command stops all ADB emulators, not just this
campaign's emulator. Do not use it in a shared emulator environment.

## Swift: macOS and iOS

Both drivers are black-box: the target app does not link Bombadil or need to
use SwiftUI. They can also drive apps written with other UI frameworks.

### Write a first specification

Save this as `app.spec.ts` in the repository root. It is a smoke specification:
it checks for action errors while waiting, not application correctness.
Replace the waits with app-specific actions and add properties over the
driver's observed state before treating it as useful fuzz coverage.

```typescript
import { always } from "@antithesishq/bombadil";
import { actions, extract } from "@antithesishq/bombadil/external";

const actionError = extract(
  (state: { actionError?: string | null }) => state.actionError ?? null,
);

export const noActionErrors = always(() => actionError.current === null);
export const wait = actions(() => [{ Wait: { ms: 100 } }]);
```

### macOS

Use Xcode and a terminal in the logged-in GUI session. Build the
[macOS driver](https://github.com/antithesishq/bombadil-drivers/tree/main/drivers/macos)
and grant Accessibility permission when prompted:

```sh
/usr/bin/swift build -c release --package-path drivers/macos
drivers/macos/.build/release/bombadil-macos --check-access
```

Run against your app's executable, not its `.app` directory. Replace the
example path with a disposable test app:

```sh
"$BOMBADIL" external fuzz \
  --specification app.spec.ts \
  --parallelism 1 --time-limit-fuzz 5m --time-limit-run 10s \
  --output-path runs/macos.bin \
  -- drivers/macos/.build/release/bombadil-macos --profile \
  -- /path/to/Example.app/Contents/MacOS/Example
```

State includes `running`, `windows`, `statusItems`, and `actionError`. Use the
driver's action model to select elements by identifier, title, role, or path.
Optional window screenshots need macOS 14+ and Screen Recording permission.

### iOS

Start a compatible
[WebDriverAgent runner](https://github.com/appium/appium-ios/tree/main/packages/webdriveragent)
on a dedicated simulator or provisioned device, and install your target app.
The driver does not install or start WDA for you. Keep its HTTP endpoint on a
trusted network or behind a tunnel.

Build the [iOS bridge](https://github.com/antithesishq/bombadil-drivers/tree/main/drivers/ios):

```sh
swift build -c release --package-path drivers/ios
```

Replace the bundle ID and WDA URL for your target:

```sh
"$BOMBADIL" external fuzz \
  --specification app.spec.ts \
  --parallelism 1 --time-limit-fuzz 5m --time-limit-run 10s \
  --output-path runs/ios.bin \
  -- drivers/ios/.build/release/bombadil-ios \
  --bundle-id com.example.app --wda-url http://127.0.0.1:8100 --profile
```

Creating a session restarts the app and replaces an existing WDA session.
State contains WDA's JSON `tree` and `actionError`. Selectors must match
exactly one element; `name` is the accessibility identifier, falling back to
the label. Add `--screenshots` for device PNGs. Full attribute capture is the
default; omit attributes only when your properties do not need them.

## Replay and shrink

An output path such as `runs/counter.bin` produces numbered traces such as
`runs/counter-0.bin`. Choose the failing trace reported by Bombadil. Replay
with `external test --reproduce`, using the same specification, driver, and
target configuration:

```sh
"$BOMBADIL" external test \
  --specification drivers/rune/examples/counter.spec.ts \
  --reproduce runs/counter-0.bin \
  -- drivers/rune/target/debug/examples/counter --bombadil
"$BOMBADIL" external shrink \
  --specification drivers/rune/examples/counter.spec.ts \
  --trace runs/counter-0.bin --output-path runs/counter-small.bin \
  --time-limit 1m --parallelism 1 \
  -- drivers/rune/target/debug/examples/counter --bombadil
```

Reset external app data and services when a replay needs a fresh environment.
A stable UI tree does not prove that background work or external I/O has
finished. Check each driver's settling contract when writing properties.

## Build these docs

Install mdBook with Cargo, then build from the repository root:

```sh
cargo install --locked mdbook --version 0.5.4
mdbook build
mdbook serve --open
```

The generated book is in `book/`. Edit the Markdown in `docs/`, not the
generated HTML. Add chapters to `docs/SUMMARY.md`.

## Optional Nix shells

You can use Nix instead of installing the toolchains yourself. Enter a shell
from this repository's root before running that driver's commands:

| Task | Command |
| --- | --- |
| Rune on Linux | `nix develop .#rune` |
| Flutter | `nix develop .#flutter` |
| Android | `nix develop .#android` |
| macOS / iOS / Swift SDK | `nix develop .#swift` |
| Documentation | `nix develop .#docs` |

The Flutter shell sets `BOMBADIL_FLUTTER_FONTS`, so skip the manual font setup.
The Android shell supplies the SDK, JDK, Gradle, and SDK environment variables,
so skip their installation and exports. Native Apple automation still needs
Xcode, permissions, and a GUI session or WDA runner.

Bombadil core has its own `nix develop` shell in the RPC checkout. Enter that
shell before its Cargo build instead of installing the core build tools
manually. Keep the `BOMBADIL` export when changing shells.

On Intel macOS, use the native setup: this repository's locked nixpkgs version
no longer supports `x86_64-darwin`.

# Rune

A grey-box [Bombadil](https://github.com/antithesishq/bombadil) driver for
[Rune](https://github.com/anomalyco/rune) apps. It sends input through Rune,
reads the element tree, and can capture frames with Rune's offscreen renderer.

The driver is a Rust library that runs inside your app. You give it the app's
runtime and a callback that polls application work. Bombadil runs the app as a
child process and handles action generation, properties, shrinking, and replay.

Start with the [counter](examples/counter.rs) for a complete example, or
[`Driver`](src/lib.rs) for the API.

## Run

From the repository root on Linux:

```sh
nix develop .#rune
cargo build --locked --manifest-path drivers/rune/Cargo.toml --examples
bombadil external test --exit-on-violation --time-limit 10s \
  --specification drivers/rune/examples/counter.spec.ts \
  -- drivers/rune/target/debug/examples/counter --bombadil
```

The [specification](examples/counter.spec.ts) deliberately fails at count four.
Without `--bombadil`, the counter opens a normal Rune window. Add `--screenshots`
after `--bombadil` to include PNGs in the trace.

Screenshots need a Vulkan loader, a Vulkan implementation, and fonts on Linux.
Mesa lavapipe works without a physical GPU. Transparent runtimes are not
supported by the screenshot adapter.

## Use in your app

Add `bombadil-rune` as an optional path dependency for your testing build.
Keep the app's normal startup path. Behind a test feature and an explicit flag,
build a headless runtime from the same `AppSpec` and pass it to `Driver::new`.
For an app without background work:

```rust
let mut driver = bombadil_rune::Driver::new(runtime, |_, _| true);
driver.run("my-app", std::io::stdin().lock(), std::io::stdout().lock())?;
```

The callback receives the runtime and the last frame's elapsed seconds. Use
`Runtime::update_if` to deliver model changes. Return `false` until a later
frame draws them. The counter has no background work, so it uses `|_, _| true`.
An app with pending work needs its own callback.

The app still owns its backends, external I/O, and cleanup. Use one runtime per
session. Reserve stdout for the JSONL protocol and send app logs to stderr.

### Rune version

The app and driver must use the same Rune crate instance. The driver pins
`49acd141a7839c30f9fed5b18499547b71c70887` in [Cargo.toml](Cargo.toml).
Match that dependency, or override it with your vendored Rune in the consumer
workspace's `[patch."https://github.com/anomalyco/rune.git"]` table.
An override shares runtime types; check API compatibility separately.

## Input and observations

[`Action`](src/lib.rs) supports taps by element key, clicks, text, keys, and
vertical scrolling. Coordinates are relative to the logical viewport, not the
desktop. Input uses Rune's handlers, not OS input injection. Modifier chords,
IME composition, and the native clipboard are not supported.

Each observation contains the viewport and Rune's elements: keys, paths, roles,
labels, text, rectangles, visibility, opacity, focus, and motion. The
`accessibility` field is null; this is not an OS accessibility tree.

`run` speaks Bombadil's version-0 JSONL protocol. It sends an initial state and
one state for each `apply_action`. EOF or `terminate` ends the session; malformed
requests produce an error. States carry nondecreasing host timestamps.
`not_applied` reports input rejected before dispatch. Accepted input need not
reach a handler, and background work can continue after a rejection.

## Settling

The driver advances Rune at 120 Hz for up to 600 frames per observation. It
reports `settled: true` when motion stops, no Rune wake is pending, and your
callback reports ready. If the frame budget runs out, it reports
`settled: false`. It does not jump animations to their final values.

Frame time is simulated. It does not advance network or service clocks, and
the callback must return before Bombadil's host stall timeout. Headless runs
exercise the app and Rune, not the compositor, native input, or physical
display. A test backend does not establish that the corresponding real service
works.

## Development

```sh
cargo test --locked --manifest-path drivers/rune/Cargo.toml
cargo fmt --manifest-path drivers/rune/Cargo.toml --check
cargo clippy --locked --manifest-path drivers/rune/Cargo.toml --all-targets -- -D warnings
```

The screenshot test needs a GPU backend and fonts. Run it separately:

```sh
cargo test --locked --manifest-path drivers/rune/Cargo.toml \
  --test contract opaque_screenshots -- --ignored
```

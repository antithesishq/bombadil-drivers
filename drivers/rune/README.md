# Rune

An **instrumented, in-process grey-box** driver for Rune applications. This is a
Rust library, not a standalone desktop automation program. The target app must
opt in, construct its own Rune runtime, and run the driver instead of its native
event loop. No application backend or mock results are built into the driver.

## Run

From the repository root, enter `nix develop .#rune` on Linux. There is no
standalone driver executable; build the example with Cargo:

```sh
cargo build --locked --manifest-path drivers/rune/Cargo.toml --examples
cargo test --locked --manifest-path drivers/rune/Cargo.toml
cargo fmt --manifest-path drivers/rune/Cargo.toml --check
cargo clippy --locked --manifest-path drivers/rune/Cargo.toml --all-targets -- -D warnings

bombadil external test --exit-on-violation --time-limit 10s \
  --specification drivers/rune/examples/counter.spec.ts \
  -- drivers/rune/target/debug/examples/counter --bombadil
```

The specification intentionally fails at count four. The example normally uses
Rune's native runner; `--bombadil` selects the headless driver. Add `--screenshots`
to capture PNGs. Screenshots require a working Vulkan implementation on Linux
(Mesa lavapipe works), a Vulkan loader, and fonts. The GPU smoke test is opt-in:

```sh
cargo test --locked --manifest-path drivers/rune/Cargo.toml \
  --test contract opaque_screenshots -- --ignored
```

## Integrate

Use `bombadil-rune` as an **optional** path dependency and enable it only in your
testing build. Match the application's Rune dependency to the exact git URL and
revision in `Cargo.toml` so Cargo shares the runtime types. Behind your test
feature and an explicit CLI flag, create a runtime with your application's real
`AppSpec`, then call:

```rust,ignore
let mut driver = bombadil_rune::Driver::new(runtime, |runtime, elapsed_seconds| {
    // Poll real application work here. If it changes the model, use
    // runtime.update_if(...) and return false until a later frame draws it.
    // Return true only when application work is complete.
    true
});
driver.run("my-app", std::io::stdin().lock(), std::io::stdout().lock())?;
```

The counter is self-contained and therefore uses `|_, _| true`. Do not use that
callback for an app with pending work. The application owns backend selection,
external I/O and cleanup; the adapter does not reset or restart the app. One
driver/runtime is intended for one session. Keep stdout exclusively for JSONL;
send application logs to stderr.

## Contract and limits

The wire contract follows Bombadil's `bombadil-rpc/PROTOCOL.md`.

- Speaks the current v0 JSONL protocol: `hello`, initial `state`, then one `state`
  per `{"type":"apply_action","action":...}`. EOF or `terminate` ends the
  session. Only `apply_action` is supported, not `apply`. Malformed requests emit
  `error` and fail the session.
- Actions are externally tagged JSON: `Tap { key }`, `Click { x, y }`,
  `TypeText { text }`, `Key { name }`, and `Scroll { x, y, dy }`. Coordinates are
  logical viewport coordinates, not desktop pixels. Keys support the named
  navigation/editing keys in `src/lib.rs`, or a single Unicode character. No
  modifier chords, IME composition, native clipboard or OS input delivery.
- Observations expose Rune's element tree (keys, paths, roles, labels, text,
  logical rectangles, visibility, opacity, focus and motion), plus viewport.
  `accessibility` is null: this is not an OS accessibility tree. `not_applied`
  means the adapter rejected an action before sending its input, not that every
  accepted click found a handler. Rejected input does not freeze background work.
- Settling advances Rune naturally at 120 Hz, up to 600 frames per observation.
  It waits for motion, Rune wake deadlines and the application's ready callback.
  Exhaustion returns `settled: false`; it does not snap animations or claim
  success. Simulated frame time does not advance real network/service clocks.
  The callback must not block beyond Bombadil's host stall timeout.
- States carry nondecreasing host epoch-microsecond timestamps within `run`.
  Optional PNGs use Rune's offscreen renderer. Transparent runtimes fail
  explicitly because they require Rune's private alpha-composition path.
- Headless results do **not** establish native window delivery, physical display
  output, compositor/material behavior, platform services, deterministic external
  I/O, or support on platforms not tested. App-side test backends only prove the
  behavior of those backends, not the corresponding real services.

The adapter was recovered from the Hokum/Rune prototype and pins Rune to
`49acd141a7839c30f9fed5b18499547b71c70887`. Newer revisions and locally patched
vendor trees need a separate compatibility check. A vendored app must override
the driver's Rune git dependency with its own Rune path in the consumer
workspace's `[patch."https://github.com/anomalyco/rune.git"]` table. This unifies
runtime types; it does not establish API compatibility.

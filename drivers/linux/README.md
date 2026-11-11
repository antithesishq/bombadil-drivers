# bombadil-linux

Experimental black-box Linux driver using AT-SPI observations and real input
through Hyprland and ydotool/uinput. The target app needs no instrumentation.
The host controller stays outside the disposable guest; it can observe guest
disconnection, power transitions and process failures. This is not a kernel fuzzer.

Build from the repository root with `nix build .#linux`, or enter
`nix develop .#linux` and run `cargo test --manifest-path drivers/linux/Cargo.toml`.
The guest needs a logged-in Hyprland session, working AT-SPI, ydotool access,
systemd/journald, and this binary. Only a single unrotated, scale-1 monitor at
(0,0) and printable ASCII text are supported. Other compositors are not implemented.

## Run

Run `bombadil-linux agent --enable-input` inside the disposable desktop guest.
Supply its transport as a JSON argv array, for example
`["ssh","-T","guest","/path/to/bombadil-linux","agent","--enable-input"]`.
The transport must preserve stdin/stdout; diagnostics go to stderr. Then:

```sh
bombadil external fuzz --specification drivers/linux/examples/fixture.spec.ts \
  --time-limit-fuzz 2m --time-limit-run 30s -- \
  ./result/bin/bombadil-linux run --enable-input \
  --agent-command /path/to/agent.json -- python3 /guest/path/fixture.py
```

The [fixture](examples/fixture.py) needs GTK 3 and PyGObject in the guest.
Specifications import the driver-owned [TypeScript API](index.ts) by relative
path. Bombadil owns generation, verification, traces, shrinking and replay;
the driver speaks the version-0 external JSONL protocol, not a separate fuzzer.

Actions include a selector, input and observable expectation. Input submission
is not success: `outcome` becomes true only when the expectation is observed
before its deadline. An expired outcome is false. Observations always report
`settled:false`; an expectation is not proof that background work is finished.
Ambiguous or unavailable targets fail closed. Uncertain input is never retried.
Invalid action data returns `not_applied`; backend failures end the session.

## Journals and VM control

`--log-options` reads a JSON array of scoped journal queries. The user chooses
the service/identifier, optional structured module field, severity and optional
exact-level match. See [journal.json](examples/journal.json) and
[journal.spec.ts](examples/journal.spec.ts); `extractLogs(0)` selects the first
query. Collection starts before application launch. Missing access, lost cursors,
overflow and malformed logs are errors, not healthy empty batches. Log arrival
is asynchronous; no causal link to the preceding action is assumed.

Lifecycle actions require both `--enable-lifecycle` and `--vm-config`:
`{"uuid":"DISPOSABLE-VM-UUID","qmpSocket":"/path/to/qmp.sock",
"startCommand":["/path/to/start-disposable-vm"]}`. The controller checks QMP
and guest UUIDs. Guest policy must separately authorize UUID reads, delayed
systemd power requests and the restricted `bombadil-linux signal` helper in
`/etc/sudoers.d/bombadil-vm`. Do not grant these capabilities on a real desktop.
The VM image and privilege policy belong in the Zoo harness, not this driver.

[Lifecycle](examples/lifecycle.spec.ts) and [process](examples/processes.spec.ts)
examples cover power state, boot identity and PID/start-time-qualified SIGKILL.
Unavailable guest observations are null, not healthy empty results. Process
replay requires the same identity; it does not retarget a new process by name.
Stable resume remains platform-dependent and is not guaranteed by the driver.

Imported from the local Bombadil desktop prototype. No application internals,
mock backend state, benchmark results or campaign evidence are included.

# Swift RPC SDK

Swift RPC library for [Bombadil](https://github.com/antithesishq/bombadil).
Shared by platform drivers and usable by instrumented framework drivers.
It has no dependency on SwiftUI, Accessibility, or XCTest.

Implement `BombadilDriver` and call `runDriver` to exchange JSONL over
stdin/stdout. States and actions are driver-specific JSON values.

See the [driver library](Sources/BombadilDriver/Driver.swift),
[counter example](Examples/CounterExample/main.swift), and
[specification](Examples/counter.spec.ts). Build and test with `swift build`
and `swift test` from this directory.

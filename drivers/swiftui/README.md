# SwiftUI and macOS

Swift RPC library for [Bombadil](https://github.com/antithesishq/bombadil).
Includes an external macOS driver using the Accessibility API, without
instrumenting the target application.

The macOS driver requires Xcode and Accessibility permission in a logged-in
GUI session.

See the [driver library](Sources/BombadilDriver/Driver.swift),
[counter specification](Examples/counter.spec.ts), and
[GUI session launcher](scripts/run-in-gui-session.sh).

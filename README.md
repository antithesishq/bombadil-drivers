# Bombadil drivers

External drivers for [Bombadil](https://github.com/antithesishq/bombadil), using
the prototype RPC protocol. Drivers range from pure black-box to
optional instrumentation for input, state extraction, and settling.

Platform drivers use OS facilities without instrumenting the target app.
Framework drivers can use instrumentation and do not need a platform driver
underneath them.

| Project | Scope | Development |
| --- | --- | --- |
| [Flutter](drivers/flutter/) | Instrumented Flutter semantics and headless execution | Flutter / Dart |
| [Android](drivers/android/) | ADB / UiAutomation, with an optional in-process Compose agent | Kotlin / Gradle / Android SDK |
| [macOS](drivers/macos/) | Black-box Accessibility driver for macOS apps | Swift / macOS |

From the repository root, enter a shell with `nix develop .#flutter`,
`nix develop .#android`, or `nix develop .#macos`. Run project commands from
the corresponding `drivers/` directory. Direnv selects the shell automatically
when entering a driver directory.

The framework-independent [Swift RPC SDK](lib/swift/) is shared by Swift
drivers. Its shell is `nix develop .#swift`; it is not a SwiftUI integration.

## License

All drivers in this repository are available under the [MIT license](LICENCE).

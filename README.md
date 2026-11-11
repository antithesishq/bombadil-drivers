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
| [iOS](drivers/ios/) | Black-box XCTest automation through a separate WebDriverAgent runner | Swift / Xcode |
| [Rune](drivers/rune/) | Opt-in grey-box Rune runtime, input and offscreen rendering | Rust / Rune |

From the repository root, enter a shell with `nix develop .#flutter`,
`nix develop .#android`, `nix develop .#macos`, or `nix develop .#ios`. Run project commands from
the corresponding `drivers/` directory. For Rune on Linux, use
`nix develop .#rune`. Direnv selects the shell when you enter a driver directory.

The framework-independent [Swift RPC SDK](lib/swift/) is shared by Swift
drivers. Its shell is `nix develop .#swift`; it is not a SwiftUI integration.

## License

All drivers in this repository are available under the [MIT license](LICENCE).

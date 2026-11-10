# Bombadil drivers

External drivers for [Bombadil](https://github.com/antithesishq/bombadil), using
the prototype RPC protocol. Drivers range from pure black-box to
optional instrumentation for input, state extraction, and settling.

| Project | Scope | Development |
| --- | --- | --- |
| [Flutter](drivers/flutter/) | Instrumented Flutter semantics and headless execution | Flutter / Dart |
| [Android](drivers/android/) | ADB / UiAutomation, with an optional in-process Compose agent | Kotlin / Gradle / Android SDK |
| [SwiftUI and macOS](drivers/swiftui/) | Swift RPC library and an external macOS Accessibility driver | Swift / macOS |

## License

All drivers in this repository are available under the [MIT license](LICENCE).

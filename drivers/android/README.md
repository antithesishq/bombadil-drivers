# Android

Android driver for [Bombadil](https://github.com/antithesishq/bombadil), using
ADB and UiAutomation, with an optional in-process Compose agent.

Requires the Android SDK and ADB. The Now in Android example also requires
x86_64 Linux, KVM, Nix, and a Bombadil build with `external` support.

See the [build configuration](build.gradle.kts),
[setup script](examples/nowinandroid/setup.sh), and
[specification](examples/nowinandroid/spec.ts).

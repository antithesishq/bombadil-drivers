# Android

Android driver for [Bombadil](https://github.com/antithesishq/bombadil), using
ADB and UiAutomation, with an optional in-process Compose agent.

Requires the Android SDK and ADB. The Now in Android example also requires
x86_64 Linux, KVM, JDK 21, Gradle, and a Bombadil build with `external` support.
See [Get started](../../docs/GET_STARTED.md#kotlin-android) for native setup
or the optional Nix shell.

See the [build configuration](build.gradle.kts),
[setup script](examples/nowinandroid/setup.sh), and
[specification](examples/nowinandroid/spec.ts).

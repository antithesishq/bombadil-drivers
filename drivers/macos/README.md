# bombadil-macos

Black-box driver for macOS applications, using Accessibility for state and
input, and ScreenCaptureKit for optional window screenshots. The target app
does not link Bombadil or need to use SwiftUI.

Support targets macOS releases receiving regular Apple security updates;
older releases are best-effort. Follow [Apple's security releases](https://support.apple.com/en-us/100100)
rather than a fixed latest-OS requirement. A deployment target is a technical
minimum, not a guarantee that every later OS version has been tested.

Requires Xcode, a logged-in GUI session, and Accessibility permission.
Screenshots also need macOS 14+ and Screen Recording permission.
Build with `/usr/bin/swift build -c release`, then run
`.build/release/bombadil-macos -- <app executable> [arguments...]`.
Use `--profile` to log tree-read, settling, action, and screenshot timings
to stderr. Protocol messages alone go to stdout.

See the [action model](Sources/BombadilMacOS/MacAppAction.swift) and
[GUI session launcher](scripts/run-in-gui-session.sh). The default settling
policy requires two equal responsive trees, 150 ms apart; that is observed
UI stability, not proof that background application work has finished.

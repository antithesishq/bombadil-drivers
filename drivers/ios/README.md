# bombadil-ios

Black-box driver for iOS applications through a separate
[WebDriverAgent](https://github.com/appium/appium-ios/tree/main/packages/webdriveragent)
XCUITest runner. The target app needs no Bombadil, SwiftUI, or React Native
instrumentation. The runner needs Xcode and a simulator, or a provisioned
device; the Bombadil bridge can connect over HTTP from another machine.

Use a dedicated runner/device: creating a session restarts the installed
target app and replaces any existing WDA session. Build with `swift build
-c release`, then run `.build/release/bombadil-ios --bundle-id com.example.app
--wda-url http://127.0.0.1:8100 --profile` against the running WDA endpoint.
Keep WDA on a trusted network or behind a tunnel; it controls the device.

Actions are `{"Tap":{"name":"save"}}`, `{"Tap":{"x":100,"y":200}}`,
`{"TypeText":{"name":"email","text":"user@example.com"}}`,
`{"Swipe":{"direction":"up"}}`, `{"Drag":{"fromX":100,"fromY":400,
"toX":100,"toY":100,"duration":0.1}}`, and `{"Wait":{"ms":100}}`.
Selectors accept exact `name`, `label`, and short `type` (e.g. `Button`)
and must match exactly one element. WDA's `name` is the accessibility identifier,
falling back to the label when the identifier is empty.
Coordinates are screen points, not pixels.
State contains WDA's JSON `tree` and an optional `actionError`.

WDA idle/cool-off waits are disabled. Bombadil instead compares snapshots;
this proves only observed UI stability. `--settle-timeout-ms 0` takes one
snapshot and reports `settled: false`. No per-node HTTP reads or XPath are
used. `--profile` separates HTTP and full-action costs. Screenshots are
opt-in, show the device screen, and are not atomic with the hierarchy.
Timeouts end the session; potentially applied actions are never retried.

Full attribute capture is the default. If a specification does not need them,
`--exclude-attributes visible,accessible,nativeAccessibilityElement` omits
`isVisible`, `isAccessible`, and `isNativeAccessibilityElement` from every node.
This also excludes them from stability comparisons; properties cannot check
those fields. Excluding only `visible` is also supported. Use `--profile` to
measure the capture cost for your app before reducing attribute coverage.

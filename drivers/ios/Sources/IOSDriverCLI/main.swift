import BombadilDriver
import BombadilIOS
import Foundation

let usage = """
    usage: bombadil-ios --bundle-id <installed app bundle ID> [options]

    Uses a dedicated WebDriverAgent runner; the target app is unmodified.
      --wda-url URL          WebDriverAgent endpoint (http://127.0.0.1:8100)
      --bundle-id ID         installed target application
      --settle-timeout-ms N  observed-tree stability limit (3000; 0 = one snapshot)
      --settle-interval-ms N interval between tree reads (50)
      --request-timeout-ms N HTTP request deadline (30000)
      --stall-timeout-ms N   Bombadil response timeout (90000)
      --exclude-attributes LIST omit comma-separated visible,accessible,nativeAccessibilityElement
      --screenshots         include device screenshots (may show system UI)
      --profile             log HTTP and action timings to stderr
    """

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("bombadil-ios: \(message)\n".utf8))
    exit(2)
}

var configuration = IOSConfiguration(serverURL: URL(string: "http://127.0.0.1:8100")!, bundleIdentifier: "")
var arguments = CommandLine.arguments.dropFirst()
func value(_ option: String) -> String {
    guard let value = arguments.popFirst() else { fail("\(option) needs a value") }
    return value
}
func milliseconds(_ option: String, allowZero: Bool = false) -> Int {
    guard let result = Int(value(option)), result >= (allowZero ? 0 : 1) else { fail("\(option) needs \(allowZero ? "a nonnegative" : "a positive") integer") }
    return result
}
while let argument = arguments.popFirst() {
    switch argument {
    case "--bundle-id": configuration.bundleIdentifier = value(argument)
    case "--wda-url":
        guard let url = URL(string: value(argument)), ["http", "https"].contains(url.scheme), url.host != nil else { fail("invalid WebDriverAgent URL") }
        configuration.serverURL = url
    case "--settle-timeout-ms": configuration.settleTimeout = Double(milliseconds(argument, allowZero: true)) / 1000
    case "--settle-interval-ms": configuration.settleInterval = Double(milliseconds(argument)) / 1000
    case "--request-timeout-ms": configuration.requestTimeout = Double(milliseconds(argument)) / 1000
    case "--stall-timeout-ms": configuration.stallTimeoutMs = milliseconds(argument)
    case "--exclude-attributes":
        let names = value(argument).split(separator: ",", omittingEmptySubsequences: false).map(String.init)
        guard names.allSatisfy({ ["visible", "accessible", "nativeAccessibilityElement"].contains($0) }) else {
            fail("--exclude-attributes accepts visible,accessible,nativeAccessibilityElement")
        }
        configuration.excludedAttributes = names
    case "--screenshots": configuration.screenshots = true
    case "--profile": configuration.profile = true
    case "-h", "--help": print(usage); exit(0)
    default: fail("unknown option \(argument)\n\(usage)")
    }
}
guard !configuration.bundleIdentifier.isEmpty else { fail("missing --bundle-id\n\(usage)") }
do { try runDriver(IOSDriver(configuration)) }
catch {
    FileHandle.standardError.write(Data("bombadil-ios: \(error)\n".utf8))
    exit(1)
}

// bombadil-macapp-driver: a Bombadil external driver for any macOS app,
// built on the Accessibility API. See `MacAppDriver` and the README.
import Foundation
#if os(macOS)
import BombadilDriver
import BombadilMacApp
#endif

let usage = """
    usage: bombadil-macapp-driver [options] -- <executable> [arguments...]
           bombadil-macapp-driver --check-access

    Launches <executable> (an app's Contents/MacOS binary, or a wrapper that
    execs it) and drives it for Bombadil over stdin and stdout.

    options:
      --env NAME=VALUE        set an environment variable for the app (repeatable)
      --app-log PATH          write the app's stdout and stderr to PATH
      --launch-timeout-ms N   wait N ms for a window or menu bar extra (30000)
      --settle-timeout-ms N   wait at most N ms for the state to settle (3000)
      --stall-timeout-ms N    stall timeout announced to Bombadil (90000)
      --include-main-menu     report the app's main menu bar as `menuBar`
      --check-access          check (and prompt for) the Accessibility permission
    """

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("bombadil-macapp-driver: \(message)\n".utf8))
    exit(2)
}

#if os(macOS)

var configuration = MacAppConfiguration(executable: "")
var arguments = CommandLine.arguments.dropFirst()
var checkAccess = false
func value(_ option: String) -> String {
    guard let value = arguments.popFirst() else { fail("\(option) needs a value\n\(usage)") }
    return value
}
func milliseconds(_ option: String) -> Int {
    guard let value = Int(value(option)), value > 0 else { fail("\(option) needs a positive integer") }
    return value
}
parsing: while let argument = arguments.popFirst() {
    switch argument {
    case "--":
        break parsing
    case "--env":
        let pair = value(argument)
        guard let equals = pair.firstIndex(of: "=") else { fail("--env needs NAME=VALUE, not \(pair)") }
        configuration.environment[String(pair[..<equals])] = String(pair[pair.index(after: equals)...])
    case "--app-log": configuration.appLogPath = value(argument)
    case "--launch-timeout-ms": configuration.launchTimeout = Double(milliseconds(argument)) / 1000
    case "--settle-timeout-ms": configuration.settleTimeout = Double(milliseconds(argument)) / 1000
    case "--stall-timeout-ms": configuration.stallTimeoutMs = milliseconds(argument)
    case "--include-main-menu": configuration.includeMainMenu = true
    case "--check-access": checkAccess = true
    case "-h", "--help":
        print(usage)
        exit(0)
    default: fail("unknown option \(argument)\n\(usage)")
    }
}

if checkAccess {
    do {
        try MacAppDriver.requireAccessibility()
        print("Accessibility access granted")
        exit(0)
    } catch {
        fail("\(error)")
    }
}

guard let executable = arguments.popFirst() else { fail("missing the app's executable\n\(usage)") }
configuration.executable = executable
configuration.arguments = Array(arguments)

// Stop the app when the driver is told to stop rather than leaving it
// running.
var signalSources: [DispatchSourceSignal] = []
for signalNumber in [SIGTERM, SIGINT, SIGHUP] {
    signal(signalNumber, SIG_IGN)
    let source = DispatchSource.makeSignalSource(signal: signalNumber, queue: .global())
    source.setEventHandler {
        if MacAppDriver.launchedPID > 0 { kill(MacAppDriver.launchedPID, SIGTERM) }
        exit(1)
    }
    source.resume()
    signalSources.append(source)
}

do {
    try runDriver(MacAppDriver(configuration))
} catch {
    FileHandle.standardError.write(Data("bombadil-macapp-driver: \(error)\n".utf8))
    exit(1)
}
#else
fail("this driver needs macOS")
#endif

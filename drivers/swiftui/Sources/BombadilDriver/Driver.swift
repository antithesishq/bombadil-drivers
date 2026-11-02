import Foundation

/// Drives one app session for Bombadil.
///
/// States and actions are JSON values whose shape the driver and the
/// specification agree on.
public protocol BombadilDriver: AnyObject {
    /// Name reported to Bombadil in the handshake.
    var name: String { get }

    /// How long Bombadil waits for each state, the first included, before
    /// it considers the driver stalled. `nil` keeps Bombadil's default of
    /// 30 seconds.
    var stallTimeoutMs: Int? { get }

    /// Launches the app and returns its first settled state.
    func start() throws -> JSONValue

    /// Performs `action` and returns the state the app settles into.
    func apply(_ action: JSONValue) throws -> JSONValue

    /// Optional PNG of the state just returned by `start` or `apply`.
    func screenshotPNG() -> Data?

    /// Shuts the app down.
    func stop()
}

extension BombadilDriver {
    public var stallTimeoutMs: Int? { nil }
    public func screenshotPNG() -> Data? { nil }
}

/// Runs `driver` for one Bombadil session, reading Bombadil's messages
/// with `readLine` and writing the driver's with `writeLine` (stdin and
/// stdout by default), until Bombadil terminates it or closes the input.
///
/// Errors from the driver are reported to Bombadil and rethrown. Log to
/// stderr: stdout carries the protocol.
public func runDriver(
    _ driver: BombadilDriver,
    readLine: () -> String? = { Swift.readLine() },
    writeLine: (String) -> Void = { line in
        FileHandle.standardOutput.write(Data((line + "\n").utf8))
    }
) throws {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let decoder = JSONDecoder()
    func send(_ message: DriverMessage) throws {
        writeLine(String(decoding: try encoder.encode(message), as: UTF8.self))
    }

    try send(.hello(name: driver.name, stallTimeoutMs: driver.stallTimeoutMs))
    defer { driver.stop() }
    do {
        let initialState = try driver.start()
        try send(.state(initialState, screenshotPNG: driver.screenshotPNG()))
        while let line = readLine() {
            if line.allSatisfy(\.isWhitespace) { continue }
            switch try decoder.decode(BombadilMessage.self, from: Data(line.utf8)) {
            case .apply(let action):
                let state = try driver.apply(action)
                try send(.state(state, screenshotPNG: driver.screenshotPNG()))
            case .terminate:
                return
            }
        }
    } catch {
        try? send(.error(message: "\(error)"))
        throw error
    }
}

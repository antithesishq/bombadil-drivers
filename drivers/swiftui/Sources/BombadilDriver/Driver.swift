import Foundation

/// Drives one app session for Bombadil.
///
/// States and actions are JSON values whose shape the driver and the
/// specification agree on.
public protocol BombadilDriver: AnyObject {
    /// Name reported to Bombadil in the handshake.
    var name: String { get }

    /// Launches the app and returns its first settled state.
    func start() throws -> JSONValue

    /// Performs `action` and returns the state the app settles into.
    func apply(_ action: JSONValue) throws -> JSONValue

    /// Shuts the app down.
    func stop()
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

    try send(.hello(name: driver.name))
    defer { driver.stop() }
    do {
        try send(.state(driver.start()))
        while let line = readLine() {
            if line.allSatisfy(\.isWhitespace) { continue }
            switch try decoder.decode(BombadilMessage.self, from: Data(line.utf8)) {
            case .apply(let action):
                try send(.state(driver.apply(action)))
            case .terminate:
                return
            }
        }
    } catch {
        try? send(.error(message: "\(error)"))
        throw error
    }
}

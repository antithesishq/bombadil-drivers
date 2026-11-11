import BombadilDriver
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct IOSConfiguration {
    public var serverURL: URL
    public var bundleIdentifier: String
    public var arguments: [String] = []
    public var environment: [String: String] = [:]
    public var requestTimeout: TimeInterval = 30
    public var settleTimeout: TimeInterval = 3
    public var settleInterval: TimeInterval = 0.05
    public var maxDepth = 50
    /// Omit selected WDA attributes from state and stability comparisons.
    /// Empty by default, so properties retain full attribute coverage.
    public var excludedAttributes: [String] = []
    public var screenshots = false
    public var profile = false
    public var stallTimeoutMs = 90_000

    public init(serverURL: URL, bundleIdentifier: String) {
        self.serverURL = serverURL
        self.bundleIdentifier = bundleIdentifier
    }
}

/// Black-box iOS driver. WebDriverAgent runs in a separate XCTest runner,
/// not in the application under test. Its HTTP endpoint must be dedicated
/// to this driver: creating a WDA session replaces the previous session.
public final class IOSDriver: BombadilDriver {
    public let name = "bombadil-ios"
    public var stallTimeoutMs: Int? { configuration.stallTimeoutMs }
    public private(set) var stateMetadata = StateMetadata(settled: false)

    private let configuration: IOSConfiguration
    private let session: URLSession
    private let sourceQuery: [URLQueryItem]
    private var sessionID: String?
    private var actionAttempted = false
    private var actionError: String?

    private enum Failure: Error, CustomStringConvertible {
        case rejected(String)
        case backend(String)
        var description: String {
            switch self {
            case .rejected(let message), .backend(let message): return message
            }
        }
    }

    private struct WebDriverError: Error, CustomStringConvertible {
        let code: String
        let description: String
    }

    // The completion writes once, then signals. The caller reads only after
    // the signal; on timeout it never reads the result. The task retains it.
    private final class PendingResponse: @unchecked Sendable {
        let completed = DispatchSemaphore(value: 0)
        var result: Result<(Data, HTTPURLResponse), Error>?
    }

    public init(_ configuration: IOSConfiguration, session: URLSession = .shared) {
        self.configuration = configuration
        self.session = session
        var query = [URLQueryItem(name: "format", value: "json")]
        if !configuration.excludedAttributes.isEmpty {
            query.append(URLQueryItem(name: "excluded_attributes", value: configuration.excludedAttributes.joined(separator: ",")))
        }
        sourceQuery = query
    }

    public func start() throws -> JSONValue {
        let response = try request("POST", "session", body: [
            "capabilities": ["alwaysMatch": [
                "bundleId": .string(configuration.bundleIdentifier),
                "arguments": .array(configuration.arguments.map(JSONValue.string)),
                "environment": .object(configuration.environment.mapValues(JSONValue.string)),
                "forceAppLaunch": true,
                "shouldTerminateApp": true,
                "waitForIdleTimeout": 0,
            ]],
        ])
        guard case .object(let fields) = response,
            case .string(let identifier)? = fields["sessionId"], !identifier.isEmpty
        else { throw Failure.backend("WebDriverAgent returned no session ID") }
        sessionID = identifier
        _ = try request("POST", route("appium/settings"), body: ["settings": [
            "waitForIdleTimeout": 0,
            "animationCoolOffTimeout": 0,
            "snapshotMaxDepth": .int(configuration.maxDepth),
            "defaultActiveApplication": .string(configuration.bundleIdentifier),
            "screenshotQuality": 0,
        ]])
        return try observedState()
    }

    public func apply(_ action: JSONValue) throws -> JSONValue {
        let started = DispatchTime.now().uptimeNanoseconds
        defer { profile("apply", since: started) }
        actionAttempted = false
        actionError = nil
        stateMetadata = StateMetadata(settled: false)
        do {
            try perform(action)
        } catch Failure.rejected(let message) {
            actionError = message
        } catch let error as WebDriverError
            where ["no such element", "stale element reference", "element not interactable",
                   "invalid element state", "invalid argument", "unknown error"].contains(error.code)
        {
            // These are action failures. Transport errors, malformed replies,
            // and lost sessions are fatal; never retry an uncertain action.
            actionError = error.description
        }
        if !actionAttempted { stateMetadata.notApplied = actionError }
        return try observedState()
    }

    public func stop() {
        guard sessionID != nil else { return }
        defer { sessionID = nil }
        do { _ = try request("DELETE", route("")) }
        catch { log("session cleanup failed: \(error)") }
    }

    public func screenshotPNG() -> Data? {
        guard configuration.screenshots else { return nil }
        do {
            guard case .string(let encoded) = try request("GET", route("screenshot")),
                let data = Data(base64Encoded: encoded),
                data.starts(with: [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
            else { throw Failure.backend("WebDriverAgent returned no PNG screenshot") }
            return data
        } catch {
            log("screenshot omitted: \(error)")
            return nil
        }
    }

    private func observedState() throws -> JSONValue {
        let started = DispatchTime.now().uptimeNanoseconds
        let deadline = ContinuousClock.now.advanced(by: .seconds(configuration.settleTimeout))
        var reads = 1
        defer { profile("settle", since: started, details: "reads=\(reads) settled=\(stateMetadata.settled)") }
        stateMetadata.settled = false
        var tree = try readTree()
        while ContinuousClock.now < deadline {
            Thread.sleep(forTimeInterval: configuration.settleInterval)
            guard ContinuousClock.now < deadline else { break }
            let current = try readTree()
            reads += 1
            if current == tree {
                stateMetadata.settled = ContinuousClock.now < deadline
                break
            }
            tree = current
        }
        return ["tree": tree, "actionError": actionError.map(JSONValue.string) ?? .null]
    }

    private func readTree() throws -> JSONValue {
        let tree = try request("GET", route("source"), query: sourceQuery)
        guard case .object = tree else { throw Failure.backend("WebDriverAgent returned no JSON hierarchy") }
        return tree
    }

    private func perform(_ action: JSONValue) throws {
        guard case .object(let object) = action, object.count == 1,
            let (tag, value) = object.first, case .object(let fields) = value
        else { throw Failure.rejected("expected an action such as {\"Tap\":{\"name\":\"save\"}}") }
        func string(_ key: String) throws -> String {
            guard case .string(let value)? = fields[key] else { throw Failure.rejected("\(tag) requires a string \(key)") }
            return value
        }
        func number(_ key: String) throws -> Double {
            let result: Double
            switch fields[key] {
            case .int(let value)?: result = Double(value)
            case .double(let value)?: result = value
            default: throw Failure.rejected("\(tag) requires a number \(key)")
            }
            guard result.isFinite else { throw Failure.rejected("\(key) must be finite") }
            return result
        }
        switch tag {
        case "Tap":
            if fields["x"] != nil || fields["y"] != nil {
                try mutate("wda/tap", ["x": .double(try number("x")), "y": .double(try number("y"))])
            } else {
                try mutate("element/\(find(fields))/click", [:])
            }
        case "TypeText":
            let text = try string("text")
            if fields["name"] != nil || fields["label"] != nil || fields["type"] != nil {
                let element = try find(fields)
                try mutate("element/\(element)/click", [:])
                try mutate("element/\(element)/value", ["text": .string(text)])
            } else {
                try mutate("wda/keys", ["value": [.string(text)]])
            }
        case "Swipe":
            let direction = try string("direction")
            guard ["up", "down", "left", "right"].contains(direction) else { throw Failure.rejected("invalid swipe direction") }
            try mutate("wda/swipe", ["direction": .string(direction)])
        case "Drag":
            var body: [String: JSONValue] = [:]
            for key in ["fromX", "fromY", "toX", "toY", "duration"] { body[key] = .double(try number(key)) }
            guard case .double(let duration)? = body["duration"], (0...10).contains(duration) else {
                throw Failure.rejected("drag press duration must be between 0 and 10 seconds")
            }
            try mutate("wda/dragfromtoforduration", .object(body))
        case "Wait":
            let milliseconds = try number("ms")
            guard (0...10_000).contains(milliseconds) else { throw Failure.rejected("wait must be between 0 and 10000 ms") }
            Thread.sleep(forTimeInterval: milliseconds / 1000)
        default: throw Failure.rejected("unknown action \(tag)")
        }
    }

    private func find(_ fields: [String: JSONValue]) throws -> String {
        let locator: JSONValue
        if case .string(let name)? = fields["name"], fields["label"] == nil, fields["type"] == nil {
            // WDA's native name query avoids its per-snapshot predicate callback.
            // Keep the plural endpoint so ambiguous selectors still fail.
            locator = ["using": "accessibility id", "value": .string(name)]
        } else {
            var predicates: [String] = []
            for key in ["name", "label", "type"] where fields[key] != nil {
                guard case .string(let value)? = fields[key] else { throw Failure.rejected("\(key) must be a string") }
                let literal = key == "type" ? "XCUIElementType\(value)" : value
                let quoted = String(decoding: try JSONEncoder().encode(literal), as: UTF8.self)
                predicates.append("\(key) == \(quoted)")
            }
            guard !predicates.isEmpty else { throw Failure.rejected("an element selector requires name, label, or type") }
            locator = ["using": "predicate string", "value": .string(predicates.joined(separator: " AND "))]
        }
        let response = try request("POST", route("elements"), body: locator)
        guard case .array(let matches) = response else { throw Failure.backend("WebDriverAgent returned no element list") }
        guard matches.count == 1 else { throw Failure.rejected("selector matched \(matches.count) elements; expected exactly one") }
        guard case .object(let element) = matches[0],
            case .string(let identifier)? = element["element-6066-11e4-a52e-4f735466cecf"]
        else { throw Failure.backend("WebDriverAgent returned no element reference") }
        return identifier
    }

    private func mutate(_ path: String, _ body: JSONValue) throws {
        actionAttempted = true
        _ = try request("POST", route(path), body: body)
    }

    private func route(_ path: String) throws -> String {
        guard let sessionID else { throw Failure.backend("no active WebDriverAgent session") }
        return path.isEmpty ? "session/\(sessionID)" : "session/\(sessionID)/\(path)"
    }

    private func request(_ method: String, _ path: String, body: JSONValue? = nil, query: [URLQueryItem] = []) throws -> JSONValue {
        let started = DispatchTime.now().uptimeNanoseconds
        defer { profile("http", since: started, details: "method=\(method) path=\(path)") }
        var components = URLComponents(url: configuration.serverURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        components.queryItems = query.isEmpty ? nil : query
        var request = URLRequest(url: components.url!, timeoutInterval: configuration.requestTimeout)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try body.map { try JSONEncoder().encode($0) }
        let pending = PendingResponse()
        let task = session.dataTask(with: request) { data, response, error in
            if let error { pending.result = .failure(error) }
            else if let data, let response = response as? HTTPURLResponse { pending.result = .success((data, response)) }
            else { pending.result = .failure(Failure.backend("WebDriverAgent returned no HTTP response")) }
            pending.completed.signal()
        }
        task.resume()
        guard pending.completed.wait(timeout: .now() + configuration.requestTimeout) == .success else {
            task.cancel()
            throw URLError(.timedOut)
        }
        let (data, response) = try pending.result!.get()
        guard case .object(let envelope) = try JSONDecoder().decode(JSONValue.self, from: data),
            let value = envelope["value"]
        else { throw Failure.backend("malformed WebDriverAgent response") }
        if case .object(let fields) = value, case .string(let code)? = fields["error"] {
            let message: String
            if case .string(let text)? = fields["message"] { message = text } else { message = code }
            throw WebDriverError(code: code, description: message)
        }
        guard (200..<300).contains(response.statusCode) else { throw Failure.backend("WebDriverAgent HTTP \(response.statusCode)") }
        return value
    }

    private func profile(_ phase: String, since started: UInt64, details: String = "") {
        guard configuration.profile else { return }
        let milliseconds = Double(DispatchTime.now().uptimeNanoseconds - started) / 1_000_000
        log("profile phase=\(phase) duration_ms=\(milliseconds) \(details)")
    }

    private func log(_ message: String) {
        FileHandle.standardError.write(Data("[\(name)] \(message)\n".utf8))
    }
}

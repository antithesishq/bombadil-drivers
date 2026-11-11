import BombadilDriver
import BombadilIOS
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest

final class IOSDriverTests: XCTestCase {
    func testSessionDistinguishesRejectedAndUncertainActions() throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [WebDriverProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel(); WebDriverProtocol.respond = nil }
        var count = 0
        var matches = 1
        var failAfterTap = false
        WebDriverProtocol.respond = { request in
            switch (request.httpMethod, request.url!.path) {
            case ("POST", "/session"): return (200, ["sessionId": "test"])
            case ("POST", "/session/test/appium/settings"), ("DELETE", "/session/test"): return (200, .null)
            case ("GET", "/session/test/source"):
                let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems ?? []
                let excluded = query.first { $0.name == "excluded_attributes" }?.value?.split(separator: ",") ?? []
                var tree: [String: JSONValue] = ["type": "Application", "children": [["type": "StaticText", "value": .int(count)]]]
                if !excluded.contains("visible") { tree["isVisible"] = "1" }
                return (200, .object(tree))
            case ("POST", "/session/test/elements"):
                return (200, .array(Array(repeating: ["element-6066-11e4-a52e-4f735466cecf": "button"], count: matches)))
            case ("POST", "/session/test/element/button/click"):
                count += 1
                return failAfterTap ? (500, ["error": "unknown error", "message": "reply failed after tapping"]) : (200, .null)
            default: throw URLError(.badURL)
            }
        }
        var options = IOSConfiguration(serverURL: URL(string: "http://wda.test")!, bundleIdentifier: "example.counter")
        options.settleInterval = 0.001
        let driver = IOSDriver(options, session: session)
        defer { driver.stop() }
        func state(_ value: Int, error: String? = nil) -> JSONValue {
            ["tree": ["type": "Application", "isVisible": "1", "children": [["type": "StaticText", "value": .int(value)]]],
             "actionError": error.map(JSONValue.string) ?? .null]
        }
        XCTAssertEqual(try driver.start(), state(0))
        XCTAssertTrue(driver.stateMetadata.settled)
        XCTAssertEqual(try driver.apply(["Tap": ["name": "increment"]]), state(1))
        matches = 2
        let ambiguous = "selector matched 2 elements; expected exactly one"
        XCTAssertEqual(try driver.apply(["Tap": ["name": "increment"]]), state(1, error: ambiguous))
        XCTAssertEqual(driver.stateMetadata.notApplied, ambiguous)
        matches = 1
        failAfterTap = true
        XCTAssertEqual(try driver.apply(["Tap": ["name": "increment"]]), state(2, error: "reply failed after tapping"))
        XCTAssertNil(driver.stateMetadata.notApplied)
        failAfterTap = false
        XCTAssertEqual(try driver.apply(["Wait": ["ms": 0]]), state(2))
        XCTAssertNil(driver.stateMetadata.notApplied)

        options.settleTimeout = 0
        let singleSnapshot = IOSDriver(options, session: session)
        defer { singleSnapshot.stop() }
        XCTAssertEqual(try singleSnapshot.start(), state(2))
        XCTAssertFalse(singleSnapshot.stateMetadata.settled)

        options.excludedAttributes = ["visible", "accessible", "nativeAccessibilityElement"]
        let reduced = IOSDriver(options, session: session)
        defer { reduced.stop() }
        XCTAssertEqual(try reduced.start(), [
            "tree": ["type": "Application", "children": [["type": "StaticText", "value": 2]]],
            "actionError": .null,
        ])
    }

    func testLateSnapshotDoesNotClaimSettled() throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [WebDriverProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel(); WebDriverProtocol.respond = nil }
        var snapshots = 0
        WebDriverProtocol.respond = { request in
            switch (request.httpMethod, request.url!.path) {
            case ("POST", "/session"): return (200, ["sessionId": "test"])
            case ("POST", "/session/test/appium/settings"), ("DELETE", "/session/test"): return (200, .null)
            case ("GET", "/session/test/source"):
                snapshots += 1
                if snapshots > 1 { Thread.sleep(forTimeInterval: 0.3) }
                return (200, ["type": "Application"])
            default: throw URLError(.badURL)
            }
        }
        var options = IOSConfiguration(serverURL: URL(string: "http://wda.test")!, bundleIdentifier: "example.app")
        options.settleTimeout = 0.2
        options.settleInterval = 0.001
        let driver = IOSDriver(options, session: session)
        defer { driver.stop() }
        XCTAssertEqual(try driver.start(), ["tree": ["type": "Application"], "actionError": .null])
        XCTAssertFalse(driver.stateMetadata.settled)
    }
}

private final class WebDriverProtocol: URLProtocol {
    static var respond: ((URLRequest) throws -> (Int, JSONValue))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, value) = try Self.respond!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: try JSONEncoder().encode(JSONValue.object(["value": value])))
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

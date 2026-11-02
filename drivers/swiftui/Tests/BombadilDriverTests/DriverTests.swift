import Foundation
import XCTest

@testable import BombadilDriver

final class FakeDriver: BombadilDriver {
    var applied: [JSONValue] = []
    var stopped = false
    var screenshots: [Data?] = []

    let name = "fake"

    func start() throws -> JSONValue { ["applied": 0] }

    func apply(_ action: JSONValue) throws -> JSONValue {
        if action == "Crash" { throw CocoaError(.featureUnsupported) }
        applied.append(action)
        return ["applied": .int(applied.count)]
    }

    func screenshotPNG() -> Data? {
        screenshots.indices.contains(applied.count) ? screenshots[applied.count] : nil
    }

    func stop() { stopped = true }
}

/// Runs `driver` on `input` lines, returning the lines it wrote and the
/// error it failed with.
func session(_ driver: BombadilDriver, _ input: [String]) -> ([String], Error?) {
    var input = input.makeIterator()
    var output: [String] = []
    do {
        try runDriver(driver, readLine: { input.next() }, writeLine: { output.append($0) })
        return (output, nil)
    } catch {
        return (output, error)
    }
}

final class DriverTests: XCTestCase {
    func testScreenshotsRoundTripBeforeTheNextInputWithoutReusingMissingCaptures() throws {
        let png = try XCTUnwrap(Data(base64Encoded:
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII="))
        let driver = FakeDriver()
        driver.screenshots = [png, nil, png]
        var output: [String] = []
        var input = [
            #"{"type":"apply","action":"First"}"#,
            #"{"type":"apply","action":"Second"}"#,
            #"{"type":"terminate"}"#,
        ].makeIterator()
        var reads = 0
        try runDriver(driver, readLine: {
            XCTAssertEqual(output.count, reads + 2, "state and capture must precede the next input")
            reads += 1
            return input.next()
        }, writeLine: { output.append($0) })
        let states = try output.dropFirst().map {
            try XCTUnwrap(JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any])
        }
        XCTAssertEqual(states.count, 3)
        for index in [0, 2] {
            let encoded = try XCTUnwrap(states[index]["screenshot_png"] as? String)
            XCTAssertEqual(Data(base64Encoded: encoded), png)
        }
        XCTAssertNil(states[1]["screenshot_png"])
        XCTAssertTrue(states.allSatisfy { ($0["state"] as? [String: Any])?["screenshot_png"] == nil })
        XCTAssertTrue(driver.stopped)
    }

    func testReportsAStateForTheStartAndEveryAppliedAction() {
        let driver = FakeDriver()
        let (output, error) = session(driver, [
            #"{"type":"apply","action":{"Tap":{"id":"ok"}}}"#,
            #"{"type":"apply","action":"Back"}"#,
            #"{"type":"terminate"}"#,
            #"{"type":"apply","action":"AfterTerminate"}"#,
        ])
        XCTAssertNil(error)
        XCTAssertEqual(output, [
            #"{"name":"fake","protocol":0,"type":"hello"}"#,
            #"{"state":{"applied":0},"type":"state"}"#,
            #"{"state":{"applied":1},"type":"state"}"#,
            #"{"state":{"applied":2},"type":"state"}"#,
        ])
        XCTAssertEqual(driver.applied, [["Tap": ["id": "ok"]], "Back"])
        XCTAssertTrue(driver.stopped)
    }

    func testReportsDriverErrorsToBombadilAndStopsTheApp() {
        let driver = FakeDriver()
        let (output, error) = session(driver, [#"{"type":"apply","action":"Crash"}"#])
        XCTAssertNotNil(error)
        XCTAssertTrue(output.last?.hasPrefix(#"{"message":"#) ?? false, "\(output)")
        XCTAssertTrue(output.last?.hasSuffix(#""type":"error"}"#) ?? false, "\(output)")
        XCTAssertTrue(driver.stopped)
    }

    func testAnnouncesTheStallTimeoutInTheHello() {
        final class SlowDriver: BombadilDriver {
            let name = "slow"
            let stallTimeoutMs: Int? = 90_000
            func start() throws -> JSONValue { nil }
            func apply(_ action: JSONValue) throws -> JSONValue { nil }
            func stop() {}
        }
        let (output, error) = session(SlowDriver(), [#"{"type":"terminate"}"#])
        XCTAssertNil(error)
        XCTAssertEqual(
            output.first, #"{"name":"slow","protocol":0,"stall_timeout_ms":90000,"type":"hello"}"#)
    }

    func testDecodesIntegersAsIntegers() throws {
        let action = try JSONDecoder().decode(JSONValue.self, from: Data(#"{"by":2,"x":0.5}"#.utf8))
        XCTAssertEqual(action, ["by": .int(2), "x": .double(0.5)])
    }
}

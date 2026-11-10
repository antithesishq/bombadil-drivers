import Foundation
import XCTest

@testable import BombadilDriver

final class FakeDriver: BombadilDriver {
    var applied: [JSONValue] = []
    var stopped = false
    var screenshots: [Data?] = []
    var stateMetadata = StateMetadata()
    var appliedMetadata: StateMetadata?

    let name = "fake"

    func start() throws -> JSONValue { ["applied": 0] }

    func apply(_ action: JSONValue) throws -> JSONValue {
        if action == "Crash" { throw CocoaError(.featureUnsupported) }
        applied.append(action)
        if let appliedMetadata { stateMetadata = appliedMetadata }
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
        driver.stateMetadata.timestampUs = 123
        let (output, error) = session(driver, [
            #"{"type":"apply","action":{"Tap":{"id":"ok"}}}"#,
            #"{"type":"apply","action":"Back"}"#,
            #"{"type":"terminate"}"#,
            #"{"type":"apply","action":"AfterTerminate"}"#,
        ])
        XCTAssertNil(error)
        XCTAssertEqual(output, [
            #"{"name":"fake","protocol":0,"type":"hello"}"#,
            #"{"settled":true,"state":{"applied":0},"timestamp_us":123,"type":"state"}"#,
            #"{"settled":true,"state":{"applied":1},"timestamp_us":123,"type":"state"}"#,
            #"{"settled":true,"state":{"applied":2},"timestamp_us":123,"type":"state"}"#,
        ])
        XCTAssertEqual(driver.applied, [["Tap": ["id": "ok"]], "Back"])
        XCTAssertTrue(driver.stopped)
    }

    func testEncodesMetadataOutsideTheState() throws {
        let message = DriverMessage.state(["settled": "application data"], metadata: StateMetadata(
            settled: false, notApplied: "missing element", timestampUs: 456))
        let encoded = try JSONEncoder().encode(message)
        let json = try JSONDecoder().decode(JSONValue.self, from: encoded)
        XCTAssertEqual(json, [
            "type": "state", "state": ["settled": "application data"],
            "settled": false, "not_applied": "missing element", "timestamp_us": 456,
        ])
    }

    func testStateMessageDefaultsAndAbsentOptionalMetadata() throws {
        let encoded = try JSONEncoder().encode(DriverMessage.state(nil))
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: encoded), [
            "type": "state", "state": nil, "settled": true,
        ])
    }

    func testGeneratedTimestampNeverGoesBelowPreviousExplicitTimestamp() {
        let driver = FakeDriver()
        driver.stateMetadata.timestampUs = 9_000_000_000_000_000
        driver.appliedMetadata = StateMetadata()
        let (output, error) = session(driver, [#"{"type":"apply","action":"Tap"}"#])
        XCTAssertNil(error)
        XCTAssertEqual(output.last,
            #"{"settled":true,"state":{"applied":1},"timestamp_us":9000000000000000,"type":"state"}"#)
    }

    func testSessionForwardsActionMetadata() {
        let driver = FakeDriver()
        driver.stateMetadata.timestampUs = 123
        driver.appliedMetadata = StateMetadata(settled: false, notApplied: "stale path", timestampUs: 124)
        let (output, error) = session(driver, [#"{"type":"apply","action":"Tap"}"#])
        XCTAssertNil(error)
        XCTAssertEqual(output.last,
            #"{"not_applied":"stale path","settled":false,"state":{"applied":1},"timestamp_us":124,"type":"state"}"#)
    }

    func testRejectsNotAppliedOnInitialState() {
        let driver = FakeDriver()
        driver.stateMetadata.notApplied = "invalid initial outcome"
        let (output, error) = session(driver, [])
        XCTAssertNotNil(error)
        XCTAssertEqual(output.count, 2) // hello and error, no invalid state
        XCTAssertTrue(driver.stopped)
    }

    func testRejectsDecreasingExplicitTimestamp() {
        let driver = FakeDriver()
        driver.stateMetadata.timestampUs = 123
        driver.appliedMetadata = StateMetadata(timestampUs: 122)
        let (output, error) = session(driver, [#"{"type":"apply","action":"Tap"}"#])
        XCTAssertNotNil(error)
        XCTAssertEqual(output.count, 3) // hello, initial state, error
        XCTAssertTrue(driver.stopped)
    }

    func testDefaultTimestampsAreHostEpochMicrosecondsAndNondecreasing() throws {
        let before = UInt64(Date().timeIntervalSince1970 * 1_000_000)
        let (output, error) = session(FakeDriver(), [#"{"type":"apply","action":"Tap"}"#])
        let after = UInt64(Date().timeIntervalSince1970 * 1_000_000)
        XCTAssertNil(error)
        let states = try output.dropFirst().map {
            try XCTUnwrap(JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any])
        }
        let timestamps = try states.map { try XCTUnwrap($0["timestamp_us"] as? UInt64) }
        XCTAssertGreaterThanOrEqual(timestamps[0], before)
        XCTAssertGreaterThanOrEqual(timestamps[1], timestamps[0])
        XCTAssertLessThanOrEqual(timestamps[1], after)
        XCTAssertTrue(states.allSatisfy { $0["not_applied"] == nil && ($0["settled"] as? Bool) == true })
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

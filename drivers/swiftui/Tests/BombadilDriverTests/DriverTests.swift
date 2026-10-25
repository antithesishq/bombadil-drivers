import Foundation
import XCTest

@testable import BombadilDriver

final class FakeDriver: BombadilDriver {
    var applied: [JSONValue] = []
    var stopped = false

    let name = "fake"

    func start() throws -> JSONValue { ["applied": 0] }

    func apply(_ action: JSONValue) throws -> JSONValue {
        if action == "Crash" { throw CocoaError(.featureUnsupported) }
        applied.append(action)
        return ["applied": .int(applied.count)]
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

    func testDecodesIntegersAsIntegers() throws {
        let action = try JSONDecoder().decode(JSONValue.self, from: Data(#"{"by":2,"x":0.5}"#.utf8))
        XCTAssertEqual(action, ["by": .int(2), "x": .double(0.5)])
    }
}

import BombadilDriver
import XCTest

@testable import BombadilMacOS

final class MacAppActionTests: XCTestCase {
    func testParsesActionsFromTheirJSON() throws {
        XCTAssertEqual(
            try MacAppAction(json: ["Press": ["path": "statusItems/0/0/3", "title": "Settings…"]]),
            .press(ElementQuery(path: "statusItems/0/0/3", title: "Settings…")))
        XCTAssertEqual(try MacAppAction(json: "OpenStatusItem"), .openStatusItem(index: 0))
        XCTAssertEqual(try MacAppAction(json: ["OpenStatusItem": ["index": 1]]), .openStatusItem(index: 1))
        XCTAssertEqual(try MacAppAction(json: "Escape"), .escape)
        XCTAssertEqual(
            try MacAppAction(json: ["TypeText": ["text": "hi"]]), .typeText("hi", into: nil))
        XCTAssertEqual(
            try MacAppAction(json: ["TypeText": ["text": "hi", "identifier": "name"]]),
            .typeText("hi", into: ElementQuery(identifier: "name")))
        XCTAssertEqual(
            try MacAppAction(json: ["Key": ["key": "w", "modifiers": ["command"]]]),
            .key("w", modifiers: ["command"]))
        XCTAssertEqual(
            try MacAppAction(json: ["CloseWindow": ["path": "windows/0"]]),
            .closeWindow(ElementQuery(path: "windows/0")))
        XCTAssertEqual(try MacAppAction(json: ["Wait": ["ms": 60_000]]), .wait(milliseconds: 10_000))
    }

    func testRejectsMalformedActions() {
        for action: JSONValue in [
            "Explode", ["Press": [:]], ["Press": "windows/0"], ["Press": ["path": 3]],
            ["TypeText": ["path": "windows/0"]], ["Key": ["key": "a", "modifiers": "command"]],
            ["Press": ["path": "windows/0"], "Escape": [:]],
        ] {
            XCTAssertThrowsError(try MacAppAction(json: action), "\(action)")
        }
    }

    func testMatchesOnlyTheGivenAttributes() {
        let query = ElementQuery(path: "windows/0", role: "AXButton")
        XCTAssertTrue(query.matches(role: "AXButton", title: "OK", identifier: nil))
        XCTAssertFalse(query.matches(role: "AXMenuItem", title: "OK", identifier: nil))
    }

    func testSplitsPathsIntoARootAndIndices() {
        XCTAssertEqual(parseElementPath("windows/0/3/1")?.root, "windows")
        XCTAssertEqual(parseElementPath("windows/0/3/1")?.indices, [0, 3, 1])
        for path in ["windows", "windows/", "/0", "windows/x", "windows/-1"] {
            XCTAssertNil(parseElementPath(path), path)
        }
    }
}

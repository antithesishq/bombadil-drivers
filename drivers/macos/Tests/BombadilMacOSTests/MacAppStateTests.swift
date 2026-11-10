#if os(macOS)
import BombadilDriver
import XCTest

@testable import BombadilMacOS

final class MacAppStateTests: XCTestCase {
    func testTimeoutDoesNotClaimSettled() {
        var configuration = MacAppConfiguration(executable: "/unused")
        configuration.settleTimeout = 0
        let driver = MacAppDriver(configuration)
        _ = driver.settledState()
        XCTAssertFalse(driver.stateMetadata.settled)
        XCTAssertNil(driver.stateMetadata.notApplied)
    }

    func testEqualResponsiveReadsAreSettled() {
        var configuration = MacAppConfiguration(executable: "/unused")
        configuration.settleInterval = 0.001
        let driver = MacAppDriver(configuration)
        _ = driver.settledState()
        XCTAssertTrue(driver.stateMetadata.settled)
    }

    func testActionOnStoppedAppIsKnownNotApplied() throws {
        var configuration = MacAppConfiguration(executable: "/unused")
        configuration.settleInterval = 0.001
        let driver = MacAppDriver(configuration)
        _ = try driver.apply("Escape")
        XCTAssertEqual(driver.stateMetadata.notApplied, "the app is not running")
        XCTAssertTrue(driver.stateMetadata.settled)
    }
}
#endif

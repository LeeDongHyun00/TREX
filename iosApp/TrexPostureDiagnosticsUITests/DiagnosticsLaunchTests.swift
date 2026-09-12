import XCTest

final class DiagnosticsLaunchTests: XCTestCase {
    /// 실제 렌더링된 화면에서 엔진 미연결 표시와 터치 응답을 함께 확인한다.
    func testLaunchKeepsEngineDisconnectedAndRespondsToTap() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()

        let engineStatus = app.staticTexts["diagnostics.engineStatus"]
        XCTAssertTrue(engineStatus.waitForExistence(timeout: 15))
        XCTAssertEqual(engineStatus.label, "평가 엔진 연결 전")

        let response = app.staticTexts["diagnostics.responseStatus"]
        XCTAssertEqual(response.label, "아직 확인하지 않았습니다")
        app.buttons["diagnostics.checkUI"].tap()
        XCTAssertEqual(response.label, "응답 확인 1회")
        XCTAssertEqual(engineStatus.label, "평가 엔진 연결 전")

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "M0-진단-화면"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}

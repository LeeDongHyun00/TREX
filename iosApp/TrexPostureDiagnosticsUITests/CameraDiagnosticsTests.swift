import XCTest

final class CameraDiagnosticsTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    override func tearDownWithError() throws {
        app.terminate()
        XCUIDevice.shared.orientation = .portrait
    }

    func testCameraPermissionDenied() throws {
        app.resetAuthorizationStatus(for: .camera)
        openCapture()
        tap("capture.start")
        answerCameraPermission(allow: false)
        expectStatus("denied")
        XCTAssertEqual(read("capture.dimensions"), "프레임 없음")
        XCTAssertEqual(read("capture.motionStatus"), "정지")
    }

    func testDeviceCameraLifecycleAndMotion() throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("실제 카메라·센서 검사는 iPhone에서만 실행합니다")
        #else
        app.resetAuthorizationStatus(for: .camera)
        openCapture()
        tap("capture.start")
        answerCameraPermission(allow: true)
        expectStatus("running")
        waitForFrame(portrait: true)
        XCTAssertEqual(read("capture.position"), "후면")
        expectText("capture.motionStatus", "원본 디바이스 중력 수신")
        let firstFrame = Int(read("capture.frameId"))!

        tap("capture.switch")
        expectText("capture.position", "전면")
        expectStatus("running")
        waitForFrame(portrait: true)
        XCTAssertGreaterThan(Int(read("capture.frameId"))!, firstFrame)
        scrollToTop()
        let preview = XCTAttachment(screenshot: app.screenshot())
        preview.name = "M1-전면-프리뷰-로컬검증"
        preview.lifetime = .keepAlways
        add(preview)

        XCUIDevice.shared.orientation = .landscapeLeft
        waitForFrame(portrait: false)
        XCUIDevice.shared.orientation = .portrait
        waitForFrame(portrait: true)
        tap("capture.switch")
        expectText("capture.position", "후면")
        expectStatus("running")
        waitForFrame(portrait: true)

        for _ in 0..<5 {
            tap("capture.stop")
            expectStatus("idle")
            XCTAssertEqual(read("capture.dimensions"), "프레임 없음")
            XCTAssertEqual(read("capture.motionStatus"), "정지")
            // 정지 직전 지연된 콜백이 화면을 다시 채우는지 확인한다.
            RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.5))
            XCTAssertEqual(read("capture.dimensions"), "프레임 없음")
            tap("capture.start")
            expectStatus("running")
            waitForFrame(portrait: true)
        }

        let beforeBackground = Int(read("capture.epoch"))!
        XCUIDevice.shared.press(.home)
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 2))
        app.activate()
        expectStatus("running")
        waitForFrame(portrait: true)
        XCTAssertGreaterThan(Int(read("capture.epoch"))!, beforeBackground)

        scrollToTop()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["diagnostics.openCapture"].waitForExistence(timeout: 5))
        app.buttons["diagnostics.openCapture"].tap()
        expectStatus("idle")
        XCTAssertEqual(read("capture.dimensions"), "프레임 없음")
        tap("capture.start")
        expectStatus("running")
        waitForFrame(portrait: true)
        XCTAssertEqual(read("capture.engineStatus"), "평가 엔진 연결 전")
        tap("capture.stop")
        expectStatus("idle")
        #endif
    }

    func testSimulatorReportsMissingCamera() throws {
        #if targetEnvironment(simulator)
        app.resetAuthorizationStatus(for: .camera)
        openCapture()
        tap("capture.start")
        answerCameraPermission(allow: true)
        expectStatus("unavailable")
        XCTAssertEqual(read("capture.dimensions"), "프레임 없음")
        XCTAssertEqual(read("capture.motionStatus"), "정지")
        #else
        throw XCTSkip("카메라 부재 검사는 시뮬레이터에서 실행합니다")
        #endif
    }

    private func openCapture() {
        app.launch()
        let link = app.buttons["diagnostics.openCapture"]
        XCTAssertTrue(link.waitForExistence(timeout: 10))
        link.tap()
        expectStatus("idle")
    }

    private func answerCameraPermission(allow: Bool) {
        let alert = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 10), "시스템 카메라 권한 대화상자가 필요합니다")
        let labels = allow ? ["Allow", "OK", "허용", "확인"] : ["Don’t Allow", "Don't Allow", "허용 안 함", "허용 안함"]
        let button = alert.buttons.matching(NSPredicate(format: "label IN %@", labels)).firstMatch
        XCTAssertTrue(button.exists, "권한 버튼 문구를 확인해야 합니다")
        button.tap()
    }

    private func expectStatus(_ value: String) {
        scrollToTop()
        let status = app.staticTexts["capture.status"]
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", value), object: status)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 15), .completed, "촬영 상태: \(value)")
    }

    private func expectText(_ id: String, _ value: String) {
        let element = element(id)
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", value), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 10), .completed)
    }

    private func waitForFrame(portrait: Bool) {
        let dimensions = element("capture.dimensions")
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let parts = dimensions.label.components(separatedBy: " × ").compactMap(Int.init)
            return parts.count == 2 && parts[0] > 0 && (portrait ? parts[1] > parts[0] : parts[0] > parts[1])
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 15), .completed, "회전된 실제 출력 버퍼 크기")
    }

    private func read(_ id: String) -> String { element(id).label }

    private func element(_ id: String) -> XCUIElement {
        let element = app.staticTexts[id]
        if element.exists && element.isHittable { return element }
        scrollToTop()
        for _ in 0..<6 {
            if element.exists && element.isHittable { return element }
            app.swipeUp()
        }
        XCTAssertTrue(element.exists, "진단 항목: \(id)")
        return element
    }

    private func scrollToTop() {
        for _ in 0..<6 {
            if app.buttons["capture.start"].isHittable { return }
            app.swipeDown()
        }
    }

    private func tap(_ id: String) {
        scrollToTop()
        app.buttons[id].tap()
    }
}

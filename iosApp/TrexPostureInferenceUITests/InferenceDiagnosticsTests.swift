import XCTest

final class InferenceDiagnosticsTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.staticTexts["m2.engine"].waitForExistence(timeout: 15))
        XCTAssertEqual(app.staticTexts["m2.engine"].label, "평가 엔진 연결 전")
    }

    override func tearDownWithError() throws {
        app.terminate()
        XCUIDevice.shared.orientation = .portrait
    }

    func testRealSDKWithExplicitSyntheticInputs() {
        button("m2.blank").tap()
        expectState("notDetected")
        XCTAssertEqual(read("m2.source"), "합성 검정 프레임")
        XCTAssertEqual(read("m2.counts"), "0 / 0")
        XCTAssertEqual(read("m2.assets"), "4/4 SHA-256 일치")
        let first = Int64(read("m2.time"))!
        button("m2.blank").tap()
        expectState("notDetected")
        XCTAssertGreaterThan(Int64(read("m2.time"))!, first)
        button("m2.errorProbe").tap()
        expectState("error")
        XCTAssertEqual(read("m2.source"), "합성 모델 경로 오류")
        XCTAssertEqual(read("m2.counts"), "0 / 0")
        button("m2.blank").tap()
        expectState("notDetected")
        scrollToTop()
        XCTAssertEqual(app.staticTexts["m2.engine"].label, "평가 엔진 연결 전")
    }

    func testDeviceCameraLifecycle() throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("실제 카메라 대신 시뮬레이터의 실제 SDK 합성 입력 검사만 수행합니다")
        #else
        startFrontCamera()
        waitForResult()
        XCTAssertEqual(read("m2.source"), "실제 카메라")
        XCTAssertEqual(read("m2.assets"), "4/4 SHA-256 일치")
        let firstTime = Int64(read("m2.time"))!
        waitForSize(portrait: true)
        let epoch = Int(read("m2.epoch"))!
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForSize(portrait: false)
        XCTAssertGreaterThan(Int(read("m2.epoch"))!, epoch)
        XCUIDevice.shared.orientation = .portrait
        waitForSize(portrait: true)
        XCTAssertGreaterThan(Int64(read("m2.time"))!, firstTime)
        button("m2.switch").tap()
        expectLabel("m2.position", "후면")
        waitForResult()
        let beforeBackground = Int(read("m2.epoch"))!
        XCUIDevice.shared.press(.home)
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 2))
        app.activate()
        waitForResult()
        XCTAssertGreaterThan(Int(read("m2.epoch"))!, beforeBackground)

        button("m2.stop").tap()
        expectState("idle")
        #endif
    }

    func testDeviceDelayedResultIsDiscarded() throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("실제 카메라의 느린 추론·늦은 완료 검사는 iPhone에서 수행합니다")
        #else
        // 카메라를 켜기 전에 개발 검사 영역의 실제 스위치를 누른다.
        // 중앙의 접근성 컨테이너 탭이나 비활성 버튼으로 스크롤을 대신하지 않는다.
        _ = element("m2.discarded")
        let toggle = app.switches["m2.delay"]
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        wait(NSPredicate(format: "value == '1'"), object: toggle)
        startFrontCamera()
        waitForResult()
        expectLabel("m2.queue", "1 / 1")
        expectLabel("m2.peak", "1")
        let beforeDiscard = Int(read("m2.discarded"))!
        button("m2.stop").tap()
        expectState("idle")
        let discarded = element("m2.discarded")
        wait(NSPredicate { _, _ in (Int(discarded.label) ?? -1) > beforeDiscard }, object: nil)
        XCTAssertEqual(read("m2.queue"), "0 / 0")
        XCTAssertEqual(read("m2.counts"), "0 / 0")
        expectState("idle")
        button("m2.start").tap()
        waitForResult()
        XCTAssertEqual(read("m2.peak"), "1")
        button("m2.stop").tap()
        expectState("idle")
        #endif
    }

    func testDeviceDetects33RawLandmarks() throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("33점 실카메라 검출은 사람이 보이는 iPhone에서만 확인합니다")
        #else
        startFrontCamera()
        expectLabel("m2.counts", "33 / 33", timeout: 45)
        expectState("detected")
        XCTAssertEqual(read("m2.source"), "실제 카메라")
        scrollToTop()
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "M2-실카메라-33점-로컬검증"
        attachment.lifetime = .keepAlways
        add(attachment)
        button("m2.stop").tap()
        expectState("idle")
        #endif
    }

    private func startFrontCamera() {
        button("m2.switch").tap()
        expectLabel("m2.position", "전면")
        button("m2.start").tap()
        let alert = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch
        if alert.waitForExistence(timeout: 3) {
            let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["Allow", "OK", "허용", "확인"])).firstMatch
            XCTAssertTrue(allow.exists)
            allow.tap()
        }
    }

    private func waitForResult() {
        scrollToTop()
        wait(NSPredicate(format: "value IN %@", ["detected", "notDetected"]), object: app.staticTexts["m2.state"])
    }

    private func waitForSize(portrait: Bool) {
        let size = element("m2.size")
        wait(NSPredicate { _, _ in
            let parts = size.label.components(separatedBy: " × ").compactMap(Int.init)
            return parts.count == 2 && parts[0] > 0 && (portrait ? parts[1] > parts[0] : parts[0] > parts[1])
        }, object: nil)
    }

    private func expectState(_ state: String) {
        scrollToTop()
        wait(NSPredicate(format: "value == %@", state), object: app.staticTexts["m2.state"])
    }

    private func expectLabel(_ id: String, _ label: String, timeout: TimeInterval = 20) {
        wait(NSPredicate(format: "label == %@", label), object: element(id), timeout: timeout)
    }

    private func wait(_ predicate: NSPredicate, object: Any?, timeout: TimeInterval = 20) {
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: object)], timeout: timeout), .completed)
    }

    private func read(_ id: String) -> String { element(id).label }
    private func element(_ id: String) -> XCUIElement {
        let element = app.staticTexts[id]
        reveal(element)
        return element
    }
    private func button(_ id: String) -> XCUIElement {
        let element = app.buttons[id]
        reveal(element)
        return element
    }
    private func reveal(_ element: XCUIElement) {
        if element.exists && element.isHittable { return }
        scrollToTop()
        for _ in 0..<20 {
            if element.exists && element.isHittable { return }
            // 가로 화면의 짧은 행을 관성 스와이프로 건너뛰지 않도록 절반 화면만 드래그한다.
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.78))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.30))
            start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .default, thenHoldForDuration: 0.15)
        }
        XCTAssertTrue(element.exists, "진단 항목: \(element.identifier)")
    }
    private func scrollToTop() {
        for _ in 0..<10 {
            if app.buttons["m2.start"].isHittable { return }
            app.swipeDown()
        }
    }
}

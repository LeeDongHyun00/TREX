import XCTest
@testable import Trex

final class TrexTests: XCTestCase {
    func testCatalogGuidesAndRuleAssetsMatch() throws {
        let exercises = try Resources.decode("catalog.json", as: [Exercise].self)
        let guides = try Resources.decode("guides.json", as: [Guide].self)
        let foods = try Resources.decode("nutrition.json", as: [Food].self)
        XCTAssertEqual(exercises.count, 26); XCTAssertEqual(guides.count, 7); XCTAssertEqual(foods.count, 350)
        XCTAssertEqual(try BundleAssets.verify().count, 4)
        for guide in guides { XCTAssertNotNil(Bundle.main.url(forResource: guide.asset, withExtension: nil)) }
    }
    func testBackupPreservesUnknownJudgementAndUserLabel() throws {
        var data = PersistedData()
        data.records = [SetRecord(name: "런지", setNumber: 1, repetitions: 0, duration: 20, report: nil, actualReps: 3, formLabel: "의도적 변형", weight: 65, height: 170)]
        let restored = try JSONDecoder().decode(PersistedData.self, from: JSONEncoder().encode(data))
        XCTAssertNil(restored.records[0].report); XCTAssertEqual(restored.records[0].actualReps, 3)
        XCTAssertEqual(restored.records[0].formLabel, "의도적 변형")
    }
    func testCaptureEpochRejectsLateFrameAnd85msGate() {
        var gate = CaptureGate(); gate.intervalMs = 85
        let old = gate.begin(); XCTAssertTrue(gate.accept(epoch: old, sampleTimeMs: 1000))
        XCTAssertFalse(gate.accept(epoch: old, sampleTimeMs: 1084)); XCTAssertTrue(gate.accept(epoch: old, sampleTimeMs: 1085))
        gate.end(); let next = gate.begin()
        XCTAssertFalse(gate.accept(epoch: old, sampleTimeMs: 2000)); XCTAssertTrue(gate.accept(epoch: next, sampleTimeMs: 2000))
    }
}

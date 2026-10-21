import XCTest
import CoreVideo
import CryptoKit
import TrexFoodRuntime
import TrexCore
@testable import Trex

final class TrexTests: XCTestCase {
    private struct FoodBaseline: Decodable { let bundledName: String; let bundledSha256: String }
    func testFoodAndPoseRuntimesCanCoexist() throws {
        let poseURL = try XCTUnwrap(Bundle.main.url(forResource: "pose_landmarker_full", withExtension: "task"))
        let pose = try PoseRunner(modelPath: poseURL.path)
        for name in ["yolov8n_food", "food_region", "food_embed"] {
            print("음식 런타임 추론 검사: \(name)")
            let url = try XCTUnwrap(Bundle.main.url(forResource: name, withExtension: "tflite"))
            if name == "yolov8n_food" {
                let baseline = try Resources.decode("FOOD_MODEL_BASELINE.json", as: FoodBaseline.self)
                XCTAssertEqual(baseline.bundledName, url.lastPathComponent)
                let digest = SHA256.hash(data: try Data(contentsOf: url)).map { String(format: "%02x", $0) }.joined()
                XCTAssertEqual(digest, baseline.bundledSha256)
            }
            let food = try FoodModel(modelPath: url.path, threadCount: 1)
            try food.allocateTensors()
            let input = try food.input(at: 0)
            XCTAssertEqual(input.dataType, .float32)
            try food.copy(Data(repeating: 0, count: input.data.count), toInputAt: 0)
            try food.invoke()
            XCTAssertFalse(try food.output(at: 0).data.isEmpty)
        }
        var buffer: CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault, 64, 64, kCVPixelFormatType_32BGRA, nil, &buffer), kCVReturnSuccess)
        let image = try XCTUnwrap(buffer)
        CVPixelBufferLockBaseAddress(image, [])
        if let base = CVPixelBufferGetBaseAddress(image) { memset(base, 0, CVPixelBufferGetDataSize(image)) }
        CVPixelBufferUnlockBaseAddress(image, [])
        _ = try pose.detect(image, timeMs: 1)
        // §101d 얼굴 모델 — 같은 프로세스에서 초기화·크롭·추론. 빈 영상이라 얼굴은 없어야 한다(정확도 검사가 아니라 SDK 경계 검사)
        let faceURL = try XCTUnwrap(Bundle.main.url(forResource: "face_landmarker", withExtension: "task"))
        let face = try FaceRunner(modelPath: faceURL.path)
        let reading = try face.detect(image, spec: ["cx": 32.0, "cy": 32.0, "rotateDeg": 0.0, "side": 60.0, "scale": 256.0 / 60.0, "headPx": 20.0, "outPx": 256])
        XCTAssertFalse(reading.found); XCTAssertTrue(reading.matrix.isEmpty)
    }
    func testEngineSpeechQueueDrivesOneSubmitAtATime() throws {
        PreparationIntro.shared.forget(name: "기본 스쿼트")
        let engine = try IosEngine(standingRules: Resources.text("rules_mp_v0.json"), floorRules: Resources.text("rules_floor_v0.json"))
        _ = try engine.configure(config: Resources.json(["name": "기본 스쿼트", "voiceReady": true, "muted": false]))
        engine.arm(nowMs: 1000)
        let first = try Resources.parse(engine.tick(nowMs: 1100), as: EngineStatus.self)
        XCTAssertEqual(first.speech.count, 1)
        XCTAssertTrue(try Resources.parse(engine.tick(nowMs: 1300), as: EngineStatus.self).speech.isEmpty, "재생 중에는 다음 문장을 내지 않는다")
        engine.speechStarted(nowMs: 1300)
        let next = try Resources.parse(engine.speechFinished(nowMs: 2500), as: [SpeechSubmit].self)
        XCTAssertLessThanOrEqual(next.count, 1)
    }
    func testCatalogGuidesAndRuleAssetsMatch() throws {
        let exercises = try Resources.decode("catalog.json", as: [Exercise].self)
        let guides = try Resources.decode("guides.json", as: [Guide].self)
        let foods = try Resources.decode("nutrition.json", as: [Food].self)
        XCTAssertEqual(exercises.count, 26); XCTAssertEqual(guides.count, 10); XCTAssertEqual(foods.count, 350)
        XCTAssertEqual(try BundleAssets.verify().count, 5)
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

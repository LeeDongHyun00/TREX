import CoreGraphics
import Foundation

struct PosePoint {
    let id: Int
    let x: Float
    let y: Float
    let z: Float
    let visibility: Float?
    let presence: Float?
}

enum DetectionState: String {
    case idle, waiting, detected, notDetected, error
    var label: String {
        switch self {
        case .idle: return "추론 정지"
        case .waiting: return "추론 결과 대기"
        case .detected: return "관절 검출 · 자세 판정 아님"
        case .notDetected: return "사람 미검출"
        case .error: return "추론 오류"
        }
    }
}

struct PoseOutput {
    var state: DetectionState = .idle
    var source = "입력 없음"
    var normalized: [PosePoint] = []
    var world: [PosePoint] = []
    var image: CGImage?
    var width = 0
    var height = 0
    var mirrored = false
    var captureEpoch = 0
    var frameId = 0
    var sampleTimeMs: Int64?
    var sourceCaptureTimeMs: Double?
    var inferenceEndMs: Int64?
    var assets = "검사 전"
    var modelHash: String?
    var error: String?
    var inFlight = 0
    var pending = 0
    var peakPending = 0
    var replaced = 0
    var discarded = 0
}

import CoreVideo
import MediaPipeTasksVision

/// 이 객체의 생성·호출·해제는 추론 직렬 큐에서만 수행한다.
final class PoseRunner {
    static let sdkVersion = "MediaPipeTasksVision 1.0.0"
    private let landmarker: PoseLandmarker

    init(modelPath: String) throws {
        let options = PoseLandmarkerOptions()
        options.baseOptions.modelAssetPath = modelPath
        options.baseOptions.delegate = .CPU
        options.runningMode = .video
        options.numPoses = 1
        options.minPoseDetectionConfidence = 0.5
        options.minPosePresenceConfidence = 0.5
        options.minTrackingConfidence = 0.5
        options.shouldOutputSegmentationMasks = false
        landmarker = try PoseLandmarker(options: options)
    }

    func detect(_ buffer: CVPixelBuffer, timeMs: Int64) throws -> ([PosePoint], [PosePoint]) {
        // AVCapture 출력이 이미 회전됐다. MPImage에서 재회전/전면 미러를 적용하지 않는다.
        let image = try MPImage(pixelBuffer: buffer)
        let result = try landmarker.detect(videoFrame: image, timestampInMilliseconds: Int(timeMs))
        guard !result.landmarks.isEmpty else { return ([], []) }
        guard result.landmarks.count == 1, result.worldLandmarks.count == 1,
              result.landmarks[0].count == 33, result.worldLandmarks[0].count == 33 else {
            throw DiagnosticError("MediaPipe 관절 배열 크기 불일치")
        }
        let normalized = result.landmarks[0].enumerated().map { id, point in
            PosePoint(id: id, x: point.x, y: point.y, z: point.z,
                      visibility: point.visibility?.floatValue, presence: point.presence?.floatValue)
        }
        let world = result.worldLandmarks[0].enumerated().map { id, point in
            PosePoint(id: id, x: point.x, y: point.y, z: point.z,
                      visibility: point.visibility?.floatValue, presence: point.presence?.floatValue)
        }
        return (normalized, world)
    }
}

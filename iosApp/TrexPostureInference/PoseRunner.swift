import CoreImage
import CoreVideo
import MediaPipeTasksVision
import UIKit

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

/// 얼굴 자세 행렬 추론 결과 — `matrix` 는 행 우선 4×4(16개). 요(°) 계산은 공통 엔진(`faceYawDeg`)이 Android 와 같은 식으로 한다.
struct FaceReading {
    let found: Bool
    let matrix: [Float]
    let inferMs: Int64
    let rotationDeg: Float
    let headPx: Float
}

/// 바닥 세 종목의 시선(spec §101d) — 머리 크롭(엔진 `IosFace.cropSpec` 이 Android `faceCrop` 과 같은 기하로 정한 정사각형)에 Face Landmarker(IMAGE 모드)를 돌린다.
/// 판정 격자(300 ms)마다 한 번만. 생성·호출·해제는 추론 직렬 큐에서만.
final class FaceRunner {
    private let landmarker: FaceLandmarker
    private let context = CIContext(options: [.cacheIntermediates: false])

    init(modelPath: String) throws {
        let options = FaceLandmarkerOptions()
        options.baseOptions.modelAssetPath = modelPath
        options.baseOptions.delegate = .CPU
        options.runningMode = .image
        options.numFaces = 1
        options.minFaceDetectionConfidence = 0.3   // 옆얼굴은 점수가 낮다(Android FACE_MIN_CONFIDENCE 와 같다)
        options.minFacePresenceConfidence = 0.3
        options.minTrackingConfidence = 0.5
        options.outputFaceBlendshapes = false
        options.outputFacialTransformationMatrixes = true
        landmarker = try FaceLandmarker(options: options)
    }

    /// `spec` = 엔진 크롭 명세(cx·cy 는 영상 픽셀, rotateDeg 는 화면 시계 방향 양수, scale = 출력 변 ÷ 크롭 변, outPx = 256).
    func detect(_ buffer: CVPixelBuffer, spec: [String: Any]) throws -> FaceReading {
        guard let cx = spec["cx"] as? Double, let cy = spec["cy"] as? Double, let rotate = spec["rotateDeg"] as? Double,
              let scale = spec["scale"] as? Double, let outPx = spec["outPx"] as? Int else { throw DiagnosticError("얼굴 크롭 명세 오류") }
        let started = Resources.nowMs()
        let height = Double(CVPixelBufferGetHeight(buffer))
        // Core Image 는 y 가 위로 자라므로 영상 좌표(y 아래로)를 뒤집어 다룬다. Android Matrix 의 시계 방향 회전 φ 는 CI 의 반시계 각으로 −φ 다.
        var image = CIImage(cvPixelBuffer: buffer)
        var t = CGAffineTransform(translationX: -cx, y: -(height - cy))
        t = t.concatenating(CGAffineTransform(rotationAngle: CGFloat(-rotate * .pi / 180)))
        t = t.concatenating(CGAffineTransform(scaleX: scale, y: scale))
        t = t.concatenating(CGAffineTransform(translationX: Double(outPx) / 2, y: Double(outPx) / 2))
        image = image.transformed(by: t)
        let square = CGRect(x: 0, y: 0, width: outPx, height: outPx)
        let black = CIImage(color: .black).cropped(to: square)
        let composed = image.cropped(to: square).composited(over: black)
        guard let cg = context.createCGImage(composed, from: square) else { throw DiagnosticError("얼굴 크롭 실패") }
        let mp = try MPImage(uiImage: UIImage(cgImage: cg))
        let result = try landmarker.detect(image: mp)
        let inferMs = Resources.nowMs() - started
        guard !result.faceLandmarks.isEmpty, let m = result.facialTransformationMatrixes.first else {
            return FaceReading(found: false, matrix: [], inferMs: inferMs, rotationDeg: Float(rotate), headPx: Float((spec["headPx"] as? Double) ?? 0))
        }
        var values: [Float] = []
        values.reserveCapacity(16)
        for r in 0..<4 { for c in 0..<4 { values.append(m.value(atRow: r, column: c)) } }
        return FaceReading(found: true, matrix: values, inferMs: inferMs, rotationDeg: Float(rotate), headPx: Float((spec["headPx"] as? Double) ?? 0))
    }
}

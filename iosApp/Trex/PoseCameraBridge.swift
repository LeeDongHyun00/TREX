import AVFoundation
import MediaPipeTasksVision
import TrexCore
import UIKit

/// 자세 화면의 iPhone 카메라·MediaPipe 다리(spec §108c). 분석 루프·판정·코칭은 공유 Kotlin(`PostureLiveSessionScreen`)이고,
/// 이 파일은 영상(세로 640×480 계열, 미러 없음)을 받아 프레임마다 Kotlin 에 넘기고, Kotlin 이 부르면 그 프레임에 MediaPipe 를 돌린다.
/// 프레임 콜백(분석 큐)이 끝날 때까지 다음 프레임은 버려진다 — Android ImageAnalysis 의 KEEP_ONLY_LATEST·단일 분석 스레드와 같다.
final class PoseCameraBridge: NSObject, IosCameraBridge, IosStillDetector, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "trex.posture.camera", qos: .userInitiated)
    private let sinkLock = NSLock()
    private var sink: IosCameraFrameSink?
    /// 지금 세션을 쓰는 화면의 세대(Kotlin `IosPoseCamera.stream` 이 start 마다 올린다) — `stop` 은 자기 세대일 때만 끈다.
    /// 종목이 바뀔 때 AnimatedContent 가 옛 자세 화면을 한 프레임 더 두어 새 화면의 start 뒤에 옛 화면의 stop 이 왔고, 세션이 꺼진 채 미리보기가 멈추고
    /// 사람 인식·준비 카운트다운이 없었다(10-30 iPhone 보고 2번). 다리는 앱에 하나라 세대로 가른다.
    private var generation: Int64 = 0
    /// 미리보기 레이어는 **하나**를 세션에 한 번만 붙인다 — 레이어를 세션에 붙이고 떼는 일은 돌아가는 세션의 재구성이라 메인 스레드를 초 단위로 막는다
    /// (10-30 보고: 종목 전환 9 s 멈춤). Compose 에는 호출마다 새 컨테이너 뷰를 주고 이 뷰를 그 안으로 옮긴다(`makePreviewView`).
    private lazy var preview: CameraPreviewView = {
        let view = CameraPreviewView()
        view.backgroundColor = .black
        view.previewLayer.videoGravity = .resizeAspect   // FIT_CENTER — 분석 범위 전체를 보인다
        view.previewLayer.session = session
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        return view
    }()
    private var pose: PoseRunner?
    private var face: FaceRunner?
    private var faceFailed = false
    // 분석 큐 안(Kotlin analyze)에서도 읽으므로 큐가 아니라 잠금으로 지킨다 — queue.sync 를 쓰면 같은 큐에서 교착한다
    private let errorLock = NSLock()
    private var poseError: String?
    var modelError: String? { errorLock.lock(); defer { errorLock.unlock() }; return poseError }

    /// Compose 에 줄 뷰는 **부를 때마다 새 컨테이너**다. 하나를 돌려쓰면 종목 전환의 한 프레임 겹침에서 두 UIKitView 가 같은 UIView 를 쥐고,
    /// Compose Multiplatform 의 인터롭 장부(UIView 가 키)에서 뒤 화면의 등록이 지워져 그 래퍼가 화면에 남았다(10-30 iPhone 보고 3번 — 근육 피로도의 카메라 잔상).
    /// 미리보기 레이어 자체는 하나(`preview`)를 새 컨테이너로 옮긴다 — 옛 컨테이너는 비고, 세션 재구성은 없다.
    func makePreviewView() -> UIView {
        let container = UIView()
        container.backgroundColor = .black
        preview.frame = container.bounds
        container.addSubview(preview)
        return container
    }

    /// Compose 가 컨테이너를 놓았다 — 아직 그 안에 있으면 미리보기를 뺀다(뒤에 다른 컨테이너가 가져갔으면 건드리지 않는다).
    func releasePreviewView(view: UIView) {
        if preview.superview === view { preview.removeFromSuperview() }
    }

    /// 구성·시작은 **카메라 큐에서**, 결과는 `sink.onStarted(failure:)` 로 메인에 — 메인에서 `queue.sync` 로 기다리면 돌아가는 세션의 재구성·시작 동안 화면이 멈춘다
    /// (10-30 보고: 종목 전환 9 s). 같은 렌즈가 이미 구성돼 있으면 재구성하지 않는다.
    func start(front: Bool, generation: Int64, sink: IosCameraFrameSink) {
        sinkLock.lock(); self.sink = sink; self.generation = generation; sinkLock.unlock()
        queue.async { [self] in
            var failure: String?
            do { try configure(front: front) } catch { failure = (error as? BridgeError)?.message ?? error.localizedDescription }
            if failure == nil, !session.isRunning { session.startRunning() }
            DispatchQueue.main.async { sink.onStarted(failure: failure) }
        }
    }

    /// [generation] 이 지금 세대일 때만 — 뒤에 시작한 화면이 있으면 그 세션을 건드리지 않는다.
    func stop(generation: Int64) {
        sinkLock.lock()
        guard self.generation == generation else { sinkLock.unlock(); return }
        // 진행 중인 프레임은 끝까지 간다(Android clearAnalyzer 와 같다). 다음 프레임부터 넘기지 않는다
        sink = nil
        sinkLock.unlock()
        queue.async { [session] in if session.isRunning { session.stopRunning() } }
    }

    /// 분석 큐 — 자세 추론(VIDEO 모드). 영상은 이미 세로로 돌아 있다(연결 방향 = 세로).
    func detectPose(frame: IosCameraFrame, timestampMs: Int64) -> IosPoseDetection? {
        guard let frame = frame as? PoseCameraFrame, let buffer = frame.buffer else { return nil }
        do {
            if pose == nil {
                guard let path = Bundle.main.path(forResource: "pose_landmarker_full", ofType: "task") else { throw BridgeError("자세 모델이 번들에 없어요") }
                pose = try PoseRunner(modelPath: path)
            }
            let (normalized, world) = try pose!.detect(buffer, timeMs: timestampMs)
            guard normalized.count == 33, world.count == 33 else { return nil }
            let xy = KotlinFloatArray(size: 66), vis = KotlinFloatArray(size: 33), w = KotlinFloatArray(size: 99)
            for (i, p) in normalized.enumerated() {
                xy.set(index: Int32(i * 2), value: p.x)
                xy.set(index: Int32(i * 2 + 1), value: p.y)
                // Android 와 같다 — min(visibility, presence), 값이 없으면 1
                vis.set(index: Int32(i), value: min(p.visibility ?? 1, p.presence ?? 1))
            }
            for (i, p) in world.enumerated() {
                w.set(index: Int32(i * 3), value: p.x)
                w.set(index: Int32(i * 3 + 1), value: p.y)
                w.set(index: Int32(i * 3 + 2), value: p.z)
            }
            return IosPoseDetection(xy: xy, visibility: vis, world: w)
        } catch {
            // 모델을 못 올렸으면 이 세션은 다시 시도하지 않는다(Android initError). 추론 한 번의 실패는 사람 없음과 같다
            if pose == nil { errorLock.lock(); poseError = "모델 로드 실패: \((error as? BridgeError)?.message ?? error.localizedDescription)"; errorLock.unlock() }
            return nil
        }
    }

    /// 분석 큐 — 얼굴 메시(IMAGE 모드)를 머리 크롭에. 크롭 기하는 공유 `FaceCrop`(Android `faceCrop` 과 같은 식)이 정했다.
    func detectFace(frame: IosCameraFrame, cx: Float, cy: Float, rotationDeg: Float, scale: Float, outPx: Int32) -> IosFaceDetection? {
        guard !faceFailed, let frame = frame as? PoseCameraFrame, let buffer = frame.buffer else { return nil }
        do {
            if face == nil {
                guard let path = Bundle.main.path(forResource: "face_landmarker", ofType: "task") else { throw BridgeError("얼굴 모델이 번들에 없어요") }
                face = try FaceRunner(modelPath: path)
            }
            let spec: [String: Any] = ["cx": Double(cx), "cy": Double(cy), "rotateDeg": Double(rotationDeg), "scale": Double(scale), "outPx": Int(outPx)]
            let reading = try face!.detect(buffer, spec: spec)
            guard reading.found else { return IosFaceDetection(found: false, matrix: nil) }
            let matrix = KotlinFloatArray(size: Int32(reading.matrix.count))
            for (i, v) in reading.matrix.enumerated() { matrix.set(index: Int32(i), value: v) }
            return IosFaceDetection(found: true, matrix: matrix)
        } catch {
            // 얼굴 모델 실패는 이 세션에서 다시 시도하지 않는다 — 얼굴 피처 없이 간다(시선 '측정 중', Android 와 같다)
            faceFailed = true
            return nil
        }
    }

    /// 개발용 확인(--posture-probe) — 번들 상대 경로의 그림 한 장(GIF 는 첫 장)을 BGRA 버퍼로 만들어 분석 큐에서 자세 추론.
    func detectStill(bundleFile: String, timestampMs: Int64) -> IosStillDetection? {
        guard let image = UIImage(contentsOfFile: Bundle.main.bundlePath + "/" + bundleFile)?.cgImage else { return nil }
        let width = image.width, height = image.height
        var buffer: CVPixelBuffer?
        let attrs = [kCVPixelBufferCGImageCompatibilityKey: true, kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary
        guard CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, attrs, &buffer) == kCVReturnSuccess, let buffer else { return nil }
        CVPixelBufferLockBaseAddress(buffer, [])
        let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: width, height: height, bitsPerComponent: 8,
                                bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        context?.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        CVPixelBufferUnlockBaseAddress(buffer, [])
        let frame = PoseCameraFrame(buffer: buffer)
        let pose = queue.sync { detectPose(frame: frame, timestampMs: timestampMs) }
        return IosStillDetection(width: Int32(width), height: Int32(height), pose: pose)
    }

    /// 분석이 끝나기 전에 온 프레임을 카메라가 버렸다(최신만) — 개발용 확인 수치.
    func captureOutput(_ output: AVCaptureOutput, didDrop sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        IosCameraCounters.shared.dropped = IosCameraCounters.shared.dropped + 1
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        sinkLock.lock(); let current = sink; sinkLock.unlock()
        guard let current, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let frame = PoseCameraFrame(buffer: buffer)
        current.onFrame(frame: frame)
        // 콜백이 끝나면 버퍼를 바로 카메라 풀에 돌려준다 — Kotlin 쪽 감싸개는 Kotlin/Native GC 가 걷을 때까지 살아 있어서,
        // 버퍼를 쥔 채 두면 풀(몇 장)이 바닥나 카메라가 프레임을 버렸다(실기기 확인: 초당 29장 중 1~2장만 도착)
        frame.release()
    }

    /// 분석 영상 = 4:3(640×480 → 세로 480×640, Android 분석 해상도와 같은 비율·크기), BGRA, 세로, 미러 없음. 전면 미러는 미리보기에서만.
    private func configure(front: Bool) throws {
        let position: AVCaptureDevice.Position = front ? .front : .back
        // 같은 렌즈가 이미 구성돼 있으면(종목 전환) 재구성하지 않는다 — 돌아가는 세션의 commitConfiguration 은 초 단위다
        if let current = session.inputs.compactMap({ $0 as? AVCaptureDeviceInput }).first, current.device.position == position,
           session.outputs.contains(output) { return }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        if session.canSetSessionPreset(.vga640x480) { session.sessionPreset = .vga640x480 }
        session.inputs.forEach { session.removeInput($0) }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) else {
            throw BridgeError("이 기기에서 카메라를 찾지 못했어요")
        }
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else { throw BridgeError("카메라 입력을 연결하지 못했어요") }
        session.addInput(input)
        if !session.outputs.contains(output) {
            output.alwaysDiscardsLateVideoFrames = true
            output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            output.setSampleBufferDelegate(self, queue: queue)
            guard session.canAddOutput(output) else { throw BridgeError("카메라 출력을 연결하지 못했어요") }
            session.addOutput(output)
        }
        if let connection = output.connection(with: .video) {
            if connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = false
            }
        }
        DispatchQueue.main.async { [weak self] in
            if let connection = self?.preview.previewLayer.connection, connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
        }
    }
}

/// CVPixelBuffer 한 장 — Kotlin 에는 크기만 보이고, 추론은 이 객체를 돌려받아 버퍼를 쓴다. 프레임 콜백이 끝나면 [release] 로 놓는다.
final class PoseCameraFrame: NSObject, IosCameraFrame {
    private(set) var buffer: CVPixelBuffer?
    let width: Int32
    let height: Int32
    init(buffer: CVPixelBuffer) {
        self.buffer = buffer
        width = Int32(CVPixelBufferGetWidth(buffer))
        height = Int32(CVPixelBufferGetHeight(buffer))
    }
    func release() { buffer = nil }
}

final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

private struct BridgeError: Error { let message: String; init(_ message: String) { self.message = message } }

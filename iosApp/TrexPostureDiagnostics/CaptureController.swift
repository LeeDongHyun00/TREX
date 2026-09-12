import AVFoundation
import CoreMotion
import SwiftUI

enum CaptureStatus: String {
    case idle, requesting, denied, restricted, unavailable, running, paused, interrupted, error

    var label: String {
        switch self {
        case .idle: return "정지"
        case .requesting: return "카메라 권한 확인 중"
        case .denied: return "카메라 권한 거부 · 설정에서 허용해 주세요"
        case .restricted: return "카메라 사용 제한"
        case .unavailable: return "사용 가능한 카메라 없음"
        case .running: return "촬영 중"
        case .paused: return "화면 비활성 · 일시 정지"
        case .interrupted: return "다른 작업으로 촬영 중단"
        case .error: return "촬영 오류 · 다시 시작해 주세요"
        }
    }
}

struct CaptureFrame {
    let frameId: Int
    let sampleTimeMs: Int64
    let sourceCaptureTimeMs: Double?
    let width: Int
    let height: Int
}

struct GravityReading {
    let x: Double
    let y: Double
    let z: Double
    let sensorTimeMs: Double
}

/// Swift 내부 진단 값이다. P1 DTO나 평가 입력을 대신 확정하지 않는다.
struct CaptureSnapshot {
    var status: CaptureStatus = .idle
    var front = false
    var orientation: AVCaptureVideoOrientation = .portrait
    var epoch = 0
    var sourceSize = "미확인"
    var frame: CaptureFrame?
    var gravity: GravityReading?
    var motionStatus = "정지"
    var errorDetail: String?

    var orientationLabel: String {
        switch orientation {
        case .portrait: return "세로"
        case .portraitUpsideDown: return "세로 반전"
        case .landscapeLeft: return "가로 왼쪽"
        case .landscapeRight: return "가로 오른쪽"
        @unknown default: return "미확인"
        }
    }
}

/// 모든 촬영/센서 제어와 콜백은 queue에서 직렬 처리한다. UI에는 최신 값 하나만 전달한다.
final class CaptureController: NSObject, ObservableObject {
    @Published private(set) var snapshot = CaptureSnapshot()
    let session = AVCaptureSession()

    private let queue = DispatchQueue(label: "trex.diagnostics.capture", qos: .userInitiated)
    private let motion = CMMotionManager()
    private let motionQueue = OperationQueue()
    private let output = AVCaptureVideoDataOutput()
    private var sink: FrameSink?
    private var observers: [NSObjectProtocol] = []
    private var gate = CaptureGate()
    private var state = CaptureSnapshot()
    private var visible = false
    private var active = false
    private var wanted = false
    private var requesting = false
    private var interrupted = false
    private var needsConfiguration = true
    private let deliveryLock = NSLock()
    private var pendingSnapshot: CaptureSnapshot?
    private var deliveryScheduled = false

    override init() {
        super.init()
        motionQueue.maxConcurrentOperationCount = 1
        motionQueue.underlyingQueue = queue
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        observe(AVCaptureSession.wasInterruptedNotification) { owner in
            guard owner.gate.running else { return }
            owner.interrupted = true
            owner.stop(.interrupted)
        }
        observe(AVCaptureSession.interruptionEndedNotification) { owner in
            owner.interrupted = false
            owner.reconcile()
        }
        observe(AVCaptureSession.runtimeErrorNotification) { owner in
            // 카메라 없는 시뮬레이터는 프리뷰 연결만으로도 알림을 보낼 수 있다.
            // 실행하지 않은 세션의 알림을 사용자 촬영 실패로 바꾸지 않는다.
            guard owner.gate.running else { return }
            owner.wanted = false
            owner.state.errorDetail = "AVCaptureSession 런타임 오류"
            owner.stop(.error)
        }
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
        let session = session, motion = motion, output = output
        queue.async {
            output.setSampleBufferDelegate(nil, queue: nil)
            motion.stopDeviceMotionUpdates()
            if session.isRunning { session.stopRunning() }
        }
    }

    func setVisible(_ value: Bool) {
        queue.async { [self] in
            visible = value
            if !value { wanted = false }
            reconcile()
        }
    }

    func setActive(_ value: Bool) {
        queue.async { [self] in active = value; reconcile() }
    }

    func start() {
        queue.async { [self] in wanted = true; reconcile() }
    }

    func stopByUser() {
        queue.async { [self] in wanted = false; state.status = .idle; reconcile() }
    }

    func switchCamera() {
        queue.async { [self] in
            stop(.idle)
            state.front.toggle()
            needsConfiguration = true
            reconcile()
        }
    }

    func setOrientation(_ value: AVCaptureVideoOrientation) {
        queue.async { [self] in
            guard state.orientation != value else { return }
            stop(.idle)
            state.orientation = value
            needsConfiguration = true
            reconcile()
        }
    }

    private func observe(_ name: Notification.Name, action: @escaping (CaptureController) -> Void) {
        observers.append(NotificationCenter.default.addObserver(forName: name, object: session, queue: nil) { [weak self] _ in
            self?.queue.async { [weak self] in
                guard let self else { return }
                action(self)
            }
        })
    }

    private func reconcile() {
        dispatchPrecondition(condition: .onQueue(queue))
        guard wanted, visible, active else {
            let terminal: [CaptureStatus] = [.denied, .restricted, .unavailable, .error]
            stop(wanted ? .paused : (terminal.contains(state.status) ? state.status : .idle))
            return
        }
        guard !interrupted else { stop(.interrupted); return }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .notDetermined:
            state.status = .requesting
            publish()
            guard !requesting else { return }
            requesting = true
            AVCaptureDevice.requestAccess(for: .video) { [weak self] _ in
                self?.queue.async { [weak self] in
                    guard let self else { return }
                    self.requesting = false
                    self.reconcile()
                }
            }
            return
        case .denied:
            wanted = false
            stop(.denied)
            return
        case .restricted:
            wanted = false
            stop(.restricted)
            return
        case .authorized: break
        @unknown default:
            wanted = false
            stop(.restricted)
            return
        }
        guard !gate.running else { return }
        do {
            if needsConfiguration { try configure() }
            state.errorDetail = nil
            let epoch = gate.begin()
            state.epoch = epoch
            let sink = FrameSink { [weak self] sample in self?.receive(sample, epoch: epoch) }
            self.sink = sink
            output.setSampleBufferDelegate(sink, queue: queue)
            session.startRunning()
            guard session.isRunning else {
                wanted = false
                state.errorDetail = "카메라 세션을 시작하지 못했습니다"
                stop(.error)
                return
            }
            state.status = .running
            if let camera = (session.inputs.first as? AVCaptureDeviceInput)?.device {
                let size = CMVideoFormatDescriptionGetDimensions(camera.activeFormat.formatDescription)
                state.sourceSize = "\(size.width) × \(size.height)"
            }
            startMotion(epoch: epoch)
            publish()
        } catch {
            wanted = false
            state.errorDetail = error.localizedDescription
            stop((error as? CaptureSetupError) == .noCamera ? .unavailable : .error)
        }
    }

    private func configure() throws {
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: state.front ? .front : .back) else {
            throw CaptureSetupError.noCamera
        }
        let input = try AVCaptureDeviceInput(device: camera)
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.inputs.forEach(session.removeInput)
        session.outputs.forEach(session.removeOutput)
        if session.canSetSessionPreset(.hd1280x720) { session.sessionPreset = .hd1280x720 }
        guard session.canAddInput(input) else { throw CaptureSetupError.configuration }
        session.addInput(input)
        guard session.canAddOutput(output) else { throw CaptureSetupError.configuration }
        session.addOutput(output)
        guard let connection = output.connection(with: .video), connection.isVideoOrientationSupported else {
            throw CaptureSetupError.configuration
        }
        // 출력 버퍼를 실제 회전한다. 전면 미러는 PreviewLayer에만 적용한다.
        connection.videoOrientation = state.orientation
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = false
        }
        let dimensions = CMVideoFormatDescriptionGetDimensions(camera.activeFormat.formatDescription)
        state.sourceSize = "\(dimensions.width) × \(dimensions.height)"
        needsConfiguration = false
    }

    private func stop(_ status: CaptureStatus) {
        if gate.running {
            gate.end()
            state.epoch = gate.epoch
        }
        output.setSampleBufferDelegate(nil, queue: nil)
        sink = nil
        motion.stopDeviceMotionUpdates()
        if session.isRunning { session.stopRunning() }
        state.status = status
        if status != .error { state.errorDetail = nil }
        state.sourceSize = "미확인"
        state.frame = nil
        state.gravity = nil
        state.motionStatus = "정지"
        publish()
    }

    private func receive(_ sample: CMSampleBuffer, epoch: Int) {
        // delegate 호출 안에서만 버퍼를 읽는다. 영상 저장/비동기 버퍼 대기열은 없다.
        let now = Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
        guard gate.accept(epoch: epoch, sampleTimeMs: now), let buffer = CMSampleBufferGetImageBuffer(sample) else { return }
        let pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample)) * 1_000
        state.frame = CaptureFrame(frameId: gate.frameId, sampleTimeMs: now,
                                   sourceCaptureTimeMs: pts.isFinite ? pts : nil,
                                   width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer))
        publish()
    }

    private func startMotion(epoch: Int) {
        guard motion.isDeviceMotionAvailable else {
            state.motionStatus = "센서 사용 불가"
            return
        }
        state.motionStatus = "중력 샘플 대기"
        motion.deviceMotionUpdateInterval = 0.3
        motion.startDeviceMotionUpdates(to: motionQueue) { [weak self] data, error in
            guard let self, self.gate.isCurrent(epoch) else { return }
            if error != nil {
                self.state.motionStatus = "Core Motion 오류"
                self.state.gravity = nil
            } else if let data {
                self.state.motionStatus = "원본 디바이스 중력 수신"
                self.state.gravity = GravityReading(x: data.gravity.x, y: data.gravity.y, z: data.gravity.z,
                                                    sensorTimeMs: data.timestamp * 1_000)
            }
            self.publish()
        }
    }

    private func publish() {
        deliveryLock.lock()
        pendingSnapshot = state
        let schedule = !deliveryScheduled
        deliveryScheduled = true
        deliveryLock.unlock()
        guard schedule else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.deliveryLock.lock()
            let newest = self.pendingSnapshot
            self.pendingSnapshot = nil
            self.deliveryScheduled = false
            self.deliveryLock.unlock()
            if let newest { self.snapshot = newest }
        }
    }
}

private enum CaptureSetupError: LocalizedError {
    case noCamera, configuration
    var errorDescription: String? {
        self == .noCamera ? "이 환경에서 선택한 카메라를 찾지 못했습니다" : "카메라 입력·방향·출력 구성을 지원하지 않습니다"
    }
}

private final class FrameSink: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let callback: (CMSampleBuffer) -> Void
    init(callback: @escaping (CMSampleBuffer) -> Void) { self.callback = callback }
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        callback(sampleBuffer)
    }
}

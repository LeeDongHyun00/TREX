import CoreImage
import CoreVideo
import SwiftUI

/// 입력 버퍼는 실행 1개+대기 1개, 표시 이미지는 최신 1개만 유지한다. 파일 저장은 없다.
final class InferenceCoordinator: ObservableObject {
    @Published private(set) var output = PoseOutput()
    let sessionId = UUID().uuidString
    let phaseEpoch = 0
    let setToken: String? = nil

    private enum Source { case camera, black, invalidModel }
    private struct Input {
        let buffer: CVPixelBuffer
        let frame: CaptureFrame
        let captureEpoch: Int
        let front: Bool
        let source: Source
        let delayMs: Int
    }
    private let lock = NSLock()
    private let worker = DispatchQueue(label: "trex.diagnostics.inference", qos: .userInitiated)
    private var scheduler = LatestOnlyScheduler<Input>()
    private var cameraActive = false
    private var captureEpoch = 0
    private var delayMs = 0
    private var state = PoseOutput()
    private var deliveryScheduled = false
    // 다음 객체는 worker에서만 접근한다.
    private let imageContext = CIContext(options: [.cacheIntermediates: false])
    private var runner: PoseRunner?
    private var runnerGeneration = -1
    private var initializationError: Error?
    private var verifiedAssets: [String: String]?

    func setDelay(_ enabled: Bool) {
        lock.lock(); delayMs = enabled ? 1_000 : 0; lock.unlock()
    }

    func setCaptureActivity(epoch: Int, active: Bool) {
        lock.lock()
        cameraActive = active
        captureEpoch = epoch
        scheduler.reset(active: active)
        let assets = state.assets, hash = state.modelHash
        state = PoseOutput()
        state.assets = assets
        state.modelHash = hash
        state.captureEpoch = epoch
        state.state = active ? .waiting : .idle
        publishLocked()
        lock.unlock()
    }

    func submit(buffer: CVPixelBuffer, frame: CaptureFrame, epoch: Int, front: Bool) {
        lock.lock()
        guard cameraActive, epoch == captureEpoch else { lock.unlock(); return }
        let input = Input(buffer: buffer, frame: frame, captureEpoch: epoch, front: front, source: .camera, delayMs: delayMs)
        let start = scheduler.submit(input)
        publishLocked()
        lock.unlock()
        if let start { worker.async { [self] in drain(start) } }
    }

    /// 실제 VIDEO 추론의 미검출 및 SDK 초기화 오류를 확인하는 명시적 합성 입력이다.
    func runProbe(invalidModel: Bool) {
        lock.lock()
        guard !cameraActive, scheduler.inFlight == nil else { lock.unlock(); return }
        let assets = state.assets, hash = state.modelHash
        state = PoseOutput()
        state.assets = assets
        state.modelHash = hash
        state.captureEpoch = captureEpoch
        state.state = .waiting
        do {
            let buffer = try Self.blackBuffer()
            scheduler.reset(active: true)
            let time = Self.nowMs()
            let frame = CaptureFrame(frameId: 0, sampleTimeMs: time, sourceCaptureTimeMs: nil, width: 256, height: 256)
            let input = Input(buffer: buffer, frame: frame, captureEpoch: captureEpoch, front: false,
                              source: invalidModel ? .invalidModel : .black, delayMs: 0)
            let start = scheduler.submit(input)
            publishLocked()
            lock.unlock()
            if let start { worker.async { [self] in drain(start) } }
        } catch {
            state.state = .error
            state.error = error.localizedDescription
            publishLocked()
            lock.unlock()
        }
    }

    private func drain(_ initial: LatestOnlyScheduler<Input>.Job) {
        var current: LatestOnlyScheduler<Input>.Job? = initial
        while let job = current {
            let result = autoreleasepool { process(job) }
            lock.lock()
            let completion = scheduler.complete(job)
            if completion.accepted { state = result }
            publishLocked()
            lock.unlock()
            current = completion.next
        }
    }

    private func process(_ job: LatestOnlyScheduler<Input>.Job) -> PoseOutput {
        let input = job.value
        var result = PoseOutput()
        result.source = input.source == .camera ? "실제 카메라" : (input.source == .black ? "합성 검정 프레임" : "합성 모델 경로 오류")
        result.width = input.frame.width; result.height = input.frame.height
        result.mirrored = input.front
        result.captureEpoch = input.captureEpoch; result.frameId = input.frame.frameId
        result.sampleTimeMs = input.frame.sampleTimeMs
        result.sourceCaptureTimeMs = input.frame.sourceCaptureTimeMs
        if input.delayMs > 0 { Thread.sleep(forTimeInterval: Double(input.delayMs) / 1_000) }
        do {
            if verifiedAssets == nil { verifiedAssets = try BundleAssets.verify() }
            if input.source == .invalidModel {
                _ = try PoseRunner(modelPath: "/__trex_diagnostic_missing_model__.task")
                throw DiagnosticError("오류 검사에서 예상과 달리 모델 초기화가 성공했습니다")
            }
            if runnerGeneration != job.generation {
                runner = nil
                initializationError = nil
                runnerGeneration = job.generation
                do {
                    guard let url = Bundle.main.url(forResource: "pose_landmarker_full", withExtension: "task") else {
                        throw DiagnosticError("Full 모델 경로 없음")
                    }
                    runner = try PoseRunner(modelPath: url.path)
                } catch { initializationError = error }
            }
            if let initializationError { throw initializationError }
            guard let runner else { throw DiagnosticError("추론 객체 없음") }
            let (normalized, world) = try runner.detect(input.buffer, timeMs: input.frame.sampleTimeMs)
            result.normalized = normalized
            result.world = world
            result.state = normalized.isEmpty ? .notDetected : .detected
            let image = CIImage(cvPixelBuffer: input.buffer)
            result.image = imageContext.createCGImage(image, from: image.extent)
        } catch {
            result.state = .error
            result.error = error.localizedDescription
        }
        result.inferenceEndMs = Self.nowMs()
        result.assets = verifiedAssets == nil ? "번들 자산 검사 실패" : "4/4 SHA-256 일치"
        result.modelHash = verifiedAssets?["pose_landmarker_full.task"]
        return result
    }

    private func publishLocked() {
        state.inFlight = scheduler.inFlight == nil ? 0 : 1
        state.pending = scheduler.pending == nil ? 0 : 1
        state.peakPending = scheduler.peakPending
        state.replaced = scheduler.replaced
        state.discarded = scheduler.discarded
        guard !deliveryScheduled else { return }
        deliveryScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let latest = self.state
            self.deliveryScheduled = false
            self.lock.unlock()
            self.output = latest
        }
    }

    private static func nowMs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }

    private static func blackBuffer() throws -> CVPixelBuffer {
        var buffer: CVPixelBuffer?
        let attributes = [kCVPixelBufferCGImageCompatibilityKey: true, kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary
        guard CVPixelBufferCreate(kCFAllocatorDefault, 256, 256, kCVPixelFormatType_32BGRA, attributes, &buffer) == kCVReturnSuccess,
              let buffer else { throw DiagnosticError("합성 검정 버퍼 생성 실패") }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { throw DiagnosticError("합성 버퍼 주소 없음") }
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        memset(base, 0, stride * 256)
        for y in 0..<256 {
            let row = base.advanced(by: y * stride).assumingMemoryBound(to: UInt8.self)
            for x in 0..<256 { row[x * 4 + 3] = 255 }
        }
        return buffer
    }
}

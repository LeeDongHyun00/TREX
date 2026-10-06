import AVFoundation
import CoreImage
import TrexCore
import SwiftUI

/// 추론·피처·판정은 직렬 큐, 입력은 실행 1개와 대기 최신 1개다. 이미지 미러는 표시에서만 적용한다.
final class PosePipeline: ObservableObject {
    @Published private(set) var status = EngineStatus()
    @Published private(set) var output = PoseOutput()
    let instruction: String
    private let worker = DispatchQueue(label: "trex.ios.engine", qos: .userInitiated)
    private let lock = NSLock()
    private let engine: IosEngine
    private var runner: PoseRunner?
    private let imageContext = CIContext(options: [.cacheIntermediates: false])
    private struct Input { let buffer: CVPixelBuffer; let frame: CaptureFrame; let epoch: Int; let front: Bool; let gravity: GravityReading? }
    private var scheduler = LatestOnlyScheduler<Input>()
    private var epoch = 0
    private var lastInference = Int64.min
    private var stopped = false
    private var paused = false
    private var instructionHeld = false
    init(item: PlanItem, mode: String) throws {
        _ = try BundleAssets.verify()
        engine = try IosEngine(standingRules: Resources.text("rules_mp_v0.json"), floorRules: Resources.text("rules_floor_v0.json"))
        engine.setNormalReference(text: try Resources.text("normal_pose_reference.tsv"))
        // Android와 같이 촬영 메타데이터 없는 개발용 기준선을 자동 보정으로 적용하지 않는다.
        let config: [String: Any] = ["name": item.name, "mode": mode, "target": item.repetitions]
        let text = try engine.configure(config: Resources.json(config))
        let result = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]
        instruction = result?["instruction"] as? String ?? "몸 전체가 보이게 자리 잡아 주세요."
        engine.arm(nowMs: Resources.nowMs())
    }
    private func isStopped() -> Bool {
        lock.lock(); defer { lock.unlock() }; return stopped
    }
    private func accepts(_ job: LatestOnlyScheduler<Input>.Job) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return scheduler.active && scheduler.generation == job.generation && !stopped
    }
    private func evaluate(_ text: String, job: LatestOnlyScheduler<Input>.Job) throws -> EngineStatus? {
        lock.lock(); defer { lock.unlock() }
        guard scheduler.active && scheduler.generation == job.generation && !stopped else { return nil }
        return try Resources.parse(engine.frame(input: text), as: EngineStatus.self)
    }
    func activity(epoch: Int, active: Bool) {
        lock.lock(); self.epoch = epoch; scheduler.reset(active: active && !stopped); lock.unlock()
        if !active { worker.async { [weak self] in
            guard let self, !self.isStopped() else { return }
            self.engine.pause(paused: true); self.paused = true
        } }
        else { worker.async { [weak self] in
            guard let self, !self.isStopped() else { return }
            if self.paused { self.engine.pause(paused: false); self.paused = false }
            self.engine.holdInstruction(hold: self.instructionHeld)
        } }
    }
    func holdInstruction(_ value: Bool) { worker.async { [weak self] in self?.instructionHeld = value; self?.engine.holdInstruction(hold: value) } }
    func submit(buffer: CVPixelBuffer, frame: CaptureFrame, epoch: Int, front: Bool, gravity: GravityReading?) {
        lock.lock()
        let interval: Int64 = ProcessInfo.processInfo.thermalState == .serious || ProcessInfo.processInfo.thermalState == .critical ? 300 : 85
        guard epoch == self.epoch, !stopped, lastInference == .min || frame.sampleTimeMs - lastInference >= interval else { lock.unlock(); return }
        lastInference = frame.sampleTimeMs
        let job = scheduler.submit(Input(buffer: buffer, frame: frame, epoch: epoch, front: front, gravity: gravity))
        lock.unlock()
        if let job { worker.async { [weak self] in self?.drain(job) } }
    }
    private func drain(_ first: LatestOnlyScheduler<Input>.Job) {
        let result = autoreleasepool { process(first) }
        lock.lock(); let completion = scheduler.complete(first); lock.unlock()
        if completion.accepted, let result {
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.lock.lock(); let valid = !self.stopped && self.scheduler.generation == first.generation; self.lock.unlock()
                if valid { self.status = result.0; self.output = result.1 }
            }
        }
        // 다음 추론을 다시 큐에 넣어 먼저 들어온 정지·복귀 명령이 엔진에 선행하게 한다.
        if let next = completion.next { worker.async { [weak self] in self?.drain(next) } }
    }
    private func process(_ job: LatestOnlyScheduler<Input>.Job) -> (EngineStatus, PoseOutput)? {
        guard accepts(job) else { return nil }
        let input = job.value
        var pose = PoseOutput()
        pose.width = input.frame.width; pose.height = input.frame.height; pose.mirrored = input.front
        pose.frameId = input.frame.frameId; pose.captureEpoch = input.epoch; pose.sampleTimeMs = input.frame.sampleTimeMs
        do {
            if runner == nil {
                guard let url = Bundle.main.url(forResource: "pose_landmarker_full", withExtension: "task") else { throw DiagnosticError("자세 모델이 없습니다.") }
                runner = try PoseRunner(modelPath: url.path)
            }
            let (normalized, world) = try runner!.detect(input.buffer, timeMs: input.frame.sampleTimeMs)
            pose.normalized = normalized; pose.world = world; pose.state = normalized.isEmpty ? .notDetected : .detected
            pose.inferenceEndMs = Resources.nowMs()
            let fresh = input.gravity.map { abs(Double(input.frame.sampleTimeMs) - $0.sensorTimeMs) <= 250 } ?? false
            let g = input.gravity
            // 세로 고정 · CoreMotion은 실제 아래 방향. 후면(+Z 카메라쪽), 전면(-Z 카메라쪽)의 축을 각각 변환한다.
            let up: [Double] = fresh ? [input.front ? g!.x : -g!.x, -g!.y, input.front ? g!.z : -g!.z] : [0, 1, 0]
            let payload: [String: Any] = ["timeMs": input.frame.sampleTimeMs, "width": pose.width, "height": pose.height,
                "xy": normalized.flatMap { [$0.x, $0.y] }, "world": world.flatMap { [$0.x, $0.y, $0.z] },
                "visibility": normalized.map { min($0.visibility ?? 1, $0.presence ?? 1) }, "up": up,
                "gravityFresh": fresh, "inferMs": (pose.inferenceEndMs ?? input.frame.sampleTimeMs) - input.frame.sampleTimeMs]
            let text = try Resources.json(payload)
            guard let status = try evaluate(text, job: job) else { return nil }
            let image = CIImage(cvPixelBuffer: input.buffer)
            pose.image = imageContext.createCGImage(image, from: image.extent)
            return (status, pose)
        } catch {
            guard accepts(job) else { return nil }
            pose.state = .error; pose.error = error.localizedDescription
            // 추론 실패는 빈 관절 입력이다. 직전 프레임으로 횟수/자세를 계속 판단하지 않는다.
            let text = try? Resources.json(["timeMs": input.frame.sampleTimeMs, "width": pose.width, "height": pose.height])
            if let text, let status = try? evaluate(text, job: job) { return (status, pose) }
            return (EngineStatus(), pose)
        }
    }
    func tick() {
        worker.async { [weak self] in
            guard let self, !self.isStopped() else { return }
            self.lock.lock(); let generation = self.scheduler.generation; self.lock.unlock()
            if let status = try? Resources.parse(self.engine.tick(nowMs: Resources.nowMs()), as: EngineStatus.self) {
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    self.lock.lock(); let valid = !self.stopped && generation == self.scheduler.generation; self.lock.unlock()
                    if valid { self.status = status }
                }
            }
        }
    }
    func manualStart() { worker.async { [weak self] in self?.engine.manualStart(nowMs: Resources.nowMs()) } }
    func finish(_ complete: @escaping (Result<(SetReport, String), Error>) -> Void) {
        lock.lock(); stopped = true; scheduler.reset(active: false); lock.unlock()
        worker.async { [self] in
            let result: Result<(SetReport, String), Error> = Result {
                let report = try Resources.parse(engine.finish(nowMs: Resources.nowMs()), as: SetReport.self)
                return (report, engine.exportFrames())
            }
            runner = nil
            DispatchQueue.main.async { complete(result) }
        }
    }
}

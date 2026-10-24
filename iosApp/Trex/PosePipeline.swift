import AVFoundation
import CoreImage
import TrexCore
import SwiftUI

/// 추론·피처·판정은 직렬 큐, 입력은 실행 1개와 대기 최신 1개다. 이미지 미러는 표시에서만 적용한다.
/// 판정·횟수·발화 정책은 전부 공통 엔진(`IosEngine`)이 정한다 — 이 파일은 카메라 프레임을 엔진 입력으로 바꾸고 엔진 출력을 화면·TTS 로 나르는 **배선**이다.
final class PosePipeline: ObservableObject {
    @Published private(set) var status = EngineStatus()
    @Published private(set) var output = PoseOutput()
    let config: EngineConfig
    var instruction: String { config.instruction }
    /// 엔진 대기열이 고른 발화 제출(메인 스레드) — `SessionController` 가 TTS 에 낸다.
    var onSpeech: (([SpeechSubmit]) -> Void)?
    var onTone: (([String]) -> Void)?
    private let worker = DispatchQueue(label: "trex.ios.engine", qos: .userInitiated)
    private let lock = NSLock()
    private let engine: IosEngine
    private var runner: PoseRunner?
    private var face: FaceRunner?
    private var faceFailed = false
    private var lastFaceMs = Int64.min / 2
    private var lastFace: FaceReading?
    private var lastFaceAt = Int64.min / 2
    private let imageContext = CIContext(options: [.cacheIntermediates: false])
    private struct Input { let buffer: CVPixelBuffer; let frame: CaptureFrame; let epoch: Int; let front: Bool; let gravity: GravityReading? }
    private var scheduler = LatestOnlyScheduler<Input>()
    private var epoch = 0
    private var lastInference = Int64.min
    private var stopped = false
    private var paused = false
    private var front = false
    init(item: PlanItem, mode: String, front: Bool, muted: Bool) throws {
        _ = try BundleAssets.verify()
        engine = try IosEngine(standingRules: Resources.text("rules_mp_v0.json"), floorRules: Resources.text("rules_floor_v0.json"))
        engine.setNormalReference(text: try Resources.text("normal_pose_reference.tsv"))
        self.front = front
        // Android와 같이 촬영 메타데이터 없는 개발용 기준선을 자동 보정으로 적용하지 않는다.
        let config: [String: Any] = ["name": item.name, "mode": mode, "target": item.repetitions, "timed": item.hold, "front": front,
                                     "muted": muted, "voiceReady": SpeechService.koreanVoiceAvailable]
        let text = try engine.configure(config: Resources.json(config))
        self.config = try Resources.parse(text, as: EngineConfig.self)
        engine.arm(nowMs: Resources.nowMs())
    }
    private func isStopped() -> Bool { lock.lock(); defer { lock.unlock() }; return stopped }
    private func accepts(_ job: LatestOnlyScheduler<Input>.Job) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return scheduler.active && scheduler.generation == job.generation && !stopped
    }
    private func evaluate(_ text: String, job: LatestOnlyScheduler<Input>.Job) throws -> EngineStatus? {
        lock.lock(); defer { lock.unlock() }
        guard scheduler.active && scheduler.generation == job.generation && !stopped else { return nil }
        return try Resources.parse(engine.frame(input: text), as: EngineStatus.self)
    }
    /// 엔진 출력의 발화·톤을 메인으로 나른다(제출 순서 보존).
    private func deliver(_ status: EngineStatus) {
        guard !status.speech.isEmpty || !status.tones.isEmpty else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if !status.tones.isEmpty { self.onTone?(status.tones) }
            if !status.speech.isEmpty { self.onSpeech?(status.speech) }
        }
    }
    func activity(epoch: Int, active: Bool) {
        lock.lock(); self.epoch = epoch; scheduler.reset(active: active && !stopped); lock.unlock()
        worker.async { [weak self] in
            guard let self, !self.isStopped() else { return }
            if !active { if !self.paused { self.engine.pause(paused: true, nowMs: Resources.nowMs()); self.paused = true } }
            else if self.paused { self.engine.pause(paused: false, nowMs: Resources.nowMs()); self.paused = false }
        }
    }
    func setMuted(_ muted: Bool) { worker.async { [weak self] in self?.engine.setMuted(muted: muted) } }
    func setFront(_ front: Bool) { lock.lock(); self.front = front; lock.unlock(); worker.async { [weak self] in self?.engine.setFrontCamera(front: front) } }
    func submit(buffer: CVPixelBuffer, frame: CaptureFrame, epoch: Int, front: Bool, gravity: GravityReading?) {
        lock.lock()
        // 열 상태가 나쁘면 추론 간격을 300ms로 줄인다 — 순간 접촉(§98a)은 놓칠 수 있다
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
            deliver(result.0)
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.lock.lock(); let valid = !self.stopped && self.scheduler.generation == first.generation; self.lock.unlock()
                if valid { self.status = result.0; self.output = result.1 }
            }
        }
        // 다음 추론을 다시 큐에 넣어 먼저 들어온 정지·복귀 명령이 엔진에 선행하게 한다.
        if let next = completion.next { worker.async { [weak self] in self?.drain(next) } }
    }
    /// §101d: 바닥 세 종목만, 판정 격자(300 ms)마다 한 번 머리 크롭에 얼굴 모델. 결과는 450 ms 안의 프레임에 얹는다(Android FACE_STALE_MS).
    private func facePayload(_ buffer: CVPixelBuffer, normalized: [PosePoint], width: Int, height: Int, now: Int64) -> [String: Any]? {
        guard config.floor, !normalized.isEmpty else { return nil }
        if now - lastFaceMs >= 300, !faceFailed {
            lastFaceMs = now
            do {
                if face == nil {
                    guard let url = Bundle.main.url(forResource: "face_landmarker", withExtension: "task") else { throw DiagnosticError("얼굴 모델이 없습니다.") }
                    face = try FaceRunner(modelPath: url.path)
                }
                let spec = try IosFace.shared.cropSpec(input: Resources.json(["xy": normalized.flatMap { [$0.x, $0.y] },
                    "visibility": normalized.map { min($0.visibility ?? 1, $0.presence ?? 1) }, "width": width, "height": height]))
                if let dict = try JSONSerialization.jsonObject(with: Data(spec.utf8)) as? [String: Any] {
                    lastFace = try face!.detect(buffer, spec: dict); lastFaceAt = now
                } else { lastFace = nil }   // 머리가 안 보이면 이번 칸은 건너뛴다 — 마지막 결과는 450 ms 뒤 사라진다
            } catch {
                // 모델 실패는 이 세션에서 다시 시도하지 않는다 — 얼굴 피처 없이 간다(시선은 '측정 중')
                faceFailed = true; lastFace = nil
            }
        }
        guard let f = lastFace, now - lastFaceAt <= 450 else { return nil }
        return ["found": f.found, "matrix": f.matrix, "inferMs": f.inferMs, "rotation": f.rotationDeg, "headPx": f.headPx]
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
            var payload: [String: Any] = ["timeMs": input.frame.sampleTimeMs, "width": pose.width, "height": pose.height,
                "xy": normalized.flatMap { [$0.x, $0.y] }, "world": world.flatMap { [$0.x, $0.y, $0.z] },
                "visibility": normalized.map { min($0.visibility ?? 1, $0.presence ?? 1) }, "up": up,
                "gravityFresh": fresh, "inferMs": (pose.inferenceEndMs ?? input.frame.sampleTimeMs) - input.frame.sampleTimeMs]
            if let face = facePayload(input.buffer, normalized: normalized, width: pose.width, height: pose.height, now: input.frame.sampleTimeMs) { payload["face"] = face }
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
    private func publish(_ status: EngineStatus, generation: Int) {
        deliver(status)
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock(); let valid = !self.stopped && generation == self.scheduler.generation; self.lock.unlock()
            if valid { self.status = status }
        }
    }
    func tick() {
        worker.async { [weak self] in
            guard let self, !self.isStopped() else { return }
            self.lock.lock(); let generation = self.scheduler.generation; self.lock.unlock()
            if let status = try? Resources.parse(self.engine.tick(nowMs: Resources.nowMs()), as: EngineStatus.self) { self.publish(status, generation: generation) }
        }
    }
    func manualStart() { worker.async { [weak self] in self?.engine.manualStart(nowMs: Resources.nowMs()) } }
    // ---- 발화(엔진 대기열) ----
    func speechStarted() { worker.async { [weak self] in self?.engine.speechStarted(nowMs: Resources.nowMs()) } }
    func speechFinished() {
        worker.async { [weak self] in
            guard let self else { return }
            let next = self.engine.speechFinished(nowMs: Resources.nowMs())
            if let submits = try? Resources.parse(next, as: [SpeechSubmit].self), !submits.isEmpty {
                DispatchQueue.main.async { [weak self] in self?.onSpeech?(submits) }
            }
        }
    }
    func stopSpeech() { worker.async { [weak self] in self?.engine.stopSpeech() } }
    /// 목표 도달 — 대기 카운트 정리·봉인(§101). 이후 `advanceDue` 가 true 를 줄 때 화면을 넘긴다.
    func beginSetEnd() { worker.async { [weak self] in self?.engine.beginSetEnd(nowMs: Resources.nowMs()) } }
    func advanceDue(_ completion: @escaping (Bool) -> Void) {
        worker.async { [weak self] in
            guard let self, !self.isStopped() else { completion(true); return }
            let due = self.engine.advanceDue(nowMs: Resources.nowMs())
            DispatchQueue.main.async { completion(due) }
        }
    }
    /// 자동 진행·정상 시작으로 화면을 넘기며 — 남은 말을 꼬리로 보호한다(세트 끝 `speech.stop()` 대신).
    func finish(handOff: Bool, _ complete: @escaping (Result<(SetReport, String), Error>) -> Void) {
        lock.lock(); stopped = true; scheduler.reset(active: false); lock.unlock()
        worker.async { [self] in
            let result: Result<(SetReport, String), Error> = Result {
                let report = try Resources.parse(engine.finish(nowMs: Resources.nowMs(), handOff: handOff), as: SetReport.self)
                return (report, engine.exportFrames())
            }
            runner = nil; face = nil
            DispatchQueue.main.async { [weak self] in
                if case .success(let (report, _)) = result, let submits = report.speech, !submits.isEmpty { self?.onSpeech?(submits) }
                complete(result)
            }
        }
    }
}

import SwiftUI
import Combine

/// 세션 진행(준비 → 운동 → 결과 → 휴식). 판정·횟수·발화 선택은 엔진이 하고, 여기서는 TTS·톤 재생과 세트 넘김 시각(Android `AdvanceHold`, spec §101 U5)만 다룬다.
@MainActor final class SessionController: ObservableObject, Identifiable {
    let id = UUID()
    let plan: [PlanItem]
    private let store: TrexStore
    @Published private(set) var exerciseIndex = 0
    @Published private(set) var setNumber = 1
    @Published private(set) var phase = "preparing"
    @Published private(set) var seconds = 0
    @Published private(set) var report: SetReport?
    @Published private(set) var pipeline: PosePipeline?
    @Published private(set) var capture: CaptureController?
    @Published private(set) var error: String?
    @Published var paused = false
    @Published var muted: Bool
    @Published private(set) var timerFallback = false
    @Published private(set) var front = false
    let speech = SpeechService()
    private let tone = ToneService()
    private var cancellables = Set<AnyCancellable>()
    private var timer: AnyCancellable?
    private var setID = UUID()
    private var startTime: Date?
    private var pausedAt: Date?
    private var pausedDuration = 0.0
    private var restUntil: Date?
    private var lastRecordID: UUID?
    private var advancing = false
    private(set) var recordIDs = Set<UUID>()
    @Published private(set) var baselineCollected = false
    var item: PlanItem { plan[exerciseIndex] }
    init(plan: [PlanItem], store: TrexStore) {
        self.plan = plan; self.store = store; muted = store.data.muted
    }
    func start() {
        guard !plan.isEmpty else { phase = "complete"; return }
        prepare()
        timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect().sink { [weak self] _ in self?.tick() }
    }
    private func prepare() {
        phase = "preparing"; report = nil; seconds = 0; paused = false; startTime = nil; pausedDuration = 0; timerFallback = false; advancing = false
        setID = UUID(); cancellables.removeAll(); pipeline = nil; capture = nil; baselineCollected = false
        speech.muted = muted
        if item.posture {
            do {
                let pipe = try PosePipeline(item: item, mode: store.data.mode, front: front, muted: muted)
                let capture = CaptureController(onActivity: pipe.activity, onPoseFrame: pipe.submit, sampleIntervalMs: 85)
                self.pipeline = pipe; self.capture = capture
                let token = setID
                // 엔진 대기열 → TTS → 엔진(시작·종결) — 한 번에 하나만
                pipe.onSpeech = { [weak self] submits in
                    // 세트가 끝난 뒤의 꼬리·결과 음성도 같은 파이프라인이 낸다(다음 세트의 준비가 시작되면 새 파이프라인이 대기열을 연다)
                    guard let self, self.setID == token else { return }
                    for s in submits { self.speech.play(s) }
                }
                pipe.onTone = { [weak self] kinds in guard let self, !self.muted else { return }; kinds.forEach { self.tone.play($0) } }
                speech.onStart = { [weak pipe] in pipe?.speechStarted() }
                speech.onFinished = { [weak pipe] in pipe?.speechFinished() }
                pipe.$status.receive(on: DispatchQueue.main).sink { [weak self] status in
                    guard let self, self.setID == token, !self.timerFallback, self.phase == "preparing" || self.phase == "active" else { return }
                    self.phase = status.stage == "active" ? "active" : "preparing"
                    if self.phase == "active", self.startTime == nil { self.startTime = Date() }
                    self.objectWillChange.send()
                    if status.done && !self.item.hold { self.reachTarget() }
                }.store(in: &cancellables)
                pipe.$output.receive(on: DispatchQueue.main).sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
                capture.$snapshot.receive(on: DispatchQueue.main).sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
                capture.setVisible(true); capture.setActive(true); capture.start()
            } catch { self.error = "카메라 평가를 시작하지 못했습니다. \(error.localizedDescription)" }
        } else { seconds = 5; startTime = Date().addingTimeInterval(5) }
    }
    /// 플랭크 등 시간 목표의 경과 — 카메라가 확인한 시간(엔진 유지 시계)이 있으면 그것, 아니면 벽시계(Android `HoldBridge` 폴백과 같은 뜻).
    private var holdSeconds: Int? {
        guard item.hold, let hud = pipeline?.status.hold else { return nil }
        return hud.camera ? hud.heldSec : hud.wallSec
    }
    private func tick() {
        guard !paused else { return }
        speech.muted = muted
        if phase == "rest", let until = restUntil {
            seconds = max(0, Int(ceil(until.timeIntervalSinceNow))); if seconds == 0 { advance() }; return
        }
        if item.posture && !timerFallback { pipeline?.tick() }
        guard phase == "active" || (!item.posture || timerFallback) && phase == "preparing", let startTime else { return }
        let elapsed = Date().timeIntervalSince(startTime) - pausedDuration
        if elapsed < 0 { seconds = Int(ceil(-elapsed)); return }
        phase = "active"; seconds = holdSeconds ?? Int(elapsed)
        let duration = item.hold ? item.repetitions : item.repetitions * item.secondsPerRep
        if (item.hold || !item.posture || timerFallback) && seconds >= duration {
            if item.posture && !timerFallback { reachTarget() } else { finishSet(handOff: false) }
        }
    }
    /// 목표 도달(spec §101 U5) — 화면은 ≤ 2 s 에 넘기고 마지막 말은 꼬리로 끝까지(상한 10 s). `speech.stop()` 으로 교정 문장을 자르지 않는다.
    private func reachTarget() {
        guard !advancing, let pipe = pipeline, phase == "active" else { return }
        advancing = true
        pipe.beginSetEnd()
        let token = setID
        func poll() {
            guard self.setID == token, self.phase == "active" else { return }
            pipe.advanceDue { [weak self] due in
                guard let self, self.setID == token else { return }
                if due { self.finishSet(handOff: true) }
                else { DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { poll() } }
            }
        }
        poll()
    }
    func togglePause() {
        guard phase == "active" || phase == "preparing" || phase == "rest" else { return }
        paused.toggle()
        if paused { pausedAt = Date(); speech.stop(); pipeline?.stopSpeech(); capture?.setActive(false) }
        else {
            if let pausedAt { let interval = Date().timeIntervalSince(pausedAt); pausedDuration += interval; if restUntil != nil { restUntil = restUntil!.addingTimeInterval(interval) } }
            pausedAt = nil; capture?.setActive(true)
        }
    }
    func sceneActive(_ active: Bool) { if !active && !paused { togglePause() } }
    func switchCamera() { speech.stop(); pipeline?.stopSpeech(); front.toggle(); pipeline?.setFront(front); capture?.switchCamera() }
    func setMuted(_ value: Bool) { muted = value; speech.muted = value; if value { speech.stop() }; pipeline?.setMuted(value) }
    func useTimer() {
        capture?.setVisible(false); speech.stop(); pipeline?.stopSpeech(); cancellables.removeAll(); setID = UUID()
        pipeline = nil; capture = nil; timerFallback = true; phase = "active"; startTime = Date()
        paused = false; pausedAt = nil; pausedDuration = 0; seconds = 0; error = nil
    }
    /// 수동 '세트 마치기' 는 즉시 멈춘다(`handOff = false`). 목표 도달 자동 진행만 꼬리를 남긴다.
    func finishSet(handOff: Bool = false) {
        guard phase == "active" || phase == "preparing" else { return }
        let token = setID; phase = "saving"; capture?.setVisible(false)
        if !handOff { speech.stop() }
        if let pipeline {
            pipeline.finish(handOff: handOff) { [weak self] result in
                guard let self, token == self.setID else { return }
                switch result {
                case .success(let (report, frames)): self.report = report; self.saveSet(report, frames: frames)
                case .failure(let error): self.error = error.localizedDescription; self.phase = "saveFailed"
                }
            }
        } else { saveSet(nil, frames: nil) }
    }
    private func saveSet(_ report: SetReport?, frames: String?) {
        let reps = report?.reps ?? (item.hold ? 0 : min(item.repetitions, seconds / item.secondsPerRep))
        let duration = report?.heldSec ?? seconds
        let record = SetRecord(name: item.name, setNumber: setNumber, repetitions: reps, duration: duration,
            report: report, weight: store.data.profile.weight, height: store.data.profile.height)
        lastRecordID = record.id; recordIDs.insert(record.id); store.record(record, frames: frames)
        phase = "result"
        // 결과 음성은 엔진 대기열이 낸다(경계 발화 — 꼬리가 돌면 그 뒤). 타이머 수행만 여기서 말한다
        if report == nil { speech.say("세트를 기록했어요.") }
    }
    func retrySaving() { phase = "active"; finishSet() }
    func next() {
        if exerciseIndex == plan.count - 1 && setNumber >= item.sets { phase = "complete"; return }
        phase = "rest"; seconds = item.restSeconds; restUntil = Date().addingTimeInterval(Double(item.restSeconds)); speech.say("\(item.restSeconds)초 쉽니다.")
    }
    func advance() {
        if setNumber < item.sets { setNumber += 1 } else { setNumber = 1; exerciseIndex += 1 }
        if exerciseIndex >= plan.count { exerciseIndex = plan.count - 1; phase = "complete" } else { prepare() }
    }
    func label(actual: Int, form: String) {
        guard let id = lastRecordID, let index = store.data.records.firstIndex(where: { $0.id == id }) else { return }
        store.data.records[index].actualReps = actual; store.data.records[index].formLabel = form; store.save()
    }
    func collectBaseline() {
        guard !baselineCollected, let report, !report.baselineValues.isEmpty else { return }
        store.acceptBaseline(report); baselineCollected = true
    }
    func stop() { timer?.cancel(); timer = nil; speech.stop(); pipeline?.stopSpeech(); capture?.setVisible(false); capture?.setActive(false); cancellables.removeAll() }
}

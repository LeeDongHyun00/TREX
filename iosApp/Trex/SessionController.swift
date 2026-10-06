import SwiftUI
import Combine

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
    let speech = SpeechService()
    private var cancellables = Set<AnyCancellable>()
    private var timer: AnyCancellable?
    private var setID = UUID()
    private var startTime: Date?
    private var pausedAt: Date?
    private var pausedDuration = 0.0
    private var lastCue: String?
    private var lastCueAt = Int64.min
    private var restUntil: Date?
    private var lastRecordID: UUID?
    private(set) var recordIDs = Set<UUID>()
    @Published private(set) var baselineCollected = false
    var item: PlanItem { plan[exerciseIndex] }
    init(plan: [PlanItem], store: TrexStore) {
        self.plan = plan; self.store = store; muted = store.data.muted
    }
    func start() {
        guard !plan.isEmpty else { phase = "complete"; return }
        prepare()
        timer = Timer.publish(every: 0.2, on: .main, in: .common).autoconnect().sink { [weak self] _ in self?.tick() }
    }
    private func prepare() {
        phase = "preparing"; report = nil; seconds = 0; paused = false; startTime = nil; pausedDuration = 0; lastCue = nil; timerFallback = false
        setID = UUID(); cancellables.removeAll(); pipeline = nil; capture = nil; baselineCollected = false
        speech.muted = muted
        if item.posture {
            do {
                let pipe = try PosePipeline(item: item, mode: store.data.mode)
                let capture = CaptureController(onActivity: pipe.activity, onPoseFrame: pipe.submit, sampleIntervalMs: 85)
                self.pipeline = pipe; self.capture = capture
                let token = setID
                pipe.$status.receive(on: DispatchQueue.main).sink { [weak self] status in
                    guard let self, self.setID == token, !self.timerFallback, self.phase == "preparing" || self.phase == "active" else { return }
                    self.phase = status.stage == "active" ? "active" : "preparing"
                    if self.phase == "active", self.startTime == nil { self.startTime = Date() }
                    self.objectWillChange.send()
                    if let cue = status.cue, cue != self.lastCue || Resources.nowMs() - self.lastCueAt > 6000 {
                        self.lastCue = cue; self.lastCueAt = Resources.nowMs(); self.speech.say(cue)
                    }
                    if status.done && !self.item.hold { self.finishSet() }
                }.store(in: &cancellables)
                pipe.$output.receive(on: DispatchQueue.main).sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
                capture.$snapshot.receive(on: DispatchQueue.main).sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
                capture.setVisible(true); capture.setActive(true); capture.start()
                pipe.holdInstruction(!muted)
                speech.onFinished = { [weak pipe] in pipe?.holdInstruction(false) }
                speech.say(pipe.instruction)
                if !speech.speaking { pipe.holdInstruction(false) }
            } catch { self.error = "카메라 평가를 시작하지 못했습니다. \(error.localizedDescription)" }
        } else { seconds = 5; startTime = Date().addingTimeInterval(5) }
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
        phase = "active"; seconds = Int(elapsed)
        let duration = item.hold ? item.repetitions : item.repetitions * item.secondsPerRep
        if (item.hold || !item.posture || timerFallback) && seconds >= duration { finishSet() }
    }
    func togglePause() {
        guard phase == "active" || phase == "preparing" || phase == "rest" else { return }
        paused.toggle()
        if paused { pausedAt = Date(); speech.stop(); capture?.setActive(false) }
        else {
            if let pausedAt { let interval = Date().timeIntervalSince(pausedAt); pausedDuration += interval; if restUntil != nil { restUntil = restUntil!.addingTimeInterval(interval) } }
            pausedAt = nil; capture?.setActive(true)
        }
    }
    func sceneActive(_ active: Bool) { if !active && !paused { togglePause() } }
    func switchCamera() { speech.stop(); capture?.switchCamera() }
    func useTimer() {
        capture?.setVisible(false); speech.stop(); cancellables.removeAll(); setID = UUID()
        pipeline = nil; capture = nil; timerFallback = true; phase = "active"; startTime = Date()
        paused = false; pausedAt = nil; pausedDuration = 0; seconds = 0; error = nil
    }
    func finishSet() {
        guard phase == "active" || phase == "preparing" else { return }
        let token = setID; phase = "saving"; capture?.setVisible(false); speech.stop()
        if let pipeline {
            pipeline.finish { [weak self] result in
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
        let record = SetRecord(name: item.name, setNumber: setNumber, repetitions: reps, duration: seconds,
            report: report, weight: store.data.profile.weight, height: store.data.profile.height)
        lastRecordID = record.id; recordIDs.insert(record.id); store.record(record, frames: frames)
        phase = "result"; speech.say(report?.voice ?? "세트를 기록했어요.")
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
    func stop() { timer?.cancel(); timer = nil; speech.stop(); capture?.setVisible(false); capture?.setActive(false); cancellables.removeAll() }
}

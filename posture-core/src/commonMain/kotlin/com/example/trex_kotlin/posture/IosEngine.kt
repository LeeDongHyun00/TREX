package com.example.trex_kotlin.posture

import com.example.trex_kotlin.JointGate

/**
 * Swift 직렬 큐 전용 입구(spec §107·§107f). 시각은 단조 증가 ms, 좌표는 미러 전 MediaPipe 원본이다.
 * 모든 임계값·판정·렙·발화 정책은 Android 정본(`PostureLive` 분석 루프 + `PostureFloorLive` + `CapturePreparationUi` + `SpeechCoach` 대기열)을 **같은 순서로** 부른다.
 * 플랫폼 경계는 JSON 뿐이고, 이 파일은 Android 화면 코드의 **배선을 옮긴 것**이지 판정을 다시 쓴 것이 아니다 — 띠·문장·간격을 여기서 바꾸지 않는다.
 *
 * 지키는 원칙(CLAUDE.md): 유보는 정상으로 세지 않는다(#1), beta 는 말하지 않고 화면 '참고'(#2), COACH/TRACK 은 정책만 다르다(#3·#4), 못 보는 범위를 밝힌다(#5),
 * 음성은 화면보다 보수적이다(#6), 횟수와 자세는 다른 질문이되 COACH 는 ship 위반 회를 뺀다(#7). 85 ms 는 화면(뼈대 게이트)·순간 접촉만, 판정은 300 ms 칸의 첫 프레임이다(§96).
 */
class IosEngine @Throws(Exception::class) constructor(standingRules: String, floorRules: String) {
    private val rules = PostureRuleSet.loadText(standingRules).let { standing ->
        PostureRuleSet(standing.version, standing.generated, standing.rules + PostureRuleSet.loadText(floorRules).rules).plusRepForm()
    }
    /** 발화 대기열(§101) — Swift 가 [IosSpeech.drain] 결과를 재생하고 시작·종결을 돌려준다. */
    val speech = IosSpeech()
    private var profile = ExerciseProfiles.all.first()
    private var exercise = profile.referenceExercise!!
    private var mode = CoachMode.COACH
    private var front = false
    private var counter: RepCounter? = null
    private var form: RepFormEvaluator? = null
    private var unit = RepUnitAccumulator(RepUnit.CYCLE)
    private var side: SideStepCounter? = null
    private var coach = LiveCoach(rules, exercise, requireAnchor = true, speakBeta = false)
    private var comparison = PostureComparisonTracker(exercise, emptyList())
    private var preparation = CapturePreparationController()
    private var direction = CaptureDirectionWindow(profile.capture, false)
    private val floor = FloorFeatureExtractor()
    private val prepFloor = FloorFeatureExtractor()
    private val contact = ContactMinimum.elbowKnee()
    private val framing = StandingFraming()
    private var gate = JointGate(0.5f)
    private val rejectGate = RejectCueGate()
    private val holdGate = RejectCueGate(HoldVoice.STOP_GAP_MS)
    private val plankGaze = PlankGazeVoice()
    private val floorGaze = FloorGazeVoice()
    private var clock: PlankHoldClock? = null
    private val samples = ArrayList<PoseSample>()
    private val times = ArrayList<Long>()
    private val records = ArrayList<RepRecord>()
    private val aggregator = FeatureAggregator()
    private var baseline: Map<String, Float>? = null
    private var stage = "preparing"
    private var detectStart = 0L
    private var anchorAt = Long.MAX_VALUE
    private var startAt = 0L
    private var lastGrid = Long.MIN_VALUE
    private var lastFrame = Long.MIN_VALUE
    private var rejectedSeen = 0
    private var notShort = 0
    private var short = 0
    private var incorrect = 0
    private var detected = 0
    private var target = 10
    private var targetMs = 0L
    private var viewEst: ViewEstimator.Estimate? = null
    private val prepFrames = ArrayDeque<Pair<Long, Map<String, Float>>>()
    private var normal = NormalPoseReference(emptyList())
    private var comparisonSpeech = ComparisonSpeech()
    private var alignmentTracker: PlankAlignmentTracker? = null
    private var alignment = AlignmentSnapshot()
    private var hold: HoldTracker? = null
    private var floorFeedback = FloorFeedbackController(exercise, emptyList())
    private var floorState: FloorFeedback? = null
    private var coverage: CoverageReport = CoverageReport.OK
    private var coverageStreak = 0
    private var normalMatches: List<NormalPoseMatch> = emptyList()
    // 화면 상태(Android `PostureLive` 의 Compose 상태와 같은 이름)
    private var formNote: String? = null
    private var provisionalNote: String? = null
    private var guideNote: String? = null
    private var guideStamp = 0L
    private var floorMeasurement: String? = null
    private var rejectNote: String? = null
    private var gazeNote: String? = null
    private var formHighlight = emptySet<Int>()
    private var formProvHighlight = emptySet<Int>()
    private var formHighlightStamp = 0L
    private var violHighlight = emptySet<Int>()
    private var provisionalHighlight = emptySet<Int>()
    private var provisionalAt = Long.MIN_VALUE / 2
    private var motionCue: MotionCueOut? = null
    private var invalidCues = 0
    private var romShortSpoken = false
    private var sideCueAt = Long.MIN_VALUE / 2
    private var angleNoteAt = Long.MIN_VALUE / 2
    private var stopSpoken: String? = null
    private var announcedMs = 0L
    private var guideQuietUntil = 0L
    private var finishedHold: PlankHoldSnapshot? = null
    private var scoreOk: Pair<Int, Int>? = null
    private var sideTallies: SideTallies? = null
    private var breathDir = 0
    private var lastRepAt: Long? = null
    private var setEndAt: Long? = null
    private var quietPolls = 0
    private var everyFrameShown = BooleanArray(MP_LANDMARK_COUNT)
    private val tones = ArrayList<String>()
    private val prep = PrepVoice()

    /** 화면에 켜진 교정 단서 하나(Android `MotionCue`) — [anchors]·[targets] 는 관절 번호, [kind] 는 `MotionKind` 이름. */
    private data class MotionCueOut(val kind: MotionKind, val anchors: Set<Int>, val startedAt: Long, val origin: MotionOrigin,
                                    val targets: Set<Int> = emptySet(), val gravity: Boolean = false, val chain: Boolean = false,
                                    val soft: Boolean = false, val ttlMs: Long = MotionSpec.DEFAULT_TTL_MS) {
        companion object {
            fun of(check: RepFormCheck, direction: FormDirection, highlight: Set<Int>, now: Long, side: StepSide? = null, view: String? = null): MotionCueOut? {
                val m = check.motion ?: return null
                val spec = MotionSpec(m, MotionOrigin.REPFORM, direction, side = side, near = FormMotion.nearSide(view), lateralOk = MotionSpec.lateralOk(view))
                val kind = spec.kind ?: return null
                val anchors = spec.anchors.let { a -> if (m.pick == MotionPick.ALL) a.intersect(highlight).ifEmpty { a } else a }
                return MotionCueOut(kind, anchors, now, MotionOrigin.REPFORM, spec.targets)
            }
            fun of(spec: MotionSpec, now: Long, soft: Boolean = false): MotionCueOut? {
                val kind = spec.kind ?: return null
                return MotionCueOut(kind, spec.anchors, now, spec.origin, spec.targets, gravity = spec.motion.frame == MotionFrame.GRAVITY,
                    chain = spec.motion.pick == MotionPick.CHAIN || spec.motion.targetPick == MotionPick.CHAIN, soft = soft, ttlMs = spec.ttlMs)
            }
            fun ofWindow(event: CoachEvent, now: Long): MotionCueOut? {
                if (event.kind == OnsetKind.RECOVERED) return null
                val m = WindowMotions.forRule(event.rule.id, event.direction) ?: return null
                return MotionCueOut(m.kindFor(event.direction) ?: return null, m.anchor.landmarks, now, MotionOrigin.WINDOW)
            }
        }
    }

    /** 준비 안내 음성(Android `CapturePreparationUi` 의 결정 논리) — 문장 계획은 공통 `PreparationVoicePlan`, 카운트는 `CountdownVoice`. */
    private inner class PrepVoice {
        var lines: List<PrepLine> = emptyList()
        var index = 0
        var first = true
        var introGiven = false
        var introPlaying = false
        var reasonSpoken = false
        var frameReady = false
        var countMode = CountdownVoice.Mode.NONE
        var lastPhase = PreparationPhase.IDLE
        var lastSaidSecond = -1
        var lastSpokenGuide = ""
        var guideDueAt = Long.MAX_VALUE
        var guideCandidate = ""
        var holdDueAt = Long.MAX_VALUE
        var restarts = 0
        fun reset(now: Long) {
            lines = profile.preparationLines(); index = 0; first = true; introPlaying = false; reasonSpoken = false; frameReady = false
            countMode = CountdownVoice.Mode.NONE; lastPhase = PreparationPhase.IDLE; lastSaidSecond = -1; lastSpokenGuide = ""
            guideDueAt = Long.MAX_VALUE; guideCandidate = ""; holdDueAt = Long.MAX_VALUE; restarts = 0
            introGiven = speech.muted || !PreparationIntro.due(profile.name, now)
        }
        /** 판정·tick 마다. */
        fun tick(now: Long) {
            val st = preparation.state
            if (!introGiven) {
                if (speech.muted || !preparation.isAutomatic || reasonSpoken) { introGiven = true; introPlaying = false }
                else if (!speech.busy(now)) {
                    val counting = st.phase == PreparationPhase.COUNTDOWN
                    val earliest = now + (if (counting) st.seconds * 1000L else 400L + 680L + CapturePreparationController.COUNTDOWN_MS)
                    val idx = PreparationVoicePlan.nextLine(lines, index, now, settledRecommended = counting || frameReady, counting = counting,
                        started = st.phase == PreparationPhase.STARTED, earliestStartMs = earliest)
                    if (idx == null) { introGiven = true; introPlaying = false }
                    else {
                        val line = lines[idx]; index = idx + 1; introPlaying = true
                        speech.speak(line.text, now, flush = first); first = false
                        if (line.kind == PrepKind.ESS) PreparationIntro.mark(profile.name, now)
                    }
                }
            } else introPlaying = introPlaying && speech.busy(now)
            if (st.phase != lastPhase) {
                lastPhase = st.phase
                if (st.phase == PreparationPhase.COUNTDOWN) { countMode = CountdownVoice.mode(introPlaying || speech.tailActive(now), speech.ready, speech.muted); lastSaidSecond = -1 }
            }
            if (!speech.muted && st.phase == PreparationPhase.COUNTDOWN && st.seconds in 1..5 && st.seconds != lastSaidSecond) {
                lastSaidSecond = st.seconds
                when (countMode) {
                    CountdownVoice.Mode.SPEAK -> speech.speak(st.seconds.toString(), now, flush = true)
                    CountdownVoice.Mode.TONE -> tones += "beep"
                    CountdownVoice.Mode.NONE -> {}
                }
            }
            // 안내 문장 — 흔들리는 관측을 연달아 말하지 않게 3.5 s 뒤 같은 문장이면(Android 와 같다)
            if (st.phase == PreparationPhase.WAITING && !speech.muted && introGiven && st.message != lastSpokenGuide) {
                if (st.message != guideCandidate) { guideCandidate = st.message; guideDueAt = now + 3_500L }
                else if (now >= guideDueAt) { speech.speak(st.message, now, flush = true); lastSpokenGuide = st.message; guideDueAt = Long.MAX_VALUE }
            } else { guideCandidate = ""; guideDueAt = Long.MAX_VALUE }
            if (st.trackingHold && !speech.muted) {
                if (holdDueAt == Long.MAX_VALUE) holdDueAt = now + 600L
                else if (now >= holdDueAt && !(introPlaying && speech.busy(now)) && st.phase == PreparationPhase.COUNTDOWN) {
                    reasonSpoken = true; speech.speak(st.message, now, flush = true); lastSpokenGuide = st.message; holdDueAt = Long.MAX_VALUE
                }
            } else holdDueAt = Long.MAX_VALUE
            if (st.restarts > restarts) {
                restarts = st.restarts
                if (!speech.muted && st.phase == PreparationPhase.WAITING && !(introPlaying && speech.busy(now))) { reasonSpoken = true; speech.speak(st.message, now, flush = true); lastSpokenGuide = st.message }
            }
        }
    }

    fun setNormalReference(text: String) { normal = NormalPoseReference.parse(text.lineSequence()) }

    /**
     * 세트 구성 — `name`(앱 이름)·`mode`(coach/track)·`target`(횟수 또는 초)·`front`(전면 카메라)·`muted`·`voiceReady`·`baseline`(개발용, 기본 없음).
     * 돌려주는 값: 준비 안내 문장·세는 조건·평가 범위.
     */
    @Throws(Exception::class) fun configure(config: String): String {
        val c = JSONObject(config)
        profile = ExerciseProfiles.forName(c.getString("name")) ?: error("미지원 운동")
        require(profile.cameraEnabled)
        exercise = profile.referenceExercise ?: error("참조 종목 없음")
        mode = if (c.optString("mode") == "track") CoachMode.TRACK else CoachMode.COACH
        front = c.optBoolean("front", false)
        target = c.optInt("target", 10).coerceIn(1, 999)
        targetMs = if (c.optBoolean("timed", false)) target * 1000L else 0L
        speech.muted = c.optBoolean("muted", false); speech.ready = c.optBoolean("voiceReady", true)
        baseline = c.optJSONObject("baseline")?.values()?.mapNotNull { (k, v) -> (v as? Number)?.toFloat()?.takeIf(Float::isFinite)?.let { k to it } }?.toMap()
        val repConfig = rules.rulesFor(exercise).firstOrNull { it.kind == "rep" }?.repConfig
        counter = RepCounter.forSession(exercise, repConfig?.direction, repConfig?.threshold, floor = profile.floor)
        form = counter?.let { RepFormSpecs.evaluatorFor(exercise, it) }
        unit = RepUnitAccumulator(RepUnit.forSession(profile, profile.floor))
        side = if (profile.name in SIDE_EACH_EXERCISES && form != null) SideStepCounter(target, strictUnknown = counter?.legTracker != null) else null
        coach = LiveCoach(rules, exercise, baseline = baseline, requireAnchor = true, speakBeta = false)
        comparison = PostureComparisonTracker(exercise, ComparisonMetrics.forExercise(exercise, rules.rulesFor(exercise)), observationKind = profile.kind)
        comparisonSpeech = ComparisonSpeech()
        alignmentTracker = if (exercise == "플랭크") PlankAlignmentTracker(rules.rulesFor(exercise)) else null
        alignment = AlignmentSnapshot()
        hold = rules.rulesFor(exercise).firstOrNull { it.holdConfig != null }?.holdConfig?.let(::HoldTracker)
        floorFeedback = FloorFeedbackController(exercise, rules.rulesFor(exercise)); floorState = null
        preparation = CapturePreparationController(profile.floor)
        direction = CaptureDirectionWindow(profile.capture, profile.floor)
        gate = JointGate(if (profile.floor) 0.35f else 0.5f)
        clock = if (profile.floor && FloorChain.Kind.of(exercise) == FloorChain.Kind.PLANK) PlankHoldClock() else null
        floor.reset(); prepFloor.reset(); contact.clear(); framing.reset(); rejectGate.reset(); holdGate.reset(); plankGaze.reset(); floorGaze.reset()
        samples.clear(); times.clear(); records.clear(); prepFrames.clear(); aggregator.reset()
        detectStart = 0L; anchorAt = Long.MAX_VALUE; startAt = 0; lastGrid = Long.MIN_VALUE; lastFrame = Long.MIN_VALUE
        rejectedSeen = 0; notShort = 0; short = 0; incorrect = 0; detected = 0; viewEst = null
        formNote = null; provisionalNote = null; guideNote = null; guideStamp = 0L; floorMeasurement = null; rejectNote = null; gazeNote = null
        formHighlight = emptySet(); formProvHighlight = emptySet(); formHighlightStamp = 0L; violHighlight = emptySet(); provisionalHighlight = emptySet()
        provisionalAt = Long.MIN_VALUE / 2; motionCue = null; invalidCues = 0; romShortSpoken = false; sideCueAt = Long.MIN_VALUE / 2; angleNoteAt = Long.MIN_VALUE / 2
        stopSpoken = null; announcedMs = 0L; guideQuietUntil = 0L; finishedHold = null; scoreOk = null; sideTallies = null; breathDir = 0; lastRepAt = null
        setEndAt = null; quietPolls = 0; coverage = CoverageReport.OK; coverageStreak = 0; normalMatches = emptyList(); tones.clear()
        stage = "preparing"
        // 준비 화면 진입 — 직전 세트의 꼬리는 지키고 묵은 대기는 버린다(Android `stopKeepingTail`)
        speech.stopKeepingCurrent()
        return Json.encode(mapOf("instruction" to profile.preparationInstruction, "conditions" to profile.countingConditions, "scope" to scope(),
            "floor" to profile.floor, "hold" to (clock != null), "startHint" to profile.startHint))
    }

    /** 준비 게이트를 켠다(세트 시작·재개). 안내 문장 계획도 여기서 시작한다. */
    fun arm(nowMs: Long) { preparation.arm(nowMs); direction.clear(); prep.reset(nowMs) }
    fun manualStart(nowMs: Long) { preparation.manual(nowMs, 5) }
    fun setMuted(muted: Boolean) { speech.muted = muted; if (muted) speech.stop() }
    fun setVoiceReady(ready: Boolean) { speech.ready = ready }
    fun setFrontCamera(front: Boolean) { this.front = front; comparison.unavailable(); direction.clear() }

    fun pause(paused: Boolean, nowMs: Long) {
        if (paused) {
            if (stage == "active") { clock?.pause(nowMs); stage = "paused" } else if (stage == "preparing") preparation.cancel()
            counter?.resetCycle(); form?.onRejected(lastFrame.coerceAtLeast(0)); comparison.unavailable(); contact.clear()
            speech.stop()
        } else if (stage == "paused") {
            stage = "active"; clock?.resume(nowMs); guideQuietUntil = nowMs + FloorCycleTracker.SIGNAL_LOST_MS
        } else if (stage == "preparing") { preparation.arm(nowMs); direction.clear() }
    }

    /**
     * 추론이 오지 않는 시간도 준비 게이트·발화 감시에 전달한다(Swift 가 200 ms 마다). 복귀 시 오래된 프레임은 세지 않는다.
     */
    fun tick(nowMs: Long): String {
        speech.watchdog(nowMs)
        if (stage == "preparing") {
            val st = preparation.tick(nowMs)
            prep.tick(nowMs)
            if (st.phase == PreparationPhase.STARTED) startWork(nowMs)
        }
        return status(nowMs)
    }

    private fun startWork(now: Long) {
        stage = "active"; if (startAt == 0L) startAt = now
        // 준비 → 운동의 정상 시작은 끊지 않는다(Android `nextSession(PREPARE)` → handOff) — 계획 규칙상 넘치는 말은 ≤ 0.3 s
        speech.handOff(now)
        clock?.start(now)
        speech.speak("시작하세요.", now, flush = true)
    }

    @Throws(Exception::class) fun frame(input: String): String {
        val j = JSONObject(input); val now = j.getLong("timeMs")
        if (now <= lastFrame) return status(now)
        lastFrame = now
        val sample = makeSample(j)
        // 85 ms 마다: 뼈대 관절 게이트(§101c, 표시 전용)·순간 접촉(§98a)
        gate.update(sample.normalizedXy, sample.visibility, sample.imageWidth.toFloat() / sample.imageHeight.coerceAtLeast(1))
        if (stage == "active") contact.offer(now, sample.features)
        // §96 판정 격자 — 아래(준비 프레임·피처·규칙·카운터·로그)는 300 ms 칸마다 첫 프레임만 받는다
        val judgmentBin = now / SESSION_SAMPLE_INTERVAL_MS
        if (lastGrid == judgmentBin) return status(now)
        lastGrid = judgmentBin
        if (stage == "preparing") {
            contact.clear()
            prepFeatures(sample, now)?.let { pf -> prepFrames.addLast(now to pf); while (prepFrames.size > 12) prepFrames.removeFirst() }
            val framed = CaptureFraming.inspect(sample, profile.capture, profile.floor, profile.framingRegion, profile.lateralReach)
            prep.frameReady = framed.ready
            val checked = if (preparation.state.phase == PreparationPhase.COUNTDOWN) framed else direction.check(framed, sample.features, now)
            preparation.observe(now, checked)
            return tick(now)
        }
        if (stage != "active") return status(now)
        // 바닥 계열(§99) — 판정 칸마다(사람이 없어도): 신호 결측·누운 기준 없음 안내, 플랭크 시계의 '사람 없음' 칸
        if (profile.floor) {
            repGuide(now)
            if (!sample.detected) holdFrame(now, null)
        }
        if (!sample.detected) {
            contact.clear()
            alignment = alignmentTracker?.add(now, emptyMap()) ?: AlignmentSnapshot()
            comparison.unavailable()
            normalMatches = emptyList()
            hold?.add(now - startAt, null)
            if (profile.floor) {
                floorMeasurement = "측정 일시 중지 · 몸 전체가 보이게 해주세요"
                floorState = floorFeedback.update(now, emptyMap(), null, emptyList(), paused = false, voiceEnabled = speech.ready && !speech.muted && legacyVoice)
                if (mode != CoachMode.TRACK) floorState?.speech?.let { speech.speakLatest(it, now) }
            }
            return status(now)
        }
        val features = if (profile.floor) withFace(floor.computeForExercise(exercise, sample.normalizedXy, sample.visibility, sample.imageWidth, sample.imageHeight,
            FloorChain.gravityUp(sample.up, sample.upFromGravity, sample.upFlipped), now), sample.features)
            else contact.drain().let { c -> if (c.isEmpty()) sample.features else sample.features + c }
        var newFloorRep: RepRecord? = null
        var comparisonPeakAt: Long? = null
        val floorObservable = features.isNotEmpty()
        if (profile.floor) holdFrame(now, features)
        alignmentTracker?.let { alignment = it.add(now, features, alignmentGate(features)) }
        if (alignmentTracker != null) gazeFrame(now, features)
        if (profile.floor && alignmentTracker == null) {
            val rep = FloorCoverage.analyze(sample.normalizedXy, sample.visibility, rules.rulesFor(exercise), front)
            if (rep.ok) { coverageStreak = 0; if (!coverage.ok) coverage = CoverageReport.OK }
            else { coverageStreak++; if (coverageStreak >= COVERAGE_STREAK) coverage = rep }
        } else if (!profile.floor) coverage = framing.update(now, sample.normalizedXy, features).let { r ->
            if (r.ok) CoverageReport.OK else CoverageReport(false, emptyList(), emptyMap(), r.message, r.fix)
        }
        if (profile.floor) {
            if (coach.isAnchored) hold?.add(now - startAt, features["hip_dev_ankle"])
            floorMeasurement = if (alignmentTracker != null) alignment.items.joinToString(" · ") { it.detail } else hold?.snapshot()?.text
                ?: FloorTemporal.observation(exercise, features).ifEmpty { "동작을 측정하고 있어요" }
        }
        aggregator.add(features)
        samples += if (features === sample.features) sample else sample.withFeatures(features); times += now
        if (features.isNotEmpty()) detected++
        if (!profile.floor) ViewEstimator.estimate(aggregator)?.let { viewEst = it }
        val rc = counter
        var completed = false
        if (rc != null) {
            val rf = form
            val judged = rc.legTracker?.annotate(features) ?: rc.floorTracker?.annotate(features) ?: features
            rf?.onFrame(now, judged)
            if (prepFrames.isNotEmpty()) {
                val seedFrames = prepFrames.toList(); prepFrames.clear(); prepFloor.reset()
                rc.standingSeedFrom(seedFrames, now)?.let { rc.seedStanding(now, it) }
            }
            completed = rc.onFrameFeatures(now, judged)
            breathDir = rc.motionDirection
            if (rc.newlyRetracted) {
                rejectedSeen = RepUnitAccumulator.onCounterRetracted(rc, records, unit, rejectedSeen); rf?.reset()
                notShort = 0; short = 0; incorrect = 0
            }
            RejectionCues.handle(rc, rf, rejectedSeen, now, rejectGate, silent = speech.muted).let { r ->
                rejectedSeen = r.seen
                if (r.cue?.tick == true) tones += "nack"
                r.cue?.speech?.let { speech.speakLatest(it, now, heardKey = r.last?.feature?.let { f -> "rj:$f" }) }
                if (r.motion != null && mode == CoachMode.COACH) motionCue = MotionCueOut.of(MotionSpec(r.motion, MotionOrigin.REASON, side = r.last?.side,
                    near = FormMotion.nearSide(rf?.lockedView), lateralOk = MotionSpec.lateralOk(rf?.lockedView)), now, soft = profile.floor)
                if (r.last != null && rc.floorTracker != null) rejectNote = "세지 않은 동작 ${rc.rejectedReps.size} · " + FloorReasons.repLabel(rc.floorTracker!!.profile, r.last.feature)
            }
            if (rc.paired) rc.takeOneArmNotice()?.let { guideNote = PairedArmCues.oneArmNote(it); guideStamp = now }
            if (completed) {
                comparisonPeakAt = rc.repTimesMs.lastOrNull()
                if (coach.anchor()) { anchorAt = now; aggregator.reset() }
                val tally = RepUnitAccumulator.onCounterFrame(rc, now, records, unit)
                if (profile.floor) newFloorRep = tally.records.lastOrNull()
                val formReps = if (rf == null) emptyList() else tally.records.map { r -> rf.onCycle(r.tMs, r.cycleMin, r.cycleMax, r.cycleStartMs, r.side) }
                notShort += tally.repsNotShort; short += tally.repsShort
                val gate = mode == CoachMode.COACH && !profile.floor
                if (gate && side == null) {
                    val excluded = formReps.indices.count { i -> !formReps[i].correct && !(rc.signal.romExcludesShort && tally.records.getOrNull(i)?.valid == false) }
                    incorrect += excluded
                    if (excluded > 0 && !speech.muted) tones += "nack"
                }
                val sc = side
                val coachNow = mode == CoachMode.COACH
                val sideEvs = if (sc == null) emptyList() else formReps.filter { !it.notStep }.map { r -> sc.offer(r.tMs, r.side, blocked = !r.correct) }
                if (sc != null) {
                    sideTallies = sc.tallies()
                    for (se in sideEvs) {
                        val e = se.of(coachNow)
                        if (!e.counted || speech.muted) {
                            if (!speech.muted && !e.known && !e.counted && !e.blocked && !e.extraOnDone) tones += "ack"
                            continue
                        }
                        if (!e.known || !speech.ready) { tones += "beep"; continue }
                        val n = sc.pool(coachNow).count(e.side)
                        speech.speak(when (e.remaining) { null -> "${e.side.label} $n"; 0 -> "${e.side.label} 끝"; else -> "${e.side.label} ${e.remaining}개 남음" }, now, flush = false, key = e.side.label)
                    }
                } else if (tally.completedReps > 0 && !speech.muted && !profile.floor) {
                    // 숫자 발화 — 음성이 되면 숫자로(최신 숫자만), 안 되면 짧은 톤(Android `speakRep`)
                    if (speech.ready) speech.speak(count().toString(), now, flush = false, key = "n") else tones += "beep"
                }
                if (tally.completedReps > 0) lastRepAt = now
                if (gate) formReps.lastOrNull()?.let { rep ->
                    val (red, prov) = RuleHighlight.forRepForm(rep)
                    formHighlight = red; formProvHighlight = prov; formHighlightStamp = if (red.isEmpty() && prov.isEmpty()) 0L else now
                }
                val lastRep = formReps.lastOrNull()
                val sideEv = sideEvs.map { it.of(coachNow) }.let { es -> es.lastOrNull { it.switchTo != null } ?: es.lastOrNull() }
                val extraStep = sideEv != null && !sideEv.counted && !sideEv.blocked
                val formEv = if (lastRep == null || rf == null) null else rf.eventFor(lastRep, now, gate = gate, speak = gate && !extraStep)
                if (lastRep?.correct == true) { formNote = null; if (motionCue?.origin == MotionOrigin.REPFORM) motionCue = null }
                val shortReason = if (gate && rc.signal.romExcludesShort && tally.repsShort > 0) (rc.newlyPublishedShort.lastOrNull { it != null } ?: RomShort.RANGE) else null
                val say = ArrayList<String>(); val screenOnly = ArrayList<String>()
                var heardKey: String? = null; var setScoped = false
                if (formEv != null && formEv.ship && gate && !extraStep) {
                    motionCue = MotionCueOut.of(formEv.check, formEv.direction, formHighlight, now, lastRep?.side, lastRep?.view)
                    heardKey = "rf:${formEv.check.id}"
                    say += (if (formEv.brief) "${formEv.message}." else "${formEv.check.fix}.") + (if (formEv.firstExclusion) " 이 회는 세지 않았어요." else "")
                } else if (shortReason != null) {
                    say += if (invalidCues < MAX_INVALID_CUES) "${rc.signal.shortCue(shortReason)}. 이 회는 세지 않았어요."
                        else when (shortReason) { RomShort.TOP -> "덜 올려서 세지 않았어요."; RomShort.BOTTOM -> "덜 펴서 세지 않았어요."; RomShort.RANGE -> "범위가 부족해 세지 않았어요." }
                    invalidCues++; romShortSpoken = true; heardKey = "rom"
                    motionCue = MotionCueOut.of(MotionSpec(when (shortReason) {
                        RomShort.TOP -> FormMotion(MotionAnchor.WRISTS, high = MotionKind.UP, pick = MotionPick.NEAR)
                        RomShort.BOTTOM -> FormMotion(MotionAnchor.WRISTS, high = MotionKind.DOWN, pick = MotionPick.NEAR)
                        RomShort.RANGE -> FormMotion.mark(MotionAnchor.WRISTS, pick = MotionPick.NEAR)
                    }, MotionOrigin.ROM, near = FormMotion.nearSide(lastRep?.view)), now)
                } else if (formEv != null && !formEv.ship && !extraStep) {
                    provisionalNote = formEv.message
                } else if (gate && !extraStep && rf != null && lastRep != null) {
                    rf.recoveryEvent(lastRep, now) { speech.recoverable("rf:$it") }?.also { say += it.message; formHighlight = emptySet(); formProvHighlight = emptySet(); motionCue = null }
                    if (say.isEmpty() && romShortSpoken && tally.records.lastOrNull()?.valid == true) {
                        romShortSpoken = false
                        if (speech.recoverable("rom")) { say += "좋아요, 가동 범위가 교정됐어요."; if (motionCue?.origin == MotionOrigin.ROM) motionCue = null }
                    }
                }
                if (rc.cueSource != null) rejectGate.takeRecovery { speech.recoverable("rj:$it") }?.let { say += it; if (motionCue?.origin == MotionOrigin.REASON) motionCue = null }
                if (profile.floor) gazeRep(now, rc)
                if (sideEv != null) {
                    val msg = when {
                        sideEv.switchTo != null -> "이제 ${sideEv.switchTo.label} 동작을 해 주세요."
                        sideEv.extraOnDone && now - sideCueAt > SIDE_CUE_GAP_MS -> "${sideEv.side.label}은 다 했어요. ${sideEv.side.other.label} 동작을 해 주세요."
                        else -> null
                    }
                    if (msg != null) {
                        sideCueAt = now; setScoped = true
                        if (sideEv.known) say += msg else screenOnly += msg
                        guideNote = msg; guideStamp = now
                    }
                }
                if (rf?.turnReminderSteps != null) rf.takeTurnReminder()?.let { kind ->
                    val msg = when (kind) {
                        TurnReminder.SIDE -> "옆으로 많이 돌아섰어요. 휴대폰 쪽으로 조금 돌아 45도쯤 비스듬히 서 주세요."
                        TurnReminder.FRONT -> "정면으로 서면 깊이와 좌우를 볼 수 없어요. 휴대폰에서 45도쯤 비스듬히 서 주세요."
                    }
                    say += msg; guideNote = msg; guideStamp = now
                }
                if (gate && rf?.turnReminderSteps == null && lastRep?.turnedTooFar == true && now - angleNoteAt > ANGLE_NOTE_GAP_MS) {
                    angleNoteAt = now; say += "옆으로 너무 돌아서 이 회는 자세를 못 봤어요. 조금 덜 돌아 주세요."
                }
                if (gate && rf != null) rf.takeNotice(now)?.let { ev ->
                    say += ev.message
                    if (ev.ship && motionCue?.startedAt != now) MotionCueOut.of(ev.check, ev.direction, RuleHighlight.landmarksFor(ev.check.feature), now, lastRep?.side, lastRep?.view)?.let { motionCue = it.copy(origin = MotionOrigin.NOTICE) }
                }
                if (say.isNotEmpty() || screenOnly.isNotEmpty()) formNote = (say + screenOnly).joinToString(" ")
                if (say.isNotEmpty()) speech.speakLatest(say.joinToString(" "), now, heardKey = heardKey, setScoped = setScoped && heardKey == null && say.size == 1)
            }
            if (!completed && rf != null && !profile.floor) {
                rf.missedDipEvent(now, rc.midCycle, speak = mode == CoachMode.COACH)?.let { (ev, s) ->
                    if (mode == CoachMode.COACH) {
                        val msg = if (ev.brief) "${ev.message}." else "${ev.check.fix}."
                        if (!speech.muted) tones += "nack"
                        formNote = msg; speech.speakLatest(msg, now)
                        formHighlight = RuleHighlight.landmarksFor(ev.check.highlight?.let { h -> s?.let { h.replace("{front}", it.key) } ?: h.replace("_{front}", "") } ?: ev.check.feature)
                        formProvHighlight = emptySet(); formHighlightStamp = now
                        motionCue = MotionCueOut.of(ev.check, ev.direction, formHighlight, now, s, rf.lockedView)
                    }
                }
            }
            if (!completed && rf != null && mode == CoachMode.COACH && !profile.floor) {
                rf.liveEvent(now)?.let { ev ->
                    formNote = ev.message; speech.speakLatest(ev.message, now)
                    formHighlight = RuleHighlight.landmarksFor(ev.check.feature); formProvHighlight = emptySet(); formHighlightStamp = now
                    motionCue = MotionCueOut.of(ev.check, ev.direction, formHighlight, now)
                }
            }
        }
        // 앵커 폴백: 렙 신호가 없는 종목이거나 첫 렙이 너무 늦으면 시간으로 앵커한다 — 기준 시점은 '검출' 이 아니라 '측정 가능'(§31a)
        if (!coach.isAnchored) {
            if (detectStart == 0L && features.isNotEmpty()) detectStart = now
            if (detectStart != 0L) {
                val wait = if (rc == null) ANCHOR_FALLBACK_NO_COUNTER_MS else ANCHOR_FALLBACK_WITH_COUNTER_MS
                if (now - detectStart >= wait && coach.anchor()) { anchorAt = now; aggregator.reset() }
            }
        }
        coach.onFrame(features)
        val ev = coach.evaluate(now)
        val track = mode == CoachMode.TRACK
        val shipStates = coach.lastStates.filter { it.rule.status != RuleStatus.BETA }
        val betaStates = coach.lastStates.filter { it.rule.status == RuleStatus.BETA }
        violHighlight = if (track || profile.floor) emptySet() else RuleHighlight.forViolations(shipStates)
        provisionalHighlight = if (track || profile.floor) emptySet() else RuleHighlight.forViolations(betaStates)
        if (!track && !profile.floor && now - provisionalAt > PROVISIONAL_NOTE_GAP_MS) {
            provisionalAt = now
            provisionalNote = betaStates.firstOrNull { it.recent == Verdict.VIOLATION }?.let { st ->
                val cue = CoachCues.cueFor(st.rule, st.direction ?: Direction.PRIMARY)
                PostureSetReport.splitCue(when (st.kind) { OnsetKind.DRIFT -> cue.drift; OnsetKind.HABIT -> cue.habit; else -> cue.current }).first
            }
        }
        if (profile.floor) {
            // 바닥 피드백은 아래의 시간·반복 상태기가 한 번만 발화한다
        } else if (!coverage.ok) {
            framing.takeSpeech(now)?.let { speech.speakLatest(it, now); guideNote = it; guideStamp = now }
        } else if (ev != null && !track) {
            speech.speak(ev.message, now, flush = true)
            if (ev.kind == OnsetKind.RECOVERED) motionCue = null else MotionCueOut.ofWindow(ev, now)?.let { motionCue = it }
        }
        shipStates.let { states -> val ok = states.count { it.recent == Verdict.OK }; val bad = states.count { it.recent == Verdict.VIOLATION }; if (ok + bad > 0) scoreOk = ok to (ok + bad) }
        if (floorObservable) comparison.add(now, features, comparisonPeakAt, coach.isAnchored || alignmentTracker != null || profile.comparisonOnly, referenceEligible = true)
        else comparison.unavailable()
        normalMatches = if (mode == CoachMode.COACH) normal.compare(exercise, viewEst?.takeIf { it.cls != ViewEstimator.ViewClass.UNKNOWN }?.letter, comparison.latestSignature, comparison.metrics) else emptyList()
        if (track || profile.comparisonOnly) comparisonSpeech.next(now, comparison.snapshot, speech.ready && !speech.muted)?.let {
            if (side != null || profile.floor) speech.speakLatest(it, now) else speech.speak(it, now, flush = true)
        }
        if (profile.floor) {
            floorState = floorFeedback.update(now, features, hold?.snapshot(), coach.lastStates, newFloorRep, coach.isAnchored, floorObservable,
                mode = mode, voiceEnabled = mode != CoachMode.TRACK && speech.ready && !speech.muted && legacyVoice, alignment = alignment.takeIf { alignmentTracker != null })
            if (mode != CoachMode.TRACK) floorState?.speech?.let { speech.speakLatest(it, now) }
            provisionalNote = floorState?.takeIf { it.phase == FloorFeedbackPhase.ATTENTION }?.message
        }
        return status(now)
    }

    // ---- 바닥 계열(Android `FloorLiveState`) ----
    private val legacyVoice: Boolean get() = FloorChain.Kind.of(exercise) == null

    private fun prepFeatures(s: PoseSample, now: Long): Map<String, Float>? {
        if (!profile.floor) return s.features.takeIf { it.isNotEmpty() }
        if (!s.detected) return null
        val f = if (FloorChain.Kind.of(exercise) != null) prepFloor.computeForExercise(exercise, s.normalizedXy, s.visibility, s.imageWidth, s.imageHeight,
            FloorChain.gravityUp(s.up, s.upFromGravity, s.upFlipped), now)
            else prepFloor.computeForExercise(exercise, s.normalizedXy, s.visibility, s.imageWidth, s.imageHeight)
        return f.takeIf { it.isNotEmpty() }
    }

    private fun withFace(floorMap: Map<String, Float>, sample: Map<String, Float>): Map<String, Float> {
        val found = sample[FloorGaze.FOUND] ?: return floorMap
        val out = HashMap<String, Float>(floorMap.size + 3); out.putAll(floorMap); out[FloorGaze.FOUND] = found
        sample[FloorGaze.YAW]?.let { out[FloorGaze.YAW] = it }; sample[FloorGaze.INFER_MS]?.let { out[FloorGaze.INFER_MS] = it }
        sample[FloorGaze.ROTATION]?.let { out[FloorGaze.ROTATION] = it }; sample[FloorGaze.SIZE]?.let { out[FloorGaze.SIZE] = it }
        return out
    }

    private fun alignmentGate(features: Map<String, Float>): Boolean =
        clock?.let { it.phase == PlankHoldClock.Phase.HOLD && PlankHoldClock.stopReason(features) == null } ?: true

    private fun holdFrame(now: Long, features: Map<String, Float>?) {
        val c = clock ?: return
        val events = c.onFrame(now, features)
        for (e in events) {
            if (speech.muted || !HoldVoice.voiced(e.kind, c.source)) continue
            when (e.kind) {
                PlankHoldEvent.Kind.START -> { tones += "ack"; speech.speakLatest(PlankHoldClock.START_CUE, now) }
                PlankHoldEvent.Kind.RESUME -> {
                    tones += "ack"
                    HoldVoice.resumeCue(stopSpoken) { speech.recoverable("hold:$it") }.also { stopSpoken = null }?.let { speech.speakLatest(it, now, setScoped = it == PlankHoldClock.RESUME_CONTINUE) }
                    motionCue = null
                }
                PlankHoldEvent.Kind.STOP -> {
                    val cue = HoldVoice.stopCue(holdGate, now, e.reason, c).also { stopSpoken = if (it.speech != null) e.reason else null }
                    if (cue.tick) tones += "nack"
                    cue.speech?.let { speech.speakLatest(it, now, heardKey = "hold:${e.reason}") }
                    if (cue.speech != null) PlankHoldClock.motionFor(e.reason)?.let { motionCue = MotionCueOut.of(MotionSpec(it, MotionOrigin.HOLD, ttlMs = MotionSpec.HOLD_TTL_MS), now, soft = true) }
                }
                PlankHoldEvent.Kind.FALLBACK -> { stopSpoken = null; PlankHoldClock.cueFor(e)?.let { speech.speakLatest(it, now) } }
                PlankHoldEvent.Kind.REMIND, PlankHoldEvent.Kind.HINT -> PlankHoldClock.cueFor(e)?.let { speech.speakLatest(it, now) }
            }
        }
        val camera = c.source == PlankHoldClock.SOURCE_CAMERA
        val shown = if (camera) c.heldMs else c.wallMs
        val say = HoldAnnounce.between(announcedMs, shown, targetMs).also { announcedMs = maxOf(announcedMs, shown) }
        if (say != null && !speech.muted) { if (speech.ready) speech.speak(say.text, now, flush = false, key = "n") else tones += if (say.countdown) "beep2" else "ack" }
    }

    private fun repGuide(now: Long) {
        val ft = counter?.floorTracker ?: return
        if (now < guideQuietUntil) return
        val cue = ft.guideCue(now) ?: return
        if (!speech.muted) speech.speakLatest(cue, now)
        guideNote = cue; guideStamp = now
    }

    private fun gazeFrame(now: Long, features: Map<String, Float>?) {
        if (clock == null) return
        val gaze = features?.let { FloorGaze.classify(it) } ?: FloorGaze.UNKNOWN
        if (mode != CoachMode.COACH || speech.muted) { gazeNote = null; return }
        val cue = plankGaze.frame(now, alignment, gaze) { speech.recoverable("gaze:plank") }
        gazeNote = gazeChip(gaze, plankGaze.lastWrong != FloorGaze.UNKNOWN, alignment.held && plankGaze.wrongForMs(now) > 0L)
        if (cue == null) return
        if (PlankGazeVoice.isRecovery(cue)) { speech.speakLatest(cue, now); motionCue = null }
        else { speech.speakLatest(cue, now, heardKey = "gaze:plank"); motionCue = MotionCueOut.of(MotionSpec(PlankGazeVoice.motionFor(plankGaze.lastSide), MotionOrigin.GAZE), now, soft = true) }
        guideNote = cue; guideStamp = now
    }

    private fun gazeRep(now: Long, rc: RepCounter) {
        val ft = rc.floorTracker ?: return
        if (mode != CoachMode.COACH || speech.muted) return
        val rep = ft.repDetail.lastOrNull() ?: return
        val cue = floorGaze.rep(now, rep, ft.profile) { speech.recoverable("gaze:floor") }
        gazeNote = gazeChip(rep.gaze, floorGaze.lastWrong != FloorGaze.UNKNOWN, floorGaze.wrongStreak > 0)
        if (cue == null) return
        if (FloorGazeVoice.isRecovery(cue)) { speech.speakLatest(cue, now); motionCue = null }
        else { speech.speakLatest(cue, now, heardKey = "gaze:floor"); motionCue = MotionCueOut.of(MotionSpec(FloorGazeVoice.motionFor(ft.profile), MotionOrigin.GAZE), now, soft = true) }
        guideNote = cue; guideStamp = now
    }

    private fun gazeChip(state: Int, spoken: Boolean, wrongOngoing: Boolean): String =
        "시선 · " + FloorGaze.label(state) + when { spoken -> " · 교정 중"; wrongOngoing && FloorGaze.wrong(state) -> " · 돌아옴 대기"; else -> "" }

    private fun holdHud(): Map<String, Any?>? {
        val c = clock ?: return null
        val camera = c.source == PlankHoldClock.SOURCE_CAMERA
        val status = if (!camera) "카메라 미확인 · 시계로 재요" else listOfNotNull(FloorReasons.holdStatus(c.phase, c.reason), gazeNote).joinToString(" · ").ifEmpty { null }
        return mapOf("heldSec" to (c.heldMs / 1000L).toInt(), "wallSec" to (c.wallMs / 1000L).toInt(), "stopped" to (c.phase != PlankHoldClock.Phase.HOLD),
            "status" to status, "camera" to camera, "heldMs" to c.heldMs)
    }

    // ---- 입력 ----
    private fun makeSample(j: JSONObject): PoseSample {
        fun array(name: String, size: Int, fallback: Float) = (j.values()[name] as? List<*>)?.let { raw ->
            FloatArray(size) { (raw.getOrNull(it) as? Number)?.toFloat()?.takeIf(Float::isFinite) ?: fallback }
        } ?: FloatArray(size) { fallback }
        val xy = array("xy", 66, Float.NaN); val vis = array("visibility", 33, 0f); val world = array("world", 99, Float.NaN)
        val width = j.optInt("width", 1).coerceAtLeast(1); val height = j.optInt("height", 1).coerceAtLeast(1)
        val rawUp = array("up", 3, 0f)
        val up = Vec3(rawUp[0], rawUp[1], rawUp[2]).unit() ?: SCREEN_UP
        val pts = Array<Vec3?>(33) { i -> if (vis[i] >= .5f && (0..2).all { world[i * 3 + it].isFinite() }) Vec3(world[i*3]*100, -world[i*3+1]*100, -world[i*3+2]*100) else null }
        val joints = buildMap<String, Vec3?> {
            Joints.SINGLE.forEach { (name, i) -> put(name, pts[i]) }
            Joints.PAIR.forEach { (name, pair) -> val a = pts[pair.first]; val b = pts[pair.second]; put(name, if (a != null && b != null) mid(a,b) else a ?: b) }
        }
        val sanity = checkUpSanity(joints, up)
        val used = if (sanity.flipped) up * -1f else up
        val f = PoseFrame(joints, used); val view = ViewEstimator.frameFeatures(joints); val aspect = width.toFloat()/height
        // detected = 검출기의 33관절 결과 여부. 실제 관측 가능 여부는 features 로 판단한다(Android 와 동일)
        val detected = (j.values()["xy"] as? List<*>)?.size == 66 && (j.values()["world"] as? List<*>)?.size == 99
        val features = HashMap<String, Float>()
        if (detected) {
            // 서서 하는 종목의 3D·2D 피처는 Android `PostureAnalyzer.analyze` 와 같은 순서·같은 함수. 바닥은 세트 추출기(`FloorFeatureExtractor`)가 판정 칸에서 만든다
            if (!profile.floor) features.putAll((f.features() + view + Stance2d.features(xy, vis, .5f, aspect) + Arm2d.features(xy, vis, .5f, aspect, Arm2d.yawOf(view)) +
                Lunge2d.features(f, xy, vis, .5f, aspect, ViewEstimator.shoulderYawOf(view), gravityUp = j.optBoolean("gravityFresh")) +
                LegGeometry.features(f, xy, vis, .5f, aspect, LegGeometry.rollDeg(used))).filterValues(Float::isFinite))
            // §101d 얼굴 메시 피처 — Swift 가 바닥 세 종목에서 300 ms 마다 머리 크롭에 얼굴 모델을 돌려 자세 행렬을 넘긴다. 없으면 키가 없다(시선 '측정 중')
            if (profile.floor) j.optJSONObject("face")?.let { face ->
                val found = face.optBoolean("found", false)
                features[FloorGaze.FOUND] = if (found) 1f else 0f
                if (found) (face.values()["matrix"] as? List<*>)?.let { m -> faceYawDeg(FloatArray(m.size) { (m[it] as? Number)?.toFloat() ?: Float.NaN }) }?.takeIf(Float::isFinite)?.let { features[FloorGaze.YAW] = it }
                features[FloorGaze.INFER_MS] = face.optDouble("inferMs", 0.0).toFloat()
                features[FloorGaze.ROTATION] = face.optDouble("rotation", 0.0).toFloat()
                features[FloorGaze.SIZE] = face.optDouble("headPx", 0.0).toFloat()
            }
        }
        return PoseSample(detected, xy, vis, features, vis.count { it >= .5f }, j.optLong("inferMs"), width, height, used, j.optBoolean("gravityFresh"), sanity.flipped, sanity.verified, if (detected) world else null)
    }

    // ---- 출력 ----
    private fun scope(): Map<String, Any?> = PostureScope.of(rules, exercise).let { mapOf("watched" to it.watched, "provisional" to it.provisional, "blind" to it.blind, "startLine" to it.startLine) }
    private fun count(): Int = side?.pool(mode == CoachMode.COACH)?.pairs ?: (notShort + short -
        if (mode == CoachMode.COACH && counter?.signal?.romExcludesShort == true) short + incorrect else if (mode == CoachMode.COACH) incorrect else 0).coerceAtLeast(0)
    private fun done(): Boolean = if (targetMs > 0L) false else if (side != null) side!!.done(mode == CoachMode.COACH) else count() >= target

    /** 목표 도달(t0) — 대기 카운트 정리·봉인(Android `beginSetEnd`). 그 뒤 [advanceDue] 가 true 가 되면 화면을 넘긴다. */
    fun beginSetEnd(nowMs: Long) { if (setEndAt == null) { setEndAt = nowMs; quietPolls = 0; speech.beginSetEnd(nowMs, dropCounts = targetMs > 0L) } }
    /** 자동 진행 대기(Android `AdvanceHold`) — 100 ms 마다 부른다. 말이 조용해진 뒤 300 ms, 상한 2 s. */
    fun advanceDue(nowMs: Long): Boolean {
        val t0 = setEndAt ?: return false
        quietPolls = if (speech.busy(nowMs)) 0 else quietPolls + 1
        return AdvanceHoldPolicy.due(nowMs - t0, quietPolls)
    }
    /** 화면을 넘기며 — 남은 말을 꼬리로 보호한다. */
    fun handOff(nowMs: Long) = speech.handOff(nowMs)
    /** Swift TTS 가 재생을 시작했다. */
    fun speechStarted(nowMs: Long) { speech.onStart(nowMs) }
    /** Swift TTS 가 끝냈다 — 다음 제출을 돌려준다(`[{text, flush}]`). */
    fun speechFinished(nowMs: Long): String { speech.onDone(nowMs); return Json.encode(speech.drain().map { mapOf("text" to it.item.text, "flush" to it.flush) }) }
    /** 사용자 조작용 전부 정지. */
    fun stopSpeech() = speech.stop()

    private fun status(now: Long): String {
        val cueOut = motionCue?.takeIf { now - it.startedAt < it.ttlMs }
        if (motionCue != null && cueOut == null) motionCue = null
        val highlightNow = if (now - formHighlightStamp < HIGHLIGHT_MS) formHighlight else emptySet()
        val provNow = if (now - formHighlightStamp < HIGHLIGHT_MS) formProvHighlight else emptySet()
        val floorNote = listOfNotNull(rejectNote, gazeNote).joinToString(" · ").ifEmpty { null }
        val lastFeatures = samples.lastOrNull()?.features
        return Json.encode(mapOf(
            "stage" to stage, "name" to profile.name, "reps" to count(), "cycles" to records.size, "target" to target,
            "done" to done(),
            "seconds" to if (stage == "preparing") preparation.state.seconds else ((now - startAt).coerceAtLeast(0)/1000),
            "message" to when (stage) {
                "preparing" -> preparation.state.message; "paused" -> "일시 정지"
                else -> if (profile.floor) FloorReasons.liveMessage(guideNote.takeIf { now - guideStamp < GUIDE_MS }, floorState) else if (lastFeatures.isNullOrEmpty()) "필요한 관절이 보이면 평가를 이어갑니다." else comparison.snapshot.message
            },
            "sideLine" to sideTallies?.headline(mode == CoachMode.COACH), "cue" to formNote,
            "provisionalNote" to provisionalNote, "guideNote" to guideNote.takeIf { now - guideStamp < GUIDE_MS },
            "note" to (if (counter?.floorTracker != null) floorNote else null), "floorMeasurement" to floorMeasurement,
            "coverage" to (if (coverage.ok) null else mapOf("message" to coverage.message, "fix" to coverage.fix)),
            "highlight" to (highlightNow + violHighlight + (floorState?.landmarks.orEmpty())).toList(), "provisional" to (provNow + provisionalHighlight).toList(),
            "shown" to gate.shown.toList(), "target2d" to gate.target.toList(),
            "motion" to cueOut?.let { mapOf("kind" to it.kind.name, "anchors" to it.anchors.toList(), "targets" to it.targets.toList(), "gravity" to it.gravity, "chain" to it.chain, "soft" to it.soft, "origin" to it.origin.name, "startedAt" to it.startedAt) },
            "hold" to holdHud(),
            "scope" to scope(), "comparison" to comparison.snapshot.values.map { it.detail },
            "breathing" to counter?.let { Breathing.word(exercise, breathDir) },
            "normal" to normalMatches.map { it.detail },
            "score" to scoreOk?.let { mapOf("ok" to it.first, "judged" to it.second) },
            "reference" to "자동 횟수 · 참고", "ship" to coach.lastStates.filter { it.rule.status == RuleStatus.SHIP }.map { mapOf("label" to it.rule.condition, "state" to it.label) },
            "beta" to coach.lastStates.filter { it.rule.status == RuleStatus.BETA }.map { mapOf("label" to it.rule.condition, "state" to it.label) },
            "speech" to speech.drain().map { mapOf("text" to it.item.text, "flush" to it.flush) },
            "tones" to ArrayList(tones).also { tones.clear() },
            "fast" to ((counter?.periodMs ?: Long.MAX_VALUE) < 1_500L),
        ))
    }

    /**
     * 세트 마감. [handOff] = 목표 도달 자동 진행(남은 말을 꼬리로 보호, 결과 음성은 꼬리 뒤에 — Android `nextSession(handOff)`), 아니면 수동 '세트 끝'(즉시 멈추고 결과 음성만).
     * 결과 JSON 의 `speech` 에 이번 호출의 제출이 실린다.
     */
    @Throws(Exception::class) fun finish(nowMs: Long, handOff: Boolean = false): String {
        stage = "finished"
        if (handOff) speech.handOff(nowMs) else speech.stop()
        finishedHold = clock?.snapshot()
        val rc = counter
        val t0 = times.firstOrNull() ?: finishedHold?.startAt ?: startAt
        val repTimes = rc?.repTimesMs?.toList().orEmpty()
        val endAt = AssessmentWindow.end(times.lastOrNull() ?: t0, repTimes)
        val formViews = RepFormSpecs.viewsFor(exercise)
        val viewOkForForm = viewEst?.let { it.cls == ViewEstimator.ViewClass.UNKNOWN || it.letter in formViews } ?: true
        val formViewLetter = viewEst?.takeIf { it.cls != ViewEstimator.ViewClass.UNKNOWN }?.letter
        val holdSnap = finishedHold?.takeIf { profile.floor }
        val formSummary = form?.summary()
        val tooFew = samples.size < MIN_FRAMES_FOR_LOG && holdSnap?.takeIf { it.wallMs > 0L } == null
        val results = if (tooFew) emptyList() else PostureAssessment.evaluate(rules, exercise, samples, times, anchorAt, endAt, records, baseline, MIN_FRAMES_FOR_LOG,
            repForm = formSummary, repFormViewOk = viewOkForForm, repFormViewLetter = formViewLetter, holdSegments = holdSnap?.segments)
        val holdSummary = holdSnap?.let { HoldSummary.of(it, t0) }
        val floorTr = rc?.floorTracker
        val unitCompleted = if (rc == null) null else side?.track?.pairs ?: unit.completed
        val unitInvalid = if (rc == null) null else unit.invalid
        val floorSummary = floorTr?.let { ft -> FloorRepSummary.of(ft.profile, unitCompleted ?: ft.completed.size, ft.rejected.map { it.feature }, ft.identityAbstain.size, ft.repDetail) }
        val measurementLines = results.mapNotNull { it.measurement }.toMutableList()
        measurementLines.addAll(0, PostureSetReport.floorLines(holdSummary, floorSummary, results.filter { it.rule.kind == "alignment" }))
        formSummary?.let { measurementLines += it.lines() }
        measurementLines += comparison.report(endAt, t0)
        comparison.snapshot.values.take(2).forEach { measurementLines += "초반 비교 · ${it.detail}" }
        if (profile.floor && measurementLines.isEmpty()) {
            val observed = samples.indices.lastOrNull { times[it] > anchorAt && times[it] <= endAt && samples[it].features.isNotEmpty() }?.let { samples[it].features }.orEmpty()
            if (observed.isNotEmpty()) measurementLines += "참고 · " + FloorTemporal.observation(exercise, observed).ifEmpty { "검출된 동작 ${records.size}회 · 자세 미확정" }
        }
        if (endAt < (times.lastOrNull() ?: endAt)) measurementLines += "마지막 검출 뒤 한 주기까지 평가했어요. 종료 구간은 추정값이에요"
        val onset = runCatching { coach.summarize() }.getOrDefault(emptyList())
        val report = PostureSetReport.build("ios-$startAt", exercise, profile.name, mode, samples.size, baseline != null, results,
            onset = if (mode == CoachMode.TRACK || endAt < (times.lastOrNull() ?: endAt)) emptyList() else onset,
            repsValid = unitCompleted?.let { it - (unitInvalid ?: 0) }, repsPartial = unitInvalid,
            tempoMs = if (rc == null) null else RepMetrics.medianPeriodMs(unit.repTimesMs), measurements = measurementLines,
            repRom = rc?.let { RepRomTier.of(it.signal) }, repUnit = if (rc == null) null else unit.unit, repHalfPending = rc != null && unit.pendingHalf && side == null,
            repSides = side?.tallies(), hold = holdSummary, floorReps = floorSummary, scopeLine = PostureScope.floorLine(exercise).takeIf { profile.floor })
        val agg = FeatureAggregator(); samples.forEachIndexed { i, s -> if (times[i] > anchorAt && times[i] <= endAt) agg.add(s.features) }
        val baselineValues = BaselineCollector.setValues(agg, rules.baselineFeaturesFor(exercise))
        // beta 가 헤드라인이면 음성은 중립 문장(원칙 #2) — Android 완료 화면과 같다. 경계 발화(flush)라 꼬리가 돌면 그 뒤에 붙는다
        val voice = if (report.headline != null && !report.headline!!.beta || report.judged == 0) report.voiceLine else "세트를 기록했어요. 참고 측정은 화면에서 확인해 주세요."
        speech.speak(voice, nowMs, flush = true)
        return Json.encode(mapOf("name" to profile.name, "exercise" to exercise, "mode" to mode.name.lowercase(),
            "reps" to count(), "cycles" to records.size, "summary" to report.summaryLine,
            "voice" to voice, "speech" to speech.drain().map { mapOf("text" to it.item.text, "flush" to it.flush) },
            "shipOk" to report.shipOk, "shipJudged" to report.shipJudged, "betaJudged" to report.betaJudged,
            "score" to report.accuracy, "scope" to scope(), "repLine" to report.repDetailLine,
            "measurements" to report.measurements, "baselineValues" to baselineValues, "baselineSets" to rules.baselineSetsFor(exercise),
            "items" to report.items.map { mapOf("id" to it.ruleId, "condition" to it.condition, "state" to it.label, "verdict" to it.overall.name, "beta" to it.beta, "observation" to it.observation, "fix" to if (mode == CoachMode.COACH && !it.beta) it.fix else "", "reason" to it.abstainReason) },
            "left" to side?.track?.left, "right" to side?.track?.right,
            "physicalReps" to unit.completed, "halfPending" to unit.pendingHalf,
            "heldSec" to holdSnap?.let { (it.heldMs / 1000L).toInt() }, "holdSource" to holdSnap?.source,
            "frames" to samples.size, "duration" to if(startAt>0) ((nowMs-startAt).coerceAtLeast(0)/1000) else 0))
    }

    /** 사진 없이 관절·중력·판정 피처를 내보낸다. iOS 전용 로그(schema 2)이며 Android SetLog 형식과 구분한다. */
    fun exportFrames(): String = (listOf(Json.encode(mapOf("kind" to "ios_session", "schema" to 2,
        "name" to profile.name, "exercise" to exercise, "mode" to mode.name.lowercase(), "target" to target, "front" to front,
        "rules_version" to rules.version, "sample_interval_ms" to SESSION_SAMPLE_INTERVAL_MS, "started_at_ms" to startAt,
        "anchor_t_ms" to anchorAt.takeUnless { it == Long.MAX_VALUE }?.let { it-startAt },
        "rep_times_ms" to counter?.repTimesMs?.map { it - startAt }, "rep_rejected" to counter?.rejectedReps?.map { mapOf("t_ms" to it.tMs - startAt, "reason" to it.feature, "side" to it.side?.name) },
        "hold" to finishedHold?.let { h -> mapOf("held_ms" to h.heldMs, "wall_ms" to h.wallMs, "source" to h.source, "stops" to h.stopMs, "segments" to h.segments.map { mapOf("t0" to it.t0 - startAt, "t1" to it.t1 - startAt, "state" to it.state) }) },
        "floor_reps" to counter?.floorTracker?.repDetail?.map { mapOf("t_ms" to it.tMs - startAt, "gaze" to FloorGaze.key(it.gaze)) },
        "gaze_spoken" to (plankGaze.spoken + floorGaze.spoken).map { mapOf("t_ms" to it.first - startAt, "text" to it.second) }))) +
        samples.indices.map { i -> val s = samples[i]; Json.encode(mapOf("kind" to "ios_frame", "t_ms" to times[i]-startAt,
            "features" to s.features, "xy" to s.normalizedXy.toList(), "visibility" to s.visibility.toList(),
            "world" to s.world?.toList(), "width" to s.imageWidth, "height" to s.imageHeight,
            "up" to listOf(s.up.x,s.up.y,s.up.z), "gravityFresh" to s.upFromGravity, "upFlipped" to s.upFlipped)) }).joinToString("\n")

    companion object {
        const val SESSION_SAMPLE_INTERVAL_MS = 300L
        private const val MIN_FRAMES_FOR_LOG = 8
        private const val COVERAGE_STREAK = 3
        private const val ANCHOR_FALLBACK_NO_COUNTER_MS = 4_000L
        private const val ANCHOR_FALLBACK_WITH_COUNTER_MS = 10_000L
        private const val PROVISIONAL_NOTE_GAP_MS = 3_000L
        private const val MAX_INVALID_CUES = 2
        private const val ANGLE_NOTE_GAP_MS = 15_000L
        private const val SIDE_CUE_GAP_MS = 8_000L
        private const val HIGHLIGHT_MS = 4_000L
        private const val GUIDE_MS = 6_000L
    }
}

/** 준비 안내 30분 간격(앱 프로세스 수명) — Android `PreparationIntro` 와 같다. */
object PreparationIntro {
    private const val REPEAT_AFTER_MS = 30 * 60_000L
    private val spokenAt = HashMap<String, Long>()
    fun due(name: String, now: Long): Boolean = spokenAt[name]?.let { now - it > REPEAT_AFTER_MS } ?: true
    fun mark(name: String, now: Long) { spokenAt[name] = now }
    fun forget(name: String) { spokenAt.remove(name) }
}

/** 목표 도달 뒤 화면을 넘기는 시각(Android `AdvanceHold`, spec §101 U5) — 말이 조용해진 뒤 300 ms, 상한 2 s. 말은 꼬리가 맡는다. */
object AdvanceHoldPolicy {
    const val MAX_MS = 2_000L
    const val GRACE_MS = 300L
    const val QUIET_POLLS = 2
    const val POLL_MS = 100L
    fun due(elapsedMs: Long, quietPolls: Int): Boolean = elapsedMs >= MAX_MS || (elapsedMs >= GRACE_MS && quietPolls >= QUIET_POLLS)
}

/**
 * 얼굴 크롭 명세(§101d, Android `PostureAnalyzer.faceCrop` 의 기하) — Swift 가 Core Image 로 자른다. 중심 = 코·귀 중 보이는 점의 평균, 크기 = max(코–귀 × 2.2, 어깨 중점 → 귀 중점, 40 px),
 * 한 변 = 크기 × 3, 회전 = 목 방향(어깨 중점 → 귀 중점)이 위를 향하게. 코·귀가 둘 다 없거나 어깨가 없으면 null. 입력은 정규화 xy(66)·가시성(33)·영상 폭·높이.
 */
object IosFace {
    const val CROP_PX = 256
    const val CROP_SCALE = 3.0f
    const val MIN_CONFIDENCE = 0.3f
    const val INTERVAL_MS = 300L
    @Throws(Exception::class) fun cropSpec(input: String): String {
        val j = JSONObject(input)
        val xy = (j.values()["xy"] as? List<*>).orEmpty().map { (it as? Number)?.toFloat() ?: Float.NaN }
        val vis = (j.values()["visibility"] as? List<*>).orEmpty().map { (it as? Number)?.toFloat() ?: 0f }
        val w = j.getInt("width").toFloat(); val h = j.getInt("height").toFloat()
        if (xy.size < 66 || vis.size < 33) return "null"
        fun ok(i: Int) = vis[i] >= 0.5f && xy[i * 2].isFinite() && xy[i * 2 + 1].isFinite()
        fun px(i: Int) = xy[i * 2] * w
        fun py(i: Int) = xy[i * 2 + 1] * h
        val head = listOf(0, 7, 8).filter { ok(it) }
        if (head.size < 2 || !(ok(11) || ok(12))) return "null"
        val cx = head.map { px(it) }.average().toFloat(); val cy = head.map { py(it) }.average().toFloat()
        val ears = listOf(7, 8).filter { ok(it) }
        val noseEar = if (ok(0) && ears.isNotEmpty()) ears.maxOf { kotlin.math.hypot(px(0) - px(it), py(0) - py(it)) } else 0f
        val shoulders = listOf(11, 12).filter { ok(it) }
        val sx = shoulders.map { px(it) }.average().toFloat(); val sy = shoulders.map { py(it) }.average().toFloat()
        val ex = if (ears.isNotEmpty()) ears.map { px(it) }.average().toFloat() else cx
        val ey = if (ears.isNotEmpty()) ears.map { py(it) }.average().toFloat() else cy
        val neck = kotlin.math.hypot(ex - sx, ey - sy)
        val headPx = maxOf(noseEar * 2.2f, neck, 40f)
        val vx = ex - sx; val vy = ey - sy
        // 목 방향이 위(−y)에서 시계 방향으로 φ 만큼 돌아 있으면 −φ 돌린다(Android Matrix 의 양의 각 = 화면 시계 방향)
        val phi = if (kotlin.math.hypot(vx, vy) > 1f) Math.toDegrees(kotlin.math.atan2(vx.toDouble(), (-vy).toDouble())).toFloat() else 0f
        val side = headPx * CROP_SCALE
        return Json.encode(mapOf("cx" to cx, "cy" to cy, "rotateDeg" to -phi, "side" to side, "scale" to CROP_PX / side, "headPx" to headPx, "outPx" to CROP_PX))
    }
}

package com.example.trex_kotlin.posture

/**
 * Swift 직렬 큐 전용 입구. 시각은 단조 증가 ms, 좌표는 미러 전 MediaPipe 원본이다.
 * 모든 임계값·판정·렙 계산은 Android 정본을 사용한다. 플랫폼 경계는 JSON만 내보낸다.
 */
class IosEngine @Throws(Exception::class) constructor(standingRules: String, floorRules: String) {
    private val rules = PostureRuleSet.loadText(standingRules).let { standing ->
        PostureRuleSet(standing.version, standing.generated, standing.rules + PostureRuleSet.loadText(floorRules).rules).plusRepForm()
    }
    private var profile = ExerciseProfiles.all.first()
    private var exercise = profile.referenceExercise!!
    private var mode = CoachMode.COACH
    private var counter: RepCounter? = null
    private var form: RepFormEvaluator? = null
    private var unit = RepUnitAccumulator(RepUnit.CYCLE)
    private var side: SideStepCounter? = null
    private var coach = LiveCoach(rules, exercise, requireAnchor = true, speakBeta = false)
    private var comparison = PostureComparisonTracker(exercise, emptyList())
    private var preparation = CapturePreparationController()
    private var direction = CaptureDirectionWindow(profile.capture, false)
    private val floor = FloorFeatureExtractor()
    private val contact = ContactMinimum.elbowKnee()
    private val samples = ArrayList<PoseSample>()
    private val times = ArrayList<Long>()
    private val records = ArrayList<RepRecord>()
    private var baseline: Map<String, Float>? = null
    private var stage = "preparing"
    private var firstUsable: Long? = null
    private var anchorAt = Long.MAX_VALUE
    private var startAt = 0L
    private var lastGrid = Long.MIN_VALUE
    private var lastFrame = Long.MIN_VALUE
    private var lastRejected = 0
    private var lastRejectCue = Long.MIN_VALUE
    private var notShort = 0
    private var short = 0
    private var incorrect = 0
    private var detected = 0
    private var cue: String? = null
    private var target = 10
    private var highlight = emptySet<Int>()
    private val prepFrames = ArrayDeque<Pair<Long, Map<String,Float>>>()
    private var normal = NormalPoseReference(emptyList())
    private var comparisonSpeech = ComparisonSpeech()
    private var alignment = PlankAlignmentTracker(emptyList())
    private var hold: HoldTracker? = null
    private var floorFeedback = FloorFeedbackController(exercise,emptyList())
    private var floorState: FloorFeedback? = null

    fun setNormalReference(text: String) { normal = NormalPoseReference.parse(text.lineSequence()) }

    @Throws(Exception::class) fun configure(config: String): String {
        val c = JSONObject(config)
        profile = ExerciseProfiles.forName(c.getString("name")) ?: error("미지원 운동")
        require(profile.cameraEnabled)
        exercise = profile.referenceExercise ?: error("참조 종목 없음")
        mode = if (c.optString("mode") == "track") CoachMode.TRACK else CoachMode.COACH
        target = c.optInt("target", 10).coerceIn(1, 999)
        baseline = c.optJSONObject("baseline")?.values()?.mapNotNull { (k, v) -> (v as? Number)?.toFloat()?.takeIf(Float::isFinite)?.let { k to it } }?.toMap()
        val repConfig = rules.rulesFor(exercise).firstOrNull { it.kind == "rep" }?.repConfig
        counter = RepCounter.forSession(exercise, repConfig?.direction, repConfig?.threshold, floor = profile.floor)
        form = counter?.let { RepFormSpecs.evaluatorFor(exercise, it) }
        unit = RepUnitAccumulator(RepUnit.forSession(profile, profile.floor))
        side = if (profile.name in SIDE_EACH_EXERCISES && form != null) SideStepCounter(target, strictUnknown = counter?.legTracker != null) else null
        coach = LiveCoach(rules, exercise, baseline = baseline, requireAnchor = true, speakBeta = false)
        comparison = PostureComparisonTracker(exercise, ComparisonMetrics.forExercise(exercise, rules.rulesFor(exercise)), observationKind = profile.kind)
        comparisonSpeech = ComparisonSpeech(); alignment = PlankAlignmentTracker(rules.rulesFor(exercise))
        hold = rules.rulesFor(exercise).firstOrNull { it.holdConfig != null }?.holdConfig?.let(::HoldTracker)
        floorFeedback = FloorFeedbackController(exercise,rules.rulesFor(exercise)); floorState = null
        preparation = CapturePreparationController(profile.floor)
        direction = CaptureDirectionWindow(profile.capture, profile.floor)
        floor.reset(); contact.clear(); samples.clear(); times.clear(); records.clear(); prepFrames.clear()
        firstUsable = null; anchorAt = Long.MAX_VALUE; startAt = 0; lastGrid = Long.MIN_VALUE; lastFrame = Long.MIN_VALUE
        lastRejected = 0; lastRejectCue = Long.MIN_VALUE; notShort = 0; short = 0; incorrect = 0; detected = 0; highlight = emptySet()
        stage = "preparing"; cue = null
        return Json.encode(mapOf("instruction" to profile.preparationInstruction, "scope" to scope()))
    }

    fun arm(nowMs: Long) { preparation.arm(nowMs) }
    fun holdInstruction(hold: Boolean) { preparation.holdStart(hold) }
    fun manualStart(nowMs: Long) { preparation.manual(nowMs, 5) }
    fun pause(paused: Boolean) {
        stage = if (paused) "paused" else "preparing"
        counter?.resetCycle(); form?.onRejected(lastFrame.coerceAtLeast(0)); comparison.unavailable(); contact.clear()
        if (!paused) { preparation.arm(lastFrame.coerceAtLeast(0)); direction.clear() }
    }

    /** 추론이 오지 않는 시간도 준비 게이트에 전달한다. 복귀 시 오래된 프레임은 세지 않는다. */
    fun tick(nowMs: Long): String {
        cue = null
        if (stage == "preparing") {
            if (preparation.tick(nowMs).phase == PreparationPhase.STARTED) {
                stage = "active"; if (startAt == 0L) startAt = nowMs
                cue = "시작하세요."
            }
        }
        return status(nowMs)
    }

    @Throws(Exception::class) fun frame(input: String): String {
        val j = JSONObject(input); val now = j.getLong("timeMs")
        cue = null
        if (now <= lastFrame) return status(now)
        lastFrame = now
        val sample = makeSample(j)
        if (stage == "active") contact.offer(now, sample.features)
        // 85 ms는 표시와 순간 닿음만. 준비·판정·렙은 Android와 같은 300 ms 칸의 첫 표본이다.
        val judgmentBin = now / 300
        if (lastGrid == judgmentBin) return if (stage == "preparing") tick(now) else status(now)
        lastGrid = judgmentBin
        if (stage == "preparing") {
            if (!profile.floor && sample.features.isNotEmpty()) { prepFrames.addLast(now to sample.features); while(prepFrames.size > 12) prepFrames.removeFirst() }
            val framed = CaptureFraming.inspect(sample, profile.capture, profile.floor, profile.framingRegion)
            val checked = if (preparation.state.phase == PreparationPhase.COUNTDOWN) framed else direction.check(framed, sample.features, now)
            preparation.observe(now, checked)
            return tick(now)
        }
        if (stage != "active") return status(now)
        val features = sample.features + contact.drain()
        val logged = PoseSample(sample.detected, sample.normalizedXy, sample.visibility, features, sample.visibleJointCount,
            sample.inferMs, sample.imageWidth, sample.imageHeight, sample.up, sample.upFromGravity, sample.upFlipped, sample.upVerified, sample.world)
        samples += logged; times += now
        if (features.isNotEmpty()) {
            detected++
            if (firstUsable == null) firstUsable = now
        } else contact.clear()
        val rc = counter
        var completed: Long? = null
        if (rc != null) {
            val judged = rc.legTracker?.annotate(features) ?: features
            form?.onFrame(now, judged)
            if (prepFrames.isNotEmpty() && !profile.floor) {
                rc.standingSeedFrom(prepFrames.toList(),now)?.let { rc.seedStanding(now,it) }; prepFrames.clear()
            }
            val done = rc.onFrameFeatures(now, judged)
            if (rc.newlyRetracted) { records.clear(); form?.reset(); lastRejected = rc.rejectedReps.size; notShort = 0; short = 0; incorrect = 0 }
            if (rc.rejectedReps.size > lastRejected) {
                for (i in lastRejected until rc.rejectedReps.size) form?.onRejected(rc.rejectedReps[i].tMs)
                lastRejected = rc.rejectedReps.size
                rc.legTracker?.let { lt -> if (lastRejectCue == Long.MIN_VALUE || now - lastRejectCue > 6000) {
                    cue = LegCycleTracker.cueFor(lt.profile, rc.rejectedReps.last().feature)
                    if (cue != null) lastRejectCue = now
                } }
            }
            if (done) {
                completed = rc.repTimesMs.lastOrNull()
                if (coach.anchor()) anchorAt = now
                val tally = RepUnitAccumulator.onCounterFrame(rc, now, records, unit)
                notShort += tally.repsNotShort; short += tally.repsShort
                val reps = tally.records.mapNotNull { r -> form?.onCycle(r.tMs, r.cycleMin, r.cycleMax, r.cycleStartMs, r.side) }
                val gate = mode == CoachMode.COACH && !profile.floor
                if (gate && side == null) incorrect += reps.indices.count { !reps[it].correct && !(rc.signal.romExcludesShort && tally.records.getOrNull(it)?.valid == false) }
                val events = reps.filter { !it.notStep }.mapNotNull { r -> side?.offer(r.tMs, r.side, !r.correct)?.of(mode == CoachMode.COACH) }
                reps.lastOrNull()?.let { r ->
                    highlight = if (gate) RuleHighlight.forRepForm(r).first else emptySet()
                    form?.eventFor(r, now, gate)?.takeIf { it.ship && gate }?.let { cue = if (it.brief) it.message else it.check.fix }
                }
                events.lastOrNull()?.let { e ->
                    if (e.counted && e.known) cue = listOfNotNull(cue,
                        if (e.remaining == 0) "${e.side.label} 끝" else "${e.side.label} ${e.remaining}개 남음",
                        e.switchTo?.let { "이제 ${it.label}으로 해 주세요." }).joinToString(". ")
                }
            }
        }
        if (anchorAt == Long.MAX_VALUE && firstUsable?.let { now - it >= if (rc == null) 4000 else 10000 } == true && coach.anchor()) anchorAt = now
        coach.onFrame(features)
        val ev = coach.evaluate(now)
        if (cue == null && ev != null && ev.rule.status == RuleStatus.SHIP && (mode == CoachMode.COACH || ev.kind in setOf(OnsetKind.DRIFT, OnsetKind.RECOVERED))) cue = ev.message
        comparison.add(now, features, completed, anchored = coach.isAnchored)
        if(mode == CoachMode.TRACK && cue == null) cue = comparisonSpeech.next(now,comparison.snapshot,true)
        if(profile.floor) {
            val holdRule=rules.rulesFor(exercise).firstOrNull { it.holdConfig != null }
            hold?.add(now-startAt,holdRule?.let { features[it.baseFeature] })
            val aligned=alignment.add(now-startAt,features)
            val coverage=FloorCoverage.analyze(sample.normalizedXy,sample.visibility,rules.rulesFor(exercise))
            floorState=floorFeedback.update(now,features,hold?.snapshot(),coach.lastStates,records.lastOrNull()?.takeIf { it.tMs == completed },
                anchored=coach.isAnchored,observable=coverage.ok,mode=mode,voiceEnabled=false,
                alignment=aligned.takeIf { exercise == "플랭크" })
            highlight=floorState?.landmarks.orEmpty()
        }
        return status(now)
    }

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
        val features = if (profile.floor) floor.computeForExercise(exercise, xy, vis, width, height) else
            f.features() + view + Stance2d.features(xy, vis, .5f, aspect) + Arm2d.features(xy, vis, .5f, aspect, Arm2d.yawOf(view)) +
            Lunge2d.features(f, xy, vis, .5f, aspect, ViewEstimator.shoulderYawOf(view)) + LegGeometry.features(f, xy, vis, .5f, aspect, LegGeometry.rollDeg(used))
        // detected는 검출기의 33관절 결과 여부다. 실제 관측 가능 여부는 features로 판단한다(Android와 동일).
        val detected = (j.values()["xy"] as? List<*>)?.size == 66 && (j.values()["world"] as? List<*>)?.size == 99
        return PoseSample(detected, xy, vis, features.filterValues(Float::isFinite), vis.count { it >= .5f },
            j.optLong("inferMs"), width, height, used, j.optBoolean("gravityFresh"), sanity.flipped, sanity.verified, world)
    }
    private fun scope(): Map<String, Any?> = PostureScope.of(rules, exercise).let { mapOf("watched" to it.watched, "provisional" to it.provisional, "blind" to it.blind, "startLine" to it.startLine) }
    private fun count(): Int = side?.pool(mode == CoachMode.COACH)?.pairs ?: (notShort + short -
        if (mode == CoachMode.COACH && counter?.signal?.romExcludesShort == true) short + incorrect else if (mode == CoachMode.COACH) incorrect else 0).coerceAtLeast(0)
    private fun status(now: Long): String = Json.encode(mapOf(
        "stage" to stage, "name" to profile.name, "reps" to count(), "cycles" to records.size, "target" to target,
        "done" to (if (side != null) side!!.done(mode == CoachMode.COACH) else count() >= target),
        "seconds" to if (stage == "preparing") preparation.state.seconds else ((now - startAt).coerceAtLeast(0)/1000),
        "message" to when (stage) { "preparing" -> preparation.state.message; "paused" -> "일시 정지"; else -> floorState?.message ?: if (samples.lastOrNull()?.features.isNullOrEmpty()) "필요한 관절이 보이면 평가를 이어갑니다." else comparison.snapshot.message },
        "sideLine" to side?.tallies()?.headline(mode == CoachMode.COACH), "cue" to cue,
        "highlight" to highlight.toList(), "scope" to scope(), "comparison" to comparison.snapshot.values.map { it.detail },
        "breathing" to counter?.let { Breathing.word(exercise,it.motionDirection) },
        "normal" to if(mode == CoachMode.COACH) normal.compare(exercise,ViewEstimator.estimate(samples.takeLast(8).map { it.features })?.letter,comparison.latestSignature,comparison.metrics).map { it.detail } else emptyList<String>(),
        "reference" to "자동 횟수 · 참고", "ship" to coach.lastStates.filter { it.rule.status == RuleStatus.SHIP }.map { mapOf("label" to it.rule.condition, "state" to it.label) },
        "beta" to coach.lastStates.filter { it.rule.status == RuleStatus.BETA }.map { mapOf("label" to it.rule.condition, "state" to it.label) }
    ))
    @Throws(Exception::class) fun finish(nowMs: Long): String {
        stage = "finished"; cue = null
        val viewAgg = FeatureAggregator(); samples.forEachIndexed { i, s -> if (times[i] > anchorAt) viewAgg.add(s.features) }
        val view = ViewEstimator.estimate(viewAgg)?.takeIf { it.cls != ViewEstimator.ViewClass.UNKNOWN }
        val results = PostureAssessment.evaluate(rules, exercise, samples, times, anchorAt, nowMs, records, baseline,
            repForm = form?.summary(), repFormViewLetter = view?.letter)
        val report = PostureSetReport.build("ios-$startAt", exercise, profile.name, mode, detected, baseline != null, results, coach.summarize(),
            counter?.let { notShort }, counter?.let { short }, RepMetrics.medianPeriodMs(unit.repTimesMs),
            comparison.snapshot.values.map { it.detail }, counter?.let { RepRomTier.of(it.signal) }, unit.unit, unit.pendingHalf, side?.tallies())
        val agg = FeatureAggregator(); samples.forEachIndexed { i,s -> if (times[i] > anchorAt) agg.add(s.features) }
        val baselineValues = BaselineCollector.setValues(agg, rules.baselineFeaturesFor(exercise))
        return Json.encode(mapOf("name" to profile.name, "exercise" to exercise, "mode" to mode.name.lowercase(),
            "reps" to count(), "cycles" to records.size, "summary" to report.summaryLine,
            "voice" to if (report.headline != null && !report.headline!!.beta || report.judged == 0) report.voiceLine else "세트를 기록했어요. 참고 측정은 화면에서 확인해 주세요.",
            "shipOk" to report.shipOk, "shipJudged" to report.shipJudged, "betaJudged" to report.betaJudged,
            "score" to report.accuracy, "scope" to scope(), "repLine" to report.repDetailLine,
            "measurements" to report.measurements, "baselineValues" to baselineValues, "baselineSets" to rules.baselineSetsFor(exercise),
            "items" to report.items.map { mapOf("id" to it.ruleId, "condition" to it.condition, "state" to it.label, "verdict" to it.overall.name, "beta" to it.beta, "observation" to it.observation, "fix" to if (mode == CoachMode.COACH && !it.beta) it.fix else "", "reason" to it.abstainReason) },
            "left" to side?.track?.left, "right" to side?.track?.right,
            "physicalReps" to unit.completed, "halfPending" to unit.pendingHalf,
            "frames" to samples.size, "duration" to if(startAt>0) ((nowMs-startAt).coerceAtLeast(0)/1000) else 0))
    }
    /** 사진 없이 관절·중력·판정 피처를 내보낸다. iOS 전용 로그이며 Android SetLog 형식과 구분한다. */
    fun exportFrames(): String = (listOf(Json.encode(mapOf("kind" to "ios_session", "schema" to 1,
        "name" to profile.name, "exercise" to exercise, "mode" to mode.name.lowercase(), "target" to target,
        "sample_interval_ms" to 300, "started_at_ms" to startAt,
        "anchor_t_ms" to anchorAt.takeUnless { it == Long.MAX_VALUE }?.let { it-startAt }))) +
        samples.indices.map { i -> val s = samples[i]; Json.encode(mapOf("kind" to "ios_frame", "t_ms" to times[i]-startAt,
            "features" to s.features, "xy" to s.normalizedXy.toList(), "visibility" to s.visibility.toList(),
            "world" to s.world?.toList(), "width" to s.imageWidth, "height" to s.imageHeight,
            "up" to listOf(s.up.x,s.up.y,s.up.z), "gravityFresh" to s.upFromGravity)) }).joinToString("\n")
}

package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** 실제 측면 관절에서 반복 판정과 세트 결과까지 이어지는 푸시업 검사. */
class RepFormPushupTest {
    private val exercise = "푸시업"
    private val signals = listOf(1.4f,1.4f,1.4f,1.4f,1.2f,1f,.8f,.6f,.6f,.8f,1f,1.2f,1.4f,1.4f,1.4f)

    private fun points(hipY: Float = .4f, noseX: Float = .18f, noseY: Float = .46f, earY: Float = .4f): FloatArray {
        val xy = FloatArray(66) { .5f }
        fun p(i: Int, x: Float, y: Float) { xy[2*i] = x; xy[2*i+1] = y }
        p(0,noseX,noseY); p(7,.18f,earY); p(8,.18f,earY+.02f)
        p(11,.25f,.4f); p(12,.25f,.42f); p(23,.5f,hipY); p(24,.5f,hipY+.02f)
        p(25,.68f,.4f); p(26,.68f,.42f); p(27,.85f,.4f); p(28,.85f,.42f)
        p(13,.25f,.6f); p(14,.25f,.62f); p(15,.25f,.75f); p(16,.25f,.77f)
        return xy
    }
    private fun visibility(right: Boolean = false) = FloatArray(33) {
        val far = it in setOf(8,12,14,16,24,26,28)
        if (far != right) .6f else .95f
    }
    private fun geometry(xy: FloatArray = points(), vis: FloatArray = visibility()) = PushupGeometry.features(xy,vis,1000,1000)
    private fun evaluator() = RepFormEvaluator(RepFormSpecs.byExercise.getValue(exercise), "wrist_shoulder_d", .30f, priors = { _, _ -> null })
    private fun rep(ev: RepFormEvaluator = evaluator(), offset: Long = 0L,
                    frame: (Int) -> Map<String,Float> = { geometry() }, gapAfter: Int? = null): RepFormRep {
        fun time(i: Int) = offset + i * 300L + if (gapAfter != null && i >= gapAfter) 1500L else 0L
        signals.forEachIndexed { i, signal -> ev.onFrame(time(i), frame(i) + ("wrist_shoulder_d" to signal)) }
        return ev.onCycle(time(signals.lastIndex), .6f, 1.4f, offset + 900L)
    }
    private fun RepFormRep.named(name: String) = outcomes.first { it.check.id.endsWith("|$name") }

    @Test fun sideGeometryCanJudgeWithoutStandingYawAndLeavesHiddenSideUnjudged() {
        val g = geometry()
        assertTrue(g.keys.containsAll(setOf("${PushupGeometry.HIP}_L","${PushupGeometry.HEAD}_L","${PushupGeometry.NECK}_L")))
        val r = rep()
        assertNull(r.view)
        assertTrue(r.outcomes.filter { it.check.feature in setOf("${PushupGeometry.HIP}_L","${PushupGeometry.HEAD}_L","${PushupGeometry.NECK}_L") }.all { it.verdict == Verdict.OK })
        assertTrue(r.outcomes.filter { it.check.feature.endsWith("_R") }.all { it.verdict == Verdict.ABSTAIN })
    }

    @Test fun initiallySaggingOrRaisedHipsDoNotBecomeTheNormalReference() {
        val sag = rep(frame = { geometry(points(hipY = .50f)) }).named("왼쪽 골반 정렬")
        val raised = rep(frame = { geometry(points(hipY = .24f)) }).named("왼쪽 골반 정렬")
        assertEquals(Verdict.VIOLATION,sag.verdict); assertEquals(FormDirection.LOW,sag.direction)
        assertEquals(Verdict.VIOLATION,raised.verdict); assertEquals(FormDirection.HIGH,raised.direction)
        assertNull(sag.reference); assertNull(raised.reference)
    }

    @Test fun raisedAndTuckedHeadHaveOppositeDirectionsWithoutChangingHipResult() {
        val up = rep(frame = { geometry(points(noseX = .12f,noseY = .43f)) })
        val down = rep(frame = { geometry(points(noseX = .23f,noseY = .43f)) })
        assertEquals(FormDirection.HIGH,up.named("왼쪽 고개 방향").direction)
        assertEquals(FormDirection.LOW,down.named("왼쪽 고개 방향").direction)
        assertEquals(Verdict.OK,up.named("왼쪽 골반 정렬").verdict)
        assertEquals(Verdict.OK,down.named("왼쪽 골반 정렬").verdict)
    }

    @Test fun raisedNeckIsMeasuredEvenIfFaceStillPointsDown() {
        val r = rep(frame = { geometry(points(earY = .31f,noseY = .37f)) })
        assertEquals(Verdict.OK,r.named("왼쪽 고개 방향").verdict)
        assertEquals(Verdict.VIOLATION,r.named("왼쪽 목 정렬").verdict)
        assertEquals(FormDirection.HIGH,r.named("왼쪽 목 정렬").direction)
    }

    @Test fun headOcclusionDoesNotDiscardVisibleHipGeometry() {
        val v = visibility().apply { this[0] = 0f; this[7] = 0f; this[8] = 0f }
        val r = rep(frame = { geometry(points(hipY = .50f),v) })
        assertEquals(Verdict.VIOLATION,r.named("왼쪽 골반 정렬").verdict)
        assertEquals(Verdict.ABSTAIN,r.named("왼쪽 고개 방향").verdict)
        assertEquals(Verdict.ABSTAIN,r.named("왼쪽 목 정렬").verdict)
    }

    @Test fun farSideOcclusionUsesVisibleSideWithoutAveragingItsCoordinates() {
        val v = visibility().apply { for (i in listOf(8,12,24,26,28)) this[i] = .25f }
        val xy = points(hipY = .46f).apply { this[48] = .1f; this[49] = .9f }
        val g = geometry(xy,v)
        assertTrue(g.keys.none { it.endsWith("_R") })
        assertEquals(-.1f,g.getValue("${PushupGeometry.HIP}_L"),.001f)
    }

    @Test fun wrongViewStandingAndLostAnklesAbstainWhileKneelingStillKeepsGeometry() {
        val front = points().apply { this[24] = .5f }
        val standing = points().let { src -> FloatArray(66) { i -> if (i % 2 == 0) src[i+1] else src[i-1] } }
        val kneeling = points().apply { this[50] = .5f; this[51] = .65f; this[52] = .5f; this[53] = .67f }
        assertTrue(geometry(kneeling).containsKey("${PushupGeometry.HIP}_L"))
        val hiddenAnkles = visibility().apply { this[27] = 0f; this[28] = 0f }
        for (g in listOf(geometry(standing),geometry(vis=hiddenAnkles))) {
            assertFalse(g.containsKey(PushupGeometry.READY))
            assertTrue(rep(frame={g}).outcomes.all { it.verdict == Verdict.ABSTAIN })
        }
    }

    @Test fun mirroredImageKeepsTheSameHeadAndHipCorrectionDirections() {
        val xy = points(hipY=.46f,noseX=.12f,noseY=.43f)
        val mirrored = xy.copyOf().apply { for (i in 0..32) this[i*2] = 1f - xy[i*2] }
        val original = geometry(xy); val reflected = geometry(mirrored)
        assertEquals(original.keys,reflected.keys)
        original.forEach { (key,value) -> assertEquals(value,reflected.getValue(key),.001f) }
    }

    @Test fun sideChangeWithinARepetitionDoesNotJoinTwoHalfObservations() {
        val r = rep(frame = { i -> geometry(vis=visibility(right = i >= 9)) })
        assertTrue(r.outcomes.filter { it.check.phase == RepPhase.CYCLE && it.check.feature != "pushup_hand_width" }.all { it.verdict == Verdict.ABSTAIN })
    }

    @Test fun losingTheSideViewAfterMeasuredRepetitionsDoesNotReuseOldGeometry() {
        val ev = evaluator()
        repeat(3) { rep(ev,it*6000L) }
        val front = points().apply { this[24] = .5f }
        val r = rep(ev,18000L,frame = { geometry(front) })
        assertTrue(r.outcomes.filter { it.check.phase == RepPhase.CYCLE && it.check.feature != "pushup_hand_width" }.all { it.verdict == Verdict.ABSTAIN })
    }

    @Test fun sparseCoverageAndLongGapsDoNotProduceACompletePostureJudgment() {
        val sparse = rep(frame = { i -> if (i in 4..8) geometry() else emptyMap() })
        val gap = rep(gapAfter=9)
        assertTrue(sparse.outcomes.filter { it.check.phase == RepPhase.CYCLE }.all { it.verdict == Verdict.ABSTAIN })
        assertTrue(gap.outcomes.all { it.verdict == Verdict.ABSTAIN })
    }

    @Test fun aSingleHipOutlierDoesNotOverrideTheRepetitionMedian() {
        val r = rep(frame = { i -> geometry() + ("${PushupGeometry.HIP}_L" to if (i == 8) .3f else 0f) })
        assertEquals(Verdict.OK,r.named("왼쪽 골반 정렬").verdict)
    }

    @Test fun targetCoachPolicyDoesNotCreateConfirmedPostureScores() {
        val checks = RepFormSpecs.byExercise.getValue(exercise)
        assertEquals(14,checks.size)
        assertTrue(checks.all { it.status == RuleStatus.BETA && !it.requireObservedView })
        for(c in checks) assertEquals(c.feature.startsWith("pushup_knee_support") || c.feature=="pushup_face_yaw" ||
            c.feature=="pushup_hand_width" || c.feature.startsWith("pushup_hip_offset"),c.gates)
        assertTrue(checks.all { it.minSamples >= 2 && it.minCoverage >= .4f && it.maxObservationGapMs == 750L })
        val ev = evaluator(); val r = rep(ev,frame = { geometry(points(hipY=.50f)) })
        val event = ev.eventFor(r,5000L,gate=true)!!
        assertFalse(event.ship); assertFalse(event.gated); assertTrue(r.correct); assertFalse(r.judged)
        assertFalse(ev.summary().hasShip)
        assertTrue(RepFormSpecs.asRules().filter { it.exercise == exercise }.all { it.status == RuleStatus.BETA })
    }

    @Test fun cameraFeaturesCounterAndFinalAssessmentShareTheRepetitionResult() {
        val extractor = FloorFeatureExtractor()
        val rc = RepCounter.forSession(exercise,"min",.7101f,floor=true)!!
        val ev = RepFormSpecs.evaluatorFor(exercise,rc)!!
        val samples = ArrayList<PoseSample>(); val times = ArrayList<Long>(); val records = ArrayList<RepRecord>()
        assertEquals("wrist_shoulder_d",rc.signal.feature)
        assertEquals(.30f,rc.signal.minAmp,0f)
        assertEquals(.7101f,rc.signal.romThreshold!!,0f)
        repeat(3) { cycle -> signals.forEachIndexed { i, signal ->
            val xy = points()
            // 몸통·다리가 일자로 함께 내려가고 손은 바닥 위치에 남는 연속 관절 시퀀스.
            val shift = .35f - .25f * signal
            for (j in listOf(0,7,8,11,12,23,24,25,26,27,28)) xy[j*2+1] += shift
            val f = extractor.computeForExercise(exercise,xy,visibility(),1000,1000)
            val now = (cycle * signals.size + i) * 300L
            assertTrue(f.containsKey("${PushupGeometry.HIP}_L"))
            samples += PoseSample(true,xy,visibility(),f,33,1,1000,1000); times += now
            ev.onFrame(now,f)
            if (rc.onFrameFeatures(now,f)) {
                val tally = RepUnitAccumulator.onCounterFrame(rc,now,records,null)
                tally.records.forEach { r -> ev.onCycle(r.tMs,r.cycleMin,r.cycleMax,r.cycleStartMs) }
            }
        } }
        assertEquals(3,records.size)
        assertEquals(records.size,ev.summary().reps.size)
        val rules = PostureRuleSet("test","",RepFormSpecs.asRules().filter { it.exercise == exercise })
        val results = PostureAssessment.evaluate(rules,exercise,samples,times,0L,times.last(),records,repForm=ev.summary())
        assertEquals(Verdict.OK,results.first { it.rule.id.endsWith("|왼쪽 골반 정렬") }.verdict)
        assertEquals(Verdict.ABSTAIN,results.first { it.rule.id.endsWith("|오른쪽 골반 정렬") }.verdict)
    }

    @Test fun otherFloorExercisesKeepTheirOriginalFeaturePath() {
        for (ex in FloorTemporal.exercises - exercise) {
            val xy=points(); val vis=visibility()
            val extractor=FloorFeatureExtractor()
            val expected=extractor.compute(xy,vis,1000,1000) + extractor.sideFeatures(xy,vis,1000,1000) +
                if (ex == "플랭크") PlankGeometry.features(xy,vis,1000,1000) else emptyMap()
            // main의 바닥 3종목은 보이는 쪽 사슬 피처도 갖는다. 푸시업 추가로 이 경로를 바꾸지 않는다.
            val chain = FloorChain.Kind.of(ex)?.let { FloorChain(it).features(0L,xy,vis,1000,1000,null) }.orEmpty()
            val expectedWithChain = if (chain.containsKey(FloorChain.TORSO)) expected + chain else expected
            assertEquals(ex,expectedWithChain,FloorFeatureExtractor().computeForExercise(ex,xy,vis,1000,1000))
        }
    }

    @Test fun replacingOldHeadWindowKeepsDepthHandsAndOtherFloorExercises() {
        val head = PostureRule(
            id="floor|푸시업|고개 젖힘/숙임 여부",exercise=exercise,condition="고개 젖힘/숙임 여부",
            subtype=null,status=RuleStatus.BETA,reason="기존 고개 각도",
            feature="head_trunk_ang__mean",baseFeature="head_trunk_ang",stat="mean",family="floor2d",
            op=">",threshold=102.95112f,view="C",viewDesc="측면",cvAuc=Float.NaN,cvBalacc=Float.NaN,
            sampleN=832,mirrorSafe=true,cautions=emptyList())
        val depth = head.copy(id="floor|푸시업|가슴의 충분한 이동",condition="가슴의 충분한 이동",
            feature="wrist_shoulder_d",baseFeature="wrist_shoulder_d",stat="rep",threshold=.7101f,
            kind="rep",repConfig=RepRuleConfig("min",.7101f,.34f,2))
        val hand = head.copy(id="floor|푸시업|손의 위치 가슴 중앙 여부",condition="손의 위치 가슴 중앙 여부",
            feature="shoulder_dev__mean",baseFeature="shoulder_dev",op="<",threshold=.535111f)
        val kneePushupHead = head.copy(id="floor|니푸쉬업|고개 젖힘/숙임 여부",exercise="니푸쉬업")
        val rules = PostureRuleSet("test","",listOf(head,depth,hand,kneePushupHead)).plusRepForm().rules
        assertEquals(RuleStatus.EXCLUDE,rules.first { it.id == head.id }.status)
        assertEquals(depth,rules.first { it.id == depth.id })
        assertEquals(hand,rules.first { it.id == hand.id })
        assertEquals(kneePushupHead,rules.first { it.id == kneePushupHead.id })
        val additions = rules.filter { it.exercise == exercise && it.kind == "rep_form" }
        assertEquals(14,additions.size)
        assertTrue(additions.all { it.status == RuleStatus.BETA })
    }

    @Test fun visibleSideCanShowReferenceWhenTheOldBilateralCoverageIsBlocked() {
        val xy = points(hipY=.46f)
        val vis = visibility().apply { for (i in listOf(8,12,24,26,28)) this[i] = .25f }
        val features = FloorFeatureExtractor().computeForExercise(exercise,xy,vis,1000,1000)
        val signal = RepSignals.byExercise.getValue(exercise).feature
        assertTrue(features[signal]?.isFinite() == true)
        assertTrue(features["${PushupGeometry.HIP}_L"]?.isFinite() == true)
        val legacy = RepFormSpecs.asRules().first { it.exercise == exercise }.copy(
            baseFeature="wrist_shoulder_d",feature="wrist_shoulder_d",kind="rep")
        val coverage = FloorCoverage.analyze(xy,vis,listOf(legacy))
        assertFalse(coverage.ok)
        assertTrue(RepFormSpecs.referenceObservable(exercise,features,signal,coverage.ok))
    }

    @Test fun referenceDisplayStillRequiresItsOwnGeometryAndCounterSignal() {
        val features = geometry() + ("wrist_shoulder_d" to 1.4f)
        assertFalse(RepFormSpecs.referenceObservable(exercise,mapOf("wrist_shoulder_d" to 1.4f),"wrist_shoulder_d",false))
        assertFalse(RepFormSpecs.referenceObservable(exercise,geometry(),"wrist_shoulder_d",false))
        assertFalse(RepFormSpecs.referenceObservable(exercise,features,null,false))
        assertFalse(RepFormSpecs.referenceObservable(exercise,features + ("wrist_shoulder_d" to Float.NaN),"wrist_shoulder_d",false))
        val standing = mapOf("forearm_vert_mean" to 160f,"elbow_L" to 160f)
        assertTrue(RepFormSpecs.referenceObservable("프런트 레이즈",standing,"forearm_vert_mean",true))
        assertFalse(RepFormSpecs.referenceObservable("프런트 레이즈",standing,"forearm_vert_mean",false))
        assertFalse(RepFormSpecs.referenceObservable("니푸쉬업",features,"wrist_shoulder_d",true))
    }
}

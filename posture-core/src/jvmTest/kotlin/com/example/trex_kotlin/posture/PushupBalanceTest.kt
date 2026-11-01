// Android 정본 테스트 — tools/sync_ios_core.py
package com.example.trex_kotlin.posture

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/** §131: 기하·유보·화면 참고 정책 회귀. 합성 좌표 시험이며 휴대폰 검출 정확도 검증이 아니다. */
class PushupBalanceTest {
    class Body(shoulderRoll: Float = 0f, pelvisRoll: Float = 0f) {
        val world = FloatArray(99)
        val xy = FloatArray(66) { .5f }
        val vis = FloatArray(33)
        var up = Vec3(0f,1f,0f)
        init {
            fun pair(left: Int, center: Vec3, length: Float, roll: Float) {
                val a = roll * .017453293f
                val axis = Vec3(.18f, sin(a)*.9836666f, cos(a)*.9836666f)
                put(left,center - axis*(length*.5f)); put(left+1,center + axis*(length*.5f))
            }
            pair(11,Vec3(-.35f,.15f,0f),.36f,shoulderRoll)
            pair(23,Vec3(.15f,.15f,0f),.28f,pelvisRoll)
            for (o in 0..1) {
                val dx = if (o == 0) -.045f else .045f
                put(13+o,Vec3(-.35f+dx,-.02f,0f)); put(15+o,Vec3(-.35f+dx,-.2f,0f))
                put(25+o,Vec3(.5f+dx,.15f,0f)); put(27+o,Vec3(.85f+dx,.15f,0f))
            }
        }
        private fun put(i: Int, p: Vec3) {
            world[i*3]=p.x; world[i*3+1]=-p.y; world[i*3+2]=-p.z
            xy[i*2]=.35f+p.x*.55f; xy[i*2+1]=.5f-p.y*.55f; vis[i]=1f
        }
        fun transform(f: (Vec3) -> Vec3) {
            for (i in 0..32) if (vis[i] > 0) put(i,f(Vec3(world[i*3],-world[i*3+1],-world[i*3+2])))
            up=f(up)
        }
        fun features(worldInput: FloatArray? = world, upInput: Vec3? = up, prone: Boolean = true) =
            PushupBalance.features(xy,vis,1000,1000,worldInput,upInput,prone)
    }

    @Test fun alignedShouldersAndPelvisProduceObservedZeroAngles() {
        val f=Body().features()
        assertEquals(PushupBalance.keys,f.keys)
        f.values.forEach { assertEquals(0f,it,.03f) }
    }

    @Test fun shoulderAndPelvisHeightDifferencesHaveSeparateObservations() {
        val shoulder=Body(35f,0f).features(); val pelvis=Body(0f,35f).features()
        assertTrue(shoulder.getValue(PushupBalance.SHOULDER)>20f)
        assertEquals(0f,shoulder.getValue(PushupBalance.PELVIS),.03f)
        assertEquals(0f,pelvis.getValue(PushupBalance.SHOULDER),.03f)
        assertTrue(pelvis.getValue(PushupBalance.PELVIS)>20f)
    }

    @Test fun relativeTwistIsDifferentFromRollingBothPairsTogether() {
        val together=Body(35f,35f).features(); val opposite=Body(20f,-20f).features()
        assertEquals(0f,together.getValue(PushupBalance.TWIST),.03f)
        assertTrue(together.getValue(PushupBalance.SHOULDER)>20f)
        assertEquals(40f,opposite.getValue(PushupBalance.TWIST),.03f)
    }

    @Test fun cameraRollAndMirroringPreserveTheAngles() {
        val b=Body(35f,-10f); val expected=b.features()
        val a=.35f
        b.transform { p -> Vec3(p.x*cos(a)-p.y*sin(a),p.x*sin(a)+p.y*cos(a),p.z) }
        for ((key,v) in expected) assertEquals(key,v,b.features().getValue(key),.03f)
        b.transform { p -> Vec3(-p.x,p.y,p.z) }
        for ((key,v) in expected) assertEquals(key,v,b.features().getValue(key),.03f)
    }

    @Test fun gravityWorldAndConfirmedPronePoseAreRequired() {
        val b=Body(35f,-10f)
        assertTrue(b.features(worldInput=null).isEmpty())
        assertTrue(b.features(upInput=null).isEmpty())
        assertTrue(b.features(upInput=Vec3(0f,0f,0f)).isEmpty())
        assertTrue(b.features(prone=false).isEmpty())
        assertTrue(b.features(worldInput=FloatArray(98)).isEmpty())
    }

    @Test fun everyPairedJointMustBeVisibleAndInsideTheImage() {
        for (i in listOf(11,12,23,24)) {
            val hidden=Body(35f,-10f); hidden.vis[i]=.79f
            assertTrue(hidden.features().isEmpty())
            val clipped=Body(35f,-10f); clipped.xy[i*2]=1.1f
            assertTrue(clipped.features().isEmpty())
        }
    }

    @Test fun imageOverlapAndTinyBodiesAbstainRatherThanUsePredictedDepth() {
        for (i in listOf(12,24)) {
            val b=Body(35f,-10f); b.xy[i*2]=b.xy[(i-1)*2]; b.xy[i*2+1]=b.xy[(i-1)*2+1]
            assertTrue(b.features().isEmpty())
        }
        val b=Body(); for (i in b.xy.indices) b.xy[i]=.5f+(b.xy[i]-.5f)*.05f
        assertTrue(b.features().isEmpty())
    }

    @Test fun invalidNumbersCollapsedAxesAndReversedLabelsAbstain() {
        for (bad in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            val b=Body(); b.world[33]=bad; assertTrue(b.features().isEmpty())
            val c=Body(); c.vis[11]=bad; assertTrue(c.features().isEmpty())
            assertTrue(Body().features(upInput=Vec3(bad,1f,0f)).isEmpty())
        }
        val collapsed=Body(); for (a in 0..2) collapsed.world[36+a]=collapsed.world[33+a]
        assertTrue(Body().features(upInput=Vec3(Float.MAX_VALUE,1f,0f)).isEmpty())
        assertTrue(collapsed.features().isEmpty())
        val reversed=Body(35f,-10f)
        for (a in 0..2) { val v=reversed.world[69+a]; reversed.world[69+a]=reversed.world[72+a]; reversed.world[72+a]=v }
        for (a in 0..1) { val v=reversed.xy[46+a]; reversed.xy[46+a]=reversed.xy[48+a]; reversed.xy[48+a]=v }
        assertTrue(reversed.features().isEmpty())
    }

    @Test fun imageAndWorldDisagreementOrUnobservableDepthAbstain() {
        val b=Body(35f,-10f)
        for (a in 0..1) { val v=b.xy[22+a]; b.xy[22+a]=b.xy[24+a]; b.xy[24+a]=v }
        assertTrue(b.features().isEmpty())
        val depth=Body()
        for (i in listOf(11,12)) depth.world[i*3]=-.35f
        assertTrue(depth.features().isEmpty())
    }

    @Test fun floorExtractorAddsReferencesOnlyToPushupsAndNeverInventsThemWithoutWorld() {
        val b=Body(30f,0f)
        fun extract(ex: String, world: FloatArray?) = FloorFeatureExtractor().computeForExercise(
            ex,b.xy,b.vis,1000,1000,b.up,0,world)
        val f=extract("푸시업",b.world)
        assertEquals(f.toString(),1f,f[FourExerciseForm.PUSHUP_POSTURE])
        assertTrue(f.keys.containsAll(PushupBalance.keys))
        assertTrue(extract("푸시업",null).keys.intersect(PushupBalance.keys).isEmpty())
        assertTrue(extract("플랭크",b.world).keys.intersect(PushupBalance.keys).isEmpty())
    }

    private fun good() = mapOf(FourExerciseForm.PUSHUP_POSTURE to 1f,FourExerciseForm.PUSHUP_HIP to 0f,
        FourExerciseForm.PUSHUP_KNEE to 175f,FourExerciseForm.PUSHUP_HEAD to 30f,FourExerciseForm.PUSHUP_NECK to 0f)

    @Test fun balanceWarningsAreAmberReferencesWithoutVoiceAndDisappearWhenUnobserved() {
        val ctrl=FloorFeedbackController("푸시업",emptyList())
        val f=good()+mapOf(PushupBalance.SHOULDER to 35f,PushupBalance.PELVIS to 35f,PushupBalance.TWIST to 45f)
        val s=ctrl.update(0,f,null,emptyList())
        assertEquals(FloorFeedbackPhase.ATTENTION,s.phase); assertNull(s.speech)
        for (label in listOf("어깨 기울어짐","골반 기울어짐","비틀림","의심","참고")) assertTrue(s.message.contains(label))
        assertEquals(setOf(11,12,23,24),s.landmarks)
        val missing=ctrl.update(300,good(),null,emptyList())
        assertEquals(FloorFeedbackPhase.MEASURING,missing.phase); assertTrue(missing.landmarks.isEmpty())
        assertTrue(missing.message.contains("기울기·비틀림은 확인하지 못했어요"))
    }

    @Test fun trackPauseAndUnconfirmedPostureDoNotShowSuspicionWarnings() {
        val ctrl=FloorFeedbackController("푸시업",emptyList()); val f=good()+mapOf(PushupBalance.TWIST to 45f)
        assertTrue(ctrl.update(0,f,null,emptyList(),mode=CoachMode.TRACK).landmarks.isEmpty())
        assertEquals(FloorFeedbackPhase.PAUSED,ctrl.update(300,f,null,emptyList(),paused=true).phase)
        val missing=ctrl.update(600,f-FourExerciseForm.PUSHUP_POSTURE,null,emptyList())
        assertTrue(missing.landmarks.isEmpty()); assertFalse(missing.message.contains("의심"))
    }

    private fun form(extra: Map<String,Float>): Pair<RepFormEvaluator,RepFormRep> {
        val ev=RepFormSpecs.evaluatorFor("푸시업",RepCounter.forSession("푸시업",floor=true)!!)!!
        val h=listOf(1f,1f,1f,.8f,.5f,.3f,.2f,.2f,.3f,.5f,.8f,1f,1f)
        val elbows=listOf(175f,175f,175f,160f,125f,100f,90f,90f,100f,125f,160f,175f,175f)
        h.forEachIndexed { i,v -> ev.onFrame(i*200L,good()+mapOf(FourExerciseForm.PUSHUP_SIGNAL to v,
            FourExerciseForm.PUSHUP_ELBOW to elbows[i])+extra) }
        return ev to ev.onCycle(2400L,.2f,1f)
    }

    @Test fun allNewRulesStayBetaAndDoNotChangeCountOrScore() {
        val (ev,rep)=form(PushupBalance.keys.associateWith { 40f })
        assertTrue(rep.correct); assertFalse(rep.judged); assertFalse(ev.summary().hasShip)
        assertEquals(3,rep.flagged.size)
        assertTrue(rep.flagged.all { it.check.status==RuleStatus.BETA && !it.check.gates && !it.countOnlyGate })
        val event=ev.eventFor(rep,2400,gate=true)!!
        assertFalse(event.gated); assertFalse(event.ship); assertTrue(event.message.contains("의심"))
        val highlights=RuleHighlight.forRepForm(rep.outcomes)
        assertTrue(highlights.first.isEmpty()); assertEquals(setOf(11,12,23,24),highlights.second)
    }

    @Test fun referenceWarningsCannotHideExistingCountExclusion() {
        val (ev,rep)=form(PushupBalance.keys.associateWith { 40f }+mapOf(FourExerciseForm.PUSHUP_KNEE to 110f))
        assertFalse(rep.correct); assertEquals(1,rep.flagged.count { it.countOnlyGate })
        val event=ev.eventFor(rep,2400,gate=true)!!
        assertTrue(event.gated); assertTrue(event.message.contains("세지 않았어요"))
        assertFalse(event.check.feature in PushupBalance.keys)
    }

    @Test fun missingBalanceIsAbstentionNotNormalAndBodyCountsContinue() {
        val (_,rep)=form(emptyMap())
        assertTrue(rep.correct)
        val balance=rep.outcomes.filter { it.check.feature in PushupBalance.keys }
        assertEquals(3,balance.size); assertTrue(balance.all { it.verdict==Verdict.ABSTAIN })
    }
}

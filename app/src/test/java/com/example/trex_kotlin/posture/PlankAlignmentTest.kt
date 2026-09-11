package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** 자세 조건에서 출발한 독립 실패 사례. 머리/골반만 바꾸고 나머지 관절은 고정한다. */
class PlankAlignmentTest {
    private val rules = javaClass.getResourceAsStream("/plank_alignment_limits.tsv")!!.bufferedReader().useLines { lines ->
        lines.map { line ->
            val (feature, lower, upper) = line.split('\t')
            PostureRule(feature,"플랭크",feature,null,RuleStatus.BETA,null,
                feature,feature,"alignment","floor2d","band",upper.toFloat(),"C","측면",Float.NaN,Float.NaN,0,true,emptyList(),
                kind="alignment",alignmentConfig=AlignmentConfig(lower.toFloat(),upper.toFloat()))
        }.toList()
    }
    private fun points(hipY: Float=.4f, noseX: Float=.18f,noseY: Float=.46f,earY: Float=.4f): FloatArray {
        val xy=FloatArray(66){.5f}
        fun p(i:Int,x:Float,y:Float){xy[2*i]=x;xy[2*i+1]=y}
        p(0,noseX,noseY);p(7,.18f,earY);p(8,.18f,earY+.02f)
        p(11,.25f,.4f);p(12,.25f,.42f);p(23,.5f,hipY);p(24,.5f,hipY+.02f)
        p(25,.68f,.4f);p(26,.68f,.42f);p(27,.85f,.4f);p(28,.85f,.42f)
        return xy
    }
    private fun visibility()=FloatArray(33){if(it in setOf(8,12,24,26,28)) .6f else .95f}
    private fun f(xy:FloatArray=points(),v:FloatArray=visibility())=PlankGeometry.features(xy,v,1000,1000)
    private fun feed(tracker:PlankAlignmentTracker,features:Map<String,Float>,start:Long=0,end:Long=2000):AlignmentSnapshot {
        for(t in start..end step 250) tracker.add(t,features)
        return tracker.snapshot
    }

    @Test fun straightPoseMeasuresBothHeadAndBodyWithoutPersonalBaseline() {
        val x=f(); assertEquals(0f,x.getValue(PlankGeometry.HIP),.001f)
        assertEquals(0f,x.getValue(PlankGeometry.HEAD),.01f);assertEquals(0f,x.getValue(PlankGeometry.NECK),.01f)
        assertTrue(feed(PlankAlignmentTracker(rules),x).referenceEligible)
    }
    @Test fun headLiftAloneTriggersHeadAndKeepsHipInside() {
        val s=feed(PlankAlignmentTracker(rules),f(points(noseX=.12f,noseY=.43f)))
        assertEquals(Verdict.VIOLATION,s.items[1].verdict);assertEquals(1,s.items[1].side)
        assertEquals(Verdict.OK,s.items[0].verdict);assertFalse(s.referenceEligible)
    }
    @Test fun headTuckHasOppositeDirection() {
        val s=feed(PlankAlignmentTracker(rules),f(points(noseX=.23f,noseY=.43f)))
        assertEquals(Verdict.VIOLATION,s.items[1].verdict);assertEquals(-1,s.items[1].side)
    }
    @Test fun neckCanRiseEvenWhenFaceStillPointsDown() {
        val s=feed(PlankAlignmentTracker(rules),f(points(earY=.31f,noseY=.37f)))
        assertEquals(Verdict.OK,s.items[1].verdict)
        assertEquals(Verdict.VIOLATION,s.items[2].verdict)
    }
    @Test fun initiallySaggingHipsAreAnIssueWithoutWaitingFiveSeconds() {
        val s=feed(PlankAlignmentTracker(rules),f(points(hipY=.46f)),end=1000)
        assertEquals(Verdict.VIOLATION,s.items[0].verdict);assertEquals(-1,s.items[0].side)
    }
    @Test fun initiallyRaisedHipsHaveTheOppositeCorrection() {
        val s=feed(PlankAlignmentTracker(rules),f(points(hipY=.30f)))
        assertEquals(Verdict.VIOLATION,s.items[0].verdict);assertEquals(1,s.items[0].side)
    }
    @Test fun straighteningBadStartIsRecoveryNotAnotherFault() {
        val t=PlankAlignmentTracker(rules);feed(t,f(points(hipY=.46f)))
        val s=feed(t,f(),2250,3250)
        assertTrue(s.items[0].recovered);assertEquals(Verdict.OK,s.items[0].verdict)
        assertEquals(Verdict.VIOLATION,t.results()[0].verdict) // 세트 이력은 회복해도 보존
    }
    @Test fun briefExcursionIsNotSpokenAndDoesNotBecomeARecordedFault() {
        val t=PlankAlignmentTracker(rules);feed(t,f())
        feed(t,f(points(hipY=.46f)),2250,2500);feed(t,f(),2750,4000)
        assertEquals(Verdict.OK,t.results()[0].verdict)
    }
    @Test fun missingHeadDoesNotDisableHipAssessment() {
        val v=visibility().apply { this[0]=0f;this[7]=0f;this[8]=0f }
        val s=feed(PlankAlignmentTracker(rules),f(points(hipY=.46f),v))
        assertEquals(Verdict.VIOLATION,s.items[0].verdict);assertEquals(Verdict.ABSTAIN,s.items[1].verdict)
    }
    @Test fun farLimbOcclusionUsesVisibleSideInsteadOfAveragingWrongCoordinates() {
        val v=visibility().apply { for(i in listOf(8,12,24,26,28)) this[i]=.25f }
        assertEquals(1f,f(points(),v).getValue(PlankGeometry.READY),0f)
    }
    @Test fun noVisibleBodyCannotBecomeAnOk() {
        val t=PlankAlignmentTracker(rules);feed(t,f(v=FloatArray(33)))
        assertTrue(t.results().all{it.verdict==Verdict.ABSTAIN})
    }
    @Test fun frontalProjectionAndStandingAreNotAcceptedAsPlank() {
        val frontal=points().apply {this[24]=.5f}
        assertEquals(0f,f(frontal).getValue(PlankGeometry.READY),0f)
        val standing=points().let { src -> FloatArray(66){i->if(i%2==0)src[i+1] else src[i-1]} }
        assertNotEquals(1f,f(standing)[PlankGeometry.READY])
    }
    @Test fun mirroringAndModerateCameraRollKeepHeadDirection() {
        val xy=points(noseX=.12f,noseY=.43f);val mirrored=xy.copyOf()
        for(i in 0..32) mirrored[i*2]=1-xy[i*2]
        assertEquals(f(xy).getValue(PlankGeometry.HEAD),f(mirrored).getValue(PlankGeometry.HEAD),.01f)
        val rotated=xy.copyOf();val a=Math.toRadians(15.0)
        for(i in 0..32){ val x=xy[i*2]-.5;val y=xy[i*2+1]-.5
            rotated[i*2]=(.5+x*cos(a)-y*sin(a)).toFloat();rotated[i*2+1]=(.5+x*sin(a)+y*cos(a)).toFloat() }
        assertEquals(f(xy).getValue(PlankGeometry.HEAD),f(rotated).getValue(PlankGeometry.HEAD),.01f)
    }
    @Test fun gapDoesNotJoinTwoShortViolationsOrClaimRecovery() {
        val t=PlankAlignmentTracker(rules);feed(t,f(points(hipY=.46f)),0,500)
        feed(t,f(points(hipY=.46f)),2000,2500)
        assertEquals(Verdict.ABSTAIN,t.results()[0].verdict)
        t.add(2750,emptyMap());assertFalse(t.snapshot.items.any{it.recovered})
    }
    @Test fun coachingFeedbackWorksBeforeTheOldAnchorAndHonorsMute() {
        val t=PlankAlignmentTracker(rules);val x=f(points(noseX=.12f,noseY=.43f));val a=feed(t,x)
        val feedback=FloorFeedbackController("플랭크",rules)
        val muted=feedback.update(2000,x,null,emptyList(),anchored=false,voiceEnabled=false,alignment=a)
        assertEquals(FloorFeedbackPhase.ATTENTION,muted.phase);assertNull(muted.speech)
        val voiced=feedback.update(2250,x,null,emptyList(),anchored=false,alignment=a)
        assertTrue(voiced.speech!!.contains("고개"));assertTrue(voiced.landmarks.contains(0))
    }
    @Test fun invalidInitialPostureIsObservedWithoutBecomingCorrect() {
        val t=PostureComparisonTracker("플랭크",ComparisonMetrics.forExercise("플랭크",rules))
        for(ms in 0L..10000L step 250) t.add(ms,f(points(hipY=.46f)),referenceEligible=false)
        assertEquals(ComparisonState.MEASURING,t.snapshot.state)
        assertTrue(t.snapshot.values.any { it.metric.feature==PlankGeometry.HIP })
        assertEquals(Verdict.VIOLATION,feed(PlankAlignmentTracker(rules),f(points(hipY=.46f))).items.first().verdict)
        for(ms in 10250L..17000L step 250)t.add(ms,f(),referenceEligible=true)
        assertTrue(t.snapshot.values.any { it.metric.feature==PlankGeometry.HIP && it.changed })
        for(ms in 17250L..21000L step 250)t.add(ms,f(points(noseX=.12f,noseY=.43f)),referenceEligible=false)
        assertTrue(t.snapshot.values.any{it.metric.feature==PlankGeometry.HEAD && it.changed})
    }
    @Test fun trackDoesNotSpeakOrHighlightAbsoluteCorrections() {
        val a=feed(PlankAlignmentTracker(rules),f(points(hipY=.46f)))
        val feedback=FloorFeedbackController("플랭크",rules).update(2000,f(),null,emptyList(),
            mode=CoachMode.TRACK,voiceEnabled=true,alignment=a)
        assertNull(feedback.speech);assertTrue(feedback.landmarks.isEmpty())
    }
    @Test fun finalAssessmentSharesAbsoluteRulesAndDoesNotNeedInitialAnchor() {
        val x=f(points(hipY=.46f));val samples=List(10){PoseSample(true,points(),visibility(),x,33,1,1000,1000)}
        val out=PostureAssessment.evaluate(PostureRuleSet("test","",rules),"플랭크",samples,List(10){it*250L},Long.MAX_VALUE,2250,emptyList())
        assertEquals(Verdict.VIOLATION,out.first().verdict)
        val window=FeatureAggregator();repeat(10){window.add(x)}
        assertTrue(PostureRuleSet("test","",rules).evaluate("플랭크",window).all{it.verdict==Verdict.ABSTAIN})
    }

    @Test fun cachedAndroidLandmarksPassThroughTheSameGeometryAndFinalEvaluator() {
        var id=""; var t=PlankAlignmentTracker(rules);var count=0;var clips=0
        val samples=ArrayList<PoseSample>();val times=ArrayList<Long>()
        fun check(){if(id.isEmpty())return
            val live=t.results();val final=PostureAssessment.evaluate(PostureRuleSet("test","",rules),"플랭크",samples,times,Long.MAX_VALUE,times.last(),emptyList())
            assertEquals(live.map{it.verdict},final.map{it.verdict})
            println("PLANK_REPLAY $id " + live.joinToString { "${it.rule.baseFeature}=${it.verdict}(n=${it.sampleCount})" });clips++ }
        javaClass.getResourceAsStream("/plank_replay_fixture.tsv")!!.bufferedReader().useLines { lines -> lines.forEach { line ->
            val c=line.split('\t')
            if(c[0]=="CASE"){check();id=c.drop(1).joinToString("/");t=PlankAlignmentTracker(rules);samples.clear();times.clear()}
            else {val ms=c[1].toLong();val w=c[2].toInt();val h=c[3].toInt();val xy=c[4].split(',').map{it.toFloat()}.toFloatArray();val vis=c[5].split(',').map{it.toFloat()}.toFloatArray()
                val features=PlankGeometry.features(xy,vis,w,h)
                val expected=c[6].split(';').filter { it.isNotEmpty() }.associate { field ->
                    val (key,value)=field.split('=');key to value.toFloat()
                }
                val actual=features.filterKeys { it in setOf(PlankGeometry.HIP,PlankGeometry.HEAD,PlankGeometry.NECK) }
                assertEquals("$id/$ms",expected.keys,actual.keys)
                expected.forEach { (key,value) -> assertEquals("$id/$ms/$key",value,actual.getValue(key),.01f) }
                assertTrue(features.values.all{it.isFinite()});t.add(ms,features)
                samples+=PoseSample(true,xy,vis,features,33,1,w,h);times+=ms;count++}
        } }
        check();assertEquals(320,count);assertEquals(20,clips)
    }
}

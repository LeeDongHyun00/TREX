package com.example.trex_kotlin.posture

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PersonalizationTest {
    @Test fun everyCatalogExerciseHasOneExplicitProfile() {
        val source=File("src/main/java/com/example/trex_kotlin/Sheets.kt").readText()
        val names=Regex("WorkoutTemplate\\(\"([^\"]+)\"").findAll(source).map { it.groupValues[1] }.toSet()
        assertEquals(26,names.size)
        assertEquals(names,ExerciseProfiles.all.map { it.name }.toSet())
        assertEquals(26,ExerciseProfiles.all.size)
        assertEquals(26,ExerciseProfiles.all.count { it.referenceExercise != null })
        assertEquals(0,ExerciseProfiles.all.count { it.kind == ObservationKind.GUIDE })
        assertTrue(ExerciseProfiles.all.filter { it.cameraEnabled }.all { it.metricFeatures.isNotEmpty() && it.capture.voice.isNotBlank() })
    }

    @Test fun guidedBundlesDoNotBorrowUnrelatedRulesAndVariantsStaySeparate() {
        assertNull(ExerciseProfiles.forName("마무리 스트레칭"))
        assertNull(ExerciseProfiles.forName("푸쉬업 입문"))
        assertNull(ExerciseProfiles.forName("벽 푸쉬업"))
        assertNull(ExerciseProfiles.forName("기본 스쿼트"))
        assertEquals(CapturePosition.RIGHT_FRONT,ExerciseProfiles.forName("런지")!!.capture)
        assertEquals(CapturePosition.FLOOR_SIDE,ExerciseProfiles.forName("플랭크")!!.capture)
    }

    @Test fun hiddenHeadDoesNotBlockHipReferenceAndCanJoinLater() {
        val hip=ComparisonMetric(PlankGeometry.HIP,"골반","비율",.06f)
        val head=ComparisonMetric(PlankGeometry.HEAD,"고개","°",8f)
        val t=PostureComparisonTracker("플랭크",listOf(hip,head))
        for(ms in 0L..8000L step 250) t.add(ms,mapOf(hip.feature to .2f),referenceEligible=false)
        assertEquals(.2f,t.snapshot.values.single().initial,.001f)
        for(ms in 8250L..16000L step 250) t.add(ms,mapOf(hip.feature to .2f,head.feature to 35f))
        assertEquals(setOf(hip.feature,head.feature),t.snapshot.values.map { it.metric.feature }.toSet())
        assertTrue(t.snapshot.values.all { !it.changed })
    }

    @Test fun stableHipCanBeCollectedWhileHeadMoves() {
        val metrics=ExerciseProfiles.metrics(ExerciseProfiles.forName("플랭크")!!)
        val t=PostureComparisonTracker("플랭크",metrics)
        for(ms in 0L..10000L step 250) t.add(ms,mapOf(PlankGeometry.HIP to .1f,PlankGeometry.HEAD to if(ms%500==0L)0f else 40f))
        assertEquals(listOf(PlankGeometry.HIP),t.snapshot.values.map { it.metric.feature })
    }

    @Test fun observationWindowsKeepLeftAndRightIndependentWithoutClaimingRepPhases() {
        val metrics=listOf(ComparisonMetric("knee_L","왼쪽 무릎각","°",8f),ComparisonMetric("knee_R","오른쪽 무릎각","°",8f))
        val t=PostureComparisonTracker("test",metrics,signal=null,observationKind=ObservationKind.WINDOW)
        for(ms in 0L..13000L step 250) t.add(ms,mapOf("knee_L" to if(ms%1000<500)30f else 100f,"knee_R" to if(ms%1000<500)50f else 90f))
        assertEquals(70f,t.snapshot.values.first { it.metric.feature=="knee_L" }.initial,.01f)
        assertEquals(40f,t.snapshot.values.first { it.metric.feature=="knee_R" }.initial,.01f)
        assertTrue(t.snapshot.values.all { it.phase==ComparisonPhase.WINDOW_RANGE })
        for(ms in 13250L..23000L step 250) t.add(ms,mapOf("knee_L" to if(ms%1000<500)60f else 90f,"knee_R" to if(ms%1000<500)50f else 90f))
        assertTrue(t.snapshot.values.first { it.metric.feature=="knee_L" }.changed)
        assertFalse(t.snapshot.values.first { it.metric.feature=="knee_R" }.changed)
    }

    @Test fun lateMetricReferenceIsNotPermanentlyExcluded() {
        val a=ComparisonMetric("a","A","비율",.06f);val b=ComparisonMetric("b","B","비율",.06f)
        val t=PostureComparisonTracker("test",listOf(a,b),signal=null)
        for(ms in 0L..13000L step 250) t.add(ms,mapOf("a" to if(ms%500==0L)1f else 0f))
        for(ms in 13250L..29000L step 250) t.add(ms,mapOf("a" to if(ms%500==0L)1f else 0f,"b" to if(ms%500==0L)2f else 0f))
        assertEquals(2,t.snapshot.values.size)
    }

    @Test fun resetPreservesChangeHistoryWithNewEpoch() {
        val t=PostureComparisonTracker("test",listOf(ComparisonMetric("x","X","°",8f)),signal=null,observationKind=ObservationKind.HOLD)
        for(ms in 0L..8000L step 250)t.add(ms,mapOf("x" to 0f))
        for(ms in 8250L..12000L step 250)t.add(ms,mapOf("x" to 30f))
        val before=t.report(12000)
        assertFalse(before.isEmpty());t.reset()
        assertEquals(1,t.referenceEpoch);assertEquals(before,t.report(12000))
        assertTrue(t.snapshot.values.isEmpty())
    }

    @Test fun singleVisibleSideProducesFeaturesAndOnlyHighlightsThatSide() {
        val xy=FloatArray(66);val vis=FloatArray(33)
        fun point(i:Int,x:Float,y:Float) { xy[i*2]=x;xy[i*2+1]=y;vis[i]=1f }
        point(11,.2f,.3f);point(13,.2f,.5f);point(15,.3f,.6f);point(23,.5f,.3f);point(25,.7f,.4f);point(27,.9f,.5f)
        val f=FloorFeatureExtractor().sideFeatures(xy,vis,1000,1000)
        assertTrue(f["hip_dev_ankle_L"]!!.isFinite());assertTrue(f["elbow_ang_L"]!!.isFinite())
        assertFalse(f.containsKey("hip_dev_ankle_R"))
        assertTrue(RuleHighlight.landmarksFor("hip_dev_ankle_L").all { it%2==1 })
        assertEquals(f,FloorFeatureExtractor().computeForExercise("벽 푸쉬업",xy,vis,1000,1000))
    }

    @Test fun sameCauseIsNotRepeatedEveryEightSeconds() {
        val voice=ComparisonSpeech();val metric=ComparisonMetric("x","X","°",8f)
        val snapshot=ComparisonSnapshot(ComparisonState.CHANGED,values=listOf(ComparisonValue(metric,ComparisonPhase.HOLD,0f,20f,8f,true)),revision=1)
        assertNotNull(voice.next(0,snapshot,true));assertNull(voice.next(8000,snapshot.copy(revision=2),true))
        assertNotNull(voice.next(20000,snapshot.copy(revision=3),true))
    }
}

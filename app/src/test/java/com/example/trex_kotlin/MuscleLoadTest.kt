package com.example.trex_kotlin

import com.example.trex_kotlin.trainingload.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MuscleLoadTest {
    private val now=1_790_000_000_000L
    private fun set(id:String="a",exercise:String="squat",reps:Double=12.0)=LoadSet(id,"session",exercise,now,reps=reps)
    private fun dose(s:LoadSet,m:Muscle=Muscle.QUADS,side:String="L")=MuscleLoadEngine.doses(s).first { it.muscle==m && it.side==side }.dose

    @Test fun everyActualCatalogExerciseHasExactlyOneResearchedProfile() {
        val expected=workoutCatalog.values.flatten().map { it.name }.toSet()
        assertEquals(26,expected.size)
        assertEquals(expected,MuscleLoadCatalog.all.map { it.name }.toSet())
        assertEquals(26,MuscleLoadCatalog.all.map { it.id }.toSet().size)
        assertTrue(MuscleLoadCatalog.all.all { it.sources.isNotEmpty() && it.primary.isNotEmpty() })
    }
    @Test fun threeSetsAccumulateAndDecayWithoutInventingRecovery() {
        val sets=(1..3).map { set(it.toString()) }
        val first=MuscleLoadEngine.snapshot(sets,now).muscles.first { it.muscle==Muscle.QUADS }
        val later=MuscleLoadEngine.snapshot(sets,now+24*3_600_000L).muscles.first { it.muscle==Muscle.QUADS }
        assertEquals(39.3469,first.value,.001)
        assertTrue(later.value in 15.0..17.0)
        assertTrue(MuscleLoadEngine.snapshot(sets,now).muscles.first { it.muscle==Muscle.BICEPS }.known.not())
    }
    @Test fun sameSetIsIdempotentButIndependentSessionsAdd() {
        val s=set()
        assertEquals(MuscleLoadEngine.snapshot(listOf(s),now),MuscleLoadEngine.snapshot(listOf(s,s),now))
        assertTrue(MuscleLoadEngine.snapshot(listOf(s,s.copy(id="b",sessionId="next")),now).peak!!.value>MuscleLoadEngine.snapshot(listOf(s),now).peak!!.value)
    }
    @Test fun lungePairIsOneDosePerSideNotTwoPerSide() {
        val s=set(exercise="lunge").copy(unit=LoadUnit.PAIR)
        assertEquals(1.0,dose(s),.00001)
        val uneven=s.copy(left=12.0,right=6.0)
        assertEquals(1.0,dose(uneven),.00001);assertEquals(.5,dose(uneven,side="R"),.00001)
    }
    @Test fun userCorrectionOverridesSideObservationsAndCanBeCleared() {
        val s=set(exercise="lunge").copy(unit=LoadUnit.PAIR,left=12.0,right=6.0)
        assertEquals(.25,dose(s.copy(actualReps=3.0)),.00001)
        assertEquals(.25,dose(s.copy(actualReps=3.0),side="R"),.00001)
        assertEquals(0.0,dose(s.copy(actualReps=0.0)),.00001)
        assertEquals(1.0,dose(s.copy(actualReps=null)),.00001)
    }
    @Test fun uncertainLungeSidesAreNotDoubleCountedAndExtraStepsRemainLoad() {
        val s=set(exercise="lunge").copy(unit=LoadUnit.PAIR,left=12.0,right=12.0,unknownSideSteps=6)
        assertEquals(1.0,dose(s),.00001)
        assertEquals(1.25,dose(s.copy(extraSteps=6)),.00001)
    }
    @Test fun timedWorkUsesActualSecondsAndNeverTargetReps() {
        val s=set(exercise="plank",reps=999.0).copy(unit=LoadUnit.SECONDS,seconds=22.5)
        assertEquals(.5,dose(s,Muscle.ABS),.00001)
        assertTrue(MuscleLoadEngine.doses(s.copy(seconds=0.0)).isEmpty())
    }
    @Test fun unknownAlternatingCyclesAreSharedAcrossSides() {
        val s=set(exercise="knee_up").copy(unit=LoadUnit.EACH)
        assertEquals(.5,dose(s,Muscle.HIP_FLEXORS),.00001)
    }
    @Test fun accuracyAndAbstentionNeverReduceTrainingLoad() {
        val s=set();assertEquals(MuscleLoadEngine.doses(s),MuscleLoadEngine.doses(s.copy(accuracy=0,judged=0,abstained=20)))
    }
    @Test fun bodyCorrectionIsBoundedAndDoesNotInventDumbbellWeight() {
        assertEquals(1.15,dose(set().copy(weightKg=300.0,heightCm=240.0)),.00001)
        assertEquals(MuscleLoadEngine.doses(set(exercise="dumbbell_curl")),MuscleLoadEngine.doses(set(exercise="dumbbell_curl").copy(weightKg=120.0,heightCm=190.0)))
        assertTrue(MuscleLoadEngine.doses(set().copy(weightKg=Double.NaN)).all { it.dose.isFinite() })
    }
    @Test fun futureAndExpiredEventsDoNotProduceNegativeDecayOrFalseHistory() {
        val snapshot=MuscleLoadEngine.snapshot(listOf(set().copy(endedAt=now+1000),set("old").copy(endedAt=now-MuscleLoadEngine.RETENTION_MS-1)),now)
        assertTrue(snapshot.sets.isEmpty());assertNull(snapshot.peak)
    }
    @Test fun recommendationsRespectEquipmentAndDoNotPresentUnknownAsRecovery() {
        assertTrue(MuscleLoadEngine.recommendations(LoadSnapshot(),Equipment.entries.toSet()).isEmpty())
        val snapshot=MuscleLoadEngine.snapshot((1..12).map { set(it.toString()) },now)
        val candidates=MuscleLoadEngine.recommendations(snapshot,emptySet())
        assertTrue(candidates.isNotEmpty());assertTrue(candidates.all { it.equipment==Equipment.NONE })
        assertTrue(candidates.none { Muscle.QUADS in it.primary })
    }
    @Test fun sameDayHistoryAppendsAndRepeatedSaveReplacesOnlyItsSet() {
        val w=Workout("s","기본 스쿼트","12회 × 1세트","",false,"하체")
        fun day(s:LoadSet)=createWorkoutHistoryDay(listOf(w),30).let { it.copy(items=it.items.map { item -> item.copy(loadSet=s) }) }
        val a=day(set());val b=day(set("b"))
        val merged=listOf(a).mergeSession(b).mergeSession(b)
        assertEquals(2,merged.single().items.size)
        assertEquals(setOf("a","b"),merged.single().items.map { it.loadSet!!.id }.toSet())
    }
    @Test fun partialTimedSetIsRecordedWithoutClaimingGoalCompletion() {
        val w=Workout("p","플랭크","45초 × 1세트","",false,"코어")
        val steps=buildSessionSteps(listOf(w));val work=steps.first { it.phase==SessionPhase.WORK }
        val progress=SessionProgress(work.token,30_000,workMillis=mapOf(work.token to 15_000))
        assertFalse(progress.completedWorkouts(steps).single().done)
        assertEquals(15,progress.workDurations(steps)[work.workout.id])
    }
    @Test fun legacyMigrationPreservesDateUncertaintyAndNeverUsesPlankTargetTime() {
        val day=createWorkoutHistoryDay(listOf(Workout("p","플랭크","45초 × 3세트","",false,"코어")),0)
        val imported=legacyLoadSets(listOf(day),UserProfile(),System.currentTimeMillis())
        assertEquals(3,imported.size);assertTrue(imported.all { it.source=="legacy" && it.timeUncertaintyMs>0 && it.seconds==0.0 })
        assertEquals(imported.map { it.id },legacyLoadSets(listOf(day),UserProfile(),System.currentTimeMillis()).map { it.id })
    }
    @Test fun equipmentBitsMatchOnboardingAndBodyweightPreferenceWins() {
        assertEquals(setOf(Equipment.DUMBBELL,Equipment.LAT_MACHINE,Equipment.DIP_BAR),UserProfile(equipmentMask=(1 shl 0) or (1 shl 8) or (1 shl 20)).loadEquipment())
        assertTrue(UserProfile(equipmentMask=-1,bodyweightOnly=true).loadEquipment().isEmpty())
    }
    @Test fun halfLungeWithZeroDisplayPairsStillEntersLedger() {
        val w=Workout("l","런지","10회 × 1세트","",true,"하체")
        val steps=buildSessionSteps(listOf(w));val work=steps.first { it.phase==SessionPhase.WORK }
        val report=com.example.trex_kotlin.posture.PostureSetReport.build("set","런지","런지",
            com.example.trex_kotlin.posture.CoachMode.COACH,20,false,emptyList(),emptyList(),null,null,null).copy(
            repSides=com.example.trex_kotlin.posture.SideTallies(10,
                com.example.trex_kotlin.posture.SideTally(7,0,2,0,0),com.example.trex_kotlin.posture.SideTally(0,0,0,0,7)))
        val progress=SessionProgress(work.token,30_000)
        val partial=progress.loadRecordableWorkouts(steps,mapOf(work.workout.id to report),setOf(work.token)).single()
        assertFalse(partial.done)
        val load=workoutLoadSet("test",partial,now,30,UserProfile(weightKg=70.0),report)!!
        assertEquals(7.0/12,dose(load),.00001);assertEquals(0.0,dose(load,side="R"),.00001)
        assertEquals(2,load.unknownSideSteps)
    }
    @Test fun rawAndPersistedDosePathsAgreeAndZeroTimeGivesNoRecommendation() {
        val s=set();val direct=MuscleLoadEngine.snapshot(listOf(s),now+3_600_000)
        assertEquals(direct,MuscleLoadEngine.snapshot(listOf(s),now+3_600_000,mapOf(s.id to MuscleLoadEngine.doses(s))))
        val zero=MuscleLoadEngine.snapshot(listOf(set(exercise="plank").copy(unit=LoadUnit.SECONDS)),now)
        assertNull(zero.peak);assertTrue(MuscleLoadEngine.recommendations(zero,emptySet()).isEmpty())
    }
}

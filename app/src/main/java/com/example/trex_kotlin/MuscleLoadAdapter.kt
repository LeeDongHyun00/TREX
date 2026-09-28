package com.example.trex_kotlin

import com.example.trex_kotlin.trainingload.*
import com.example.trex_kotlin.posture.PostureSetReport
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal fun workoutLoadSet(sessionId: String, workout: Workout, endedAt: Long, seconds: Int,
    profile: UserProfile, report: PostureSetReport?): LoadSet? {
    val p = MuscleLoadCatalog.find(workout.name) ?: return null
    val sides = report?.repSides?.track
    val count = Regex("\\d+").find(workout.reps)?.value?.toDoubleOrNull() ?: 0.0
    // COACH에서 동작 오류로 막힌 걸음도 몸을 움직인 부하다. TRACK 관측을 쓰며 정확도를 곱하지 않는다.
    val left = sides?.left?.toDouble()
    val right = sides?.right?.toDouble()
    return LoadSet(id="$sessionId:${workout.id}", sessionId=sessionId, exerciseId=p.id, endedAt=endedAt,
        reps=if(p.unit == LoadUnit.SECONDS) 0.0 else count + (if(sides==null && report?.repHalfPending==true && p.unit==LoadUnit.PAIR) .5 else 0.0),
        seconds=seconds.coerceAtLeast(0).toDouble(), unit=p.unit, left=left, right=right,
        weightKg=profile.weightKg, heightCm=profile.heightCm,
        source=if(sides != null) (if(sides.unknown > 0 || sides.extra > 0) "track_sides_mixed" else "track_sides") else "session_count",
        postureSetId=report?.setId, judged=report?.shipJudged, abstained=report?.abstained, accuracy=report?.accuracy,
        unknownSideSteps=sides?.unknown ?: 0, extraSteps=sides?.extra ?: 0)
}

/** 표시 횟수가 0쌍이어도 한쪽 걸음은 부분 수행으로 기록한다. 자세 성공 여부와 구분한다. */
internal fun SessionProgress.loadRecordableWorkouts(steps: List<SessionStep>, reports: Map<String,PostureSetReport>, captured: Set<Int>): List<Workout> {
    val base=completedWorkouts(steps).associateBy { it.id }
    return steps.filter { it.phase==SessionPhase.WORK && it.token in captured }.mapNotNull { step ->
        base[step.workout.id] ?: reports[step.workout.id]?.let { report ->
            val tally=report.repSides?.track
            if((tally?.let { it.steps+it.extra } ?: 0)>0 || report.repHalfPending)
                step.workout.copy(done=false,reps="0회 × 1세트") else null
        }
    }
}

/** 날짜만 있는 구기록은 정오 실측으로 위장하지 않는다. 하루 범위의 중간값과 오차 범위를 함께 보존한다. */
internal fun legacyLoadSets(history: List<WorkoutHistoryDay>, profile: UserProfile, now: Long): List<LoadSet> = history.flatMap { day ->
    val zone = ZoneId.systemDefault()
    val start = LocalDate.ofEpochDay(day.epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = minOf(LocalDate.ofEpochDay(day.epochDay + 1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
    if(end < start) return@flatMap emptyList()
    day.items.flatMapIndexed { index, item ->
        if(item.loadSet != null) return@flatMapIndexed emptyList()
        val p = MuscleLoadCatalog.find(item.workoutName) ?: return@flatMapIndexed emptyList()
        val spec = Workout("legacy",item.workoutName,item.reps,"",false, item.category ?: "").repsSpec()
        val n = spec.sets.coerceIn(1,99)
        val pc = item.postureCorrection
        (0 until n).map { set ->
            val key = "legacy:${day.epochDay}:$index:${item.workoutName}:$set"
            LoadSet(UUID.nameUUIDFromBytes(key.toByteArray()).toString(), "legacy:${day.epochDay}",p.id,(start+end)/2,
                timeUncertaintyMs=(end-start)/2, reps=if(p.unit==LoadUnit.SECONDS) 0.0 else spec.count.toDouble(),
                // 구기록은 저장된 수행 시간만 배분하고 목표 시간을 완수했다고 가정하지 않는다.
                seconds=(item.durationSeconds ?: (item.durationMinutes * 60)).coerceAtLeast(0).toDouble()/n,
                unit=p.unit, weightKg=profile.weightKg,heightCm=profile.heightCm,source="legacy",
                postureSetId=pc?.setId, actualReps=pc?.actualReps?.toDouble(),judged=pc?.judged,abstained=pc?.abstained,accuracy=item.accuracy)
        }
    }
}

internal fun UserProfile.loadEquipment(): Set<Equipment> {
    if(bodyweightOnly) return emptySet()
    fun has(bit: Int) = equipmentMask and (1 shl bit) != 0
    return buildSet {
        if(has(0)) add(Equipment.DUMBBELL)
        if(has(1)) add(Equipment.BARBELL)
        if(has(8)) add(Equipment.LAT_MACHINE)
        if(has(20)) add(Equipment.DIP_BAR)
        if(has(21)) add(Equipment.PULLUP_BAR)
        if(has(16) || has(17)) add(Equipment.BENCH)
    }
}

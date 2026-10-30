package com.example.trex_kotlin.routines

import com.example.trex_kotlin.Workout
import com.example.trex_kotlin.WorkoutAlt
import com.example.trex_kotlin.WorkoutNames
import com.example.trex_kotlin.WorkoutTarget
import com.example.trex_kotlin.postureExerciseMap
import com.example.trex_kotlin.resolvedTarget
import com.example.trex_kotlin.timeOnlyNormalized
import org.json.JSONArray
import org.json.JSONObject

/*
 * 운동 한 개 ↔ JSON(docs/superpowers/specs/2026-10-29-routine-share-design.md §4 "운동 목록 JSON") — 오늘 루틴 저장(TrexStore plan),
 * 내 루틴 보관함, 메신저로 보내는 공유 루틴이 같은 칸을 쓴다. 기기 전용 칸(id·done)은 공유·보관함에서 뺀다.
 */

/** [withDeviceFields] 면 `id`·`done` 도 넣는다(오늘 루틴 저장). */
internal fun workoutJson(w: Workout, withDeviceFields: Boolean): JSONObject {
    val o = JSONObject()
    if (withDeviceFields) o.put("id", w.id)
    o.put("name", w.name)
        .put("reps", w.reps)
        .put("duration", w.duration)
        .put("posture", w.posture)
        .put("category", w.category)
    if (withDeviceFields) o.put("done", w.done)
    w.alt?.let { o.put("alt", JSONObject().put("name", it.name).put("reps", it.reps)) }
    w.secondsPerRep?.let { o.put("secondsPerRep", it) }
    w.restSeconds?.let { o.put("restSeconds", it) }
    w.resolvedTarget().let { goal ->
        o.put("targetKind", if (goal is WorkoutTarget.Duration) "duration" else "repetitions")
        o.put("targetAmount", goal.amount)
    }
    return o
}

/** JSON → 운동. 필수 칸(name·reps·duration·category)이 없으면 던진다. 개명된 종목은 새 이름으로, 시간 전용 종목은 시간 목표로(spec §108g). */
internal fun workoutFromJson(o: JSONObject, id: String, done: Boolean, posture: Boolean = o.optBoolean("posture", false)): Workout = Workout(
    id = id,
    name = WorkoutNames.canonical(o.getString("name")),
    reps = o.getString("reps"),
    duration = o.getString("duration"),
    posture = posture,
    category = o.getString("category"),
    alt = o.optJSONObject("alt")?.let { a -> WorkoutAlt(a.getString("name"), a.getString("reps")) },
    done = done,
    secondsPerRep = if (o.has("secondsPerRep")) o.optInt("secondsPerRep", 3).coerceIn(1, 15) else null,
    restSeconds = if (o.has("restSeconds")) o.optInt("restSeconds", 45).coerceIn(0, 600) else null,
    target = when (o.optString("targetKind")) {
        "duration" -> WorkoutTarget.Duration(o.optInt("targetAmount", 30).coerceIn(1, 3600))
        "repetitions" -> WorkoutTarget.Repetitions(o.optInt("targetAmount", 12).coerceIn(1, 999))
        else -> null
    },
).timeOnlyNormalized()

/** 공유·보관함용 운동 목록(기기 전용 칸 없음). */
fun encodeRoutineExercises(workouts: List<Workout>): JSONArray = JSONArray().also { arr -> workouts.forEach { arr.put(workoutJson(it, false)) } }

/**
 * 공유·보관함 운동 목록 → 운동들. id 는 `"$idPrefix-$순번"`, 완료 표시는 지운다. 자세 교정은 보낸 값이 켜져 있고 이 앱이 아는 종목일 때만
 * ([postureKnown] — 받는 앱이 옛 버전이면 모르는 종목이 올 수 있다). 깨진 항목은 건너뛴다.
 */
fun decodeRoutineExercises(
    arr: JSONArray,
    idPrefix: String,
    postureKnown: (String) -> Boolean = postureExerciseMap::containsKey,
): List<Workout> = (0 until arr.length()).mapNotNull { i ->
    runCatching {
        val o = arr.getJSONObject(i)
        val name = WorkoutNames.canonical(o.getString("name"))
        workoutFromJson(o, "$idPrefix-$i", done = false, posture = o.optBoolean("posture", false) && postureKnown(name))
    }.getOrNull()
}

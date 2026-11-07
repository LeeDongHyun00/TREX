package com.example.trex_kotlin.routines

import com.example.trex_kotlin.Workout
import com.example.trex_kotlin.WorkoutTarget
import com.example.trex_kotlin.postureSupported
import com.example.trex_kotlin.timeOnlyNormalized
import com.example.trex_kotlin.workoutCatalog

/*
 * 추천 루틴 프리셋(§131, 디자인 P10·P12) — 데이터는 생성 파일 RoutinePresets.kt(tools/gen_routine_presets.py ← research/routines/routine_presets.json).
 * 프리셋은 자료집이 쓴 분할 템플릿이다. '당신에게 맞다' 고 단정하지 않고 자세 교정 지원 종목을 앞에 둔다(디자인 P10 안내문).
 */

/** 관심 목표 한 가지(T01~T10) — [daysPerWeek] 는 자료의 시작안, [evidence] 는 관심도 근거(ACSM·ClassPass·Strava, 국내 순위 아님). */
data class RoutineTopic(val id: String, val title: String, val daysPerWeek: Int, val schedule: String, val evidence: String)

/** 루틴 안의 종목 하나 — [unit] "회"|"초", [perSide] 는 한쪽씩(좌우 모두 하면 1세트), [restSeconds] 는 세트 사이 휴식. */
data class PresetItem(val name: String, val sets: Int, val min: Int, val max: Int, val unit: String, val perSide: Boolean, val restSeconds: Int)

/** 루틴 프리셋 — [topic] 은 관심 목표 id(부위별 기본 루틴 R01~R16 은 null), [equipment] "맨몸"|"밴드"|"덤벨"|"헬스장"|"복합". */
data class RoutinePreset(
    val id: String, val title: String, val topic: String?, val equipment: String, val part: String, val level: String,
    val minutes: String, val required: String, val note: String, val items: List<PresetItem>,
) {
    val postureCount: Int get() = items.count { postureSupported(it.name) }
    /** "상하체 분할 A · 하체" 꼴의 표시 이름 — 관심 목표 루틴은 장비를 붙여 가른다(같은 제목이 장비마다 있다). */
    val displayName: String get() = if (topic != null) "$title · $equipment" else title
    val ref: String get() = "preset:$id"
    /**
     * 수준 순위 — 입문 0 · 초급 1 · 초중급 2 · 초중급~중급 3 · 중급 4. '초보' 보기(사용자 요청 2026-11-07)는 **상한이 중급이 아닌 것**(입문·초급·초중급)만 보인다 —
     * 자료집이 '4주 스타터'·'바쁜 날 짧은 전신' 같은 입문용 루틴에 초중급을 매겼으므로 초급만 남기면 초보가 고를 루틴이 13개뿐이고 목표 8개가 통째로 사라진다.
     */
    val levelRank: Int get() = when {
        level.startsWith("입문") -> 0
        level.startsWith("초급") -> 1
        level.contains("~") -> 3
        level.startsWith("초중급") -> 2
        else -> 4
    }
    val beginnerFriendly: Boolean get() = levelRank <= 2
    /** 부위 키 — 카드 그림·부위 칩의 기준. 복근·코어는 한 그림(core)이다. */
    val partKey: String get() = when {
        part.startsWith("전신") -> "전신"
        part.startsWith("하체") -> "하체"
        part.startsWith("상체") -> "상체"
        else -> "코어"
    }
    /** 카드 그림 — `assets/routine/` 의 png(홈 카드와 같은 그림). */
    val image: String get() = when (partKey) { "전신" -> "full"; "하체" -> "lower"; "상체" -> "upper"; else -> "core" }
}

/** 부위 칩 순서와 그림 — 루틴 추천 화면의 부위 고르기. */
val presetParts: List<Pair<String, String>> = listOf("전신" to "full", "하체" to "lower", "상체" to "upper", "코어" to "core")

/** 프리셋 → 오늘 루틴 운동 목록. 반복은 자료 범위의 상한, 자세 교정은 지원 종목에 켜 둔다. */
fun RoutinePreset.toWorkouts(nowMillis: Long): List<Workout> = items.mapIndexed { i, item ->
    val template = workoutCatalog.values.flatten().firstOrNull { it.name == item.name }
    val timed = item.unit == "초"
    val reps = if (timed) "${item.max}초 × ${item.sets}세트" else "${item.max}회 × ${item.sets}세트"
    Workout(
        id = "preset-$id-$nowMillis-$i", name = item.name, reps = reps, duration = template?.duration ?: "8분",
        posture = postureSupported(item.name), category = template?.category ?: "전신",
        restSeconds = item.restSeconds.coerceIn(0, 600),
        target = if (timed) WorkoutTarget.Duration(item.max.coerceIn(1, 3600)) else WorkoutTarget.Repetitions(item.max.coerceIn(1, 999)),
    ).timeOnlyNormalized()
}

/** 디자인 P10 의 필터 — 관심 목표(없으면 부위별 기본), 장비, 부위([RoutinePreset.partKey]), 초보([RoutinePreset.beginnerFriendly]). */
data class PresetFilter(val topic: String? = null, val equipment: String? = null, val beginner: Boolean = false, val part: String? = null)

/** 거른 뒤 초보 보기에서는 쉬운 수준이 먼저다(같은 수준 안에서는 자료 순서). */
fun List<RoutinePreset>.filtered(f: PresetFilter): List<RoutinePreset> = filter { p ->
    (f.topic == null || p.topic == f.topic) &&
        (f.equipment == null || p.equipment == f.equipment || (f.equipment == "헬스장" && p.equipment == "복합")) &&
        (f.part == null || p.partKey == f.part) &&
        (!f.beginner || p.beginnerFriendly)
}.let { if (f.beginner) it.sortedBy { p -> p.levelRank } else it }

fun presetById(id: String): RoutinePreset? = routinePresets.firstOrNull { it.id == id }

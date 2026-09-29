package com.example.trex_kotlin.trainingload

import kotlin.math.exp
import kotlin.math.pow

/** 생리 측정값이 아닌 기록 기반 부하 지수. 계수의 근거/한계는 docs/MUSCLE_LOAD_IMPLEMENTATION.md. */
enum class Muscle(val label: String) {
    QUADS("앞 허벅지"), GLUTES("대둔근"), ABDUCTORS("옆 엉덩이"), ADDUCTORS("안쪽 허벅지"),
    HAMSTRINGS("뒤 허벅지"), CALVES("종아리"), HIP_FLEXORS("고관절 굽힘근"), ABS("복직근"),
    OBLIQUES("옆구리"), ERECTORS("척추기립근"), PECS("가슴"), DELTOIDS("어깨"), LATS("광배근"),
    TRAPS("승모근"), SERRATUS("전거근"), BICEPS("팔 앞쪽"), TRICEPS("팔 뒤쪽"), FOREARMS("전완")
}
enum class LoadUnit { REP, PAIR, EACH, SECONDS }
enum class Equipment { NONE, DUMBBELL, BARBELL, LAT_MACHINE, DIP_BAR, PULLUP_BAR, BENCH }

data class ExerciseLoadProfile(
    val id: String, val name: String, val weights: Map<Muscle, Double>,
    val sources: List<String>, val evidence: String,
    val unit: LoadUnit = LoadUnit.REP, val bodyweight: Boolean = false,
    val equipment: Equipment = Equipment.NONE,
) {
    val primary: List<Muscle> get() = weights.filterValues { it >= .75 }.keys.toList()
}

object MuscleLoadCatalog {
    const val VERSION = 1
    private fun p(id: String, name: String, sources: String, evidence: String,
        unit: LoadUnit = LoadUnit.REP, body: Boolean = false, equipment: Equipment = Equipment.NONE,
        vararg muscles: Pair<Muscle, Double>) = ExerciseLoadProfile(id, name, muscles.toMap(),
        sources.split(",").map { "https://pubmed.ncbi.nlm.nih.gov/$it/" }, evidence, unit, body, equipment)
    // 역할 가중치는 EMG %를 옮긴 값이 아니다. 주동/보조/안정 역할에 부여한 제품 초기값이다.
    val all = listOf(
        p("squat", "기본 스쿼트", "19075302", "바벨 스쿼트 연구를 맨몸 동작에 적용", body=true,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.GLUTES to .8, Muscle.ADDUCTORS to .3, Muscle.HAMSTRINGS to .2, Muscle.CALVES to .2, Muscle.ERECTORS to .15, Muscle.ABS to .1)),
        p("lunge", "런지", "32236133,39982282", "전방 런지 연구 · 부하/보폭 차이", LoadUnit.PAIR, true,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.GLUTES to .8, Muscle.ABDUCTORS to .4, Muscle.HAMSTRINGS to .3, Muscle.ADDUCTORS to .25, Muscle.CALVES to .2, Muscle.OBLIQUES to .1)),
        p("barbell_lunge", "바벨 런지", "39982282", "외부 중량 전방 런지 연구 · 중량 미입력", LoadUnit.PAIR, equipment=Equipment.BARBELL,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.GLUTES to .8, Muscle.ABDUCTORS to .4, Muscle.HAMSTRINGS to .3, Muscle.ADDUCTORS to .25, Muscle.CALVES to .2, Muscle.ERECTORS to .15)),
        p("side_lunge", "사이드 런지", "23584319,19321909", "측방 런지 관절역학 + 전방 런지 근활성에서 추론", LoadUnit.PAIR, true,
            muscles=arrayOf(Muscle.QUADS to .9, Muscle.GLUTES to .75, Muscle.ADDUCTORS to .6, Muscle.ABDUCTORS to .35, Muscle.HAMSTRINGS to .25, Muscle.OBLIQUES to .1)),
        p("cross_lunge", "크로스 런지", "32236133,19321909", "유사 런지 연구와 관절 작용에 근거한 추론", LoadUnit.PAIR, true,
            muscles=arrayOf(Muscle.QUADS to .9, Muscle.GLUTES to .8, Muscle.ABDUCTORS to .45, Muscle.ADDUCTORS to .3, Muscle.HAMSTRINGS to .25, Muscle.OBLIQUES to .1)),
        p("deadlift", "바벨 데드리프트", "28151780", "바벨 데드리프트 직접 연구 · 중량 미입력", equipment=Equipment.BARBELL,
            muscles=arrayOf(Muscle.GLUTES to 1.0, Muscle.HAMSTRINGS to .8, Muscle.ERECTORS to .55, Muscle.QUADS to .4, Muscle.ADDUCTORS to .25, Muscle.FOREARMS to .25, Muscle.LATS to .15, Muscle.TRAPS to .15)),
        p("good_morning", "굿모닝", "25653899", "바벨 굿모닝 연구 · 중량/변형 차이", equipment=Equipment.BARBELL,
            muscles=arrayOf(Muscle.HAMSTRINGS to 1.0, Muscle.GLUTES to .75, Muscle.ERECTORS to .5, Muscle.ABS to .1)),
        p("hip_thrust", "힙 쓰러스트", "28151780", "바벨 힙 쓰러스트 연구 · 중량/벤치 조건 차이", equipment=Equipment.BENCH,
            muscles=arrayOf(Muscle.GLUTES to 1.0, Muscle.HAMSTRINGS to .35, Muscle.QUADS to .2, Muscle.ABS to .15, Muscle.ERECTORS to .15)),
        p("overhead_press", "오버헤드 프레스", "35936912", "프레스 직접 연구 · 덤벨/바벨 및 방향 차이", equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.DELTOIDS to 1.0, Muscle.TRICEPS to .6, Muscle.TRAPS to .3, Muscle.SERRATUS to .2, Muscle.PECS to .15, Muscle.ABS to .1)),
        p("lat_pulldown", "랫풀 다운", "40981044", "랫풀 다운 직접 연구 · 그립 차이", equipment=Equipment.LAT_MACHINE,
            muscles=arrayOf(Muscle.LATS to 1.0, Muscle.BICEPS to .5, Muscle.TRAPS to .3, Muscle.DELTOIDS to .2, Muscle.FOREARMS to .2)),
        p("dips", "딥스", "36293792", "바 딥스 직접 연구 · 벤치 딥스와 구분", body=true, equipment=Equipment.DIP_BAR,
            muscles=arrayOf(Muscle.TRICEPS to 1.0, Muscle.PECS to .8, Muscle.DELTOIDS to .3, Muscle.SERRATUS to .2, Muscle.ABS to .1)),
        p("dumbbell_curl", "덤벨 컬", "30013836,17545433", "컬 근활성 + 상완근 해부 연구", equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.BICEPS to 1.0, Muscle.FOREARMS to .4)),
        p("barbell_curl", "바벨 컬", "30013836,17545433", "바벨/EZ바 컬 직접 연구", equipment=Equipment.BARBELL,
            muscles=arrayOf(Muscle.BICEPS to 1.0, Muscle.FOREARMS to .4)),
        p("lateral_raise", "사이드 레터럴 레이즈", "32824894", "측면 들기 직접 연구 · 어깨는 한 근육군으로 표시", equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.DELTOIDS to 1.0, Muscle.TRAPS to .35, Muscle.SERRATUS to .2)),
        p("front_raise", "프런트 레이즈", "32824894", "전면 들기 직접 연구 · 어깨는 한 근육군으로 표시", equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.DELTOIDS to 1.0, Muscle.PECS to .3, Muscle.TRAPS to .2, Muscle.SERRATUS to .2)),
        p("upright_row", "업라이트로우", "22362088", "업라이트로우 직접 연구 · 그립 폭 차이", equipment=Equipment.BARBELL,
            muscles=arrayOf(Muscle.DELTOIDS to 1.0, Muscle.TRAPS to .8, Muscle.BICEPS to .3, Muscle.FOREARMS to .2)),
        p("pushup", "푸쉬업", "20664364,24511343", "일반 푸쉬업 직접 연구", body=true,
            muscles=arrayOf(Muscle.PECS to 1.0, Muscle.TRICEPS to .75, Muscle.SERRATUS to .45, Muscle.DELTOIDS to .35, Muscle.ABS to .15)),
        p("knee_pushup", "니 푸쉬업", "20664364,27693098", "일반/무릎 플러스 변형 연구 · 일반 니 푸쉬업에 추론", body=true,
            muscles=arrayOf(Muscle.PECS to .8, Muscle.TRICEPS to .6, Muscle.SERRATUS to .35, Muscle.DELTOIDS to .25, Muscle.ABS to .1)),
        p("y_raise", "Y 레이즈", "23068897", "견갑면 팔 올리기 연구 · 자세/지지 조건에 따른 추론",
            muscles=arrayOf(Muscle.TRAPS to 1.0, Muscle.DELTOIDS to .4, Muscle.SERRATUS to .35)),
        p("plank", "플랭크", "30856100", "플랭크 직접 연구 · 버틴 시간 기준", LoadUnit.SECONDS, true,
            muscles=arrayOf(Muscle.ABS to 1.0, Muscle.OBLIQUES to .5, Muscle.SERRATUS to .25, Muscle.DELTOIDS to .2, Muscle.GLUTES to .15, Muscle.ERECTORS to .1)),
        p("side_crunch", "스탠딩 사이드 크런치", "12937449", "체간 측굴 연구를 서서 수행하는 동작에 적용", LoadUnit.EACH, true,
            muscles=arrayOf(Muscle.OBLIQUES to 1.0, Muscle.ABS to .35, Muscle.ERECTORS to .15)),
        p("knee_up", "스탠딩 니업", "9118976,34455371", "다리 들기/고관절 굴곡 연구에서 추론", LoadUnit.EACH, true,
            muscles=arrayOf(Muscle.HIP_FLEXORS to 1.0, Muscle.ABS to .35, Muscle.OBLIQUES to .2, Muscle.QUADS to .25)),
        p("hanging_leg_raise", "행잉 레그 레이즈", "9118976,27065536", "누운 다리 들기 연구 + 매달리기 관절 작용에서 추론", equipment=Equipment.PULLUP_BAR,
            muscles=arrayOf(Muscle.HIP_FLEXORS to 1.0, Muscle.ABS to .8, Muscle.OBLIQUES to .3, Muscle.FOREARMS to .3, Muscle.LATS to .15)),
        p("crunch", "크런치", "12937449", "컬업/체간 굴곡 직접 연구", body=true,
            muscles=arrayOf(Muscle.ABS to 1.0, Muscle.OBLIQUES to .3)),
        p("leg_raise", "레그 레이즈", "27065536,34455371", "누운 다리 들기 직접 연구", body=true,
            muscles=arrayOf(Muscle.HIP_FLEXORS to 1.0, Muscle.ABS to .6, Muscle.OBLIQUES to .25, Muscle.QUADS to .3)),
        p("scissor_cross", "시저 크로스", "9118976,20625774", "다리 들기 연구 + 교차 내전 동작의 해부학적 추론", LoadUnit.EACH, true,
            muscles=arrayOf(Muscle.HIP_FLEXORS to .8, Muscle.ABS to .6, Muscle.ADDUCTORS to .5, Muscle.OBLIQUES to .25, Muscle.QUADS to .2)),
    )
    fun find(name: String) = all.firstOrNull { it.name == name || it.id == name || (name == "바벨 스쿼트" && it.id == "squat") }
}

/** 세트 시점의 프로필과 관측 원본을 보존한다. null/미관측을 0회 판정으로 바꾸지 않는다. */
data class LoadSet(
    val id: String, val sessionId: String, val exerciseId: String, val endedAt: Long,
    val timeUncertaintyMs: Long = 0, val reps: Double = 0.0, val seconds: Double = 0.0,
    val unit: LoadUnit = LoadUnit.REP, val left: Double? = null, val right: Double? = null,
    val weightKg: Double = 70.0, val heightCm: Double = 170.0,
    val source: String = "session_count", val postureSetId: String? = null,
    val actualReps: Double? = null, val judged: Int? = null, val abstained: Int? = null,
    val accuracy: Int? = null, val modelVersion: Int = MuscleLoadCatalog.VERSION,
    val unknownSideSteps: Int = 0, val extraSteps: Int = 0,
)
data class MuscleDose(val muscle: Muscle, val side: String, val dose: Double)
data class LoadLedger(val sets: List<LoadSet>, val doses: Map<String,List<MuscleDose>>)
data class MuscleStatus(val muscle: Muscle, val left: Double, val right: Double, val lastAt: Long?) {
    val value get() = maxOf(left, right)
    val known get() = lastAt != null
}
data class LoadSnapshot(val muscles: List<MuscleStatus> = Muscle.entries.map { MuscleStatus(it, 0.0, 0.0, null) },
    val sets: List<LoadSet> = emptyList(), val calculatedAt: Long = 0) {
    val peak get() = muscles.filter { it.known }.maxByOrNull { it.value }
    val approximate get() = sets.any { it.source == "legacy" || it.timeUncertaintyMs > 0 }
}

object MuscleLoadEngine {
    const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    fun doses(set: LoadSet): List<MuscleDose> {
        val p = MuscleLoadCatalog.find(set.exerciseId) ?: return emptyList()
        fun sane(v: Double) = if (v.isFinite()) v.coerceAtLeast(0.0) else 0.0
        val n = sane(set.actualReps ?: set.reps)
        val amount = if (set.unit == LoadUnit.SECONDS) sane(set.seconds) / 45.0 else n / 12.0
        if (amount == 0.0 && set.left == null && set.right == null) return emptyList()
        val kg = set.weightKg.takeIf { it.isFinite() && it in 20.0..350.0 } ?: 70.0
        val cm = set.heightCm.takeIf { it.isFinite() && it in 80.0..250.0 } ?: 170.0
        val body = if (p.bodyweight) ((kg / 70.0) * (cm / 170.0)).pow(.25).coerceIn(.85, 1.15) else 1.0
        // 사용자가 횟수를 고쳤으면 기존 좌우 관측과 섞지 않고 같은 단위로 다시 분배한다.
        val sided = set.unit == LoadUnit.PAIR || set.unit == LoadUnit.EACH
        val observed = sided && set.actualReps == null && set.left != null && set.right != null
        // unknown은 이미 좌우 카운트에 들어 있다. extra만 별도 미귀속 걸음으로 양쪽에 나눈다.
        val extra = set.extraSteps.coerceAtLeast(0) / 2.0
        val l = if (observed) (sane(set.left) + extra) / 12.0 else amount / if (set.unit == LoadUnit.EACH) 2.0 else 1.0
        val r = if (observed) (sane(set.right) + extra) / 12.0 else amount / if (set.unit == LoadUnit.EACH) 2.0 else 1.0
        return p.weights.flatMap { (m, w) -> listOf(MuscleDose(m, "L", l * w * body), MuscleDose(m, "R", r * w * body)) }
    }
    fun decay(hours: Double): Double = .35 * 2.0.pow(-hours.coerceAtLeast(0.0) / 6.0) + .65 * 2.0.pow(-hours.coerceAtLeast(0.0) / 24.0)
    fun score(load: Double) = 100.0 * (1.0 - exp(-load.coerceAtLeast(0.0) / 6.0))
    fun snapshot(sets: List<LoadSet>, now: Long, prepared: Map<String,List<MuscleDose>> = emptyMap()): LoadSnapshot {
        // 재시도/세트 수정은 같은 ID의 최신 입력 한 건으로 계산한다.
        val retained = sets.associateBy { it.id }.values.filter { it.endedAt <= now && it.endedAt >= now - RETENTION_MS }
        val sums = mutableMapOf<Pair<Muscle, String>, Double>(); val last = mutableMapOf<Muscle, Long>()
        retained.forEach { s ->
            val k = decay((now - s.endedAt) / 3_600_000.0)
            (prepared[s.id] ?: doses(s)).filter { it.dose > 0 }.forEach { d ->
                val key = d.muscle to d.side; sums[key] = (sums[key] ?: 0.0) + d.dose * k
                last[d.muscle] = maxOf(last[d.muscle] ?: 0, s.endedAt)
            }
        }
        return LoadSnapshot(Muscle.entries.map { m -> MuscleStatus(m, score(sums[m to "L"] ?: 0.0), score(sums[m to "R"] ?: 0.0), last[m]) }, retained.toList(), now)
    }
    /** 운동 가능/안전 판정이 아니다. 기록 부하와 덜 겹치는 후보만 제공한다. */
    fun recommendations(snapshot: LoadSnapshot, available: Set<Equipment>): List<ExerciseLoadProfile> {
        if (snapshot.peak == null) return emptyList()
        val values = snapshot.muscles.associate { it.muscle to it.value }
        return MuscleLoadCatalog.all.filter { it.equipment == Equipment.NONE || it.equipment in available }
            .filter { p -> p.primary.none { (values[it] ?: 0.0) >= 55.0 } }
            .sortedBy { p -> p.weights.entries.sumOf { (m,w) -> (values[m] ?: 0.0) * w } / p.weights.values.sum() }
            .distinctBy { it.primary.toSet() }.take(3)
    }
}

package com.example.trex_kotlin.posture

import kotlin.math.abs

/** §93 반복 창의 직접 관측 후보. 전부 beta이며 임계는 폰 검증 전 잠정값이다. 척추 중립·힘을 판정하지 않는다. */
object FourExerciseForm {
    private val front = setOf("C", "B", "D")
    private val oblique = setOf("B", "D")
    fun checks(ex: FourExercise): List<RepFormCheck> {
        fun check(name: String, part: String, feature: String, lo: Float? = null, hi: Float? = null,
                  views: Set<String> = front, phase: RepPhase = RepPhase.BOTTOM, stat: RepFormStat = RepFormStat.MEDIAN,
                  low: String? = null, high: String? = null, fix: String): RepFormCheck = RepFormCheck(
            "repform|${ex.title}|$name", ex.title, name, part, RuleStatus.BETA, feature, phase, stat, RepFormRef.NONE,
            lo, hi, low, high, "부족", "큼", fix,
            "§93: 관절 기하로 직접 측정하는 개발 후보. 잠정 임계이며 AIHub 창 규칙의 정확도를 승계하지 않는다. 실휴대폰 검증 전이다.",
            views = views, gates = false, cue = name,
            cautions = listOf("참고 측정입니다. 자세 합격·점수·음성·횟수 차감에 쓰지 않습니다."))
        val result = ArrayList<RepFormCheck>()
        if (ex.paired) {
            result += check("지지 무릎 굽힘", "무릎", "knee_{side}", hi = 125f, views = oblique,
                high = "무릎 굽힘이 작은 동작으로 관측됐습니다.", fix = "편안한 범위에서 무릎을 굽혀 주세요")
            result += check("몸통 옆 기울기", "몸통", "four_abs_roll", hi = 20f,
                high = "몸통이 옆으로 기울어진 동작으로 관측됐습니다.", fix = "몸통의 옆 기울기를 확인해 주세요")
        } else {
            result += check("허벅지 들림", "무릎", "four_thigh_{side}", hi = 115f, views = oblique,
                high = "허벅지가 낮게 올라온 동작으로 관측됐습니다.", fix = "편안한 범위에서 허벅지를 들어 주세요")
            result += check("지지 다리 굽힘", "무릎", "knee_{other}", lo = 140f,
                low = "지지 다리가 많이 굽혀진 동작으로 관측됐습니다.", fix = "지지 다리를 안정적으로 유지해 주세요")
        }
        result += check("몸통 전후 기울기", "몸통", "torso_pitch", lo = -20f, hi = if (ex == FourExercise.SIDE) 55f else 35f,
            views = oblique, low = "몸통이 뒤로 기울어진 동작으로 관측됐습니다.", high = "몸통이 앞으로 기울어진 동작으로 관측됐습니다.",
            fix = "몸통의 전후 기울기를 확인해 주세요")
        result += check("어깨 골반 회전 차이", "몸통", "four_twist", hi = 30f,
            high = "어깨와 골반의 회전 차이가 크게 관측됐습니다.", fix = "몸통의 비틀림을 확인해 주세요")
        if (ex == FourExercise.SIDE) result += check("반대 무릎 폄", "무릎", "knee_{other}", lo = 145f,
            low = "반대 다리도 많이 굽혀진 동작으로 관측됐습니다.", fix = "반대 다리를 편안하게 펴 주세요")
        if (ex == FourExercise.KNEE_UP) result += check("몸통 옆 기울기", "몸통", "four_abs_roll", hi = 20f,
            high = "몸통이 옆으로 기울어진 동작으로 관측됐습니다.", fix = "몸통을 안정적으로 유지해 주세요")
        if (ex == FourExercise.SIDE_CRUNCH) {
            result += check("같은 쪽 팔꿈치 무릎 접근", "옆구리", "four_elbow_knee_{side}", hi = 1f,
                high = "팔꿈치와 같은 쪽 무릎 사이가 멀게 관측됐습니다.", fix = "같은 쪽 팔꿈치와 무릎을 편안하게 가까이 해 주세요")
            result += check("무릎 옆 경로", "무릎", "four_knee_out_{side}", lo = .12f, views = setOf("C"),
                low = "무릎의 옆 방향 이동이 작게 관측됐습니다.", fix = "무릎의 옆 방향 경로를 확인해 주세요")
            for (s in listOf("L", "R")) result += check("${if (s == "L") "왼손" else "오른손"} 머리 위치", "손", "four_hand_head_$s", hi = .65f,
                high = "손이 머리에서 멀게 관측됐습니다.", fix = "양손을 머리 가까이에 두는 변형인지 확인해 주세요")
            // 측굴 자체는 이 종목의 정상 동작이다. 기울기 크기로 오류 판정을 만들지 않는다.
        }
        return result
    }

    fun evaluate(number: Int, endMs: Long, frames: List<Pair<Long, Map<String, Float>>>,
                 checks: List<RepFormCheck>, signal: String, min: Float, max: Float, side: StepSide?): RepFormRep {
        val view = ViewEstimator.estimate(frames.map { it.second }, 4, ViewEstimator.FEAT_COS_SH, ViewEstimator.FEAT_SIN_SH)
            ?.takeIf { it.cls != ViewEstimator.ViewClass.UNKNOWN }?.letter
        val key = side?.let { signal.replace("{side}", it.key) }
        val bottom = if (key == null) emptyList() else frames.filter { (it.second[key] ?: Float.POSITIVE_INFINITY) <= min + (max - min) / 3f }
        val outcomes = checks.map { c ->
            val phase = if (c.phase == RepPhase.BOTTOM) bottom else frames
            val feature = side?.let { c.feature.replace("{side}", it.key).replace("{other}", it.other.key) } ?: c.feature
            val values = phase.mapNotNull { (_, f) -> if (feature == "four_abs_roll") f["torso_roll"]?.let(::abs) else f[feature] }.filter { it.isFinite() }
            val reason = when {
                side == null -> "좌우 미확인"
                view == null -> "촬영 방향 미확인"
                view !in c.views -> "촬영 방향 · $view"
                values.size < 2 || values.size < phase.size * .6f -> "관측 프레임 부족"
                else -> null
            }
            if (reason != null) RepFormOutcome(c, Verdict.ABSTAIN, null, null, null, null, values.size, reason)
            else {
                val sorted = values.sorted()
                val v = if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f else sorted[sorted.size / 2]
                val direction = c.judge(v)
                RepFormOutcome(c, if (direction == null) Verdict.OK else Verdict.VIOLATION, v, v, null, direction, values.size, gate = false)
            }
        }
        return RepFormRep(number, endMs, outcomes, view = view, viewRaw = view, side = side)
    }
}

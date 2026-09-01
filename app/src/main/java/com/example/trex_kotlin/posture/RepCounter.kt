package com.example.trex_kotlin.posture

/**
 * 자동 렙 카운터 v4 — 반전(reversal) 방식 (spec §27, 라벨 세트 실측으로 확정).
 *
 * v3(창 분위수 밴드)의 실측 결함 2건이 라벨 세트(깊3·얕3·깊3·얕3=12)에서 드러나 교체했다:
 *  (a) 손목-붕괴 잡프레임(값≈0.01)이 창 p10 을 끌어내려 밴드 전체가 내려앉음 → 얕은 렙 미카운트
 *  (b) 깊/얕 혼합 세트에서 밴드 상단(hi)이 깊은 렙 기준으로 높아져 얕은 렙의 복귀가 hi 에 못 닿음
 * v4 는 절대 위치(밴드)를 버리고 **방향 반전이 최소 스윙 h 를 넘을 때 극점을 확정**한다(만보기 원리):
 *  렙 = 하단 확정 → 상단 확정. 결과: 라벨 세트 9/12 검출(유효 5·무효 4 — 무효 렙 검출 최초 성공),
 *  baseline1 픽스처 4(정답 3~4), 플랭크 잡음 0·0·0·1. 놓친 3개는 잡프레임 구간·3.3fps 융합(±예산).
 *
 * 규약 (전부 모집단 파라미터 — 특정 사용자로 튜닝하지 않는다):
 *  1) h = 종목별 최소 스윙(물리 단위, 각도 35°/거리 0.25~0.30) — 플랭크 잡음 바닥 실측 위
 *  2) 물리 타당 범위 게이트: AIHub 프레임 분포 밖 값(손목 붕괴 등)은 가림과 동일하게 일시정지
 *  3) 평활은 **샘플 간격 기반**(dt≤0.35s 일 때만 3점 중앙값) — 주기 추정을 기다리는 이전 조건은
 *     초기 구간에서 성긴 신호까지 평활해 렙 꼭대기를 지웠다
 *  4) 불응기 1.2s — 단 불응기에 기각된 상단에서 하단 정보를 버리지 않는다(연쇄 유실 방지)
 *  5) 사이클 극값(lastCycleMin/Max)을 렙마다 노출 → ROM 유효성 판정 입력
 *
 * 참고: AIHub 0.6s(렙당 3~4샘플) 오프라인 분석은 밴드 v3(batch)가 우세 — 연구 코드는 그대로 두고
 * 이 클래스는 기기 스트리밍(렙당 10~17샘플) 전용이다. 밀도별 알고리즘 분리는 확립된 원칙.
 */
class RepCounter(
    val signal: RepSignal,
    private val refractoryMs: Long = 1_200L,
) {
    var reps: Int = 0
        private set
    val repTimesMs = ArrayList<Long>()

    /** 방금 완료된 렙의 사이클 극값 (onFrame 이 true 를 돌려준 직후 유효). */
    var lastCycleMin: Float = Float.NaN
        private set
    var lastCycleMax: Float = Float.NaN
        private set

    /** 최근 렙 주기(ms) 지수평활 추정 — 빠른 렙 자가진단·적응 샘플링 신호. */
    var periodMs: Long? = null
        private set

    private val raw3 = FloatArray(3)
    private var rawCount = 0
    private var dirn = 0                     // 0=초기, -1=하강 추적, +1=상승 추적
    private var ext = Float.NaN              // 현재 방향의 극값 후보
    private var extT = 0L
    private var pendingBottom = Float.NaN    // 확정된 하단 (상단 확정 시 렙으로 승격)
    private var lastRepAt = Long.MIN_VALUE / 2
    private var prevT: Long? = null
    private var dtMs: Float? = null          // 샘플 간격 지수평활 (평활 모드 판단)

    fun reset() {
        reps = 0
        repTimesMs.clear()
        periodMs = null
        rawCount = 0
        dirn = 0
        ext = Float.NaN
        pendingBottom = Float.NaN
        lastRepAt = Long.MIN_VALUE / 2
        prevT = null
        dtMs = null
        lastCycleMin = Float.NaN
        lastCycleMax = Float.NaN
    }

    /** @return 이 프레임에서 렙이 완료됐으면 true. value=null(가림)·물리범위 밖이면 일시정지. */
    fun onFrame(tMs: Long, value: Float?): Boolean {
        if (value == null || !value.isFinite()) return false
        val plo = signal.plausibleMin
        val phi = signal.plausibleMax
        if ((plo != null && value < plo) || (phi != null && value > phi)) return false

        prevT?.let { p ->
            val d = (tMs - p).toFloat()
            if (d > 0f && d < 2_000f) dtMs = dtMs?.let { it * 0.7f + d * 0.3f } ?: d
        }
        prevT = tMs
        raw3[rawCount % 3] = value
        rawCount++
        // 평활: 촘촘한 샘플링(≤350ms)일 때만 — 성긴 신호 평활은 렙 꼭대기를 지운다 (AIHub 실측)
        val v = if (rawCount >= 3 && (dtMs ?: 999f) <= 350f) median3(raw3) else value

        if (ext.isNaN()) {
            ext = v
            extT = tMs
            return false
        }
        val h = signal.minAmp
        if (dirn <= 0) {                                   // 하강 추적(또는 초기)
            if (v < ext) {
                ext = v; extT = tMs
            } else if (v - ext >= h) {                     // 반등이 h 를 넘음 → 하단 확정
                pendingBottom = ext
                dirn = 1
                ext = v; extT = tMs
            }
        } else {                                           // 상승 추적
            if (v > ext) {
                ext = v; extT = tMs
            } else if (ext - v >= h) {                     // 하락이 h 를 넘음 → 상단 확정
                var fired = false
                if (!pendingBottom.isNaN() && tMs - lastRepAt >= refractoryMs) {
                    if (lastRepAt > Long.MIN_VALUE / 4) {
                        val p = tMs - lastRepAt
                        periodMs = periodMs?.let { (it + p) / 2 } ?: p
                    }
                    reps++
                    repTimesMs.add(extT)
                    lastCycleMin = pendingBottom
                    lastCycleMax = ext
                    lastRepAt = tMs
                    pendingBottom = Float.NaN              // 카운트된 하단만 소거 — 불응기 기각 시엔 유지
                    fired = true
                }
                dirn = -1
                ext = v; extT = tMs
                if (fired) return true
            }
        }
        return false
    }

    companion object {
        /** 종목에 카운터가 정의돼 있고 등척성이 아니면 생성 (플랭크 등은 HoldTimer 대상 — 카운터 미적용). */
        fun forExercise(exercise: String): RepCounter? {
            val sig = RepSignals.byExercise[exercise] ?: return null
            return if (sig.isometric) null else RepCounter(sig)
        }

        private fun median3(a: FloatArray): Float {
            val x = a[0]; val y = a[1]; val z = a[2]
            return maxOf(minOf(x, y), minOf(maxOf(x, y), z))
        }
    }
}

/** 종목별 렙 신호. minAmp 는 물리 단위(각도=도, 거리=몸통 정규화). validated=실기기 라벨 검증 여부. */
data class RepSignal(
    val feature: String,
    val minAmp: Float,
    val isometric: Boolean = false,
    val validated: Boolean = false,
    /**
     * ROM(가동범위) 유효성 — "얕으면 무효 렙" (spec §27 수정판, REP_VALIDITY.md).
     * 임계값은 AIHub **전 조건 정상 클립**의 렙 극값 분포에서 기준 렙 90% 통과 분위수 —
     * 문장 기준을 좌표에 직역하면 정상 스쿼트 98% 가 실격이라, 기준은 반드시 데이터에서.
     * romDirection: "min"=사이클 하단 극값이 임계값 이하여야 유효, "max"=상단 극값이 이상.
     * romValidated: ROM 성격의 AIHub 조건으로 판별력 검증됨(위반 클립 무효율 2.8~3.8배) —
     * 검증 종목만 구체 사유(romCue)를 말하고, 나머지는 방향 중립 사유로.
     */
    val romDirection: String? = null,
    val romThreshold: Float? = null,
    val romValidated: Boolean = false,
    val romCue: String? = null,
    /** 물리 타당 범위 (모집단: AIHub 프레임 분포) — 밖의 값은 측정 붕괴로 보고 일시정지. */
    val plausibleMin: Float? = null,
    val plausibleMax: Float? = null,
) {
    /** 완료된 렙의 ROM 유효성. null = ROM 기준 없음(항상 유효 취급). */
    fun isValidRep(cycleMin: Float, cycleMax: Float): Boolean? {
        val thr = romThreshold ?: return null
        return when (romDirection) {
            "min" -> cycleMin <= thr
            "max" -> cycleMax >= thr
            else -> null
        }
    }

    val invalidCue: String
        get() = romCue ?: "동작 범위가 부족했어요. 끝까지 움직여 주세요"
}

/**
 * 렙 신호 등록부 (REP_SIGNALS.md 큐레이션).
 *
 * 바닥 종목은 기기 실측(푸시업류 4/4 적중) + 운동학 채택. 서서 종목은 AIHub 전수 설문(합의일치
 * 0.81~1.00) 승자를 **앱 가용 피처로 매핑**한 것 — 설문 승자 중 일부(hip_R, knee_fwd_mean,
 * elbow_h 등)는 앱 피처 집합에 없어 같은 패밀리/러너업으로 대체했다. 전부 beta: 실기기 라벨로
 * 확정 전까지 카운트는 참고용이며 ±1 오차를 약속에 포함하지 않는다.
 */
object RepSignals {
    private const val ANGLE = 35f      // 각도형 게이트: 플랭크 유지 중 잡음 바닥(10~30°/5s) 위
    private const val NORM = 0.25f     // 몸통 정규화 거리형
    private const val NORM_S = 0.10f   // 작은 스케일 정규화형 (이탈·높이차)

    private data class Rom(val dir: String, val thr: Float, val validated: Boolean, val cue: String?)

    /**
     * ROM 유효성 임계값 (rep_validity_thresholds.py 산출, REP_VALIDITY.md).
     * AIHub 전 조건 정상 클립의 렙 극값 분포에서 기준 렙 90% 통과 분위수.
     * validated=true(5종목)는 ROM 성격의 AIHub 조건으로 판별력 검증됨 — 구체 사유 발화.
     * 나머지는 방향 자동판정이 수축 끝 대신 복귀 끝을 잡았을 수 있어 방향 중립 사유만.
     */
    private val ROM: Map<String, Rom> = mapOf(
        "푸시업" to Rom("min", 0.7101f, true, "얕았어요. 가슴을 더 내려 주세요"),
        "니푸쉬업" to Rom("min", 0.8377f, true, "얕았어요. 가슴을 더 내려 주세요"),
        "크런치" to Rom("max", 0.2953f, true, "덜 올라왔어요. 상체를 더 말아 올려 주세요"),
        "라잉 레그 레이즈" to Rom("min", 119.4981f, false, null),
        "힙쓰러스트" to Rom("min", -0.1618f, false, null),
        "Y - Exercise" to Rom("min", 0.1275f, false, null),
        "시저크로스" to Rom("max", 0.3932f, false, null),
        "바이시클 크런치" to Rom("max", 0.4310f, false, null),
        "바벨 데드리프트" to Rom("min", 102.2493f, false, null),
        "바벨 스티프 데드리프트" to Rom("min", 100.0967f, false, null),
        "굿모닝" to Rom("min", 109.2276f, false, null),
        "바벨 스쿼트" to Rom("min", 97.8905f, false, null),
        "버피 테스트" to Rom("min", 129.9882f, false, null),
        "크로스 런지" to Rom("min", 115.6725f, false, null),
        "바벨 런지" to Rom("min", 112.0852f, true, "무릎을 충분히 굽혀 주세요"),
        "사이드 런지" to Rom("min", 106.0722f, false, null),
        "스텝 포워드 다이나믹 런지" to Rom("min", -0.0076f, false, null),
        "스텝 백워드 다이나믹 런지" to Rom("min", 141.7641f, false, null),
        "스탠딩 니업" to Rom("min", 142.7811f, false, null),
        "풀업" to Rom("min", 80.3965f, false, null),
        "딥스" to Rom("min", 93.9059f, true, "얕았어요. 더 내려가 주세요"),
        "바벨 로우" to Rom("min", 112.6863f, false, null),
        "덤벨 벤트오버 로우" to Rom("min", 113.4363f, false, null),
        "바벨 컬" to Rom("min", 66.4238f, false, null),
        "덤벨 컬" to Rom("min", 81.3342f, false, null),
        "페이스 풀" to Rom("min", 84.0116f, false, null),
        "랫풀 다운" to Rom("max", 19.2011f, false, null),
        "사이드 레터럴 레이즈" to Rom("min", 107.4791f, false, null),
        "프런트 레이즈" to Rom("min", 96.5170f, false, null),
        "업라이트로우" to Rom("min", 116.1889f, false, null),
        "덤벨 체스트 플라이" to Rom("max", 31.6000f, false, null),
        "덤벨 인클라인 체스트 플라이" to Rom("max", 29.0803f, false, null),
        "오버 헤드 프레스" to Rom("min", 0.4568f, false, null),
        "케이블 푸시 다운" to Rom("min", -0.7237f, false, null),
        "라잉 트라이셉스 익스텐션" to Rom("min", 0.4038f, false, null),
        "덤벨 풀 오버" to Rom("min", 0.2460f, false, null),
        "로잉머신" to Rom("min", -0.1773f, false, null),
        "행잉 레그 레이즈" to Rom("min", 0.1498f, false, null),
        "케이블 크런치" to Rom("min", 1.1342f, false, null),
    )

    val byExercise: Map<String, RepSignal> = base().mapValues { (ex, sig) ->
        ROM[ex]?.let { sig.copy(romDirection = it.dir, romThreshold = it.thr, romValidated = it.validated, romCue = it.cue) } ?: sig
    }

    private fun base(): Map<String, RepSignal> = buildMap {
        // ---- 바닥 (M0 재생 검증 — rep_replay.py SIGNALS 와 일치)
        put("푸시업", RepSignal("wrist_shoulder_d", 0.30f, validated = true, plausibleMin = 0.10f))
        put("니푸쉬업", RepSignal("wrist_shoulder_d", 0.30f, validated = true, plausibleMin = 0.10f))
        put("크런치", RepSignal("head_ground", 0.15f))
        put("라잉 레그 레이즈", RepSignal("hip_ang", 25f))
        put("힙쓰러스트", RepSignal("hip_dev_ankle", NORM_S))
        put("Y - Exercise", RepSignal("hand_shoulder_off", 0.20f))
        put("시저크로스", RepSignal("knee_gap2d", NORM))
        put("바이시클 크런치", RepSignal("knee_gap2d", NORM))
        put("플랭크", RepSignal("trunk_ankle_ang", ANGLE, isometric = true))
        // ---- 서서: 힙 힌지 (설문 hip_R 0.98~1.00 → 앱 가용 hip_mean)
        for (ex in listOf("바벨 데드리프트", "바벨 스티프 데드리프트", "굿모닝")) put(ex, RepSignal("hip_mean", ANGLE))
        // ---- 스쿼트·런지·버피 (무릎각 계열)
        put("바벨 스쿼트", RepSignal("knee_mean", ANGLE))
        put("버피 테스트", RepSignal("knee_mean", ANGLE))
        put("크로스 런지", RepSignal("knee_mean", ANGLE))
        put("바벨 런지", RepSignal("knee_minside", ANGLE))
        put("사이드 런지", RepSignal("knee_minside", ANGLE))
        put("스텝 포워드 다이나믹 런지", RepSignal("knee_out_mean", NORM_S))
        put("스텝 백워드 다이나믹 런지", RepSignal("hip_mean", ANGLE))
        put("스탠딩 니업", RepSignal("hip_mean", ANGLE))
        // ---- 팔꿈치 각 계열 (풀업·랫풀·딥스·로우·컬·페이스풀)
        for (ex in listOf("풀업", "딥스", "바벨 로우", "덤벨 벤트오버 로우", "바벨 컬", "덤벨 컬", "페이스 풀")) {
            put(ex, RepSignal("elbow_mean", ANGLE))
        }
        put("랫풀 다운", RepSignal("forearm_vert_mean", ANGLE))
        // ---- 전완 수직도 계열 (들어올림)
        for (ex in listOf("사이드 레터럴 레이즈", "프런트 레이즈", "업라이트로우", "덤벨 체스트 플라이", "덤벨 인클라인 체스트 플라이")) {
            put(ex, RepSignal("forearm_vert_mean", ANGLE))
        }
        // ---- 손 높이/거리 계열
        put("오버 헤드 프레스", RepSignal("palm_h_sh", NORM))
        put("케이블 푸시 다운", RepSignal("palm_h_sh", NORM))
        put("라잉 트라이셉스 익스텐션", RepSignal("palm_h_sh", NORM))
        put("덤벨 풀 오버", RepSignal("palm_h_sh", NORM))
        put("로잉머신", RepSignal("palm_fwd_knee", NORM))
        put("행잉 레그 레이즈", RepSignal("hip_below_knee", NORM_S))
        put("케이블 크런치", RepSignal("knee_elbow_dist", NORM))
        // 미등록(신뢰 가능한 앱 가용 신호 없음): 스탠딩 사이드 크런치 — 오카운트보다 미표시가 정직
    }
}

/** 완료된 렙 하나의 기록 — 사이클 극값과 ROM 판정. 세트 로그에 렙별로 남겨 후반 드리프트(피로)
 *  분석을 오프라인에서 가능하게 한다 (spec §29 — 숙련자 계기판의 원자재). */
data class RepRecord(val tMs: Long, val cycleMin: Float, val cycleMax: Float, val valid: Boolean?)

object RepMetrics {
    /**
     * 렙 간격 **중앙값**(ms) — 기록 모드 템포 표시용. RepCounter.periodMs(지수평활)는 이상 렙
     * 하나(휴식 끼임 등)에 끌려가므로, 표시는 중앙값으로 강건하게. 렙 2개 미만이면 null.
     */
    fun medianPeriodMs(repTimesMs: List<Long>): Long? {
        if (repTimesMs.size < 2) return null
        val gaps = repTimesMs.zipWithNext { a, b -> b - a }.sorted()
        val m = gaps.size
        return if (m % 2 == 1) gaps[m / 2] else (gaps[m / 2 - 1] + gaps[m / 2]) / 2
    }
}

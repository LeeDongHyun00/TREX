package com.example.trex_kotlin.posture

import kotlin.math.max

/**
 * 자동 렙 카운터 (spec §27, 파이썬 레퍼런스 `research/aihub_fitness/rep_replay.py` 와 패리티).
 *
 * 규약 (M0 실기기 재생으로 확정 — 라벨 세트 적중 1 · ±1 1 · 실패 0):
 *  1) 평활은 렙당 샘플 ≥8 일 때만 3점 이동 중앙값 — 성긴 신호 평활은 렙 꼭대기를 지운다 (AIHub 실측)
 *  2) 밴드 = 극값-중점: 최근 20s 창의 (p10+p90)/2 ± 0.15×(p90−p10).
 *     분위수 밴드는 비대칭 파형(컬형)에서 붕괴, 밴드 확장(0.30)은 얕은 렙을 놓친다 — 둘 다 실측 기각
 *  3) 진폭 게이트는 물리 단위: 각도형 ≥35°(플랭크 유지 중 기기 잡음 바닥 10~30°/5s 실측 위),
 *     정규화 거리형 ≥0.25 안팎 — 분위수식 상대 게이트는 방향이 틀림 (컬형 기각 사고)
 *  4) 렙 1 = 하단 아래 → 상단 위 복귀(전체 사이클) + 불응기 1.2s
 *  5) 가림(피처 유보) 프레임은 일시정지 — 추측 카운트 금지
 *
 * 한계(정직하게): ±1 오차는 구조적(3.3fps 시각 카운터). 정확한 횟수를 약속하지 않고,
 * 렙 주기 추정이 1.5s 아래로 오면 UI 가 '빠른 반복' 미확정 표시를 하도록 periodMs 를 노출한다.
 */
class RepCounter(
    val signal: RepSignal,
    private val windowMs: Long = 20_000L,
    private val frac: Float = 0.15f,
    private val refractoryMs: Long = 1_200L,
) {
    var reps: Int = 0
        private set
    val repTimesMs = ArrayList<Long>()

    /** 최근 렙 주기(ms) 지수평활 추정 — null 이면 아직 렙 2개 미만. 빠른 렙 자가진단·적응 샘플링 신호. */
    var periodMs: Long? = null
        private set

    private val tHist = ArrayList<Long>()
    private val vHist = ArrayList<Float>()
    private val raw3 = FloatArray(3)
    private var rawCount = 0
    private var state = 0            // 0=mid, 1=low, 2=high
    private var lastRepAt = Long.MIN_VALUE / 2

    fun reset() {
        reps = 0
        repTimesMs.clear()
        periodMs = null
        tHist.clear(); vHist.clear()
        rawCount = 0
        state = 0
        lastRepAt = Long.MIN_VALUE / 2
    }

    private fun samplesPerRep(): Float {
        val p = periodMs ?: return 99f
        if (tHist.size < 2) return 99f
        val dt = (tHist.last() - tHist.first()).toFloat() / (tHist.size - 1)
        return p / max(dt, 1f)
    }

    /** @return 이 프레임에서 렙이 완료됐으면 true. value=null(가림)이면 일시정지. */
    fun onFrame(tMs: Long, value: Float?): Boolean {
        if (value == null || !value.isFinite()) return false
        raw3[rawCount % 3] = value
        rawCount++
        // 평활: 렙당 샘플이 충분할 때만 (성긴 신호 평활 금지)
        val v = if (rawCount >= 3 && samplesPerRep() >= 8f) median3(raw3) else value
        tHist.add(tMs)
        vHist.add(v)
        while (tHist.isNotEmpty() && tMs - tHist.first() > windowMs) {
            tHist.removeAt(0)
            vHist.removeAt(0)
        }
        if (vHist.size < 8) return false
        val sorted = vHist.toFloatArray().also { it.sort() }
        val p10 = quantileSorted(sorted, 0.10f)
        val p90 = quantileSorted(sorted, 0.90f)
        if (p90 - p10 < signal.minAmp) {          // 진폭 게이트 (물리 단위) — 활동 없음/잡음
            state = 0
            return false
        }
        val center = (p10 + p90) / 2f
        val half = frac * (p90 - p10)
        val lo = center - half
        val hi = center + half
        if (state != 1 && v <= lo) {
            state = 1
        } else if (state == 1 && v >= hi) {
            state = 2
            if (tMs - lastRepAt >= refractoryMs) {
                if (lastRepAt > Long.MIN_VALUE / 4) {
                    val p = tMs - lastRepAt
                    periodMs = periodMs?.let { (it + p) / 2 } ?: p
                }
                reps++
                repTimesMs.add(tMs)
                lastRepAt = tMs
                return true
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

        /** numpy linear-interpolation 분위수 (정렬 배열). */
        fun quantileSorted(sorted: FloatArray, q: Float): Float {
            if (sorted.isEmpty()) return Float.NaN
            val pos = q * (sorted.size - 1)
            val i = pos.toInt()
            if (i >= sorted.size - 1) return sorted.last()
            val f = pos - i
            return sorted[i] * (1 - f) + sorted[i + 1] * f
        }
    }
}

/** 종목별 렙 신호. minAmp 는 물리 단위(각도=도, 거리=몸통 정규화). validated=실기기 라벨 검증 여부. */
data class RepSignal(
    val feature: String,
    val minAmp: Float,
    val isometric: Boolean = false,
    val validated: Boolean = false,
)

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

    val byExercise: Map<String, RepSignal> = buildMap {
        // ---- 바닥 (M0 재생 검증 — rep_replay.py SIGNALS 와 일치)
        put("푸시업", RepSignal("wrist_shoulder_d", 0.30f, validated = true))
        put("니푸쉬업", RepSignal("wrist_shoulder_d", 0.30f, validated = true))
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

package com.example.trex_kotlin.posture

/**
 * 서서 하는 종목의 **세트 중** 가로 잘림 감시(spec §101, 10-08 사이드 런지 '깨지는 뼈대') — 순수 Kotlin(재생기 engineFiles).
 * 종전에는 세트 중 촬영 안내가 바닥 전용(`FloorCoverage`)이라 서서 하는 종목의 커버리지가 늘 OK 였다. 10-08 사이드 런지는 사용자가 이미지 오른쪽으로 흘러
 * 왼발이 가장자리에 걸렸고(골반 정지·발목 점프 31회, 가장자리 25회) MediaPipe 가 '편 다리'·'접힌 다리' 두 가설을 오갔다 — 화면은 그걸 '혼자 움직이는 다리' 로 그렸다.
 *
 *  - 판정 프레임(300 ms)마다 [update]. 발목·뒤꿈치·발끝(27~32) 중 하나의 x 가 [EDGE, 1 − EDGE] 밖인 프레임이 **같은 쪽으로** [STREAK] 번 이어지면 막힘(`ok = false`),
 *    깨끗한 프레임이 [STREAK] 번 이어지면 풀린다.
 *  - 방향은 **사용자 기준**: `leg_face_dx` ≥ 0(카메라를 봄)이면 이미지 오른쪽 이탈 = 사용자 왼발이라 "오른쪽으로" 옮기라고 말한다. 뒤돌아섰으면 뒤집는다.
 *  - 문장은 [takeSpeech] — [GAP_MS](15 s) 간격, 세트당 최대 [MAX_PER_SET]. 10 s 안에 양쪽이 다 나가면 "한 걸음 뒤로".
 *
 * 띠 근거(설계 §3.7): 가장자리 0.02·연속 3·간격 15 s 로 10-08 사이드 런지 6.3·25.8·40.8 s 세 번, 10-06 깨끗한 세트·스쿼트·컬·니업·전방 런지 0회,
 * MM-Fit 0/185 · FMS 0/60 · mmfit_arms 0/25 · REHAB 1/36. 0.05 로 넓히면 깨끗한 10-06 s3:9 에서도 울려 0.02 다.
 */
class StandingFraming {
    /** 감시 결과 — 재생기에서도 쓰이도록 `CoverageReport`(앱 규칙 타입에 묶임)와 분리했다. [outRight] = 이미지 오른쪽으로 나감(null = 안 나감). 앱은 `CoverageReport` 로 바꿔 쓴다. */
    data class Report(val ok: Boolean, val message: String = "", val fix: String = "", val outRight: Boolean? = null) {
        companion object { val OK = Report(true) }
    }
    private var outStreak = 0
    private var okStreak = 0
    private var outSide: Boolean? = null   // true = 이미지 오른쪽
    private var blocked: Report = Report.OK
    private var lastSpokenAt = Long.MIN_VALUE / 2
    private var spoken = 0
    private var leftOutAt = Long.MIN_VALUE / 2
    private var rightOutAt = Long.MIN_VALUE / 2
    private var facing = 1f

    fun reset() { outStreak = 0; okStreak = 0; outSide = null; blocked = Report.OK; lastSpokenAt = Long.MIN_VALUE / 2; spoken = 0; leftOutAt = Long.MIN_VALUE / 2; rightOutAt = Long.MIN_VALUE / 2; facing = 1f }

    /** 지금 막혀 있는가(판정 근거 없음 — 창 규칙 코칭을 막는 의미는 `FloorCoverage` 와 같다). */
    val report: Report get() = blocked

    /** 판정 프레임 하나 — [xy] 는 정규화 이미지 좌표(33×2), [features] 에서 `leg_face_dx` 를 읽는다(없으면 종전 방향 유지). 돌려주는 값은 현재 커버리지. */
    fun update(now: Long, xy: FloatArray, features: Map<String, Float>): Report {
        features[LegGeometry.FACE_DX]?.takeIf { it.isFinite() && it != 0f }?.let { facing = if (it >= 0f) 1f else -1f }
        var dir: Boolean? = null
        if (xy.size >= 66) for (i in FEET) {
            val x = xy[i * 2]
            if (!x.isFinite()) continue
            if (x < EDGE) { dir = false; break }
            if (x > 1f - EDGE) { dir = true; break }
        }
        if (dir == null) {
            okStreak++; outStreak = 0
            if (okStreak >= STREAK && !blocked.ok) { blocked = Report.OK; outSide = null }
            return blocked
        }
        okStreak = 0
        if (dir == outSide) outStreak++ else { outSide = dir; outStreak = 1 }
        if (!dir) leftOutAt = now else rightOutAt = now
        if (outStreak >= STREAK) blocked = reportFor(dir, now)
        return blocked
    }

    /** 사용자 기준 발 이름·옮길 방향 — 이미지 오른쪽 이탈은 카메라를 보는 사용자의 **왼발**. */
    private fun reportFor(outRight: Boolean, now: Long): Report {
        val both = now - leftOutAt <= BOTH_WINDOW_MS && now - rightOutAt <= BOTH_WINDOW_MS
        val userLeft = outRight == (facing >= 0f)
        return if (both) Report(false, BOTH_MESSAGE, BOTH_FIX, outRight)
        else Report(false,
            if (userLeft) "왼발이 화면 밖으로 나가요" else "오른발이 화면 밖으로 나가요",
            if (userLeft) "오른쪽으로 반걸음 옮겨 서 주세요" else "왼쪽으로 반걸음 옮겨 서 주세요", outRight)
    }

    /** 말할 문장(막혀 있고 간격·상한 안이면) — 소진한다. 두 모드 모두(촬영 안내). */
    fun takeSpeech(now: Long): String? {
        if (blocked.ok || spoken >= MAX_PER_SET || now - lastSpokenAt < GAP_MS) return null
        lastSpokenAt = now; spoken++
        return "${blocked.message}. ${blocked.fix}."
    }

    companion object {
        const val EDGE = 0.02f
        const val STREAK = 3
        const val GAP_MS = 15_000L
        const val MAX_PER_SET = 3
        const val BOTH_WINDOW_MS = 10_000L
        const val BOTH_MESSAGE = "다리를 벌리면 화면 밖으로 나가요"
        const val BOTH_FIX = "한 걸음 뒤로 가 주세요"
        private val FEET = intArrayOf(27, 28, 29, 30, 31, 32)
    }
}

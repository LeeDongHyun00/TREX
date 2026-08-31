package com.example.trex_kotlin.posture

/**
 * 촬영 뷰를 사람이 이해할 수 있는 말로 (spec §26).
 *
 * 규칙 JSON 의 `view_best_front` 는 AIHub 카메라 코드(A~E)라 사용자에게 의미가 없다.
 * 여기서 **폰을 어디에 두라는 지시**로 바꾼다. 코드↔실제 기하는 관측치로 확정했다
 * (research/aihub_fitness/view_geometry.py):
 *
 * | 코드 | 서서 하는 종목 | 바닥 종목 |
 * |---|---|---|
 * | C | 정면 (정면비율 0.92, 어깨폭/몸통 0.650 = 최대) | **측면** (몸길이/어깨폭 15.8 vs 타 뷰 3~5.7) |
 * | B | 전방 사선 왼쪽 +40° (0.83) | 몸 옆 비스듬히 (4.8) |
 * | D | 전방 사선 오른쪽 −40° (0.92) | 몸 옆 비스듬히 (4.2) |
 * | A | 후방 사선 (정면비율 0.09) | 몸 축 방향 = 머리/발 쪽 (3.0 = 최소) |
 * | E | 후방 사선 (0.17) | 몸 옆 비스듬히 (5.7) |
 *
 * 같은 코드라도 **서서 vs 바닥이 완전히 다르다** — 카메라는 방에 고정인데 사람이 누우면
 * 서 있을 때 정면이던 카메라가 몸의 측면을 보게 되기 때문이다.
 *
 * 좌우(B=왼쪽 / D=오른쪽)는 `mirrorSafe` 규칙이면 의미가 없다(좌우 반전 불변) → "좌우 어느 쪽이든".
 */
object ViewGuide {

    /** 화면에 쓸 짧은 이름 — 목록·배지용. */
    fun shortName(view: String, floor: Boolean): String = when {
        view.isEmpty() -> "정면"
        floor -> when (view) {
            "C" -> "측면"
            "A" -> "머리·발 쪽"
            else -> "측면 비스듬히"
        }
        else -> when (view) {
            "C" -> "정면"
            "B", "D" -> "앞 비스듬히"
            else -> "뒤 비스듬히"
        }
    }

    /** 폰을 어디에 둘지 한 줄 지시 — 가이드 문구용. */
    fun placement(view: String, floor: Boolean, mirrorSafe: Boolean = true): String {
        if (floor) {
            val dir = when (view) {
                "C" -> "몸 옆(측면)에서 몸 전체가 옆으로 길게 보이게"
                "A" -> "머리 쪽 또는 발 쪽에서 몸 축을 따라"
                else -> "몸 옆에서 약간 비스듬히"
            }
            // §25d 재투영 실측: 높이(바닥~서있는)와 거리는 판정에 거의 무해(순위ρ 0.98)하지만
            // 방위는 ±25° 에서 무너지고 특히 발쪽 치우침이 최악(0.61) — 엄격해야 하는 축을 정확히 말해 준다.
            return "폰을 바닥에 눕히듯 낮게 두고, $dir — 발쪽으로 치우치지 않게 (높이·거리는 자유로워요)"
        }
        val side = if (mirrorSafe) "좌우 어느 쪽이든" else if (view == "B") "왼쪽" else "오른쪽"
        val dir = when (view) {
            "C" -> "몸을 정면에서 마주보게"
            "B", "D" -> "정면에서 약 40° 비스듬히($side)"
            else -> "뒤쪽에서 약 40° 비스듬히($side)"
        }
        return "폰을 허리 높이에 세로로 세우고, $dir"
    }

    /**
     * 규칙 목록의 대표 뷰 요약 — "정면 (규칙 3개)" 처럼. 규칙마다 뷰가 다르면 많은 순으로 나열한다.
     * 뷰가 섞여 있으면 **가장 많은 뷰 기준으로 찍되 나머지는 유보될 수 있다**는 뜻이라 개수를 같이 보인다.
     */
    fun summary(rules: List<PostureRule>, floor: Boolean): String = rules
        .map { it.view }.filter { it.isNotEmpty() }
        .groupingBy { shortName(it, floor) }.eachCount()
        .entries.sortedByDescending { it.value }
        .joinToString(", ") { "${it.key} ${it.value}개" }

    /** 규칙 목록에서 가장 많이 쓰인 뷰 코드 (배치 지시를 하나로 정할 때). */
    fun dominantView(rules: List<PostureRule>): String? = rules
        .map { it.view }.filter { it.isNotEmpty() }
        .groupingBy { it }.eachCount()
        .maxByOrNull { it.value }?.key
}

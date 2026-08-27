package com.example.trex_kotlin.posture

/**
 * 바닥 운동 촬영 커버리지 진단 (spec §25b).
 *
 * 실측(§25a)에서 확인된 문제: MediaPipe 는 사람을 100% 검출하는데도 **발목이 프레임 밖으로 잘려**
 * 푸시업 한 세트의 85% 프레임이 버려졌다. 게다가 그 세트의 규칙 3개는 발목을 **쓰지도 않았다**.
 * 그래서 두 가지를 바로잡는다:
 *
 *  1. **필요 관절을 규칙에서 역산한다.** 종목마다 고정된 '코어 관절'을 요구하지 않고,
 *     그 종목의 활성 규칙이 쓰는 피처 → 필요 부위만 요구한다(§25b 표).
 *  2. **못 잡는 이유를 구분해 해결책을 다르게 안내한다.**
 *     - 좌표가 화면 밖([0,1] 이탈) → **프레임 문제**: 카메라를 멀리 두거나 그 방향으로 옮긴다.
 *     - 화면 안인데 가시성이 낮음 → **가림 문제**: 몸 옆에서 보이게 각도를 바꾼다.
 *     MediaPipe 는 화면 밖 관절도 외삽 좌표를 내므로 이 구분이 가능하다.
 */

/** 사용자에게 말할 수 있는 단위의 신체 부위 (관절 인덱스는 MediaPipe 33점). */
enum class BodyPart(val label: String, val indices: IntArray) {
    HEAD("머리", intArrayOf(0, 7, 8)),
    SHOULDER("어깨", intArrayOf(11, 12)),
    ELBOW("팔꿈치", intArrayOf(13, 14)),
    WRIST("손목", intArrayOf(15, 16)),
    HIP("골반", intArrayOf(23, 24)),
    KNEE("무릎", intArrayOf(25, 26)),
    ANKLE("발목", intArrayOf(27, 28)),
}

/** 화면에서 벗어난 방향 (카메라 프레임 기준). */
enum class OutDirection(val label: String) {
    LEFT("왼쪽"), RIGHT("오른쪽"), TOP("위"), BOTTOM("아래")
}

/**
 * 바닥 2D 피처 → 계산에 필요한 부위.
 * [FloorFeatureExtractor.compute] 의 각 `put(...)` 인자와 1:1로 대응한다 — 한쪽을 고치면 다른 쪽도 고칠 것.
 * `*_ground` 계열은 접지선(골반↔발목 또는 손목↔발목)을 쓰므로 발목·골반이 추가로 필요하다.
 */
val FLOOR_FEATURE_PARTS: Map<String, Set<BodyPart>> = mapOf(
    // 신체 주축 대비 이탈
    "hip_dev_ankle" to setOf(BodyPart.SHOULDER, BodyPart.HIP, BodyPart.ANKLE),
    "hip_dev_knee" to setOf(BodyPart.SHOULDER, BodyPart.HIP, BodyPart.KNEE),
    "knee_dev" to setOf(BodyPart.HIP, BodyPart.KNEE, BodyPart.ANKLE),
    "shoulder_dev" to setOf(BodyPart.SHOULDER, BodyPart.HIP, BodyPart.WRIST),
    // 분절 각도
    "elbow_ang" to setOf(BodyPart.SHOULDER, BodyPart.ELBOW, BodyPart.WRIST),
    "knee_ang" to setOf(BodyPart.HIP, BodyPart.KNEE, BodyPart.ANKLE),
    "hip_ang" to setOf(BodyPart.SHOULDER, BodyPart.HIP, BodyPart.KNEE),
    "trunk_ankle_ang" to setOf(BodyPart.SHOULDER, BodyPart.HIP, BodyPart.ANKLE),
    "head_trunk_ang" to setOf(BodyPart.HEAD, BodyPart.HIP),
    "shoulder_arm_ang" to setOf(BodyPart.HIP, BodyPart.SHOULDER, BodyPart.ELBOW),
    // 정규화 거리 (torso = 어깨↔골반 이므로 둘이 항상 필요)
    "hand_shoulder_off" to setOf(BodyPart.WRIST, BodyPart.SHOULDER, BodyPart.HIP),
    "wrist_shoulder_d" to setOf(BodyPart.WRIST, BodyPart.SHOULDER, BodyPart.HIP),
    "knee_shoulder_d" to setOf(BodyPart.KNEE, BodyPart.SHOULDER, BodyPart.HIP),
    "ankle_hip_d" to setOf(BodyPart.ANKLE, BodyPart.SHOULDER, BodyPart.HIP),
    "elbow_width" to setOf(BodyPart.SHOULDER, BodyPart.ELBOW, BodyPart.WRIST),
    "knee_gap2d" to setOf(BodyPart.KNEE, BodyPart.SHOULDER, BodyPart.HIP),
    "ankle_gap2d" to setOf(BodyPart.ANKLE, BodyPart.SHOULDER, BodyPart.HIP),
    "shoulder_asym2d" to setOf(BodyPart.SHOULDER, BodyPart.HIP),
    // 접지선 기준 (지면 = 골반↔발목 또는 손목↔발목의 중앙값)
    "shoulder_ground" to setOf(BodyPart.SHOULDER, BodyPart.HIP, BodyPart.ANKLE),
    "hip_ground" to setOf(BodyPart.HIP, BodyPart.ANKLE),
    "knee_ground" to setOf(BodyPart.KNEE, BodyPart.HIP, BodyPart.ANKLE),
    "ankle_ground" to setOf(BodyPart.ANKLE, BodyPart.HIP),
    "head_ground" to setOf(BodyPart.HEAD, BodyPart.HIP, BodyPart.ANKLE),
)

/** 한 부위의 상태. */
data class PartStatus(
    val part: BodyPart,
    val visible: Boolean,
    /** 화면 밖으로 벗어난 방향. null 이면 화면 안(= 안 보이면 가림). */
    val outDirection: OutDirection?,
)

/**
 * 커버리지 진단 결과. [ok] 가 false 면 그 프레임으로는 일부 규칙을 계산할 수 없다.
 * @param blocked 계산 불가한 (규칙 조건 → 그 규칙이 요구하는 부위 중 안 보이는 것)
 */
data class CoverageReport(
    val ok: Boolean,
    val missing: List<PartStatus>,
    val blocked: Map<String, List<BodyPart>>,
    /** 사용자에게 보여줄 한 줄 — 어느 부위가 필요한지 */
    val message: String,
    /** 무엇을 하면 되는지 — 카메라를 멀리 / 옮기기 / 각도 바꾸기 */
    val fix: String,
) {
    companion object {
        val OK = CoverageReport(true, emptyList(), emptyMap(), "", "")
    }
}

object FloorCoverage {

    /** 이 프레임에서 부위가 '보인다'고 볼 최소 가시성 — FloorFeatureExtractor 의 피처 게이트와 같은 값. */
    const val VIS_CUT = 0.35f

    /** 좌표가 화면 밖이라고 볼 여유 — 경계에 딱 붙은 것은 잘림으로 치지 않는다. */
    private const val MARGIN = 0.02f

    /** 규칙들이 실제로 요구하는 부위 (모르는 피처는 무시 — 서서 하는 종목 규칙이 섞여도 안전). */
    fun requiredParts(rules: List<PostureRule>): Set<BodyPart> =
        rules.flatMapTo(LinkedHashSet()) { FLOOR_FEATURE_PARTS[it.baseFeature].orEmpty() }

    /** 규칙별로 '이 규칙을 계산하려면 필요한 부위'. */
    fun requiredByRule(rules: List<PostureRule>): Map<String, Set<BodyPart>> =
        rules.mapNotNull { r -> FLOOR_FEATURE_PARTS[r.baseFeature]?.let { r.condition to it } }.toMap()

    /**
     * 한 프레임의 커버리지 진단.
     *
     * @param normalizedXy 33×2 정규화 좌표(0..1 이 화면 안). 화면 밖이면 MediaPipe 가 외삽한 값이 들어온다.
     * @param mirror 전면 카메라로 좌우 반전 표시 중이면 true — 안내의 좌/우를 사용자가 보는 화면 기준으로 맞춘다.
     */
    fun analyze(
        normalizedXy: FloatArray,
        visibility: FloatArray?,
        rules: List<PostureRule>,
        mirror: Boolean = false,
    ): CoverageReport {
        if (rules.isEmpty() || visibility == null || normalizedXy.size < MP_LANDMARK_COUNT * 2) {
            return CoverageReport.OK
        }
        val required = requiredParts(rules)
        if (required.isEmpty()) return CoverageReport.OK

        val missing = ArrayList<PartStatus>()
        for (part in required) {
            val vis = part.indices.minOf { visibility[it] }
            if (vis >= VIS_CUT) continue
            missing += PartStatus(part, false, outDirectionOf(part, normalizedXy, mirror))
        }
        if (missing.isEmpty()) return CoverageReport.OK

        val missingParts = missing.map { it.part }.toSet()
        val blocked = requiredByRule(rules)
            .filterValues { need -> need.any { it in missingParts } }
            .mapValues { (_, need) -> need.filter { it in missingParts } }

        return CoverageReport(
            ok = false,
            missing = missing,
            blocked = blocked,
            message = messageOf(missing),
            fix = fixOf(missing),
        )
    }

    /** 부위 중심점이 화면 밖이면 그 방향. 가장 많이 벗어난 축을 고른다. */
    private fun outDirectionOf(part: BodyPart, xy: FloatArray, mirror: Boolean): OutDirection? {
        var cx = 0f
        var cy = 0f
        for (i in part.indices) {
            cx += xy[i * 2]
            cy += xy[i * 2 + 1]
        }
        cx /= part.indices.size
        cy /= part.indices.size
        // 각 변을 얼마나 벗어났는지 (양수면 이탈)
        val left = -cx
        val right = cx - 1f
        val top = -cy
        val bottom = cy - 1f
        val worst = maxOf(left, right, top, bottom)
        if (worst <= MARGIN) return null   // 화면 안 → 가림
        return when (worst) {
            left -> if (mirror) OutDirection.RIGHT else OutDirection.LEFT
            right -> if (mirror) OutDirection.LEFT else OutDirection.RIGHT
            top -> OutDirection.TOP
            else -> OutDirection.BOTTOM
        }
    }

    private fun messageOf(missing: List<PartStatus>): String {
        val names = missing.joinToString("·") { it.part.label }
        return "$names 이(가) 화면에 들어와야 판정할 수 있어요"
    }

    /**
     * 해결책 — 원인에 따라 다르게 안내한다.
     *  · 화면 밖 부위가 여러 개(또는 서로 반대 방향) → 전신이 안 들어옴 → 카메라를 멀리
     *  · 한 방향으로만 잘림 → 그 방향으로 카메라를 옮기거나 사용자가 반대로 이동
     *  · 화면 안인데 안 보임 → 가림 → 각도·조명
     */
    private fun fixOf(missing: List<PartStatus>): String {
        val out = missing.mapNotNull { it.outDirection }
        val occluded = missing.filter { it.outDirection == null }
        if (out.isEmpty()) {
            val names = occluded.joinToString("·") { it.part.label }
            return "$names 이(가) 몸에 가려졌어요. 폰을 몸 옆으로 옮겨 옆모습이 보이게 하세요"
        }
        val dirs = out.toSet()
        val horizontal = dirs.count { it == OutDirection.LEFT || it == OutDirection.RIGHT }
        val bothSides = OutDirection.LEFT in dirs && OutDirection.RIGHT in dirs
        if (bothSides || dirs.size >= 3 || (missing.size >= 3 && horizontal > 0)) {
            return "몸 전체가 안 들어와요. 폰을 더 멀리 두거나(2~3걸음) 세로가 아닌 가로로 놓아 보세요"
        }
        val d = out.first()
        val part = missing.first { it.outDirection == d }.part.label
        return when (d) {
            OutDirection.LEFT -> "$part 이(가) 화면 왼쪽으로 벗어났어요. 폰을 왼쪽으로 옮기거나 오른쪽으로 이동하세요"
            OutDirection.RIGHT -> "$part 이(가) 화면 오른쪽으로 벗어났어요. 폰을 오른쪽으로 옮기거나 왼쪽으로 이동하세요"
            OutDirection.TOP -> "$part 이(가) 화면 위로 벗어났어요. 폰을 조금 뒤로 빼거나 위로 향하게 기울이세요"
            OutDirection.BOTTOM -> "$part 이(가) 화면 아래로 벗어났어요. 폰을 조금 뒤로 빼거나 아래로 향하게 기울이세요"
        }
    }
}

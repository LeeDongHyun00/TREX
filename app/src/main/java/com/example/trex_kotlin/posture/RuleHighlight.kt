package com.example.trex_kotlin.posture

/**
 * 위반 부위 시각화 (수정할점 #1): 위반 중인 규칙의 피처가 어느 관절을 재는지 → 스켈레톤에서
 * 그 관절·연결선을 강조해 "어디가 틀렸는지" 눈으로 보이게 한다.
 *
 * 매핑은 피처 정의(무엇을 재는가)에서 역산 — FLOOR_REQUIREMENTS 의 부위 역산과 같은 원칙.
 * 모르는 피처는 빈 집합(강조 없음) — 틀린 부위를 잘못 가리키는 것보다 안 가리키는 게 낫다.
 */
object RuleHighlight {

    private const val NOSE = 0
    private const val L_EAR = 7; private const val R_EAR = 8
    private const val L_SH = 11; private const val R_SH = 12
    private const val L_EL = 13; private const val R_EL = 14
    private const val L_WR = 15; private const val R_WR = 16
    private const val L_HIP = 23; private const val R_HIP = 24
    private const val L_KNEE = 25; private const val R_KNEE = 26
    private const val L_ANK = 27; private const val R_ANK = 28
    private const val L_HEEL = 29; private const val R_HEEL = 30
    private const val L_FOOT = 31; private const val R_FOOT = 32

    private val HEAD = setOf(NOSE, L_EAR, R_EAR)
    private val SHOULDERS = setOf(L_SH, R_SH)
    private val ELBOWS = setOf(L_EL, R_EL)
    private val WRISTS = setOf(L_WR, R_WR)
    private val HIPS = setOf(L_HIP, R_HIP)
    private val KNEES = setOf(L_KNEE, R_KNEE)
    private val ANKLES = setOf(L_ANK, R_ANK)
    private val FEET = setOf(L_HEEL, R_HEEL, L_FOOT, R_FOOT)
    private val TORSO = SHOULDERS + HIPS

    /** base feature → 강조할 MP 랜드마크. 접두 일치로 변형(_L/_R/_mean/_minside…)을 흡수한다. */
    private val PREFIX_MAP: List<Pair<String, Set<Int>>> = listOf(
        // 머리·시선
        "head_pitch" to HEAD, "head_yaw" to HEAD, "face_vs_torso" to HEAD + SHOULDERS,
        "face_vs_forward" to HEAD, "head_trunk_ang" to HEAD + HIPS, "ear_shoulder_gap" to HEAD + SHOULDERS,
        "head_ground" to HEAD,
        // 몸통·척추
        "torso_incl" to TORSO, "torso_pitch" to TORSO, "torso_roll" to TORSO,
        "sh_over_hip_fwd" to TORSO, "shoulder_asym" to SHOULDERS, "shoulder_h" to SHOULDERS,
        "shoulder_neck_gap" to SHOULDERS, "trunk_ankle_ang" to TORSO + ANKLES,
        "neck_over_ankle" to SHOULDERS + ANKLES, "hip_height_rel" to HIPS,
        // 팔
        "elbow_torso" to ELBOWS + TORSO, "elbow_wrist_h" to ELBOWS + WRISTS, "elbow_h" to ELBOWS,
        "elbow_width" to ELBOWS, "elbow" to ELBOWS,
        "forearm_vert" to ELBOWS + WRISTS, "upperarm_vert" to SHOULDERS + ELBOWS,
        "grip_w" to WRISTS, "hand_h_asym" to WRISTS, "wrist" to WRISTS,
        "palm_head_dist" to WRISTS + HEAD, "palm" to WRISTS, "hand_shoulder_off" to WRISTS + SHOULDERS,
        "wrist_shoulder_d" to WRISTS + SHOULDERS,
        // 하체
        "knee_out" to KNEES + FEET, "kneefoot" to KNEES + FEET, "knee_gap" to KNEES,
        "knee_elbow_dist" to KNEES + ELBOWS, "knee_shoulder_d" to KNEES + SHOULDERS,
        "knee_lat" to KNEES, "knee_fwd" to KNEES, "knee_h" to KNEES, "knee_dev" to KNEES,
        "knee_ground" to KNEES, "knee_ang" to HIPS + KNEES + ANKLES, "knee" to KNEES,
        "hip_below_knee" to HIPS + KNEES, "hip_dev_ankle" to HIPS + SHOULDERS + ANKLES,
        "hip_dev_knee" to HIPS + SHOULDERS + KNEES, "hip_ang" to SHOULDERS + HIPS + KNEES,
        "hip_ground" to HIPS, "hip" to HIPS,
        "stance_w" to ANKLES, "ankle_hip_d" to ANKLES + HIPS, "ankle_gap" to ANKLES,
        "ankle_ground" to ANKLES, "ankle" to ANKLES,
        "heel_lift" to FEET, "foot_pitch" to FEET + ANKLES, "foot_y" to FEET, "foot" to FEET,
        "shoulder_ground" to SHOULDERS, "shoulder_dev" to SHOULDERS + HIPS + WRISTS,
        "shoulder_arm_ang" to HIPS + SHOULDERS + ELBOWS, "shoulder" to SHOULDERS,
        "hip_asym" to HIPS, "spine" to TORSO,
    )

    fun landmarksFor(baseFeature: String): Set<Int> {
        for ((prefix, parts) in PREFIX_MAP) {
            if (baseFeature.startsWith(prefix)) return parts
        }
        return emptySet()
    }

    /** 위반 중인 규칙들의 강조 관절 합집합. */
    fun forViolations(states: List<OnsetState>): Set<Int> = states
        .filter { it.recent == Verdict.VIOLATION }
        .flatMap { landmarksFor(it.rule.baseFeature) }
        .toSet()
}

package com.example.trex_kotlin.posture

/** 계수의 끝점과 정자세 기준은 별개다. 아래 이동 폭은 검출용 초기값이며 정확도 인증값이 아니다. */
enum class CountPoint { RETURN, TURN, HOLD }
enum class MotionSide { LEFT, RIGHT, BOTH, VISIBLE, NONE }
data class MotionChannel(
    val primary: String, val support: String, val travel: Float, val supportTravel: Float,
    val direction: Int = -1, val side: MotionSide = MotionSide.NONE,
    val reanchorReady: Boolean = true,
)
data class MotionContract(
    val exercise: String, val channels: List<MotionChannel>, val countPoint: CountPoint,
    val bilateral: Boolean = false, val visibleSide: Boolean = false, val countHint: String,
    val singleLegCycle: Boolean = false,
) {
    val hold get() = countPoint == CountPoint.HOLD
}

/** 26종목만 등록한다. 평균으로 교대 동작을 지우지 않으며 같은 부위의 신호 두 개가 관측되어야 센다. */
object MotionContracts {
    const val VERSION = "motion-1"
    private fun sides(primary: String, support: String, travel: Float = 14f, aux: Float = 5f, dir: Int = -1) =
        listOf(MotionSide.LEFT to "L", MotionSide.RIGHT to "R").map { (side, suffix) ->
            MotionChannel("${primary}_$suffix", "${support}_$suffix", travel, aux, dir, side)
        }
    val all = buildList {
        fun add(ex: String, channels: List<MotionChannel>, point: CountPoint = CountPoint.RETURN,
                both: Boolean = false, visible: Boolean = false, singleLeg: Boolean = false, hint: String) {
            add(MotionContract(ex, channels, point, both, visible, hint, singleLeg))
        }
        fun legs(ex: String, both: Boolean = false, hint: String = "내려갔다 돌아오면 해당 다리 1회") =
            add(ex, sides("knee", "hip"), both = both, singleLeg = !both, hint = hint)
        legs("바벨 스쿼트", true, "내려갔다 일어서면 1회")
        legs("스텝 포워드 다이나믹 런지")
        legs("바벨 런지")
        legs("사이드 런지")
        legs("크로스 런지")
        add("바벨 데드리프트", sides("hip", "knee", dir = 1), CountPoint.TURN, both = true,
            hint = "낮은 시작에서 일어서면 1회 · 내린 뒤 다음 회")
        add("굿모닝", sides("hip", "shoulder_h", aux = .04f), both = true, hint = "숙였다 일어서면 1회")
        add("딥스", sides("elbow", "upperarm_vert"), both = true, hint = "내려갔다 팔을 펴면 1회")
        add("오버 헤드 프레스", sides("elbow", "palm_h_sh", aux = .08f, dir = 1), CountPoint.TURN, both = true,
            hint = "양팔을 밀어 올리면 1회 · 내린 뒤 다음 회")
        add("덤벨 컬", sides("elbow", "forearm_vert"), CountPoint.TURN,
            hint = "팔마다 굽힘 1회 · 왼쪽과 오른쪽 따로 측정")
        add("바벨 컬", sides("elbow", "forearm_vert"), CountPoint.TURN, both = true,
            hint = "양팔을 굽히면 1회 · 편 뒤 다음 회")
        for (ex in listOf("사이드 레터럴 레이즈", "프런트 레이즈", "업라이트로우"))
            add(ex, sides("upperarm_vert", "palm_h_sh", aux = .08f), CountPoint.TURN, both = true,
                hint = "양팔을 올리면 1회 · 내린 뒤 다음 회")
        add("랫풀 다운", sides("elbow", "upperarm_vert"), CountPoint.TURN, both = true,
            hint = "양팔을 당기면 1회 · 위로 돌아간 뒤 다음 회")
        add("스탠딩 사이드 크런치", listOf(
            MotionChannel("torso_roll", "shoulder_h_L", 8f, .04f, 1, MotionSide.LEFT, false),
            MotionChannel("torso_roll", "shoulder_h_R", 8f, .04f, -1, MotionSide.RIGHT, false)),
            hint = "옆으로 굽혔다 중앙에 돌아오면 해당 쪽 1회")
        add("스탠딩 니업", sides("hip", "knee_h", aux = .08f), CountPoint.TURN,
            hint = "무릎을 들면 해당 다리 1회 · 내린 뒤 다음 회")
        add("행잉 레그 레이즈", sides("hip", "knee_h", aux = .08f), CountPoint.TURN, both = true,
            hint = "양다리를 올리면 1회 · 내린 뒤 다음 회")
        for (ex in listOf("푸시업", "니푸쉬업"))
            add(ex, sides("elbow_ang", "wrist_shoulder_d", aux = .06f), visible = true,
                hint = "내려갔다 팔을 펴면 1회")
        add("플랭크", emptyList(), CountPoint.HOLD, hint = "횟수 대신 관측 유지시간을 측정해요")
        add("크런치", sides("hip_ang", "shoulder_knee_d", travel = 8f, aux = .04f), CountPoint.TURN,
            visible = true, hint = "몸통을 올리면 1회 · 내려온 뒤 다음 회")
        add("라잉 레그 레이즈", sides("hip_ang", "ankle_shoulder_d", aux = .08f), CountPoint.TURN,
            visible = true, hint = "다리를 올리면 1회 · 내린 뒤 다음 회")
        add("힙쓰러스트", sides("hip_ang", "hip_dev_knee", aux = .04f, dir = 1), CountPoint.TURN,
            visible = true, hint = "골반을 올리면 1회 · 내린 뒤 다음 회")
        add("시저크로스", listOf(MotionChannel("ankle_cross2d", "ankle_gap2d", .18f, .08f, 0)),
            hint = "다리 교차가 처음 순서로 돌아오면 1회")
        add("Y - Exercise", sides("hand_shoulder_off", "shoulder_arm_ang", .10f, 5f, 0),
            visible = true, hint = "팔을 들었다 내리면 1회")
    }
    fun forExercise(name: String): MotionContract? = all.firstOrNull { it.exercise == name }
}

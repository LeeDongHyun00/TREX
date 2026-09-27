package com.example.trex_kotlin

/** 교육용 안내다. 카메라 촬영 방향·판정 규칙으로 사용하지 않는다. */
internal data class ExerciseGuide(
    val name: String, val asset: String, val comment: String,
    val setup: List<String>, val steps: List<String>, val breathing: String,
    val cautions: List<String>, val sourceName: String, val sourceUrl: String,
)

internal object ExerciseGuides {
    const val breathingSource = "https://sportsmedicine.mayoclinic.org/news/weight-training-dos-and-donts-of-proper-technique/"
    // 2026-09-27 ACE·Mayo Clinic 원문 확인. 종목별 근거는 docs/EXERCISE_GUIDE_UI.md에 기록한다.
    val all = listOf(
        ExerciseGuide("기본 스쿼트", "exercise_guides/squat.gif",
            "허벅지와 엉덩이를 사용하는 기본 하체 운동입니다. 깊이보다 발바닥과 몸통을 안정적으로 유지하는 데 집중하세요.",
            setup = listOf(
                "발을 골반 너비보다 조금 넓게 벌리고 발끝을 살짝 바깥으로 향하게 서세요.",
                "복부에 힘을 주고 가슴을 편 채 몸통을 안정시키세요.",
            ),
            steps = listOf(
                "엉덩이를 뒤로 보내면서 무릎과 고관절을 함께 굽혀 내려가세요.",
                "발뒤꿈치가 뜨거나 등이 말리지 않는 범위까지 천천히 낮추세요.",
                "발바닥으로 바닥을 밀며 엉덩이와 몸통을 함께 일으켜 시작 자세로 돌아오세요.",
            ),
            breathing = "내려갈 때 들이마시고, 일어설 때 내쉬세요. 숨을 계속 참지 마세요.",
            cautions = listOf(
                "무릎이 안쪽으로 모이지 않게 발끝 방향을 따라 움직이세요.",
                "발뒤꿈치를 바닥에 두고, 균형을 유지할 수 있는 깊이로 조절하세요.",
                "반동으로 튀어 오르지 말고 움직임을 통제하세요. 통증이 생기면 중단하세요.",
            ), sourceName = "ACE · Bodyweight Squat",
            sourceUrl = "https://www.acefitness.org/resources/everyone/exercise-library/135/bodyweight-squat/"),
        ExerciseGuide("런지", "exercise_guides/lunge.gif",
            "한 발을 앞으로 내디뎌 허벅지와 엉덩이를 사용하는 운동입니다. 몸이 좌우로 흔들리지 않도록 균형을 먼저 잡으세요.",
            setup = listOf(
                "두 발로 똑바로 서서 어깨의 힘을 빼고 복부에 힘을 주세요.",
                "허리를 과하게 젖히지 말고 한쪽 발을 내디딜 준비를 하세요.",
            ),
            steps = listOf(
                "한 발을 앞으로 내디디며 뒤꿈치부터 닿게 한 뒤 발바닥을 안정적으로 디디세요.",
                "양 무릎을 굽히며 엉덩이를 바닥 쪽으로 낮추세요. 균형을 유지할 수 있는 범위에서 움직이세요.",
                "앞쪽 다리로 바닥을 밀어 시작 자세로 돌아오고 반대쪽도 진행하세요.",
            ),
            breathing = "몸을 낮출 때 들이마시고, 바닥을 밀어 돌아올 때 내쉬세요. 숨을 참지 마세요.",
            cautions = listOf(
                "몸통이 좌우로 기울거나 흔들리지 않도록 속도를 줄이세요.",
                "엉덩이를 앞으로 밀어 넣기보다 아래로 낮추세요. 등을 둥글게 말지 마세요.",
                "균형을 잃는 깊이까지 무리하지 마세요. 통증이 생기면 중단하세요.",
            ), sourceName = "ACE · Forward Lunge",
            sourceUrl = "https://www.acefitness.org/resources/everyone/exercise-library/94/forward-lunge/"),
        ExerciseGuide("덤벨 컬", "exercise_guides/dumbbell-curl.gif",
            "위팔 앞쪽의 이두근을 사용하는 운동입니다. 무게를 높이기보다 팔꿈치를 안정시키고 천천히 올리고 내리세요.",
            setup = listOf(
                "덤벨을 들고 안정적으로 서서 팔을 몸 옆에 두세요.",
                "손바닥을 앞쪽으로 향하게 하고 손목이 꺾이지 않도록 곧게 유지하세요.",
            ),
            steps = listOf(
                "팔꿈치를 몸 가까이에 둔 채 팔을 접어 덤벨을 천천히 들어 올리세요.",
                "팔이나 팔꿈치를 흔들어 반동을 만들지 마세요.",
                "손목을 곧게 유지하며 덤벨을 천천히 시작 위치로 내리세요.",
            ),
            breathing = "덤벨을 올릴 때 내쉬고, 내릴 때 들이마시세요. 숨을 참지 마세요.",
            cautions = listOf(
                "덤벨을 올리려고 손목을 안쪽으로 접지 마세요.",
                "몸이나 팔을 흔들어야 들 수 있다면 무게를 줄이세요.",
                "내려놓는 구간도 천천히 조절하세요. 통증이 생기면 중단하세요.",
            ), sourceName = "Mayo Clinic · Biceps curl with dumbbell",
            sourceUrl = "https://www.mayoclinic.org/healthy-lifestyle/fitness/multimedia/biceps-curl/vid-20084675"),
    )

    fun forName(name: String): ExerciseGuide? = all.firstOrNull { it.name == name }

    /** 첫 준비에서만 안내한다. 이미 본 동작도 버튼으로 다시 볼 수 있다. */
    fun introduction(name: String?, preparing: Boolean, seen: Set<String>): ExerciseGuide? =
        name?.takeIf { preparing && it !in seen }?.let(::forName)
}

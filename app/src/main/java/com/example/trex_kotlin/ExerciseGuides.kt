package com.example.trex_kotlin

/** 교육용 안내다. 카메라 촬영 방향·판정 규칙으로 사용하지 않는다. */
internal data class ExerciseGuide(
    val name: String, val asset: String, val comment: String,
    val setup: List<String>, val steps: List<String>, val breathing: String,
    val cautions: List<String>, val sourceName: String, val sourceUrl: String,
)

internal object ExerciseGuides {
    const val breathingSource = "https://sportsmedicine.mayoclinic.org/news/weight-training-dos-and-donts-of-proper-technique/"
    // ACE·Mayo Clinic·Physitrack·NHS 원문 확인. 종목별 근거와 적용 범위는 docs/EXERCISE_GUIDE_UI.md에 기록한다.
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
                "빨강은 화면에 보이는 대퇴사두근입니다. 대둔근도 고관절을 펴는 주요 근육이며, 햄스트링·종아리·몸통 근육도 참여합니다. 옷이나 시점에 가린 근육은 생략했습니다. 색은 자극 강도나 순위가 아닙니다.",
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
                "빨강은 화면에 보이는 대퇴사두근입니다. 대둔근도 주요 근육이며 중둔근·햄스트링·내전근·종아리가 함께 참여합니다. 보폭과 자세에 따라 역할이 달라지며, 가려진 근육은 생략했습니다. 색은 자극 강도가 아닙니다.",
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
                "빨강은 상완이두근, 주황은 전완의 상완요골근입니다. 이두근 아래의 상완근도 팔꿈치를 굽히지만 그림에서는 생략했습니다. 색은 역할 구분이며 자극 강도나 순위가 아닙니다.",
            ), sourceName = "Mayo Clinic · Biceps curl with dumbbell",
            sourceUrl = "https://www.mayoclinic.org/healthy-lifestyle/fitness/multimedia/biceps-curl/vid-20084675"),
        ExerciseGuide("크로스 런지", "exercise_guides/cross-lunge.gif",
            "한 발을 반대쪽 뒤로 교차해 디디며 허벅지와 엉덩이를 사용하는 운동입니다. 다리를 깊게 교차하기보다 앞다리의 균형을 유지하세요.",
            setup = listOf(
                "발을 골반 너비로 벌리고 서서 복부에 힘을 주세요.",
                "가슴과 골반이 앞을 향하도록 두고 두 손을 가슴 앞에 모아 균형을 잡으세요.",
            ),
            steps = listOf(
                "한 발을 반대쪽 다리의 뒤쪽 대각선으로 옮겨 앞꿈치를 디디세요.",
                "앞발의 발바닥을 바닥에 둔 채 양 무릎을 굽혀 몸을 천천히 낮추세요.",
                "앞다리로 바닥을 밀어 시작 자세로 돌아오고 반대쪽도 진행하세요.",
            ),
            breathing = "몸을 낮출 때 들이마시고, 바닥을 밀어 돌아올 때 내쉬세요. 숨을 참지 마세요.",
            cautions = listOf(
                "앞쪽 무릎이 안으로 무너지지 않도록 발끝 방향을 따라 움직이세요.",
                "골반이나 몸통을 비틀며 내려가지 마세요. 균형을 잃으면 교차 폭과 깊이를 줄이세요.",
                "뒤쪽 무릎을 바닥에 부딪치지 마세요. 무릎이나 고관절에 통증이 생기면 중단하세요.",
                "빨강은 대퇴사두근과 수행 자세에서 보이는 엉덩이 근육의 위치, 주황은 옆쪽 엉덩이의 중둔근 위치를 나타냅니다. 바지 위 색은 위치 설명입니다. 이 동작의 직접 연구는 부족해 기능 해부학에 근거했으며, 다른 런지보다 특정 근육을 더 쓴다는 뜻은 아닙니다.",
            ), sourceName = "Physitrack · Curtsy Lunge",
            sourceUrl = "https://www.physitrack.com/exercise-library/how-to-perform-the-curtsy-lunge-exercise"),
        ExerciseGuide("사이드 런지", "exercise_guides/side-lunge.gif",
            "옆으로 한 발을 내디뎌 허벅지와 엉덩이를 사용하는 운동입니다. 디딘 쪽 엉덩이를 뒤로 보내며 발바닥을 안정시키세요.",
            setup = listOf(
                "발을 골반 너비로 벌리고 두 발끝이 앞을 향하도록 서세요.",
                "복부에 힘을 주고 두 손을 가슴 앞에 모아 균형을 잡으세요.",
            ),
            steps = listOf(
                "한 발을 옆으로 내디뎌 발바닥을 바닥에 안정적으로 놓으세요.",
                "디딘 쪽 무릎을 굽히고 엉덩이를 뒤로 보내세요. 반대쪽 다리는 길게 펴 두세요.",
                "디딘 쪽 발로 바닥을 밀어 시작 자세로 돌아오고 반대쪽도 진행하세요.",
            ),
            breathing = "옆으로 디디며 몸을 낮출 때 들이마시고, 시작 자세로 돌아올 때 내쉬세요. 숨을 참지 마세요.",
            cautions = listOf(
                "굽힌 무릎이 안쪽으로 모이지 않도록 발끝 방향을 따라 움직이세요.",
                "두 발뒤꿈치를 바닥에 두세요. 발바닥이 뜨거나 무릎 방향을 유지하기 어렵다면 보폭을 줄이세요.",
                "등을 둥글게 말거나 반동으로 돌아오지 마세요. 통증이 생기면 중단하세요.",
                "빨강은 대퇴사두근, 주황은 허벅지 안쪽 내전근 부위입니다. 대둔근과 중둔근도 참여하지만 정면과 옷에 가린 부분은 생략했습니다. 내전근의 역할에는 기능 해부학적 추론이 포함되며 색은 근활성도 순위가 아닙니다.",
            ), sourceName = "ACE · Side Lunge",
            sourceUrl = "https://www.acefitness.org/resources/everyone/exercise-library/50/side-lunge/"),
        ExerciseGuide("스탠딩 니업", "exercise_guides/standing-knee-up.gif",
            "서서 무릎을 번갈아 들어 올리는 운동입니다. 몸통을 세우고 지지하는 다리로 균형을 잡으며 천천히 움직이세요.",
            setup = listOf(
                "발을 골반 너비로 벌리고 서서 시선을 앞에 두세요.",
                "손을 허리에 두고 복부에 힘을 주세요. 균형이 어렵다면 벽을 가볍게 짚으세요.",
            ),
            steps = listOf(
                "한쪽 발에 체중을 싣고 반대쪽 무릎을 몸 앞쪽으로 천천히 들어 올리세요.",
                "몸통을 세운 채 균형을 유지할 수 있는 높이까지 올리세요.",
                "발을 천천히 내려 시작 자세로 돌아오고 반대쪽 무릎도 들어 올리세요.",
            ),
            breathing = "무릎을 들어 올릴 때 내쉬고, 내릴 때 들이마시세요. 숨을 참지 마세요.",
            cautions = listOf(
                "무릎을 높이 들려고 허리를 뒤로 젖히거나 몸통을 흔들지 마세요.",
                "지지하는 발로 뛰거나 반동을 만들지 말고 움직임을 통제하세요.",
                "균형을 유지하기 어렵다면 높이와 속도를 줄이세요. 통증이 생기면 중단하세요.",
                "빨강은 고관절을 굽히는 대퇴직근, 주황은 몸통과 지지쪽 골반의 안정화 부위입니다. 주요 고관절 굽힘근인 장요근은 깊은 곳에 있어 생략했습니다. 복근만으로 다리를 드는 운동은 아니며, 색은 자극 강도가 아닙니다.",
            ), sourceName = "Physitrack · Standing marching",
            sourceUrl = "https://ca.physitrack.com/home-exercise-video/standing-marching"),
        ExerciseGuide("스탠딩 사이드 크런치", "exercise_guides/standing-side-crunch.gif",
            "서서 같은 쪽 팔꿈치와 무릎을 가까이 모으며 옆구리를 사용하는 운동입니다. 목을 당기지 말고 옆구리를 천천히 접으세요.",
            setup = listOf(
                "발을 골반 너비로 벌리고 서서 복부에 힘을 주세요.",
                "손끝을 귀 옆에 가볍게 대고 팔꿈치를 옆으로 열어 두세요.",
            ),
            steps = listOf(
                "한쪽 무릎을 몸 옆으로 들어 올리며 몸통을 같은 쪽으로 천천히 기울이세요.",
                "같은 쪽 팔꿈치와 무릎을 가까이 모으세요. 서로 닿게 하려고 무리하지 마세요.",
                "몸통을 세우고 발을 천천히 내려 시작 자세로 돌아온 뒤 반대쪽도 진행하세요.",
            ),
            breathing = "옆구리를 접으며 무릎을 올릴 때 내쉬고, 시작 자세로 돌아올 때 들이마시세요. 숨을 참지 마세요.",
            cautions = listOf(
                "손으로 머리나 목을 당기지 마세요. 어깨에 힘을 빼고 시선을 앞에 두세요.",
                "몸통을 앞으로 숙이거나 비틀지 말고 같은 쪽 옆구리를 접으세요.",
                "지지하는 발을 바닥에 두고 반동을 줄이세요. 균형을 잃거나 통증이 생기면 중단하세요.",
                "빨강은 옆구리의 외복사근, 주황은 복직근과 다리를 드는 쪽의 참여 부위입니다. 내복사근·요방형근·장요근 등 깊은 근육은 생략했습니다. 정확히 같은 동작의 직접 연구는 부족해 기능 해부학을 적용했으며 색은 자극 강도가 아닙니다.",
            ), sourceName = "Physitrack · Side crunch in standing with knee lifts",
            sourceUrl = "https://na.physitrack.com/home-exercise-video/side-crunch-in-standing-with-knee-lifts"),
        ExerciseGuide("크런치", "exercise_guides/crunch.gif",
            "복부에 힘을 주어 상체를 작게 말아 올리는 운동입니다.",
            setup = listOf(
                "매트에 등을 대고 누워 무릎을 굽히고 두 발바닥을 바닥에 두세요.",
                "손끝을 귀 옆에 가볍게 대고 팔꿈치를 열어 두세요. 목과 어깨의 힘을 빼세요.",
            ),
            steps = listOf(
                "복부에 힘을 주며 갈비뼈를 골반 쪽으로 가까이 가져가듯 상체를 천천히 말아 올리세요.",
                "상부 등이 매트에서 살짝 들리면 잠시 멈추세요. 발과 골반은 바닥에 두세요.",
                "상체를 천천히 내려 시작 자세로 돌아오세요.",
            ),
            breathing = "상체를 올릴 때 내쉬고, 내려올 때 들이마시세요. 숨을 참지 마세요.",
            cautions = listOf(
                "손으로 머리나 목을 당기지 마세요.",
                "윗몸일으키기처럼 상체를 끝까지 세우거나 반동을 쓰지 마세요.",
                "발과 골반을 바닥에 두세요. 목이나 허리에 통증이 생기면 중단하세요.",
                "빨강은 복직근, 주황은 옆구리 복사근 부위입니다. 깊은 내복사근도 참여하지만 그림에서는 생략했습니다. 위·아래 복근을 따로 고립시킨다는 뜻이 아니며 색은 자극 강도나 순위가 아닙니다.",
            ), sourceName = "ACE · Crunch",
            sourceUrl = "https://www.acefitness.org/resources/everyone/exercise-library/52/crunch/"),
        ExerciseGuide("레그 레이즈", "exercise_guides/lying-leg-raise.gif",
            "몸통을 안정시킨 채 두 다리를 함께 올리고 내리는 운동입니다.",
            setup = listOf(
                "매트에 등을 대고 누워 머리와 어깨를 편하게 내려놓으세요.",
                "팔은 몸 옆에 두고 손바닥을 바닥에 대세요. 두 다리를 나란히 펴고 복부에 힘을 주세요.",
            ),
            steps = listOf(
                "머리와 몸통을 바닥에 둔 채 두 다리를 함께 천천히 들어 올리세요.",
                "골반을 안정적으로 유지할 수 있는 높이까지 올리세요.",
                "허리가 과하게 뜨지 않는 범위까지 두 다리를 함께 천천히 내려 반복하세요.",
            ),
            breathing = "다리를 올릴 때 내쉬고, 내릴 때 들이마시세요. 숨을 참지 마세요.",
            cautions = listOf(
                "다리를 낮출 때 허리가 뜨거나 몸통이 흔들리면 내려가는 범위를 줄이세요.",
                "다리를 번갈아 들거나 무릎을 가슴 쪽으로 접지 마세요. 두 다리를 함께 움직이세요.",
                "반동으로 다리를 던지지 마세요. 허리나 고관절에 통증이 생기면 중단하세요.",
                "빨강은 허벅지 앞쪽 대퇴직근, 주황은 몸통을 안정시키는 복근 부위입니다. 다리를 드는 주요 근육인 장요근은 깊은 곳에 있어 생략했습니다. 하복부만 고립하는 운동이 아니며, 색은 자극 강도가 아닙니다.",
            ), sourceName = "Norfolk and Suffolk NHS · Double leg raises",
            sourceUrl = "https://www.nsft.nhs.uk/help-for-your-body-and-mind/"),
        ExerciseGuide("굿모닝", "exercise_guides/good-morning.gif",
            "엉덩이를 뒤로 보내며 허벅지 뒤쪽과 엉덩이를 사용하는 맨몸 운동입니다. 등을 굽히기보다 고관절을 접는 데 집중하세요.",
            setup = listOf(
                "발을 골반 너비로 벌리고 서서 두 팔을 가슴 앞에 교차하세요.",
                "무릎을 살짝 굽히고 복부에 힘을 주세요. 머리와 몸통을 자연스럽게 일직선으로 두세요.",
            ),
            steps = listOf(
                "무릎의 굽힘을 크게 바꾸지 않고 엉덩이를 뒤로 보내며 상체를 천천히 숙이세요.",
                "등을 둥글게 말지 않고 허벅지 뒤쪽이 당기는 범위까지만 고관절을 접으세요.",
                "발바닥으로 바닥을 누르며 엉덩이를 앞으로 가져와 똑바로 서세요.",
            ),
            breathing = "상체를 숙일 때 들이마시고, 일어설 때 내쉬세요. 숨을 참지 마세요.",
            cautions = listOf(
                "스쿼트처럼 무릎을 깊게 굽혀 앉지 마세요. 엉덩이를 뒤로 보내세요.",
                "고개를 들어 앞을 보려고 목을 꺾거나, 일어선 뒤 허리를 뒤로 젖히지 마세요.",
                "발바닥을 바닥에 두고 균형을 유지하세요. 허리나 허벅지 뒤쪽에 통증이 생기면 중단하세요.",
                "그림의 빨강은 허벅지 뒤쪽, 주황은 엉덩이와 척추기립근을 구분합니다. 바지 위 색은 옷 아래 근육의 위치를 나타냅니다. 색은 역할 구분이며 자극 강도나 순위가 아닙니다.",
            ), sourceName = "NSCA · Standing Good Morning (맨몸으로 적용)",
            sourceUrl = "https://www.nsca.com/education/articles/nsca-coach/exercise-progressions-for-resuming-strength-training-following-posterior-chain-muscle-injury/"),
        ExerciseGuide("푸쉬업", "exercise_guides/push-up.gif",
            "손바닥과 발끝으로 몸을 지지하며 가슴과 팔 뒤쪽을 사용하는 운동입니다. 가슴과 골반을 함께 움직이세요.",
            setup = listOf(
                "손을 어깨 너비보다 조금 넓게 바닥에 놓고 다리를 뒤로 펴 발끝으로 지지하세요.",
                "복부와 엉덩이에 힘을 주어 머리부터 발뒤꿈치까지 길게 정렬하고 시선은 바닥에 두세요.",
            ),
            steps = listOf(
                "팔꿈치를 몸통에서 약 45도 방향으로 굽히며 가슴과 골반을 함께 낮추세요.",
                "몸통 정렬을 유지할 수 있는 범위에서 가슴을 바닥 가까이 내리세요.",
                "손바닥으로 바닥을 밀며 몸 전체를 함께 올려 시작 자세로 돌아오세요.",
            ),
            breathing = "몸을 낮출 때 들이마시고, 바닥을 밀어 올라올 때 내쉬세요. 숨을 참지 마세요.",
            cautions = listOf(
                "허리가 처지거나 엉덩이만 먼저 올라가지 않도록 복부에 힘을 유지하세요.",
                "팔꿈치를 어깨 옆으로 과하게 벌리거나 목을 앞으로 내밀지 마세요.",
                "자세를 유지하기 어렵다면 내려가는 범위를 줄이세요. 손목이나 어깨에 통증이 생기면 중단하세요.",
                "그림의 빨강은 가슴과 팔 뒤쪽, 주황은 앞쪽 어깨와 전거근을 구분합니다. 몸통을 지지하는 근육도 함께 사용되며, 색은 모든 근육의 활동이나 자극 강도를 나타내지 않습니다.",
            ), sourceName = "NASM · Push-Up",
            sourceUrl = "https://www.nasm.org/resource-center/exercise-library/push-up"),
        ExerciseGuide("프런트 레이즈", "exercise_guides/front-raise.gif",
            "덤벨을 몸 앞으로 들어 올려 어깨 앞쪽을 사용하는 운동입니다. 몸통의 반동 없이 조절할 수 있는 가벼운 무게로 시작하세요.",
            setup = listOf(
                "발을 골반 너비로 벌리고 양손에 덤벨을 들어 허벅지 앞에 두세요. 손바닥은 몸을 향하게 하세요.",
                "복부에 힘을 주고 어깨의 힘을 빼세요. 팔꿈치는 살짝 굽히고 손목은 곧게 두세요.",
            ),
            steps = listOf(
                "팔꿈치의 굽힘을 유지하며 두 덤벨을 몸 앞으로 천천히 들어 올리세요.",
                "손등이 위를 향하도록 두고 팔이 어깨 높이에 이르면 멈추세요.",
                "몸통을 세운 채 덤벨을 천천히 허벅지 앞으로 내려 반복하세요.",
            ),
            breathing = "덤벨을 올릴 때 내쉬고, 내릴 때 들이마시세요. 숨을 참지 마세요.",
            cautions = listOf(
                "덤벨을 올리려고 허리를 뒤로 젖히거나 무릎과 몸통으로 반동을 만들지 마세요.",
                "어깨를 귀 쪽으로 움츠리지 말고, 팔을 어깨 높이보다 높게 들지 마세요.",
                "손목을 꺾거나 덤벨을 떨어뜨리듯 내리지 마세요. 어깨에 통증이 생기면 중단하세요.",
                "그림의 빨강은 앞쪽 어깨, 주황은 윗가슴을 구분합니다. 색은 주요 대상과 함께 쓰이는 부위를 설명하며, 자극 강도나 순위를 뜻하지 않습니다.",
            ), sourceName = "ACE · Front Raise",
            sourceUrl = "https://www.acefitness.org/resources/everyone/exercise-library/54/front-raise/"),
        ExerciseGuide("사이드 레터럴 레이즈", "exercise_guides/lateral-raise.gif",
            "덤벨을 양옆으로 들어 올려 어깨 옆쪽을 사용하는 운동입니다. 무게보다 어깨와 팔의 움직임을 천천히 조절하는 데 집중하세요.",
            setup = listOf(
                "발을 골반 너비 정도로 벌리고 양손에 덤벨을 들어 몸 옆에 두세요. 손바닥은 몸을 향하게 하세요.",
                "복부에 힘을 주고 몸통을 세우세요. 팔꿈치를 살짝 굽히고 손목은 곧게 유지하세요.",
            ),
            steps = listOf(
                "팔꿈치와 위팔을 함께 움직이며 두 팔을 양옆으로 천천히 들어 올리세요.",
                "어깨 높이 가까이에서는 엄지 쪽을 살짝 위로 향하게 하고, 위팔이 어깨 높이에 이르면 멈추세요.",
                "팔꿈치의 굽힘을 유지하며 덤벨을 천천히 몸 옆으로 내리세요.",
            ),
            breathing = "덤벨을 올릴 때 내쉬고, 내릴 때 들이마시세요. 숨을 참지 마세요.",
            cautions = listOf(
                "어깨를 귀 쪽으로 끌어올리거나 몸통을 흔들어 덤벨을 들지 마세요.",
                "물을 따르듯 엄지를 아래로 꺾지 마세요. 손목을 곧게 두고 어깨 높이까지만 올리세요.",
                "동작을 통제하기 어렵다면 무게를 줄이세요. 어깨에 통증이 생기면 중단하세요.",
                "그림의 빨강은 어깨 중간부, 주황은 승모근 위쪽과 전거근을 구분합니다. 깊은 곳의 극상근 등 회전근개도 함께 작용하지만 그림에서는 생략했습니다. 색은 자극 강도나 순위가 아닙니다.",
            ), sourceName = "ACE · Lateral Raise",
            sourceUrl = "https://www.acefitness.org/resources/everyone/exercise-library/26/lateral-raise/"),
        ExerciseGuide("바벨 컬", "exercise_guides/barbell-curl.gif",
            "바벨을 양손으로 함께 들어 팔꿈치를 굽히는 운동입니다. 몸통 반동 없이 조절할 수 있는 무게를 사용하세요.",
            setup = listOf(
                "발을 골반 너비로 벌리고 바벨을 어깨 너비 정도로 잡으세요. 손바닥은 앞을 향하게 하세요.",
                "손목을 곧게 두고 팔꿈치를 몸 옆에 놓으세요. 복부에 힘을 주고 몸통을 세우세요.",
            ),
            steps = listOf(
                "위팔을 몸 옆에 둔 채 두 팔꿈치를 함께 굽혀 바벨을 들어 올리세요.",
                "팔꿈치가 앞으로 크게 움직이거나 몸통이 흔들리기 전까지 편안한 범위로 올리세요.",
                "바벨을 천천히 내려 시작 자세로 돌아오세요. 팔꿈치를 뒤로 꺾어 잠그지 마세요.",
            ),
            breathing = "바벨을 올릴 때 내쉬고 내릴 때 들이마시세요. 숨을 참지 마세요.",
            cautions = listOf(
                "허리를 젖히거나 무릎과 몸통의 반동으로 바벨을 올리지 마세요.",
                "손목이 꺾이지 않게 하고, 손목이나 팔꿈치에 통증이 있으면 중단하세요.",
                "빨강은 상완이두근, 주황은 상완요골근입니다. 깊은 상완근도 팔꿈치 굽힘에 참여하지만 생략했습니다. 색은 자극 강도나 덤벨 컬과의 우열을 뜻하지 않습니다.",
            ), sourceName = "Marcolin et al. · Curl EMG study",
            sourceUrl = "https://pmc.ncbi.nlm.nih.gov/articles/PMC6047503/"),
        ExerciseGuide("바벨 런지", "exercise_guides/barbell-lunge.gif",
            "바벨을 등 위쪽에 지지하고 한 발씩 앞으로 디디는 런지입니다. 먼저 맨몸에서 균형을 익힌 뒤 가벼운 무게를 사용하세요.",
            setup = listOf(
                "바벨을 목뼈가 아닌 등 위쪽 근육에 안정적으로 지지하고 양손으로 잡으세요.",
                "발을 골반 너비로 두고 몸통에 힘을 주세요. 주변 공간과 바벨 양쪽 원판의 고정을 확인하세요.",
            ),
            steps = listOf(
                "한 발을 앞으로 내디뎌 발바닥을 안정적으로 지지하세요.",
                "몸통을 안정시킨 채 양 무릎을 천천히 굽히세요. 뒤쪽 무릎은 바닥에 부딪히지 않게 하세요.",
                "앞발로 바닥을 밀어 시작 자세로 돌아오세요. 균형을 회복한 뒤 반대쪽도 진행하세요.",
            ),
            breathing = "내려갈 때 들이마시고 올라올 때 내쉬세요. 숨을 참지 마세요.",
            cautions = listOf(
                "앞 무릎이 안쪽으로 모이지 않게 발끝 방향을 따라 움직이세요.",
                "바벨이 흔들리거나 균형이 무너지면 무게와 보폭을 줄이세요. 통증이 생기면 중단하세요.",
                "빨강은 보이는 대퇴사두근입니다. 대둔근·중둔근·햄스트링·내전근과 몸통 안정화 근육도 참여하지만 가려진 부분은 생략했습니다. 부하 런지 연구를 참고했으며 색은 자극 강도나 바벨 위치별 우열이 아닙니다.",
            ), sourceName = "Escamilla et al. · Loaded forward lunge",
            sourceUrl = "https://pubmed.ncbi.nlm.nih.gov/39982282/"),
        ExerciseGuide("플랭크", "exercise_guides/forearm-plank.gif",
            "전완과 발끝으로 몸을 지지하며 몸통을 안정적으로 유지하는 운동입니다.",
            setup = listOf(
                "매트에 엎드려 팔꿈치를 어깨 바로 아래에 놓고 전완을 바닥에 대세요.",
                "발끝을 바닥에 대고 다리를 펴세요. 복부에 힘을 주고 시선은 바닥에 두세요.",
            ),
            steps = listOf(
                "전완과 발끝으로 바닥을 밀며 몸통을 천천히 들어 올리세요.",
                "머리부터 발뒤꿈치까지 길게 이어지는 자세를 유지하세요.",
                "자세를 유지하기 어려워지면 몸을 천천히 내려 쉬세요.",
            ),
            breathing = "유지하는 동안 자연스럽게 들이마시고 내쉬세요. 숨을 참지 마세요.",
            cautions = listOf(
                "허리가 처지거나 엉덩이가 높이 올라가지 않도록 몸통을 안정시키세요.",
                "어깨를 귀 쪽으로 움츠리지 말고 팔꿈치 위에서 지지하세요.",
                "목표 시간을 채우려고 무너진 자세로 버티지 마세요. 통증이 생기면 중단하세요.",
                "빨강은 복직근, 주황은 옆구리·어깨뼈 주변·엉덩이의 안정화 부위입니다. 깊은 복횡근·내복사근도 참여하지만 생략했습니다. 바지 위 색은 근육 위치 설명이며 자극 강도가 아닙니다. GIF는 유지 자세 예시이며 몸을 위아래로 반복하지 않습니다.",
            ), sourceName = "ACE · Front Plank",
            sourceUrl = "https://www.acefitness.org/resources/everyone/exercise-library/32/front-plank/"),
    )

    fun forName(name: String): ExerciseGuide? = all.firstOrNull { it.name == name }

    /** 첫 준비에서만 안내한다. 이미 본 동작도 버튼으로 다시 볼 수 있다. */
    fun introduction(name: String?, preparing: Boolean, seen: Set<String>): ExerciseGuide? =
        name?.takeIf { preparing && it !in seen }?.let(::forName)
}

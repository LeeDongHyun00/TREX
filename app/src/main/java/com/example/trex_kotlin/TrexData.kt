package com.example.trex_kotlin

import androidx.compose.runtime.Immutable
import com.example.trex_kotlin.posture.CoachMode
import com.example.trex_kotlin.posture.OnsetKind
import com.example.trex_kotlin.posture.PostureSetReport
import com.example.trex_kotlin.posture.SetVerdict
import java.time.LocalDate
import java.util.Calendar

enum class TrexTab(val label: String) {
    Home("홈"),
    Workout("운동"),
    Diet("식단"),
    Profile("내 정보"),
}

enum class LoginMode {
    Login,
    Signup,
    Find,
}

enum class WorkoutNavigationTab(val label: String) {
    Schedule("운동 스케쥴"),
    History("운동 기록"),
}

/** 온보딩에서 수집한 사용자 프로필 — 권장 영양/강도 계산의 입력. */
@Immutable
data class UserProfile(
    val goal: String = "general",
    val dayMask: Int = 0,
    val place: String? = null,
    val bodyweightOnly: Boolean = false,
    val equipmentMask: Int = 0,
    val gender: String = "none",
    val heightCm: Double = 170.0,
    val weightKg: Double = 65.0,
    val age: Int = 30,
    val activityFactor: Double = 1.35,
)

@Immutable
data class Workout(
    val id: String,
    val name: String,
    val reps: String,
    val duration: String,
    val posture: Boolean,
    val category: String,
    val alt: WorkoutAlt? = null,
    /** 오늘 세션에서 완료했는지 (리디자인: 홈/운동 탭 진행률과 카드 번호 칩 상태). */
    val done: Boolean = false,
    /** null이면 종목별 기본 속도/휴식. 기존 저장 계획과 호환한다. */
    val secondsPerRep: Int? = null,
    val restSeconds: Int? = null,
    /** 목표 단위는 실행 종료 조건이다. null은 기존 문자열 계획을 읽는 호환 경로다. */
    val target: WorkoutTarget? = null,
)

@Immutable
data class WorkoutAlt(
    val name: String,
    val reps: String,
)

/**
 * 기록 항목에 접혀 들어가는 세트 리포트 요약 (spec §30). [focus] 는 관찰 문장(끝 마침표 없음) —
 * CLEAN 이면 "자세 깨끗", UNJUDGED 면 "자세 판정 없음", TRACK 이면 summaryLine. 나머지는 리포트에서 그대로 옮긴 값이고
 * [actualReps]/[formLabel] 만 완료 화면의 자가 라벨로 나중에 채워진다. 새 필드는 전부 기본값 null 이라 구버전 저장 기록도 그대로 읽힌다.
 */
@Immutable
data class PostureCorrection(
    val focus: String,
    /** "habit"|"drift"|"recovered"|"violation"|"clean"|"reference"|"unjudged". null = 구버전·목업 기록. */
    val kind: String? = null,
    val bodyPart: String? = null,
    val fix: String? = null,
    val note: String? = null,
    val beta: Boolean = false,
    val setId: String? = null,
    /** "coach"|"track" */
    val mode: String? = null,
    val judged: Int? = null,
    val abstained: Int? = null,
    val repsValid: Int? = null,
    val repsPartial: Int? = null,
    val tempoMs: Long? = null,
    val actualReps: Int? = null,
    val formLabel: String? = null,
)

/**
 * 세트 리포트 → 기록 항목. posture 패키지는 app 에 의존할 수 없어서 확장함수는 이쪽에 둔다.
 * kind 는 verdict 소문자를 쓰되 ISSUE 만 헤드라인의 onset 종류(habit/drift/violation)로 세분한다 — 기록 화면이 라벨 색을 고르는 키.
 */
fun PostureSetReport.toCorrection(): PostureCorrection {
    // REFERENCE 는 non-beta 헤드라인이 없으므로 첫 후보(베타)를 대표로 — beta 플래그가 같이 실려 UI 가 "참고" 로 낮춘다
    val lead = headline ?: candidates.firstOrNull()
    val focus = when {
        (exercise in com.example.trex_kotlin.posture.FloorTemporal.exercises || judged == 0) && measurements.isNotEmpty() -> measurements.joinToString(" · ")
        mode == CoachMode.TRACK -> (listOf(summaryLine) + measurements).joinToString(" · ")
        verdict == SetVerdict.CLEAN -> if (betaOnly) "검증 중인 항목 기준으로는 이상 없었어요" else "자세 깨끗했어요"
        verdict == SetVerdict.UNJUDGED -> "자세 판정 없음"
        // "좋아요, 무릎 자세가 교정됐어요" 는 코칭 발화 문장이라 "{운동}에서 {관찰}" 틀에 안 맞는다 — 기록용 관찰문으로
        verdict == SetVerdict.RECOVERED -> "${lead?.bodyPart ?: "자세"} 자세가 세트 후반에 교정됐어요"
        else -> lead?.observation ?: summaryLine
    }
    val kind = if (mode == CoachMode.TRACK) {
        if (judged == 0) "unjudged" else "reference"
    } else when (verdict) {
        SetVerdict.ISSUE -> when (headline?.kind) {
            OnsetKind.HABIT -> "habit"
            OnsetKind.DRIFT -> "drift"
            else -> "violation"
        }
        else -> verdict.name.lowercase()
    }
    return PostureCorrection(
        focus = focus,
        kind = kind,
        bodyPart = lead?.bodyPart,
        fix = lead?.fix?.takeIf { mode != CoachMode.TRACK && it.isNotBlank() && verdict != SetVerdict.RECOVERED },
        note = (listOfNotNull(lead?.note) + measurements.filterNot { focus.contains(it) }).joinToString(" · ").takeIf { it.isNotBlank() },
        beta = lead?.beta ?: false,
        setId = setId,
        mode = if (mode == CoachMode.TRACK) "track" else "coach",
        judged = judged,
        abstained = abstained,
        repsValid = repsValid,
        repsPartial = repsPartial,
        tempoMs = tempoMs,
    )
}

@Immutable
data class WorkoutHistoryItem(
    val workoutName: String,
    val reps: String,
    val durationMinutes: Int,
    val calories: Int,
    val postureCorrection: PostureCorrection? = null,
    /** 자세 정확도(%) — 자세 엔진이 산출. 없으면 null 로 두고 UI 에서 숨긴다. */
    val accuracy: Int? = null,
    val category: String? = null,
    val durationSeconds: Int? = null,
)

@Immutable
data class WorkoutHistoryDay(
    /** 그 날의 epoch day — 날짜가 바뀌어도 기록이 밀리지 않게 하는 정본 키. */
    val epochDay: Long,
    val dayLabel: String,
    val dateLabel: String,
    val items: List<WorkoutHistoryItem>,
    val averageMinutes: Int,
    val averageCalories: Int,
)

@Immutable
data class Nutrition(
    val kcal: Int,
    val carb: Double,
    val protein: Double,
    val fat: Double,
)

@Immutable
data class FoodEntry(
    val name: String,
    val nutrition: Nutrition,
    /** 수량 — 리디자인의 직접 기록 시트 스테퍼. 합산 시 곱해진다. */
    val qty: Int = 1,
)

@Immutable
data class MealMeta(
    val id: String,
    val label: String,
)

@Immutable
data class GoalItem(
    val id: String,
    val label: String,
    val description: String,
)

val todayPlan = listOf(
    Workout(
        id = "squat",
        name = "기본 스쿼트",
        reps = "12회 x 3세트",
        duration = "8분",
        posture = true,
        category = "하체",
        alt = WorkoutAlt("의자 스쿼트", "10회 x 3세트"),
    ),
    Workout(
        id = "plank",
        name = "플랭크",
        reps = "60초 x 3세트",
        duration = "5분",
        posture = false,
        category = "코어",
        alt = WorkoutAlt("데드버그", "12회 x 3세트"),
    ),
    Workout(
        id = "lunge",
        name = "런지",
        reps = "10회 x 3세트",
        duration = "10분",
        posture = true,
        category = "하체",
        alt = WorkoutAlt("제자리 스텝업", "12회 x 3세트"),
    ),
    Workout(
        id = "pushup",
        name = "푸쉬업 입문",
        reps = "8회 x 3세트",
        duration = "6분",
        posture = false,
        category = "상체",
        alt = WorkoutAlt("벽 푸쉬업", "12회 x 3세트"),
    ),
    Workout(
        id = "stretch",
        name = "마무리 스트레칭",
        reps = "전신 6분",
        duration = "6분",
        posture = false,
        category = "회복",
        alt = WorkoutAlt("폼롤러 마무리", "전신 5분"),
    ),
)

val onboardingGoals = listOf(
    GoalItem("lower", "건강한 하체 만들어룡!", "스쿼트 · 런지 중심"),
    GoalItem("diet", "다이어트를 목표로 해룡!", "유산소 + 식단 관리"),
    GoalItem("simple", "간단하게 운동만 하고 싶어룡!", "하루 10분 루틴"),
    GoalItem("core", "탄탄한 코어 잡고싶어룡!", "플랭크 · 복근 루틴"),
    GoalItem("posture", "자세부터 바로잡고 싶어룡!", "거북목 · 골반 교정"),
)

val mealMetas = listOf(
    MealMeta("breakfast", "아침"),
    MealMeta("lunch", "점심"),
    MealMeta("snack", "간식"),
    MealMeta("dinner", "저녁"),
)

val foodDatabase = linkedMapOf(
    // 모델 클래스에 없는 일반 식품 — 직접 기록 검색용
    "닭가슴살" to Nutrition(165, 0.0, 31.0, 3.6),
    "바나나" to Nutrition(89, 23.0, 1.1, 0.3),
    "오트밀" to Nutrition(150, 27.0, 5.0, 3.0),
    "그릭요거트" to Nutrition(100, 4.0, 17.0, 0.0),
    "고구마" to Nutrition(130, 30.0, 2.0, 0.1),
    "샐러드" to Nutrition(120, 8.0, 4.0, 7.0),
    "사과" to Nutrition(95, 25.0, 0.5, 0.3),
    "아몬드" to Nutrition(160, 6.0, 6.0, 14.0),
    // 아래는 음식 인식 모델(yolov8n_food)의 클래스와 1:1 대응한다.
    // AI Hub 74번 "음식분류 AI 데이터 영양DB" 의 1인분 실측값 — 괄호 안은 1인분 기준 중량.
    "쌀밥" to Nutrition(335, 73.7, 5.8, 0.5),  // 210g
    "기타잡곡밥" to Nutrition(302, 65.5, 6.7, 0.7),  // 200g
    "콩밥" to Nutrition(323, 65.8, 8.4, 1.7),  // 200g
    "보리밥" to Nutrition(316, 70.6, 5.6, 0.1),  // 200g
    "돌솥밥" to Nutrition(529, 101.9, 10.2, 8.3),  // 350g
    "현미밥" to Nutrition(351, 77.9, 6.7, 1.1),  // 230g
    "흑미밥" to Nutrition(318, 70.3, 5.4, 0.4),  // 200g
    "감자밥" to Nutrition(308, 68.6, 5.7, 0.1),  // 200g
    "곤드레밥" to Nutrition(507, 108.1, 14.1, 8.4),  // 350g
    "김치볶음밥" to Nutrition(657, 79.3, 8.7, 5.1),  // 500g
    "주먹밥" to Nutrition(210, 36.2, 6.7, 3.5),  // 150g
    "볶음밥" to Nutrition(688, 100.3, 24.5, 19.3),  // 400g
    "일반비빔밥" to Nutrition(703, 95.7, 22.6, 25.3),  // 500g
    "전주비빔밥" to Nutrition(662, 93.0, 15.8, 13.0),  // 450g
    "삼선볶음밥" to Nutrition(684, 113.4, 19.3, 16.8),  // 400g
    "새우볶음밥" to Nutrition(635, 91.6, 24.1, 17.9),  // 400g
    "알밥" to Nutrition(607, 92.1, 15.2, 3.5),  // 400g
    "산채비빔밥" to Nutrition(496, 89.7, 10.7, 11.0),  // 400g
    "오므라이스" to Nutrition(685, 101.6, 23.1, 19.8),  // 450g
    "육회비빔밥" to Nutrition(661, 91.7, 32.0, 17.5),  // 450g
    "해물볶음밥" to Nutrition(659, 85.2, 22.7, 23.7),  // 400g
    "열무비빔밥" to Nutrition(446, 90.0, 13.0, 3.2),  // 400g
    "불고기덮밥" to Nutrition(700, 92.1, 29.3, 21.4),  // 500g
    "소고기국밥" to Nutrition(332, 54.9, 16.0, 4.7),  // 700g
    "송이덮밥" to Nutrition(600, 103.6, 16.3, 14.5),  // 600g
    "오징어덮밥" to Nutrition(694, 83.4, 40.6, 20.9),  // 500g
    "자장밥" to Nutrition(730, 98.1, 25.8, 24.9),  // 500g
    "잡채밥" to Nutrition(852, 125.7, 21.1, 28.6),  // 650g
    "잡탕밥" to Nutrition(737, 100.6, 29.0, 22.5),  // 750g
    "장어덮밥" to Nutrition(672, 103.0, 26.1, 19.2),  // 400g
    "제육덮밥" to Nutrition(797, 95.8, 37.8, 27.5),  // 500g
    "짬뽕밥" to Nutrition(697, 93.4, 41.8, 22.4),  // 900g
    "순대국밥" to Nutrition(690, 34.2, 17.3, 16.5),  // 900g
    "카레라이스" to Nutrition(653, 92.6, 13.4, 10.7),  // 500g
    "전주콩나물국밥" to Nutrition(432, 88.7, 12.5, 3.5),  // 900g
    "해물덮밥" to Nutrition(838, 100.1, 51.8, 29.0),  // 700g
    "회덮밥" to Nutrition(698, 101.8, 43.0, 11.4),  // 500g
    "소머리국밥" to Nutrition(891, 77.1, 48.9, 40.8),  // 1100g
    "돼지국밥" to Nutrition(811, 78.3, 56.8, 36.5),  // 1200g
    "하이라이스" to Nutrition(478, 84.6, 7.9, 9.7),  // 360g
    "김치김밥" to Nutrition(377, 71.8, 11.4, 4.6),  // 250g
    "농어초밥" to Nutrition(415, 74.1, 19.9, 2.4),  // 250g
    "문어초밥" to Nutrition(378, 71.8, 16.9, 1.0),  // 250g
    "새우초밥" to Nutrition(396, 69.5, 22.7, 1.2),  // 250g
    "새우튀김롤" to Nutrition(572, 81.5, 21.1, 18.9),  // 300g
    "샐러드김밥" to Nutrition(422, 75.8, 12.8, 7.5),  // 250g
    "광어초밥" to Nutrition(472, 72.5, 33.7, 3.2),  // 300g
    "소고기김밥" to Nutrition(426, 76.1, 14.7, 6.6),  // 250g
    "갈비삼각김밥" to Nutrition(183, 32.7, 6.9, 2.6),  // 100g
    "연어롤" to Nutrition(519, 76.1, 20.7, 14.7),  // 300g
    "연어초밥" to Nutrition(451, 70.9, 24.9, 5.8),  // 250g
    "유부초밥" to Nutrition(463, 78.5, 12.4, 11.2),  // 250g
    "장어초밥" to Nutrition(486, 74.8, 16.3, 12.7),  // 400g
    "참치김밥" to Nutrition(401, 47.6, 19.8, 14.6),  // 250g
    "참치마요삼각김밥" to Nutrition(190, 31.9, 8.1, 2.9),  // 100g
    "치즈김밥" to Nutrition(462, 77.1, 17.1, 8.8),  // 250g
    "캘리포니아롤" to Nutrition(468, 81.2, 12.8, 9.7),  // 300g
    "한치초밥" to Nutrition(390, 77.3, 13.5, 1.2),  // 250g
    "일반김밥" to Nutrition(349, 63.6, 10.3, 5.4),  // 200g
    "간자장" to Nutrition(808, 121.7, 29.3, 26.4),  // 650g
    "굴짬뽕" to Nutrition(641, 115.8, 37.4, 7.8),  // 900g
    "기스면" to Nutrition(646, 98.3, 43.5, 11.0),  // 1000g
    "김치라면" to Nutrition(512, 80.6, 13.0, 20.3),  // 650g
    "김치우동" to Nutrition(513, 99.1, 16.7, 4.7),  // 800g
    "김치말이국수" to Nutrition(310, 60.8, 10.4, 2.5),  // 600g
    "닭칼국수" to Nutrition(643, 70.2, 39.9, 19.6),  // 900g
    "들깨칼국수" to Nutrition(442, 76.7, 17.5, 6.8),  // 600g
    "떡라면" to Nutrition(672, 121.1, 15.5, 17.6),  // 700g
    "라면" to Nutrition(509, 83.2, 14.1, 17.7),  // 550g
    "막국수" to Nutrition(567, 111.3, 26.9, 5.1),  // 550g
    "메밀국수" to Nutrition(589, 120.1, 25.3, 4.5),  // 600g
    "물냉면" to Nutrition(580, 96.4, 28.8, 9.8),  // 800g
    "비빔국수" to Nutrition(577, 114.5, 16.7, 9.5),  // 550g
    "비빔냉면" to Nutrition(594, 91.5, 23.7, 9.1),  // 550g
    "삼선우동" to Nutrition(692, 89.2, 56.2, 10.5),  // 1000g
    "삼선자장면" to Nutrition(788, 111.6, 38.3, 24.7),  // 700g
    "삼선짬뽕" to Nutrition(629, 89.3, 39.2, 13.5),  // 900g
    "수제비" to Nutrition(622, 99.2, 38.5, 6.5),  // 800g
    "쌀국수" to Nutrition(321, 46.2, 21.8, 5.8),  // 600g
    "열무김치국수" to Nutrition(488, 81.6, 21.9, 8.2),  // 800g
    "오일소스스파게티" to Nutrition(627, 99.2, 14.7, 16.6),  // 400g
    "일식우동" to Nutrition(421, 81.2, 16.9, 1.7),  // 700g
    "볶음우동" to Nutrition(378, 62.3, 9.6, 10.8),  // 300g
    "자장면" to Nutrition(761, 134.3, 15.7, 23.2),  // 650g
    "잔치국수" to Nutrition(564, 104.7, 20.3, 6.2),  // 700g
    "짬뽕" to Nutrition(650, 118.5, 25.5, 13.5),  // 1000g
    "짬뽕라면" to Nutrition(633, 88.8, 31.4, 24.6),  // 750g
    "쫄면" to Nutrition(622, 110.9, 12.4, 6.9),  // 450g
    "치즈라면" to Nutrition(599, 83.5, 23.3, 23.3),  // 600g
    "콩국수" to Nutrition(624, 67.1, 47.8, 20.4),  // 800g
    "크림소스스파게티" to Nutrition(825, 85.9, 19.4, 45.4),  // 400g
    "토마토소스스파게티" to Nutrition(642, 102.1, 20.2, 17.4),  // 500g
    "해물칼국수" to Nutrition(621, 124.2, 24.9, 4.0),  // 900g
    "회냉면" to Nutrition(639, 131.9, 20.2, 8.8),  // 550g
    "떡국" to Nutrition(715, 144.0, 21.9, 5.4),  // 800g
    "떡만둣국" to Nutrition(625, 114.0, 22.7, 9.4),  // 700g
    "짜장라면" to Nutrition(409, 63.8, 12.0, 14.9),  // 250g
    "고기만두" to Nutrition(454, 55.3, 18.7, 18.2),  // 250g
    "군만두" to Nutrition(684, 76.2, 19.8, 31.2),  // 250g
    "김치만두" to Nutrition(425, 60.8, 18.4, 13.1),  // 250g
    "물만두" to Nutrition(158, 20.5, 5.9, 5.8),  // 120g
    "만둣국" to Nutrition(433, 53.3, 19.2, 14.9),  // 700g
    "게살죽" to Nutrition(554, 103.6, 18.1, 7.6),  // 800g
    "깨죽" to Nutrition(506, 71.1, 13.4, 18.9),  // 800g
    "닭죽" to Nutrition(1182, 92.5, 75.9, 48.2),  // 1000g
    "소고기버섯죽" to Nutrition(573, 102.9, 20.5, 6.5),  // 800g
    "어죽" to Nutrition(559, 90.5, 15.5, 6.1),  // 800g
    "잣죽" to Nutrition(873, 153.8, 15.8, 20.3),  // 700g
    "전복죽" to Nutrition(587, 105.0, 14.6, 11.5),  // 800g
    "참치죽" to Nutrition(658, 105.9, 26.1, 13.9),  // 800g
    "채소죽" to Nutrition(515, 100.8, 11.9, 5.1),  // 800g
    "팥죽" to Nutrition(483, 100.7, 20.6, 0.6),  // 600g
    "호박죽" to Nutrition(430, 111.4, 8.1, 0.7),  // 600g
    "콘스프" to Nutrition(280, 35.4, 5.8, 14.0),  // 400g
    "토마토스프" to Nutrition(382, 12.4, 18.2, 25.4),  // 400g
    "굴국" to Nutrition(194, 11.4, 22.8, 8.6),  // 450g
    "김치국" to Nutrition(86, 13.0, 6.0, 3.4),  // 450g
    "달걀국" to Nutrition(193, 5.4, 16.2, 11.1),  // 450g
    "감자국" to Nutrition(220, 37.0, 17.0, 2.2),  // 700g
    "미역국" to Nutrition(50, 4.7, 2.7, 4.4),  // 500g
    "바지락조개국" to Nutrition(159, 8.5, 25.8, 1.8),  // 550g
    "소고기무국" to Nutrition(125, 8.1, 13.5, 4.7),  // 400g
    "소고기미역국" to Nutrition(155, 6.9, 20.2, 6.8),  // 650g
    "순대국" to Nutrition(551, 22.8, 41.2, 32.9),  // 800g
    "어묵국" to Nutrition(252, 37.2, 22.9, 4.2),  // 600g
    "오징어국" to Nutrition(169, 10.6, 27.7, 2.4),  // 500g
    "토란국" to Nutrition(463, 85.2, 23.9, 7.1),  // 250g
    "탕국" to Nutrition(94, 2.6, 12.1, 4.3),  // 250g
    "홍합미역국" to Nutrition(169, 14.1, 20.0, 6.4),  // 650g
    "황태해장국" to Nutrition(184, 6.4, 25.2, 7.2),  // 600g
    "근대된장국" to Nutrition(109, 8.1, 15.3, 3.2),  // 450g
    "미소된장국" to Nutrition(38, 1.7, 4.1, 1.7),  // 150g
    "배추된장국" to Nutrition(122, 11.0, 14.0, 3.6),  // 700g
    "뼈다귀해장국" to Nutrition(716, 29.7, 53.6, 45.4),  // 1000g
    "선지(해장)국" to Nutrition(314, 22.3, 48.1, 5.0),  // 1000g
    "콩나물국" to Nutrition(23, 1.3, 1.8, 1.4),  // 400g
    "시금치된장국" to Nutrition(121, 9.9, 16.7, 3.4),  // 400g
    "시래기된장국" to Nutrition(99, 10.7, 10.0, 2.5),  // 450g
    "쑥된장국" to Nutrition(117, 17.2, 13.2, 2.6),  // 450g
    "아욱된장국" to Nutrition(104, 10.0, 12.0, 2.9),  // 450g
    "우거지된장국" to Nutrition(86, 13.7, 6.4, 1.8),  // 450g
    "우거지해장국" to Nutrition(158, 14.9, 14.2, 5.7),  // 600g
    "우렁된장국" to Nutrition(245, 21.2, 24.7, 7.0),  // 500g
    "갈비탕" to Nutrition(240, 8.2, 18.7, 14.3),  // 600g
    "감자탕" to Nutrition(964, 49.6, 60.6, 58.3),  // 900g
    "곰탕" to Nutrition(181, 15.5, 16.6, 5.4),  // 300g
    "매운탕" to Nutrition(403, 18.8, 37.2, 21.6),  // 600g
    "꼬리곰탕" to Nutrition(751, 10.9, 54.9, 52.8),  // 700g
    "꽃게탕" to Nutrition(241, 20.8, 31.7, 5.5),  // 600g
    "낙지탕" to Nutrition(186, 11.8, 29.2, 2.7),  // 600g
    "내장탕" to Nutrition(550, 13.7, 57.0, 31.5),  // 700g
    "닭곰탕" to Nutrition(528, 15.4, 58.9, 24.1),  // 650g
    "닭볶음탕" to Nutrition(372, 19.2, 33.8, 17.3),  // 300g
    "지리탕" to Nutrition(261, 10.5, 38.3, 8.4),  // 600g
    "도가니탕" to Nutrition(564, 5.6, 54.6, 34.8),  // 800g
    "삼계탕" to Nutrition(881, 44.1, 76.6, 40.6),  // 1000g
    "설렁탕" to Nutrition(423, 10.9, 52.7, 18.2),  // 600g
    "알탕" to Nutrition(424, 49.5, 49.2, 7.0),  // 700g
    "연포탕" to Nutrition(541, 21.6, 91.7, 9.2),  // 1000g
    "오리탕" to Nutrition(481, 24.2, 43.2, 21.6),  // 600g
    "추어탕" to Nutrition(339, 24.4, 37.4, 11.5),  // 700g
    "해물탕" to Nutrition(272, 19.6, 41.7, 3.4),  // 600g
    "닭개장" to Nutrition(317, 19.2, 33.7, 14.9),  // 700g
    "육개장" to Nutrition(138, 11.6, 13.4, 5.9),  // 440g
    "뼈해장국" to Nutrition(693, 25.4, 67.9, 37.2),  // 1000g
    "미역오이냉국" to Nutrition(77, 19.7, 5.6, 1.4),  // 450g
    "고등어찌개" to Nutrition(605, 32.0, 59.2, 28.2),  // 600g
    "꽁치찌개" to Nutrition(357, 14.5, 29.5, 20.7),  // 300g
    "동태찌개" to Nutrition(370, 18.9, 59.6, 7.8),  // 800g
    "부대찌개" to Nutrition(526, 46.8, 27.7, 28.5),  // 600g
    "된장찌개" to Nutrition(147, 16.0, 11.7, 5.3),  // 400g
    "청국장찌개" to Nutrition(275, 15.0, 25.8, 14.4),  // 400g
    "두부전골" to Nutrition(315, 16.2, 29.1, 19.2),  // 500g
    "곱창전골" to Nutrition(533, 26.9, 38.3, 34.7),  // 600g
    "소고기전골" to Nutrition(203, 16.5, 19.5, 7.5),  // 300g
    "국수전골" to Nutrition(643, 66.9, 45.3, 22.4),  // 400g
    "돼지고기김치찌개" to Nutrition(246, 9.3, 15.5, 18.3),  // 400g
    "버섯찌개" to Nutrition(172, 15.3, 16.6, 7.5),  // 400g
    "참치김치찌개" to Nutrition(194, 13.3, 16.9, 10.9),  // 400g
    "순두부찌개" to Nutrition(198, 8.8, 14.7, 14.0),  // 400g
    "콩비지찌개" to Nutrition(249, 24.5, 16.0, 12.8),  // 400g
    "햄김치찌개" to Nutrition(190, 15.4, 11.6, 10.7),  // 300g
    "호박찌개" to Nutrition(98, 12.7, 7.8, 2.3),  // 300g
    "고추장찌개" to Nutrition(263, 20.1, 25.6, 12.3),  // 500g
    "대구찜" to Nutrition(373, 26.4, 54.1, 8.4),  // 500g
    "도미찜" to Nutrition(126, 0.8, 21.0, 3.7),  // 100g
    "문어숙회" to Nutrition(67, 0.2, 14.1, 0.7),  // 80g
    "아귀찜" to Nutrition(311, 17.6, 48.8, 6.7),  // 400g
    "조기찜" to Nutrition(185, 1.8, 22.3, 9.2),  // 100g
    "참꼬막" to Nutrition(90, 4.9, 13.0, 2.2),  // 80g
    "해물찜" to Nutrition(397, 36.0, 50.3, 8.9),  // 500g
    "소갈비찜" to Nutrition(500, 11.3, 42.5, 29.2),  // 250g
    "돼지갈비찜" to Nutrition(250, 8.8, 20.6, 14.4),  // 170.1g
    "돼지고기수육" to Nutrition(1218, 8.7, 61.5, 99.5),  // 300g
    "찜닭" to Nutrition(1358, 140.6, 114.9, 36.5),  // 1500g
    "족발" to Nutrition(382, 32.3, 26.0, 16.6),  // 150g
    "달걀찜" to Nutrition(190, 4.8, 16.2, 10.9),  // 250g
    "닭갈비" to Nutrition(562, 24.5, 52.3, 28.9),  // 300g
    "닭꼬치" to Nutrition(178, 12.9, 12.3, 7.9),  // 70g
    "돼지갈비" to Nutrition(249, 7.6, 19.9, 14.7),  // 100g
    "떡갈비" to Nutrition(763, 26.6, 43.1, 51.6),  // 250g
    "불고기" to Nutrition(387, 13.1, 32.9, 21.8),  // 150g
    "소곱창구이" to Nutrition(639, 6.5, 35.6, 51.7),  // 150g
    "소양념갈비구이" to Nutrition(987, 27.7, 62.0, 66.5),  // 300g
    "소불고기" to Nutrition(175, 19.6, 14.2, 4.7),  // 200g
    "양념왕갈비" to Nutrition(486, 15.0, 29.3, 33.7),  // 150g
    "햄버거스테이크" to Nutrition(437, 21.4, 24.9, 28.1),  // 200g
    "훈제오리" to Nutrition(790, 11.8, 38.2, 64.7),  // 250g
    "치킨데리야끼" to Nutrition(693, 50.7, 47.3, 32.2),  // 340g
    "치킨윙" to Nutrition(219, 10.0, 11.4, 14.2),  // 100g
    "더덕구이" to Nutrition(184, 31.8, 5.8, 5.6),  // 100g
    "양배추구이" to Nutrition(61, 7.6, 2.7, 2.7),  // 100g
    "두부구이" to Nutrition(91, 1.5, 9.4, 6.3),  // 100g
    "삼치구이" to Nutrition(356, 8.5, 37.8, 18.0),  // 200g
    "가자미전" to Nutrition(220, 6.7, 30.0, 7.1),  // 150g
    "굴전" to Nutrition(193, 13.9, 12.6, 9.1),  // 100g
    "동태전" to Nutrition(265, 11.5, 19.9, 16.1),  // 150g
    "해물파전" to Nutrition(267, 27.7, 12.9, 12.6),  // 150g
    "동그랑땡" to Nutrition(312, 14.7, 19.7, 18.7),  // 150g
    "햄부침" to Nutrition(233, 9.7, 13.1, 15.5),  // 100g
    "육전" to Nutrition(197, 6.7, 19.6, 9.5),  // 100g
    "감자전" to Nutrition(366, 53.8, 9.7, 13.6),  // 200g
    "고추전" to Nutrition(261, 17.8, 13.9, 14.9),  // 150g
    "김치전" to Nutrition(286, 32.1, 13.2, 12.5),  // 150g
    "깻잎전" to Nutrition(358, 16.7, 18.3, 24.6),  // 150g
    "녹두빈대떡" to Nutrition(201, 18.6, 10.0, 8.3),  // 100g
    "미나리전" to Nutrition(216, 30.1, 6.1, 8.6),  // 150g
    "배추전" to Nutrition(241, 32.5, 6.4, 10.5),  // 150g
    "버섯전" to Nutrition(240, 18.6, 11.9, 12.9),  // 150g
    "부추전" to Nutrition(241, 32.0, 7.1, 9.5),  // 150g
    "야채전" to Nutrition(195, 25.0, 4.9, 8.8),  // 100g
    "파전" to Nutrition(281, 37.5, 7.7, 12.0),  // 150g
    "호박부침개" to Nutrition(131, 8.7, 3.4, 9.2),  // 100g
    "호박전" to Nutrition(215, 16.6, 6.6, 14.4),  // 150g
    "달걀말이" to Nutrition(172, 4.7, 12.0, 11.2),  // 100g
    "두부부침" to Nutrition(135, 4.3, 9.9, 8.8),  // 100g
    "두부전" to Nutrition(254, 8.1, 18.7, 18.0),  // 150g
    "건새우볶음" to Nutrition(69, 4.8, 7.2, 2.3),  // 20g
    "낙지볶음" to Nutrition(181, 23.5, 17.9, 3.0),  // 200g
    "멸치볶음" to Nutrition(69, 5.7, 7.0, 2.0),  // 20g
    "어묵볶음" to Nutrition(282, 36.3, 18.0, 8.1),  // 150g
    "오징어볶음" to Nutrition(244, 27.4, 20.4, 6.8),  // 200g
    "오징어채볶음" to Nutrition(56, 7.0, 5.4, 0.7),  // 20g
    "주꾸미볶음" to Nutrition(212, 21.7, 20.2, 6.1),  // 200g
    "해물볶음" to Nutrition(421, 36.5, 37.5, 15.3),  // 400g
    "감자볶음" to Nutrition(58, 8.2, 1.3, 2.5),  // 50g
    "김치볶음" to Nutrition(190, 21.8, 5.3, 12.1),  // 200g
    "깻잎나물볶음" to Nutrition(212, 17.3, 8.0, 16.4),  // 200g
    "느타리버섯볶음" to Nutrition(133, 14.2, 4.4, 8.9),  // 150g
    "두부김치" to Nutrition(292, 13.8, 19.1, 21.3),  // 250g
    "머위나물볶음" to Nutrition(103, 7.7, 4.3, 8.0),  // 150g
    "양송이버섯볶음" to Nutrition(132, 10.6, 5.5, 9.8),  // 150g
    "표고버섯볶음" to Nutrition(144, 14.3, 4.0, 7.4),  // 150g
    "고추잡채" to Nutrition(264, 22.1, 12.9, 12.7),  // 200g
    "호박볶음" to Nutrition(29, 3.1, 0.8, 2.0),  // 50g
    "돼지고기볶음" to Nutrition(353, 15.3, 25.7, 20.9),  // 200g
    "돼지껍데기볶음" to Nutrition(346, 22.7, 22.4, 19.2),  // 150g
    "소세지볶음" to Nutrition(476, 28.8, 17.0, 33.2),  // 200g
    "순대볶음" to Nutrition(580, 71.0, 17.6, 25.6),  // 400g
    "오리불고기" to Nutrition(560, 24.5, 38.2, 34.2),  // 250g
    "오삼불고기" to Nutrition(357, 21.5, 23.3, 20.2),  // 200g
    "떡볶이" to Nutrition(301, 58.9, 8.7, 3.0),  // 200g
    "라볶이" to Nutrition(266, 41.1, 8.0, 9.6),  // 200g
    "마파두부" to Nutrition(227, 11.0, 16.9, 12.0),  // 200g
    "가자미조림" to Nutrition(301, 20.3, 40.7, 6.9),  // 300g
    "갈치조림" to Nutrition(99, 5.5, 10.7, 3.9),  // 100g
    "고등어조림" to Nutrition(459, 11.0, 45.4, 25.4),  // 250g
    "꽁치조림" to Nutrition(280, 8.4, 22.6, 16.7),  // 150g
    "동태조림" to Nutrition(271, 16.8, 39.0, 4.1),  // 250g
    "북어조림" to Nutrition(185, 15.7, 23.9, 3.0),  // 100g
    "조기조림" to Nutrition(378, 14.2, 41.4, 16.3),  // 300g
    "코다리조림" to Nutrition(147, 4.6, 18.5, 5.6),  // 100g
    "달걀장조림" to Nutrition(134, 10.0, 8.8, 6.4),  // 100g
    "메추리알장조림" to Nutrition(205, 7.4, 12.1, 13.7),  // 100g
    "돼지고기메추리알장조림" to Nutrition(63, 3.1, 7.6, 2.1),  // 50g
    "소고기메추리알장조림" to Nutrition(61, 3.4, 6.7, 2.2),  // 50g
    "고추조림" to Nutrition(106, 14.8, 2.9, 4.2),  // 100g
    "감자조림" to Nutrition(39, 8.4, 1.6, 0.2),  // 50g
    "우엉조림" to Nutrition(68, 15.5, 1.1, 0.3),  // 30g
    "알감자조림" to Nutrition(56, 10.5, 1.4, 1.3),  // 50g
    "(검은)콩조림" to Nutrition(57, 7.0, 3.8, 2.1),  // 20g
    "콩조림" to Nutrition(59, 7.8, 3.6, 1.9),  // 20g
    "두부고추장조림" to Nutrition(67, 4.0, 5.1, 4.0),  // 50g
    "땅콩조림" to Nutrition(80, 6.5, 2.9, 5.1),  // 20g
    "미꾸라지튀김" to Nutrition(382, 30.6, 12.7, 22.5),  // 100g
    "새우튀김" to Nutrition(311, 21.8, 11.8, 19.6),  // 100g
    "생선가스" to Nutrition(646, 57.4, 24.5, 37.1),  // 200g
    "쥐포튀김" to Nutrition(353, 38.0, 11.2, 16.5),  // 100g
    "오징어튀김" to Nutrition(308, 26.0, 13.5, 16.5),  // 100g
    "닭강정" to Nutrition(323, 24.2, 18.3, 15.6),  // 100g
    "닭튀김" to Nutrition(910, 45.9, 54.5, 52.3),  // 300g
    "돈가스" to Nutrition(621, 36.6, 27.8, 39.1),  // 200g
    "모래집튀김" to Nutrition(457, 31.9, 22.3, 25.9),  // 150g
    "양념치킨" to Nutrition(568, 38.8, 30.5, 31.1),  // 200g
    "치즈돈가스" to Nutrition(758, 45.5, 36.0, 46.1),  // 250g
    "치킨가스" to Nutrition(582, 51.3, 31.0, 28.6),  // 200g
    "탕수육" to Nutrition(454, 56.5, 17.1, 16.6),  // 200g
    "깐풍기" to Nutrition(585, 43.4, 27.8, 33.3),  // 200g
    "감자튀김" to Nutrition(462, 50.1, 6.2, 25.8),  // 150g
    "고구마맛탕" to Nutrition(491, 90.2, 3.3, 13.5),  // 200g
    "고구마튀김" to Nutrition(242, 34.1, 3.2, 10.8),  // 100g
    "고추튀김" to Nutrition(198, 12.7, 6.5, 13.6),  // 100g
    "김말이튀김" to Nutrition(241, 32.5, 2.2, 12.4),  // 100g
    "채소튀김" to Nutrition(312, 36.3, 3.0, 18.5),  // 100g
    "노각무침" to Nutrition(81, 16.4, 3.1, 2.1),  // 150g
    "단무지무침" to Nutrition(19, 3.2, 0.5, 0.9),  // 50g
    "달래나물무침" to Nutrition(133, 25.3, 4.8, 3.3),  // 150g
    "더덕무침" to Nutrition(221, 48.4, 4.9, 2.7),  // 150g
    "도라지생채" to Nutrition(165, 38.6, 4.1, 1.7),  // 150g
    "도토리묵" to Nutrition(43, 9.9, 0.4, 0.3),  // 100g
    "마늘쫑무침" to Nutrition(38, 9.4, 1.0, 0.3),  // 30g
    "무생채" to Nutrition(74, 16.0, 2.6, 1.3),  // 150g
    "무말랭이" to Nutrition(40, 9.6, 1.4, 0.3),  // 30g
    "오이생채" to Nutrition(23, 4.6, 0.9, 0.5),  // 50g
    "파무침" to Nutrition(124, 19.5, 3.8, 5.4),  // 150g
    "상추겉절이" to Nutrition(131, 18.0, 5.6, 6.2),  // 200g
    "쑥갓나물무침" to Nutrition(95, 8.8, 5.5, 6.5),  // 150g
    "청포묵무침" to Nutrition(158, 19.7, 3.0, 4.2),  // 250g
    "해파리냉채" to Nutrition(87, 13.8, 6.6, 1.5),  // 150g
    "가지나물" to Nutrition(22, 3.0, 0.7, 1.3),  // 50g
    "고사리나물" to Nutrition(44, 3.8, 2.0, 3.3),  // 50g
    "도라지나물" to Nutrition(55, 5.3, 0.7, 3.8),  // 50g
    "무나물" to Nutrition(35, 3.0, 0.6, 2.6),  // 50g
    "미나리나물" to Nutrition(28, 2.6, 1.0, 2.1),  // 50g
    "숙주나물" to Nutrition(20, 1.6, 1.3, 1.3),  // 50g
    "시금치나물" to Nutrition(38, 3.8, 2.1, 2.4),  // 50g
    "취나물" to Nutrition(73, 3.5, 1.6, 6.7),  // 50g
    "콩나물" to Nutrition(24, 1.1, 1.6, 2.0),  // 50g
    "고구마줄기나물" to Nutrition(30, 3.0, 0.6, 2.2),  // 50g
    "우거지나물무침" to Nutrition(126, 10.3, 5.1, 8.7),  // 150g
    "골뱅이무침" to Nutrition(107, 15.6, 7.9, 2.3),  // 100g
    "김무침" to Nutrition(81, 12.2, 4.7, 4.1),  // 30g
    "미역초무침" to Nutrition(25, 5.7, 1.1, 0.5),  // 50g
    "북어채무침" to Nutrition(332, 31.3, 37.4, 5.8),  // 150g
    "회무침" to Nutrition(312, 42.9, 27.2, 4.3),  // 300g
    "쥐치채" to Nutrition(53, 10.2, 2.8, 0.2),  // 20g
    "파래무침" to Nutrition(32, 5.4, 2.3, 0.7),  // 30g
    "홍어무침" to Nutrition(193, 24.9, 21.6, 2.3),  // 200g
    "골뱅이국수무침" to Nutrition(256, 39.6, 10.5, 7.0),  // 230g
    "오징어무침" to Nutrition(250, 13.6, 38.7, 4.2),  // 200g
    "잡채" to Nutrition(199, 37.5, 2.6, 4.7),  // 150g
    "탕평채" to Nutrition(101, 10.2, 3.5, 3.1),  // 100g
)

/**
 * 사용자가 쓰는 이름 → foodDatabase 키. 같은 음식인데 표기가 다른 경우만 넣는다.
 *
 * 실사용 식단 사진 16장을 대조하다 나왔다 — "흰쌀밥"을 쳐도 "쌀밥"이 안 나왔다
 * (docs/FOOD_COVERAGE_FINDINGS.md). 부분일치로 이미 찾아지는 것(칼국수·김밥·냉면 등)은
 * 넣지 않아 표를 짧게 유지한다. 새 별칭은 실제로 못 찾은 사례가 나왔을 때만 추가한다.
 */
val foodNameAliases: Map<String, String> = mapOf(
    "흰쌀밥" to "쌀밥",
    "백미밥" to "쌀밥",
    "공기밥" to "쌀밥",
    "돈까스" to "돈가스",
    "돈카츠" to "돈가스",
    "야채무침" to "무생채",
    "채소무침" to "무생채",
    "냉모밀" to "메밀국수",
    "모밀" to "메밀국수",
    "양념고기" to "불고기",
    "소면" to "잔치국수",
    "짜장면" to "자장면",
    "라멘" to "라면",
    "계란찜" to "달걀찜",
    "계란국" to "달걀국",
    "계란말이" to "달걀말이",
)

/**
 * 영양값이 실측이 아니라 추정인 항목. 나머지는 AI Hub 74번 영양DB 의 1인분 실측값이다.
 * 결과 화면은 이 둘을 같은 확신으로 말하지 않는다 — 추정이 섞이면 그렇다고 밝힌다.
 *
 * 모델 클래스에 없는 일반 식품 8종 — 직접 기록 검색에서만 쓰인다.
 */
val approximateNutritionNames: Set<String> = setOf(
    "닭가슴살",
    "바나나",
    "오트밀",
    "그릭요거트",
    "고구마",
    "샐러드",
    "사과",
    "아몬드",
)

fun emptyDietSlots(): Map<String, List<FoodEntry>> =
    mealMetas.associate { it.id to emptyList() }

fun Nutrition.plus(other: Nutrition): Nutrition = Nutrition(
    kcal = kcal + other.kcal,
    carb = carb + other.carb,
    protein = protein + other.protein,
    fat = fat + other.fat,
)

fun Iterable<FoodEntry>.totalNutrition(): Nutrition =
    fold(Nutrition(0, 0.0, 0.0, 0.0)) { acc, entry ->
        Nutrition(
            kcal = acc.kcal + entry.nutrition.kcal * entry.qty,
            carb = acc.carb + entry.nutrition.carb * entry.qty,
            protein = acc.protein + entry.nutrition.protein * entry.qty,
            fat = acc.fat + entry.nutrition.fat * entry.qty,
        )
    }

/** @param reports workoutId → 이번 세션의 세트 리포트. 자세를 켠 운동이라도 리포트가 없으면 항목의 자세 칸은 null 이다(판정 안 한 것을 지어내지 않는다). */
fun createWorkoutHistoryDay(
    plan: List<Workout>,
    elapsedSeconds: Int,
    reports: Map<String, PostureSetReport> = emptyMap(),
    elapsedByWorkout: Map<String, Int> = emptyMap(),
): WorkoutHistoryDay {
    val calendar = Calendar.getInstance()
    val items = plan.map { workout ->
        val report = reports[workout.id]
        val seconds = elapsedByWorkout[workout.id]?.coerceAtLeast(0) ?: 0
        WorkoutHistoryItem(
            workoutName = workout.name,
            reps = workout.reps,
            durationMinutes = seconds / 60,
            durationSeconds = seconds,
            calories = workout.estimatedCalories(seconds),
            postureCorrection = report?.toCorrection(),
            accuracy = report?.accuracy,
            category = workout.category,
        )
    }

    return WorkoutHistoryDay(
        epochDay = LocalDate.now().toEpochDay(),
        dayLabel = calendar.koreanDayOfWeek(),
        dateLabel = "${calendar.get(Calendar.MONTH) + 1}/${calendar.get(Calendar.DAY_OF_MONTH)}",
        items = items,
        averageMinutes = 0,
        averageCalories = 0,
    )
}

fun List<WorkoutHistoryDay>.replaceTodayWith(record: WorkoutHistoryDay): List<WorkoutHistoryDay> {
    val existingIndex = indexOfLast { it.epochDay == record.epochDay }
    val updated = if (existingIndex >= 0) {
        toMutableList().also { it[existingIndex] = record }
    } else {
        this + record
    }
    return updated.retainVisibleWorkoutHistory(record.epochDay).sortedBy { it.epochDay }
}

fun WorkoutHistoryDay.totalMinutes(): Int =
    items.sumOf { it.durationSeconds ?: (it.durationMinutes * 60) } / 60

fun WorkoutHistoryDay.totalCalories(): Int =
    items.sumOf { it.calories }

/** 데이터가 없는 비교 기준을 만들지 않는다. */
fun WorkoutHistoryDay.summaryText(): String = dayWorkoutAssessment(this)

fun WorkoutHistoryDay.durationLabel(): String {
    val seconds = items.sumOf { it.durationSeconds ?: it.durationMinutes * 60 }
    return if (seconds < 60) "${seconds}초" else "${seconds / 60}분"
}

fun Calendar.koreanDayOfWeek(): String = when (get(Calendar.DAY_OF_WEEK)) {
    Calendar.MONDAY -> "월"
    Calendar.TUESDAY -> "화"
    Calendar.WEDNESDAY -> "수"
    Calendar.THURSDAY -> "목"
    Calendar.FRIDAY -> "금"
    Calendar.SATURDAY -> "토"
    else -> "일"
}

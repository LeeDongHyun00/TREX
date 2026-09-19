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
    // --- 구 모델(30클래스, 2026-09-09) 전용 추정값. 새 모델 적용 시 이 묶음을 지운다. ---
    "갈비구이" to Nutrition(480, 10.0, 30.0, 35.0),
    "갈치구이" to Nutrition(150, 0.0, 20.0, 7.0),
    "고등어구이" to Nutrition(200, 0.0, 22.0, 12.0),
    "곱창구이" to Nutrition(380, 5.0, 20.0, 30.0),
    "삼겹살" to Nutrition(500, 0.0, 25.0, 45.0),
    "장어구이" to Nutrition(380, 8.0, 28.0, 26.0),
    "조개구이" to Nutrition(120, 5.0, 18.0, 2.0),
    "조기구이" to Nutrition(130, 0.0, 20.0, 5.0),
    "황태구이" to Nutrition(160, 8.0, 26.0, 3.0),
    "계란국" to Nutrition(90, 3.0, 7.0, 5.0),
    "떡국_만두국" to Nutrition(520, 80.0, 20.0, 12.0),
    "무국" to Nutrition(60, 6.0, 4.0, 2.0),
    "북엇국" to Nutrition(110, 4.0, 14.0, 4.0),
    "시래기국" to Nutrition(80, 8.0, 5.0, 3.0),
    "과메기" to Nutrition(250, 2.0, 25.0, 16.0),
    "젓갈" to Nutrition(30, 2.0, 5.0, 0.5),
    "콩자반" to Nutrition(90, 10.0, 6.0, 3.0),
    "편육" to Nutrition(250, 1.0, 22.0, 17.0),
    "피자" to Nutrition(300, 32.0, 13.0, 13.0),
    "후라이드치킨" to Nutrition(600, 30.0, 45.0, 32.0),
    "갓김치" to Nutrition(25, 4.0, 1.5, 0.5),
    // 모델 클래스에 없는 일반 식품 — 직접 기록 검색용
    "닭가슴살" to Nutrition(165, 0.0, 31.0, 3.6),
    "현미밥" to Nutrition(220, 46.0, 5.0, 1.7),
    "바나나" to Nutrition(89, 23.0, 1.1, 0.3),
    "오트밀" to Nutrition(150, 27.0, 5.0, 3.0),
    "그릭요거트" to Nutrition(100, 4.0, 17.0, 0.0),
    "고구마" to Nutrition(130, 30.0, 2.0, 0.1),
    "샐러드" to Nutrition(120, 8.0, 4.0, 7.0),
    "사과" to Nutrition(95, 25.0, 0.5, 0.3),
    "아몬드" to Nutrition(160, 6.0, 6.0, 14.0),
    // 아래는 음식 인식 모델(yolov8n_food)의 클래스와 1:1 대응한다.
    // AI Hub 74번 "음식분류 AI 데이터 영양DB" 의 1인분 실측값 — 괄호 안은 1인분 기준 중량.
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
 * 영양값이 실측이 아니라 추정인 항목. 나머지는 AI Hub 74번 영양DB 의 1인분 실측값이다.
 * 결과 화면은 이 둘을 같은 확신으로 말하지 않는다 — 추정이 섞이면 그렇다고 밝힌다.
 *
 * 구 모델 전용 21종 + 일반 식품 9종(직접 기록 검색용).
 * 새 모델을 적용하면 구 모델 전용 항목과 함께 여기서도 지운다.
 */
val approximateNutritionNames: Set<String> = setOf(
    "갈비구이",
    "갈치구이",
    "고등어구이",
    "곱창구이",
    "삼겹살",
    "장어구이",
    "조개구이",
    "조기구이",
    "황태구이",
    "계란국",
    "떡국_만두국",
    "무국",
    "북엇국",
    "시래기국",
    "과메기",
    "젓갈",
    "콩자반",
    "편육",
    "피자",
    "후라이드치킨",
    "갓김치",
    "닭가슴살",
    "현미밥",
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

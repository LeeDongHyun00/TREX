package com.example.trex_kotlin

import kotlin.math.roundToInt

/*
 * 소모 kcal 의 근거(2026-11-07 밤, 사용자 결정 "MET 공식으로 바꿔" — 근거 감사 `docs/EXERCISE_DATA_AUDIT_2026-11-07.md` §3 의 '분당 고정값 5~7' 을 대체).
 *
 * 식: kcal/분 = MET × 3.5 × 체중(kg) / 200. 1 MET = 3.5 mL O₂·kg⁻¹·min⁻¹(안정 대사), 산소 1 L ≈ 5 kcal 이므로
 * MET × 3.5 × kg mL/분 ÷ 1000 × 5 = MET × 3.5 × kg / 200 kcal/분 (ACSM Guidelines for Exercise Testing and Prescription 의 대사 계산).
 *
 * MET 값: 2024 Adult Compendium of Physical Activities — Herrmann SD, Willis EA, Ainsworth BE 외, J Sport Health Sci 2024;13(1):6-12,
 * doi 10.1016/j.jshs.2023.10.010 (PubMed 38242596). 행(코드·값·설명)은 pacompendium.com 의 분류 페이지에서 2026-11-07 에 옮겨 적었다:
 * Conditioning Exercise(02xxx) · Running(12xxx) · Walking(17xxx) · Sports(15xxx). 값은 **휴식을 포함한 활동 전체의 평균**이다(저항운동 3.5 METs 는
 * 세트 사이 휴식을 포함한 세션 평균) — 그래서 시간도 가동 시간이 아니라 휴식을 포함한 시간을 쓴다(`sessionKcalByWorkout`·`offlineActivitySeconds`).
 *
 * 정확히 맞는 행이 없는 종목은 가장 가까운 행을 골랐고 그 판단을 아래 표의 주석에 남긴다(운반·제자리 걷기 등). 강도(중량·RPE·심박)는 반영하지 않는다 —
 * 그래서 어디서나 '추정' 이고, 화면 ⓘ 가 식·출처·쓰인 체중을 밝힌다(원칙 #2).
 */

/** Compendium 의 활동 행 하나 — [code] 활동 코드, [met] MET, [row] 행 설명(원문 요약). */
data class MetCode(val code: String, val met: Double, val row: String)

/** 체중 기록이 없을 때의 체중 — `UserProfile` 기본값과 같다(테스트가 잠근다). 화면 ⓘ 가 쓰인 값을 보여 주므로 사용자가 바꿀 수 있다. */
const val DEFAULT_BODY_KG = 65.0

object ExerciseMet {
    // ---- Conditioning Exercise
    /** 저항운동, 여러 종목 8~15회 — 중량·머신·케이블·밴드 종목의 기본값 */
    val RESISTANCE_MULTI = MetCode("02054", 3.5, "resistance training, multiple exercises, 8-15 repetitions at varied resistance")
    /** 스쿼트·데드리프트(중량) */
    val SQUAT_DEADLIFT = MetCode("02052", 5.0, "resistance training, squats and deadlifts, slow or explosive effort")
    /** 맨몸 저항운동 일반 — 스쿼트·런지·푸쉬업·크런치 */
    val BODYWEIGHT_GENERAL = MetCode("02056", 3.0, "bodyweight resistance exercises, general (squats, lunges, push-ups, crunches)")
    /** 보통 강도 체조 — 푸쉬업·싯업·풀업·런지 */
    val CALISTHENICS_MODERATE = MetCode("02022", 3.8, "calisthenics, moderate effort (push-ups, sit-ups, pull-ups, lunges)")
    /** 가벼운 체조 — 컬업·크런치·플랭크 */
    val CALISTHENICS_LIGHT = MetCode("02024", 2.8, "calisthenics, light effort (curl-ups, crunches, planks)")
    /** 가볍거나 보통인 일반 체조 — 등 운동, 바닥에서 오르내리기 */
    val CALISTHENICS_BACK = MetCode("02030", 3.5, "calisthenics, light or moderate effort, general (back exercises, getting up and down from the floor)")
    /** 격렬한 체조 — 푸쉬업·싯업·풀업·점핑잭·버피·배틀 로프 */
    val CALISTHENICS_VIGOROUS = MetCode("02020", 7.5, "calisthenics, vigorous effort (push-ups, sit-ups, pull-ups, jumping jacks, burpees, battle ropes)")
    /** 가벼운 스트레칭 */
    val STRETCHING = MetCode("02101", 2.3, "stretching, mild")
    /** 집에서 하는 운동 일반 — 분류도 모르는 종목의 마지막 기본값 */
    val HOME_EXERCISE = MetCode("02064", 3.8, "home exercise, general")
    // ---- Running · Walking · Sports
    /** 제자리 조깅 */
    val JOG_IN_PLACE = MetCode("12025", 4.8, "jogging, in place")
    /** 조깅+걷기 섞어서 — 종류를 모르는 유산소의 기본값 */
    val JOG_WALK = MetCode("12010", 6.0, "jog/walk combination (jogging component of less than 10 minutes)")
    /** 보통 걸음(2.8~3.4 mph, 평지) */
    val WALK_MODERATE = MetCode("17190", 3.8, "walking, 2.8 to 3.4 mph, level, moderate pace, firm surface")
    /** 섀도 복싱(가상의 라운드, 운동) */
    val BOXING_SIMULATED = MetCode("15125", 9.3, "boxing, simulated boxing round, exercise")

    /**
     * 종목별 행 — 카탈로그 72종 + 퇴역 종목(옛 기록·루틴, `retiredWorkoutCategories`). 카탈로그 종목은 전부 여기 있어야 한다(`ExerciseMetTest`).
     * 판단 기준: 바벨·덤벨 스쿼트/데드리프트 = 02052, 그 밖의 중량·머신·케이블·밴드 = 02054, 선 자세 맨몸(스쿼트·런지·푸쉬업·브릿지) = 02056,
     * 매달리거나 당기는 맨몸(풀업·딥스·인버티드 로우·행잉) = 02022, 바닥 코어(플랭크·크런치·레이즈) = 02024, 네발·엎드린 등 운동(버드독·데드버그·Y) = 02030.
     */
    private val byName: Map<String, MetCode> = mapOf(
        // 하체
        "기본 스쿼트" to SQUAT_DEADLIFT, "바벨 스쿼트" to SQUAT_DEADLIFT, "고블릿 스쿼트" to SQUAT_DEADLIFT,
        "바벨 데드리프트" to SQUAT_DEADLIFT, "덤벨 루마니안 데드리프트" to SQUAT_DEADLIFT,
        "바벨 런지" to RESISTANCE_MULTI, "굿모닝" to RESISTANCE_MULTI, "힙 쓰러스트" to RESISTANCE_MULTI, "밴드 스쿼트" to RESISTANCE_MULTI,
        "밴드 루마니안 데드리프트" to RESISTANCE_MULTI, "레그 프레스" to RESISTANCE_MULTI, "시티드 레그 컬" to RESISTANCE_MULTI, "사이드 밴드 워크" to RESISTANCE_MULTI,
        "런지" to BODYWEIGHT_GENERAL, "사이드 런지" to BODYWEIGHT_GENERAL, "크로스 런지" to BODYWEIGHT_GENERAL, "리버스 런지" to BODYWEIGHT_GENERAL,
        "의자 스쿼트" to BODYWEIGHT_GENERAL, "불가리안 스플릿 스쿼트" to BODYWEIGHT_GENERAL, "스텝업" to BODYWEIGHT_GENERAL,
        "글루트 브릿지" to BODYWEIGHT_GENERAL, "싱글레그 글루트 브릿지" to BODYWEIGHT_GENERAL, "카프 레이즈" to BODYWEIGHT_GENERAL,
        "네발기기 힙 익스텐션" to CALISTHENICS_BACK,
        // 상체
        "오버헤드 프레스" to RESISTANCE_MULTI, "랫풀 다운" to RESISTANCE_MULTI, "덤벨 컬" to RESISTANCE_MULTI, "바벨 컬" to RESISTANCE_MULTI,
        "사이드 레터럴 레이즈" to RESISTANCE_MULTI, "프런트 레이즈" to RESISTANCE_MULTI, "업라이트로우" to RESISTANCE_MULTI,
        "덤벨 벤치 프레스" to RESISTANCE_MULTI, "덤벨 플로어 프레스" to RESISTANCE_MULTI, "머신 체스트 프레스" to RESISTANCE_MULTI,
        "밴드 체스트 프레스" to RESISTANCE_MULTI, "밴드 숄더 프레스" to RESISTANCE_MULTI, "원암 덤벨 로우" to RESISTANCE_MULTI, "밴드 로우" to RESISTANCE_MULTI,
        "시티드 케이블 로우" to RESISTANCE_MULTI, "케이블 페이스 풀" to RESISTANCE_MULTI, "밴드 리버스 플라이" to RESISTANCE_MULTI,
        "케이블 트라이셉스 푸시다운" to RESISTANCE_MULTI,
        "푸쉬업" to BODYWEIGHT_GENERAL, "니 푸쉬업" to BODYWEIGHT_GENERAL, "벽 푸쉬업" to BODYWEIGHT_GENERAL, "인클라인 푸쉬업" to BODYWEIGHT_GENERAL,
        "파이크 푸쉬업" to BODYWEIGHT_GENERAL,
        "딥스" to CALISTHENICS_MODERATE, "풀업" to CALISTHENICS_MODERATE, "인버티드 로우" to CALISTHENICS_MODERATE,
        "Y 레이즈" to CALISTHENICS_BACK, "I-Y-T 레이즈" to CALISTHENICS_BACK,
        // 코어 — 운반(캐리)은 Compendium 에 전용 행이 없어 저항운동 다종목(02054)으로 둔다
        "플랭크" to CALISTHENICS_LIGHT, "무릎 플랭크" to CALISTHENICS_LIGHT, "사이드 플랭크" to CALISTHENICS_LIGHT, "무릎 사이드 플랭크" to CALISTHENICS_LIGHT,
        "싱글레그 애브도미널 프레스" to CALISTHENICS_LIGHT,
        "데드버그" to CALISTHENICS_BACK, "버드독" to CALISTHENICS_BACK,
        "팔로프 프레스" to RESISTANCE_MULTI, "파머스 캐리" to RESISTANCE_MULTI, "수트케이스 캐리" to RESISTANCE_MULTI,
        // 복근
        "크런치" to CALISTHENICS_LIGHT, "레그 레이즈" to CALISTHENICS_LIGHT, "시저 크로스" to CALISTHENICS_LIGHT, "리버스 크런치" to CALISTHENICS_LIGHT,
        "바이시클 크런치" to CALISTHENICS_LIGHT, "짐볼 크런치" to CALISTHENICS_LIGHT, "러시안 트위스트" to CALISTHENICS_LIGHT,
        "스탠딩 사이드 크런치" to BODYWEIGHT_GENERAL, "스탠딩 니업" to BODYWEIGHT_GENERAL,
        "행잉 레그 레이즈" to CALISTHENICS_MODERATE, "캡틴체어 니 레이즈" to CALISTHENICS_MODERATE,
        // 퇴역 종목(옛 기록·루틴) — 제자리 걷기는 전용 행이 없어 보통 걸음(17190), 하이 니는 제자리 조깅(12025)으로 보수적으로 둔다
        "월 싯" to CALISTHENICS_LIGHT, "푸쉬업 입문" to BODYWEIGHT_GENERAL, "플랭크 숄더탭" to CALISTHENICS_LIGHT, "할로우 홀드" to CALISTHENICS_LIGHT,
        "힙 브릿지 홀드" to CALISTHENICS_LIGHT,
        "제자리 걷기" to WALK_MODERATE, "하이 니" to JOG_IN_PLACE, "마운틴 클라이머" to CALISTHENICS_VIGOROUS, "점핑잭" to CALISTHENICS_VIGOROUS,
        "스키터 점프" to CALISTHENICS_VIGOROUS, "버피" to CALISTHENICS_VIGOROUS, "버피 테스트" to CALISTHENICS_VIGOROUS, "섀도 복싱" to BOXING_SIMULATED,
        "마무리 스트레칭" to STRETCHING, "캣카우 스트레칭" to STRETCHING, "차일드 포즈" to STRETCHING, "폼롤러 마무리" to STRETCHING,
        "햄스트링 스트레칭" to STRETCHING, "흉추 회전 스트레칭" to STRETCHING,
    )

    /** 이름이 표에 있는가 — 테스트와 '추정' 표시용. */
    fun hasRow(name: String): Boolean = name in byName

    /** 종목의 행 — 이름 표에 없으면(내가 추가한 종목) 분류 기본값: 하체·상체·부위 → 02054, 코어·복근 → 02024, 유산소 → 12010, 회복·스트레칭 → 02101, 그 밖 → 02064. */
    fun of(name: String, category: String?): MetCode = byName[name] ?: when (category) {
        "하체", "상체", "가슴", "등", "어깨", "팔", "전신" -> RESISTANCE_MULTI
        "코어", "복근" -> CALISTHENICS_LIGHT
        "유산소" -> JOG_WALK
        "회복", "스트레칭" -> STRETCHING
        else -> HOME_EXERCISE
    }
}

/** kcal = MET × 3.5 × 체중 / 200 × 분 — [seconds] 는 휴식을 포함한 활동 시간. 음수는 0 으로. */
fun estimatedKcal(met: Double, bodyKg: Double, seconds: Int): Int =
    (met * 3.5 * bodyKg / 200.0 * (seconds.coerceAtLeast(0) / 60.0)).roundToInt().coerceAtLeast(0)

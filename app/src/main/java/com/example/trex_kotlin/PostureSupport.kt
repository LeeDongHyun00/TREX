package com.example.trex_kotlin

/**
 * 자세 교정 진입 게이트 — 운동 목록·편집·세션 라우팅이 부른다. 라이브 화면 파일(PostureLive.kt)과 **따로** 둔다:
 * 그 파일의 거대 Composable 이 기기 검증기(ART)에 거부되면 같은 클래스의 이 함수까지 못 불러 운동 탭이 죽었고(2026-10-02 VerifyError),
 * 거부되지 않아도 목록 첫 진입에 그 클래스 전체를 로드·검증하느라 느렸다. 라이브 화면을 실제로 열 때만 그 클래스가 로드되게 분리한다.
 */

/** 앱 운동명 → AIHub 규칙 종목 매핑 — 여기 있는 운동만 자세 교정을 켤 수 있다. */
val postureExerciseMap: Map<String, String> = mapOf(
    "기본 스쿼트" to "바벨 스쿼트",   // 표시 이름만 바뀜 — 규칙·카운터·로그의 종목 이름은 AIHub 그대로
    "런지" to "스텝 포워드 다이나믹 런지",
    "바벨 런지" to "바벨 런지",
    "사이드 런지" to "사이드 런지",
    "크로스 런지" to "크로스 런지",
    "바벨 데드리프트" to "바벨 데드리프트",
    "굿모닝" to "굿모닝",
    "딥스" to "딥스",
    "오버헤드 프레스" to "오버 헤드 프레스",
    "덤벨 컬" to "덤벨 컬",
    "바벨 컬" to "바벨 컬",
    "사이드 레터럴 레이즈" to "사이드 레터럴 레이즈",
    "프런트 레이즈" to "프런트 레이즈",
    "랫풀 다운" to "랫풀 다운",
    "업라이트로우" to "업라이트로우",
    "스탠딩 사이드 크런치" to "스탠딩 사이드 크런치",
    "스탠딩 니업" to "스탠딩 니업",
    "행잉 레그 레이즈" to "행잉 레그 레이즈",
    // 바닥 종목 (rules_floor_v0.1 — 2D 평면 경로, 전부 beta·임계값 미보정, spec §25/§25a).
    // 바이시클 크런치는 MP 충실도 게이트 후 남은 규칙이 없어 제외.
    "푸쉬업" to "푸시업",
    "니 푸쉬업" to "니푸쉬업",
    "플랭크" to "플랭크",
    "크런치" to "크런치",
    "레그 레이즈" to "라잉 레그 레이즈",
    "힙 쓰러스트" to "힙쓰러스트",
    "시저 크로스" to "시저크로스",
    "Y 레이즈" to "Y - Exercise",
)

fun Workout.postureSupported(): Boolean = com.example.trex_kotlin.posture.ExerciseProfiles.forName(name)?.cameraEnabled == true

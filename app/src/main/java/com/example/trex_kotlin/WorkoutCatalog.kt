package com.example.trex_kotlin

// 운동 카탈로그 데이터(§108 에서 Sheets.kt 에서 옮김 — 화면 파일과 떨어져 있어야 앱 상태가 iPhone 에서도 컴파일된다).
// tools/export_ios_resources.py 가 이 파일의 WorkoutTemplate 26종을 읽는다.

internal data class WorkoutTemplate(val name: String, val reps: String, val duration: String, val category: String, val posture: Boolean)

/**
 * 운동 카탈로그 — AIHub 피트니스 자세 데이터에 규칙 근거가 있는 26종목만 둔다(spec §56).
 * 앱 이름과 AIHub 종목이 1:1 이고 전부 postureExerciseMap 에 있다. 데이터에 없는 종목을 남기면
 * 카메라는 켜지는데 판정 근거가 없어, 비교 전용 안내가 자세 평가처럼 읽힌다.
 * "기본 스쿼트" 는 AIHub 바벨 스쿼트의 앱 표시 이름이다(사용자 결정 2026-09-25 저녁, 이전엔 "바벨 스쿼트" 로 노출). 저장된 옛 이름은 로드 시 바꾼다(WorkoutNames.canonical).
 * 바이시클 크런치는 MP 충실도 게이트(spec §25a) 후 남은 규칙이 없어 처음부터 제외.
 */
internal val workoutCatalog = mapOf(
    "하체" to listOf(
        WorkoutTemplate("기본 스쿼트", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("런지", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("바벨 런지", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("사이드 런지", "10회 × 3세트", "9분", "하체", true),
        WorkoutTemplate("크로스 런지", "10회 × 3세트", "9분", "하체", true),
        WorkoutTemplate("바벨 데드리프트", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("굿모닝", "12회 × 3세트", "8분", "하체", true),
        WorkoutTemplate("힙 쓰러스트", "12회 × 3세트", "8분", "하체", true),
    ),
    "상체" to listOf(
        WorkoutTemplate("오버헤드 프레스", "10회 × 3세트", "9분", "상체", true),
        WorkoutTemplate("랫풀 다운", "12회 × 3세트", "8분", "상체", true),
        WorkoutTemplate("딥스", "10회 × 3세트", "8분", "상체", true),
        WorkoutTemplate("덤벨 컬", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("바벨 컬", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("사이드 레터럴 레이즈", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("프런트 레이즈", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("업라이트로우", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("푸쉬업", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("니 푸쉬업", "10회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("Y 레이즈", "12회 × 3세트", "6분", "상체", true),
    ),
    "코어" to listOf(
        WorkoutTemplate("플랭크", "45초 × 3세트", "6분", "코어", true),
    ),
    "복근" to listOf(
        WorkoutTemplate("스탠딩 사이드 크런치", "12회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("스탠딩 니업", "12회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("행잉 레그 레이즈", "10회 × 3세트", "8분", "복근", true),
        WorkoutTemplate("크런치", "15회 × 3세트", "6분", "복근", true),
        WorkoutTemplate("레그 레이즈", "12회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("시저 크로스", "20회 × 3세트", "7분", "복근", true),
    ),
)

package com.example.trex_kotlin

// 운동 카탈로그 데이터(§108 에서 Sheets.kt 에서 옮김 — 화면 파일과 떨어져 있어야 앱 상태가 iPhone 에서도 컴파일된다).
// tools/export_ios_resources.py 가 이 파일의 WorkoutTemplate 72종을 읽는다(줄 모양 그대로 — 정규식).

internal data class WorkoutTemplate(val name: String, val reps: String, val duration: String, val category: String, val posture: Boolean)

/**
 * 운동 카탈로그 — AIHub 피트니스 자세 데이터에 규칙 근거가 있는 26종목(spec §56) + 자세 평가 없는 46종목(§130, 2026-11-07).
 * AIHub 26종은 전부 postureExerciseMap 에 있고 앱 이름이 AIHub 종목과 1:1 이다. 나머지 46종은 map 에 없고 `ExerciseProfiles` 도 없어
 * `postureSupported()` 가 거짓이다 — 카메라 자세 교정은 그 종목의 규칙·카운터가 생길 때까지 꺼져 있다(사용자 결정 2026-11-07).
 * 46종의 근육·근거는 `MuscleLoadCatalog`(코드)와 docs/EXERCISE_EXPANSION_46.md.
 * "기본 스쿼트" 는 AIHub 바벨 스쿼트의 앱 표시 이름이다(사용자 결정 2026-09-25 저녁, 이전엔 "바벨 스쿼트" 로 노출). 저장된 옛 이름은 로드 시 바꾼다(WorkoutNames.canonical).
 * 바이시클 크런치는 MP 충실도 게이트(spec §25a) 후 남은 규칙이 없어 자세 평가 없이 §130 에서 들어왔다.
 * 2026-08 카탈로그에 있다가 §56 에서 빠진 이름(글루트 브릿지·카프 레이즈·사이드 플랭크 …)은 같은 철자로 돌아왔다 — 옛 기록과 이어지고 `retiredWorkoutCategories` 에서는 뺐다.
 */
internal val workoutCatalog = mapOf(
    "하체" to listOf(
        WorkoutTemplate("기본 스쿼트", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("런지", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("바벨 런지", "10회 × 3세트", "10분", "하체", true),
        WorkoutTemplate("사이드 런지", "10회 × 3세트", "9분", "하체", true),
        WorkoutTemplate("크로스 런지", "10회 × 3세트", "9분", "하체", true),
        WorkoutTemplate("바벨 데드리프트", "10회 × 3세트", "10분", "하체", false),
        WorkoutTemplate("굿모닝", "12회 × 3세트", "8분", "하체", true),
        WorkoutTemplate("힙 쓰러스트", "12회 × 3세트", "8분", "하체", false),
        // §130 — 자세 평가 없음
        WorkoutTemplate("의자 스쿼트", "12회 × 3세트", "8분", "하체", false),
        WorkoutTemplate("고블릿 스쿼트", "12회 × 3세트", "9분", "하체", false),
        WorkoutTemplate("밴드 스쿼트", "12회 × 3세트", "8분", "하체", false),
        WorkoutTemplate("불가리안 스플릿 스쿼트", "10회 × 3세트", "10분", "하체", false),
        WorkoutTemplate("리버스 런지", "10회 × 3세트", "9분", "하체", false),
        WorkoutTemplate("스텝업", "10회 × 3세트", "9분", "하체", false),
        WorkoutTemplate("레그 프레스", "12회 × 3세트", "10분", "하체", false),
        WorkoutTemplate("덤벨 루마니안 데드리프트", "12회 × 3세트", "9분", "하체", false),
        WorkoutTemplate("밴드 루마니안 데드리프트", "12회 × 3세트", "8분", "하체", false),
        WorkoutTemplate("시티드 레그 컬", "15회 × 3세트", "8분", "하체", false),
        WorkoutTemplate("글루트 브릿지", "15회 × 3세트", "7분", "하체", false),
        WorkoutTemplate("싱글레그 글루트 브릿지", "12회 × 3세트", "8분", "하체", false),
        WorkoutTemplate("네발기기 힙 익스텐션", "12회 × 3세트", "7분", "하체", false),
        WorkoutTemplate("사이드 밴드 워크", "15회 × 3세트", "7분", "하체", false),
        WorkoutTemplate("카프 레이즈", "20회 × 3세트", "6분", "하체", false),
    ),
    "상체" to listOf(
        WorkoutTemplate("오버헤드 프레스", "10회 × 3세트", "9분", "상체", false),
        WorkoutTemplate("랫풀 다운", "12회 × 3세트", "8분", "상체", false),
        WorkoutTemplate("딥스", "10회 × 3세트", "8분", "상체", false),
        WorkoutTemplate("덤벨 컬", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("바벨 컬", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("사이드 레터럴 레이즈", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("프런트 레이즈", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("업라이트로우", "12회 × 3세트", "7분", "상체", false),
        WorkoutTemplate("푸쉬업", "12회 × 3세트", "7분", "상체", true),
        WorkoutTemplate("니 푸쉬업", "10회 × 3세트", "7분", "상체", false),
        WorkoutTemplate("Y 레이즈", "12회 × 3세트", "6분", "상체", false),
        // §130 — 자세 평가 없음
        WorkoutTemplate("벽 푸쉬업", "12회 × 3세트", "6분", "상체", false),
        WorkoutTemplate("인클라인 푸쉬업", "12회 × 3세트", "7분", "상체", false),
        WorkoutTemplate("파이크 푸쉬업", "10회 × 3세트", "8분", "상체", false),
        WorkoutTemplate("덤벨 벤치 프레스", "12회 × 3세트", "10분", "상체", false),
        WorkoutTemplate("덤벨 플로어 프레스", "12회 × 3세트", "8분", "상체", false),
        WorkoutTemplate("머신 체스트 프레스", "12회 × 3세트", "9분", "상체", false),
        WorkoutTemplate("밴드 체스트 프레스", "12회 × 3세트", "7분", "상체", false),
        WorkoutTemplate("밴드 숄더 프레스", "12회 × 3세트", "7분", "상체", false),
        WorkoutTemplate("원암 덤벨 로우", "12회 × 3세트", "9분", "상체", false),
        WorkoutTemplate("밴드 로우", "15회 × 3세트", "8분", "상체", false),
        WorkoutTemplate("시티드 케이블 로우", "12회 × 3세트", "9분", "상체", false),
        WorkoutTemplate("인버티드 로우", "12회 × 3세트", "8분", "상체", false),
        WorkoutTemplate("풀업", "8회 × 3세트", "9분", "상체", false),
        WorkoutTemplate("케이블 페이스 풀", "15회 × 3세트", "7분", "상체", false),
        WorkoutTemplate("밴드 리버스 플라이", "15회 × 3세트", "7분", "상체", false),
        WorkoutTemplate("I-Y-T 레이즈", "6회 × 3세트", "7분", "상체", false),
        WorkoutTemplate("케이블 트라이셉스 푸시다운", "15회 × 3세트", "7분", "상체", false),
    ),
    "코어" to listOf(
        WorkoutTemplate("플랭크", "45초 × 3세트", "6분", "코어", true),
        // §130 — 자세 평가 없음(무릎 플랭크는 플랭크 엔진이 '멈춤' 으로 보는 자세라 카메라 경로를 잇지 않는다)
        WorkoutTemplate("무릎 플랭크", "25초 × 3세트", "5분", "코어", false),
        WorkoutTemplate("사이드 플랭크", "30초 × 3세트", "6분", "코어", false),
        WorkoutTemplate("무릎 사이드 플랭크", "25초 × 3세트", "5분", "코어", false),
        WorkoutTemplate("데드버그", "8회 × 3세트", "7분", "코어", false),
        WorkoutTemplate("버드독", "8회 × 3세트", "7분", "코어", false),
        WorkoutTemplate("팔로프 프레스", "12회 × 3세트", "7분", "코어", false),
        WorkoutTemplate("파머스 캐리", "40초 × 3세트", "6분", "코어", false),
        WorkoutTemplate("수트케이스 캐리", "30초 × 3세트", "6분", "코어", false),
        WorkoutTemplate("싱글레그 애브도미널 프레스", "15초 × 3세트", "5분", "코어", false),
    ),
    "복근" to listOf(
        WorkoutTemplate("스탠딩 사이드 크런치", "12회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("스탠딩 니업", "12회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("행잉 레그 레이즈", "10회 × 3세트", "8분", "복근", false),
        WorkoutTemplate("크런치", "15회 × 3세트", "6분", "복근", true),
        WorkoutTemplate("레그 레이즈", "12회 × 3세트", "7분", "복근", true),
        WorkoutTemplate("시저 크로스", "20회 × 3세트", "7분", "복근", false),
        // §130 — 자세 평가 없음
        WorkoutTemplate("리버스 크런치", "12회 × 3세트", "6분", "복근", false),
        WorkoutTemplate("바이시클 크런치", "20회 × 3세트", "7분", "복근", false),
        WorkoutTemplate("짐볼 크런치", "15회 × 3세트", "6분", "복근", false),
        WorkoutTemplate("캡틴체어 니 레이즈", "12회 × 3세트", "8분", "복근", false),
        WorkoutTemplate("러시안 트위스트", "20회 × 3세트", "7분", "복근", false),
    ),
)

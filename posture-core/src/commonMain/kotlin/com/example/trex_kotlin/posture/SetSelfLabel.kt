package com.example.trex_kotlin.posture

// PostureSetLabel.SetSelfLabel 정본 슬라이스(§108).
data class SetSelfLabel(
    val setId: String,
    val exercise: String,
    val actualReps: Int?,
    /** "edited" | "confirmed" | null(렙 라벨 없음). */
    val repsSource: String?,
    val form: FormLabel?,
    val createdAtIso: String,
    /**
     * 라벨을 적을 때 화면에 보인 횟수 단위(`RepUnit.key` — "cycle"|"side_pair"), 모르면 null(키를 쓰지 않는다 — 이전 줄과 같은 형식).
     * 런지류(좌우 짝, spec §59)의 [actualReps] 는 걸음이 아니라 **좌우 한 쌍**의 수다 — 세트 로그를 잇지 않고도 라벨을 읽을 수 있게 함께 적는다.
     * rep_truth.csv 에는 넣지 않는다(열이 고정이다 — 헤더가 바뀌면 기존 파일과 섞여 파싱이 깨진다).
     */
    val repUnit: String? = null,
)

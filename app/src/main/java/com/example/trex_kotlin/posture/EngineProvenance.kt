package com.example.trex_kotlin.posture

/** 세트를 실제로 처리한 앱·엔진 경로. 런타임에서 선택한 값만 기록한다. */
data class EngineProvenance(
    val applicationId: String,
    val repEngine: String,
    val repProfile: String?,
    val repPattern: String?,
    val formEngine: String,
    val poseEstimator: String,
)

/** 알고리즘 계약 버전. 실행한 세트의 정확도나 검증 완료를 나타내지 않는다. */
object EngineVersions {
    const val REP = "return-bilateral/1"
    const val PROFILES = "exercise-rep-profiles/1"
    const val FORM = "rule-coach/1"
    const val POSE = "mediapipe-pose-landmarker/full"
}

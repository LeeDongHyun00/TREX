package com.example.trex_kotlin.posture

/*
 * 재생기 전용 심 — 앱 PostureFloor.kt(바닥 2D 피처)를 복사하지 않고 그 자리에서 컴파일하려고, 그 파일이 참조하는 안드로이드 쪽 파일
 * (PostureAnalyzer.kt·PlankAlignment.kt — MediaPipe·Android·org.json 에 묶여 JVM 에서 컴파일되지 않는다)의 심볼만 옮겼다.
 * 바닥 피처 계산(FloorFeatureExtractor)은 앱 소스 그대로다. 여기 있는 것은 계산에 들어가지 않는다:
 *   MP_LANDMARK_COUNT — 같은 값(33)
 *   PoseSample       — PostureFloor.kt 끝의 withFeatures 확장이 컴파일되도록 같은 생성자 모양만. 재생기는 부르지 않는다
 *   PlankGeometry    — 플랭크 전용 피처. 재생기는 플랭크를 재생하지 않으므로 부르면 멈춘다(조용히 빈 값을 내지 않는다)
 */

const val MP_LANDMARK_COUNT = 33

class PoseSample(
    val detected: Boolean,
    val normalizedXy: FloatArray,
    val visibility: FloatArray,
    val features: Map<String, Float>,
    val visibleJointCount: Int,
    val inferMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val up: Vec3 = Vec3(0f, 1f, 0f),
    val upFromGravity: Boolean = false,
    val upFlipped: Boolean = false,
    val upVerified: Boolean = false,
    val world: FloatArray? = null,
)

object PlankGeometry {
    fun features(xy: FloatArray, vis: FloatArray, width: Int, height: Int): Map<String, Float> =
        error("재생기는 플랭크 피처(PlankAlignment.kt)를 컴파일하지 않는다 — 플랭크 바닥 재생은 지원하지 않음")
}

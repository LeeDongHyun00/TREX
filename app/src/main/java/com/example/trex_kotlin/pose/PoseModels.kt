package com.example.trex_kotlin.pose

import kotlin.math.min

/**
 * MediaPipe Pose Landmarker의 33개 랜드마크와 동일한 인덱스를 사용한다.
 *
 * 카메라 계층은 SDK 타입을 이 enum으로 변환한 뒤 도메인 계층에 전달한다.
 */
enum class PoseJoint(val mediaPipeIndex: Int) {
    NOSE(0),
    LEFT_EYE_INNER(1),
    LEFT_EYE(2),
    LEFT_EYE_OUTER(3),
    RIGHT_EYE_INNER(4),
    RIGHT_EYE(5),
    RIGHT_EYE_OUTER(6),
    LEFT_EAR(7),
    RIGHT_EAR(8),
    MOUTH_LEFT(9),
    MOUTH_RIGHT(10),
    LEFT_SHOULDER(11),
    RIGHT_SHOULDER(12),
    LEFT_ELBOW(13),
    RIGHT_ELBOW(14),
    LEFT_WRIST(15),
    RIGHT_WRIST(16),
    LEFT_PINKY(17),
    RIGHT_PINKY(18),
    LEFT_INDEX(19),
    RIGHT_INDEX(20),
    LEFT_THUMB(21),
    RIGHT_THUMB(22),
    LEFT_HIP(23),
    RIGHT_HIP(24),
    LEFT_KNEE(25),
    RIGHT_KNEE(26),
    LEFT_ANKLE(27),
    RIGHT_ANKLE(28),
    LEFT_HEEL(29),
    RIGHT_HEEL(30),
    LEFT_FOOT_INDEX(31),
    RIGHT_FOOT_INDEX(32),
    ;

    companion object {
        private val byIndex = entries.associateBy(PoseJoint::mediaPipeIndex)

        fun fromMediaPipeIndex(index: Int): PoseJoint? = byIndex[index]
    }
}

/** 한 관절의 좌표와 현재 프레임의 신뢰도. */
data class PoseLandmark(
    val x: Double,
    val y: Double,
    val z: Double = 0.0,
    val visibility: Double = 1.0,
    val presence: Double = 1.0,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "Pose coordinates must be finite"
        }
        require(visibility in 0.0..1.0) { "visibility must be in [0, 1]" }
        require(presence in 0.0..1.0) { "presence must be in [0, 1]" }
    }

    val confidence: Double
        get() = min(visibility, presence)
}

/**
 * 한 시점의 자세 결과.
 *
 * [landmarks]는 화면 오버레이에 쓰는 정규화 이미지 좌표이고,
 * [worldLandmarks]는 가능할 때 판정에 우선 사용하는 hip 중심 meter 좌표다.
 * 회전과 미러 필드는 화면 좌표 변환용 메타데이터이며 판정기가 좌우 관절을 뒤집지는 않는다.
 */
data class PoseFrame(
    val timestampMs: Long,
    val landmarks: Map<PoseJoint, PoseLandmark>,
    val worldLandmarks: Map<PoseJoint, PoseLandmark> = emptyMap(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val rotationDegrees: Int = 0,
    val isMirrored: Boolean = false,
) {
    init {
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(imageWidth >= 0 && imageHeight >= 0) { "image dimensions cannot be negative" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "rotationDegrees must be 0, 90, 180, or 270"
        }
    }

    /** 정규화 좌표에서 x/y 축의 물리적 비율을 맞추는 데 사용한다. */
    val imageAspectRatio: Double
        get() = if (imageWidth > 0 && imageHeight > 0) {
            imageWidth.toDouble() / imageHeight.toDouble()
        } else {
            1.0
        }
}

/**
 * 카탈로그 운동명이 아니라 아직 데이터셋 운동에 연결되지 않은 관절 규칙 프로필이다.
 * 사용자 노출/세션 라우팅에는 [com.example.trex_kotlin.catalog.AiHubExercise]만 사용한다.
 */
enum class PoseMotionProfile {
    SYMMETRIC_SQUAT,
    ALTERNATING_LUNGE,
}

enum class PoseSide {
    LEFT,
    RIGHT,
}

enum class PosePhase {
    SEEKING,
    READY,
    DESCENDING,
    BOTTOM,
    ASCENDING,
}

enum class PoseTrackingState {
    TRACKING,
    LOW_CONFIDENCE,
    NO_POSE,
}

enum class PoseFeedbackSeverity {
    INFO,
    COACHING,
    WARNING,
}

enum class PoseFeedbackCode {
    MOVE_FULL_BODY_INTO_FRAME,
    LOW_LANDMARK_CONFIDENCE,
    DESCEND_WITH_CONTROL,
    GO_DEEPER,
    DRIVE_UP,
    STAND_TALL,
    KEEP_CHEST_UP,
    KEEP_KNEES_EVEN,
    KEEP_KNEES_OUT,
    WIDEN_STANCE,
    LOWER_BACK_KNEE,
    CONTROL_MOVEMENT,
    REP_COMPLETE,
}

data class PoseFeedback(
    val code: PoseFeedbackCode,
    val message: String,
    val severity: PoseFeedbackSeverity = PoseFeedbackSeverity.COACHING,
)

/** UI와 기록 계층에서 공통으로 사용할 수 있는 운동 독립적인 측정치. */
data class PoseMetrics(
    val leftKneeAngle: Double,
    val rightKneeAngle: Double,
    val leftHipAngle: Double,
    val rightHipAngle: Double,
    val torsoLean: Double,
    val kneeAngleDifference: Double,
    val kneeTrackingRatio: Double?,
    val stanceRatio: Double?,
    val minimumConfidence: Double,
)

data class Evaluation(
    val profile: PoseMotionProfile,
    val phase: PosePhase,
    val repCount: Int,
    val trackingState: PoseTrackingState,
    val feedback: List<PoseFeedback>,
    /** 완료된 반복들의 누적 평균 점수. 첫 반복 전에는 null이다. */
    val score: Int?,
    val lastRepScore: Int?,
    val activeSide: PoseSide? = null,
    val repsBySide: Map<PoseSide, Int> = emptyMap(),
    val metrics: PoseMetrics? = null,
)

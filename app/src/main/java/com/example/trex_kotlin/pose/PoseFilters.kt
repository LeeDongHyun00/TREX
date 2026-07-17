package com.example.trex_kotlin.pose

/** 판정기가 어느 좌표 집합을 선택했는지 나타낸다. */
enum class PoseCoordinateSpace {
    NORMALIZED_IMAGE,
    WORLD,
}

/**
 * 랜드마크별 지수이동평균(EMA) 필터.
 *
 * 신뢰도가 낮아진 관절에는 이전 값을 대신 내보내지 않는다. 오래된 관절 좌표가 반복 수를
 * 올리는 일을 막기 위해 해당 관절의 필터 상태를 폐기하고 현재의 낮은 신뢰도를 그대로 보존한다.
 */
class EmaPoseSmoother(
    val alpha: Double = 0.45,
    val minimumConfidence: Double = 0.5,
    val resetAfterMs: Long = 800L,
) {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in (0, 1]" }
        require(minimumConfidence in 0.0..1.0) { "minimumConfidence must be in [0, 1]" }
        require(resetAfterMs > 0L) { "resetAfterMs must be positive" }
    }

    private val normalizedState = mutableMapOf<PoseJoint, PoseLandmark>()
    private val worldState = mutableMapOf<PoseJoint, PoseLandmark>()
    private var lastTimestampMs: Long? = null

    fun smooth(frame: PoseFrame): PoseFrame {
        val previousTimestamp = lastTimestampMs
        if (previousTimestamp != null &&
            (frame.timestampMs <= previousTimestamp || frame.timestampMs - previousTimestamp > resetAfterMs)
        ) {
            reset()
        }

        val smoothedNormalized = smoothMap(frame.landmarks, normalizedState)
        val smoothedWorld = smoothMap(frame.worldLandmarks, worldState)
        lastTimestampMs = frame.timestampMs
        return frame.copy(
            landmarks = smoothedNormalized,
            worldLandmarks = smoothedWorld,
        )
    }

    fun reset() {
        normalizedState.clear()
        worldState.clear()
        lastTimestampMs = null
    }

    private fun smoothMap(
        current: Map<PoseJoint, PoseLandmark>,
        state: MutableMap<PoseJoint, PoseLandmark>,
    ): Map<PoseJoint, PoseLandmark> {
        val disappeared = state.keys - current.keys
        disappeared.forEach(state::remove)

        return current.mapValues { (joint, landmark) ->
            if (landmark.confidence < minimumConfidence) {
                state.remove(joint)
                landmark
            } else {
                val previous = state[joint]
                val output = if (previous == null) {
                    landmark
                } else {
                    PoseLandmark(
                        x = ema(previous.x, landmark.x),
                        y = ema(previous.y, landmark.y),
                        z = ema(previous.z, landmark.z),
                        // Gate가 현재 프레임의 실제 신뢰도를 보도록 confidence는 평활하지 않는다.
                        visibility = landmark.visibility,
                        presence = landmark.presence,
                    )
                }
                state[joint] = output
                output
            }
        }
    }

    private fun ema(previous: Double, current: Double): Double =
        alpha * current + (1.0 - alpha) * previous
}

data class PoseGateResult(
    val accepted: Boolean,
    val coordinateSpace: PoseCoordinateSpace,
    val landmarks: Map<PoseJoint, PoseLandmark>,
    val missingJoints: Set<PoseJoint>,
    val lowConfidenceJoints: Set<PoseJoint>,
    val minimumConfidence: Double,
)

/** 필요한 관절이 모두 존재하고 현재 프레임에서 충분히 보이는지 검사한다. */
class PoseVisibilityGate(
    val minimumVisibility: Double = 0.6,
    val minimumPresence: Double = 0.6,
) {
    init {
        require(minimumVisibility in 0.0..1.0) { "minimumVisibility must be in [0, 1]" }
        require(minimumPresence in 0.0..1.0) { "minimumPresence must be in [0, 1]" }
    }

    fun check(frame: PoseFrame, requiredJoints: Set<PoseJoint>): PoseGateResult {
        require(requiredJoints.isNotEmpty()) { "requiredJoints cannot be empty" }

        val (space, selected) = selectCoordinateSpace(frame, requiredJoints)
        val missing = requiredJoints.filterTo(mutableSetOf()) { it !in selected }
        val lowConfidence = requiredJoints.filterTo(mutableSetOf()) { joint ->
            val landmark = selected[joint]
            landmark != null && (
                landmark.visibility < minimumVisibility || landmark.presence < minimumPresence
            )
        }
        val confidence = requiredJoints.mapNotNull(selected::get)
            .minOfOrNull(PoseLandmark::confidence)
            ?: 0.0

        return PoseGateResult(
            accepted = missing.isEmpty() && lowConfidence.isEmpty(),
            coordinateSpace = space,
            landmarks = selected,
            missingJoints = missing,
            lowConfidenceJoints = lowConfidence,
            minimumConfidence = confidence,
        )
    }

    private fun selectCoordinateSpace(
        frame: PoseFrame,
        requiredJoints: Set<PoseJoint>,
    ): Pair<PoseCoordinateSpace, Map<PoseJoint, PoseLandmark>> {
        val worldCount = requiredJoints.count(frame.worldLandmarks::containsKey)
        val normalizedCount = requiredJoints.count(frame.landmarks::containsKey)
        return if (worldCount == requiredJoints.size || worldCount > normalizedCount) {
            PoseCoordinateSpace.WORLD to frame.worldLandmarks
        } else {
            PoseCoordinateSpace.NORMALIZED_IMAGE to frame.landmarks
        }
    }
}

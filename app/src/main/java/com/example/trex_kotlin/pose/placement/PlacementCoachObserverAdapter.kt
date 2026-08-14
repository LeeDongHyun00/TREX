package com.example.trex_kotlin.pose.placement

import com.example.trex_kotlin.camera.FRONTAL_AXIS_VIEW_CONTRACT_ID
import com.example.trex_kotlin.camera.FULL_BODY_LATERAL_VIEW_CONTRACT_ID
import com.example.trex_kotlin.camera.FULL_BODY_PHASE_VIEW_CONTRACT_ID
import com.example.trex_kotlin.camera.PoseObserverUpdate

/**
 * Narrows one observer update to the diagnostics the placement coach may consume.
 *
 * This is deliberately branch-free. Every decision belongs to [PlacementCoachDisplayPolicy], which
 * a unit test can drive without standing up an observer; anything decided here would escape that
 * coverage.
 */
internal fun PoseObserverUpdate.toPlacementObservedSignal(): PlacementObservedSignal =
    PlacementObservedSignal(
        trackingStatus = trackingStatus,
        unknownReasons = unknownReasons,
        hasPrimaryPersonLock = observation.hasPrimaryPersonLock,
        fullBodyViewQualified = observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID),
        lateralViewQualified = observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID),
        frontalViewQualified = observation.isViewQualified(FRONTAL_AXIS_VIEW_CONTRACT_ID),
        candidateCount = candidateCount,
    )

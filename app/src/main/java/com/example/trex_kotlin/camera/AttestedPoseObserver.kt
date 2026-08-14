package com.example.trex_kotlin.camera

import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryEpoch
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import com.example.trex_kotlin.pose.runtime.PosePersonTrackEpoch
import com.example.trex_kotlin.pose.runtime.PoseViewQualification
import java.util.Collections
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal const val FULL_BODY_PHASE_VIEW_CONTRACT_ID = "trex.view.full-body-any.v1"
internal const val FULL_BODY_LATERAL_VIEW_CONTRACT_ID = "trex.view.lateral-full-body.v1"

/**
 * The body's bilateral axis faces the camera, front or back.
 *
 * Named for the axis rather than for the front on purpose: a shoulder and hip axis cannot tell a
 * chest from a back, and this token does not claim it can — [PoseObserverUnknownReason
 * .FRONT_REAR_UNRESOLVED] still travels with it. What the token does assert is the thing a
 * coronal-plane movement needs: the camera is looking across the plane the movement happens in
 * rather than down it. Every quantity this project measures from it is a rotation-invariant
 * included angle, which reads the same whichever way the person is turned, so the unresolved half
 * is unresolved and harmless rather than unresolved and load-bearing.
 */
internal const val FRONTAL_AXIS_VIEW_CONTRACT_ID = "trex.view.frontal-axis-full-body.v1"

/** Temporal state of the selected pose candidate. It is not a correctness verdict. */
enum class PoseObserverTrackingStatus {
    PERSON_NOT_FOUND,
    INSUFFICIENT_LANDMARKS,
    ACQUIRING,
    TRACKED,
    AMBIGUOUS,
    TRACK_DISCONTINUITY,
}

/** Coarse camera-relative body orientation used only to qualify compatible criteria. */
enum class PoseObserverView {
    FRONTAL_AXIS,
    LATERAL,
    OBLIQUE,
    UNKNOWN,
}

/** Fail-closed diagnostic reasons. They never imply that the exercise form is wrong. */
enum class PoseObserverUnknownReason {
    PERSON_NOT_FOUND,
    PERSON_AMBIGUOUS,
    PERSON_LOCK_ACQUIRING,
    PERSON_TRACK_DISCONTINUITY,
    CAMERA_GEOMETRY_DISCONTINUITY,
    REQUIRED_LANDMARK_MISSING,
    REQUIRED_LANDMARK_LOW_CONFIDENCE,
    BODY_OUT_OF_FRAME,
    BODY_TOO_SMALL,
    BODY_TOO_LARGE,
    WORLD_LANDMARKS_UNAVAILABLE,
    FRONT_REAR_UNRESOLVED,
    VIEW_AMBIGUOUS,
    VIEW_QUALIFICATION_STABILIZING,
}

/**
 * One observer update. The observation may intentionally carry no person epoch or view token.
 * Consumers must use [observation] rather than [displayFrame] for evaluation.
 */
class PoseObserverUpdate internal constructor(
    val observation: AttestedPoseObservation,
    val displayFrame: PoseFrame?,
    val trackingStatus: PoseObserverTrackingStatus,
    val view: PoseObserverView,
    val candidateCount: Int,
    unknownReasons: Set<PoseObserverUnknownReason>,
) {
    val unknownReasons: Set<PoseObserverUnknownReason> =
        Collections.unmodifiableSet(LinkedHashSet(unknownReasons.sortedBy { it.name }))
}

internal data class PoseCandidateBatch(
    val timestampMs: Long,
    val candidates: List<PoseFrame>,
    val rawCandidateCount: Int = candidates.size,
    val geometryContext: PoseCameraGeometryContext,
) {
    init {
        require(timestampMs >= 0L)
        require(rawCandidateCount >= candidates.size)
        require(candidates.all { it.timestampMs == timestampMs })
        require(candidates.all(geometryContext::matchesOutputFrame)) {
            "Every candidate frame must match the batch camera geometry context"
        }
    }

    fun emptyFrame(): PoseFrame = PoseFrame(
        timestampMs = timestampMs,
        landmarks = emptyMap(),
        worldLandmarks = emptyMap(),
        imageWidth = geometryContext.outputImageWidth,
        imageHeight = geometryContext.outputImageHeight,
        rotationDegrees = geometryContext.outputRotationDegrees,
        isMirrored = geometryContext.displayMirrored,
    )

    val rejectedCandidateCount: Int
        get() = rawCandidateCount - candidates.size
}

internal data class PosePersonLockConfig(
    val minimumAnchorConfidence: Double = 0.55,
    val minimumAnchorCount: Int = 4,
    val minimumSharedAnchorCount: Int = 3,
    val minimumBodyRatioCount: Int = 4,
    val acquisitionDwellMs: Long = 1_000L,
    val maximumFrameGapMs: Long = 250L,
    val maximumCenterDistanceBodyScales: Double = 0.85,
    val maximumScaleLogDelta: Double = 0.50,
    val maximumMeanAnchorDistanceBodyScales: Double = 0.80,
    val maximumMeanBodyRatioLogDelta: Double = 0.20,
    val minimumAssociationCostMargin: Double = 0.18,
    /**
     * A candidate whose landmark envelope is smaller than this fraction of the largest
     * candidate's is scenery rather than a second subject, and is excluded before ambiguity is
     * judged. Apparent size falls with distance, so 0.55 means "at least about 1.8 times farther
     * away than the nearest person" — a gym bystander walking past at a comparable distance
     * still reads as ambiguous and still stops evaluation.
     */
    val backgroundEnvelopeRatioCeiling: Double = 0.55,
) {
    init {
        require(minimumAnchorConfidence in 0.0..1.0)
        require(backgroundEnvelopeRatioCeiling in 0.0..<1.0) {
            "The background ratio must be a strict fraction of the largest candidate"
        }
        require(minimumAnchorCount in 4..TRACKING_ANCHORS.size)
        require(minimumSharedAnchorCount in 3..minimumAnchorCount)
        require(minimumBodyRatioCount in 4..BODY_SEGMENTS.size + 2)
        require(acquisitionDwellMs > 0L)
        require(maximumFrameGapMs > 0L)
        require(maximumCenterDistanceBodyScales > 0.0)
        require(maximumScaleLogDelta > 0.0)
        require(maximumMeanAnchorDistanceBodyScales > 0.0)
        require(maximumMeanBodyRatioLogDelta > 0.0)
        require(minimumAssociationCostMargin > 0.0)
    }

    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "personLockSchemaVersion" to "3",
            "implementationContractId" to "trex.primary-person-lock.algorithm.v3",
            // v3: distant scenery no longer counts toward multiplicity, but two candidates of
            // comparable apparent size still abstain — the attribution rule is unchanged. The
            // ratio is judged against the locked subject when one exists, so a candidate larger
            // than the subject can never demote the subject to scenery.
            "candidateMultiplicityPolicy" to "EXACTLY_ONE_FOREGROUND_AND_VALID_CANDIDATE",
            "backgroundCandidatePolicy" to "ENVELOPE_RATIO_VS_LOCKED_PRIMARY_ELSE_LARGEST",
            "backgroundEnvelopeRatioCeiling" to backgroundEnvelopeRatioCeiling.toString(),
            // A self-occluded far side must not dissolve the descriptor: shoulder and hip
            // references use the best available side of each bilateral pair.
            "occlusionPolicy" to "BILATERAL_PAIR_BEST_SIDE_REFERENCE",
            "minimumAnchorConfidence" to minimumAnchorConfidence.toString(),
            "minimumAnchorCount" to minimumAnchorCount.toString(),
            "minimumSharedAnchorCount" to minimumSharedAnchorCount.toString(),
            "minimumBodyRatioCount" to minimumBodyRatioCount.toString(),
            "acquisitionDwellMs" to acquisitionDwellMs.toString(),
            "maximumFrameGapMs" to maximumFrameGapMs.toString(),
            "maximumCenterDistanceBodyScales" to maximumCenterDistanceBodyScales.toString(),
            "maximumScaleLogDelta" to maximumScaleLogDelta.toString(),
            "maximumMeanAnchorDistanceBodyScales" to
                maximumMeanAnchorDistanceBodyScales.toString(),
            "maximumMeanBodyRatioLogDelta" to maximumMeanBodyRatioLogDelta.toString(),
            "minimumAssociationCostMargin" to minimumAssociationCostMargin.toString(),
        ),
    )
}

internal data class PoseViewQualifierConfig(
    val minimumLandmarkConfidence: Double = 0.55,
    val frameMargin: Double = 0.01,
    val minimumBodyHeight: Double = 0.30,
    val maximumBodyHeight: Double = 0.99,
    val maximumFrontYawDegrees: Double = 30.0,
    val minimumSagittalYawDegrees: Double = 60.0,
    /** Once lateral, the view stays lateral until the smoothed yaw falls below this. */
    val lateralExitYawDegrees: Double = 45.0,
    /**
     * Once frontal, the view stays frontal until the smoothed yaw rises above this.
     *
     * The mirror of [lateralExitYawDegrees] and it exists for the same reason: without
     * hysteresis a person standing near the boundary flaps the token set every few frames, and
     * an identity-based dwell can never finish while the set keeps changing.
     */
    val frontalExitYawDegrees: Double = 40.0,
    val maximumBilateralYawDisagreementDegrees: Double = 15.0,
    /** World-side confidence floor for a single axis-yaw sample; below it the axis abstains. */
    val minimumAxisSideConfidence: Double = 0.35,
    /** Time constant of the exponential yaw smoother that keeps boundary noise out of tokens. */
    val yawSmoothingTimeConstantMs: Long = 150L,
    val qualificationDwellMs: Long = 300L,
) {
    init {
        require(minimumLandmarkConfidence in 0.0..1.0)
        require(frameMargin in 0.0..<0.5)
        require(minimumBodyHeight in 0.0..1.0)
        require(maximumBodyHeight in minimumBodyHeight..1.0)
        require(maximumFrontYawDegrees in 0.0..<lateralExitYawDegrees)
        require(lateralExitYawDegrees <= minimumSagittalYawDegrees)
        // The frontal latch may reach past the entry threshold but never as far as the lateral
        // one, or a single yaw could satisfy both latches and mint two contradictory tokens.
        require(frontalExitYawDegrees in maximumFrontYawDegrees..<lateralExitYawDegrees)
        require(minimumSagittalYawDegrees <= 90.0)
        require(maximumBilateralYawDisagreementDegrees in 0.0..90.0)
        // Strictly positive: a zero floor would let two zero-weight axes divide 0/0 into a NaN
        // yaw that poisons the smoother until the next reset.
        require(minimumAxisSideConfidence > 0.0)
        require(minimumAxisSideConfidence <= minimumLandmarkConfidence)
        require(yawSmoothingTimeConstantMs > 0L)
        require(qualificationDwellMs > 0L)
    }

    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "viewQualifierSchemaVersion" to "3",
            "implementationContractId" to "trex.body-view-qualifier.algorithm.v3",
            // A self-occluded far side must not revoke the whole placement: the nose plus the
            // best side of every bilateral pair carries the visibility gates.
            "occlusionPolicy" to "NOSE_REQUIRED_BILATERAL_PAIR_BEST_SIDE",
            "minimumLandmarkConfidence" to minimumLandmarkConfidence.toString(),
            "frameMargin" to frameMargin.toString(),
            "minimumBodyHeight" to minimumBodyHeight.toString(),
            "maximumBodyHeight" to maximumBodyHeight.toString(),
            "maximumFrontYawDegrees" to maximumFrontYawDegrees.toString(),
            "minimumSagittalYawDegrees" to minimumSagittalYawDegrees.toString(),
            "lateralExitYawDegrees" to lateralExitYawDegrees.toString(),
            "frontalExitYawDegrees" to frontalExitYawDegrees.toString(),
            "maximumBilateralYawDisagreementDegrees" to
                maximumBilateralYawDisagreementDegrees.toString(),
            "minimumAxisSideConfidence" to minimumAxisSideConfidence.toString(),
            "yawSmoothingTimeConstantMs" to yawSmoothingTimeConstantMs.toString(),
            "qualificationDwellMs" to qualificationDwellMs.toString(),
            "fullBodyPhaseViewContractId" to FULL_BODY_PHASE_VIEW_CONTRACT_ID,
            "lateralViewContractId" to FULL_BODY_LATERAL_VIEW_CONTRACT_ID,
            "frontalAxisViewContractId" to FRONTAL_AXIS_VIEW_CONTRACT_ID,
        ),
    )
}

/**
 * Serial, deterministic primary-person observer.
 *
 * It provides temporal candidate continuity, not biometric identity. Ambiguous association,
 * missing candidates, large time gaps, or implausible jumps immediately remove the opaque person
 * epoch. Reacquisition always creates a new epoch after a fresh dwell, so evidence cannot cross a
 * possible person switch.
 */
internal class MediaPipePoseObserver(
    val observationSource: PoseObservationSource,
    private val personLockConfig: PosePersonLockConfig,
    private val viewQualifierConfig: PoseViewQualifierConfig,
) : AutoCloseable {
    private val viewQualifier = PoseViewQualifier(viewQualifierConfig)
    private var lastTimestampMs: Long? = null
    private var pendingAcquisition: PendingAcquisition? = null
    private var lockedDescriptor: CandidateDescriptor? = null
    private var lockedSceneDescriptors: List<CandidateDescriptor> = emptyList()
    private var personTrackEpoch: PosePersonTrackEpoch? = null
    private var cameraGeometryEpoch: PoseCameraGeometryEpoch? = null
    private var pendingViewIds: Set<String> = emptySet()
    private var pendingViewStartedAtMs: Long? = null
    private var stableViewIds: Set<String> = emptySet()
    private var closed = false

    init {
        require(
            observationSource.contract.personLockArtifactSha256 ==
                personLockConfig.artifactSha256,
        ) { "Observation source and person-lock implementation do not match" }
        require(
            observationSource.contract.viewQualifierArtifactSha256 ==
                viewQualifierConfig.artifactSha256,
        ) { "Observation source and view-qualifier implementation do not match" }
        require(
            observationSource.contract.phaseViewContractId ==
                FULL_BODY_PHASE_VIEW_CONTRACT_ID,
        ) { "Observation source uses an unsupported phase view contract" }
        require(
            observationSource.contract.allowedViewContractIds == setOf(
                FULL_BODY_PHASE_VIEW_CONTRACT_ID,
                FULL_BODY_LATERAL_VIEW_CONTRACT_ID,
                FRONTAL_AXIS_VIEW_CONTRACT_ID,
            ),
        ) { "Observation source view allowlist does not match the qualifier implementation" }
    }

    fun accept(batch: PoseCandidateBatch): PoseObserverUpdate {
        check(!closed) { "Pose observer is closed" }
        require(
            batch.geometryContext.preprocessingArtifactSha256 ==
                observationSource.contract.preprocessingArtifactSha256,
        ) { "Batch camera geometry does not match the observation preprocessing contract" }
        val previousTimestamp = lastTimestampMs
        require(previousTimestamp == null || batch.timestampMs > previousTimestamp) {
            "Observer timestamps must be strictly increasing"
        }
        lastTimestampMs = batch.timestampMs

        val previousGeometryEpoch = cameraGeometryEpoch
        val geometryDiscontinuity = previousGeometryEpoch != null &&
            previousGeometryEpoch.contextArtifactSha256 != batch.geometryContext.artifactSha256
        if (previousGeometryEpoch == null || geometryDiscontinuity) {
            if (geometryDiscontinuity) clearLock()
            cameraGeometryEpoch = observationSource.newCameraGeometryEpoch(batch.geometryContext)
        }

        // Scenery is separated before multiplicity is judged, so a bystander crossing the far
        // side of a gym no longer dissolves the lock. Candidates the mapper rejected outright
        // carry no geometry, so they cannot be shown to be scenery and keep counting.
        val foreground = foregroundCandidates(batch.candidates)
        val effectiveCandidateCount = batch.rejectedCandidateCount + foreground.size
        val described = foreground.mapNotNull { frame ->
            CandidateDescriptor.from(frame, personLockConfig)?.let { descriptor ->
                DescribedCandidate(frame, descriptor)
            }
        }
        val invalidCandidateCount = batch.rejectedCandidateCount +
            (foreground.size - described.size)

        if (geometryDiscontinuity) {
            val candidate = if (effectiveCandidateCount <= 1) {
                beginOrContinueAcquisition(batch.timestampMs, described)
            } else {
                pendingAcquisition = null
                null
            }
            return update(
                batch = batch,
                selected = candidate,
                status = PoseObserverTrackingStatus.TRACK_DISCONTINUITY,
                reasons = buildSet {
                    add(PoseObserverUnknownReason.CAMERA_GEOMETRY_DISCONTINUITY)
                    if (effectiveCandidateCount > 1) {
                        add(PoseObserverUnknownReason.PERSON_AMBIGUOUS)
                    } else if (effectiveCandidateCount == 0) {
                        add(PoseObserverUnknownReason.PERSON_NOT_FOUND)
                    }
                    if (invalidCandidateCount > 0) {
                        add(PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE)
                    }
                },
            )
        }

        if (effectiveCandidateCount > 1) {
            clearLock()
            return update(
                batch = batch,
                selected = null,
                status = PoseObserverTrackingStatus.AMBIGUOUS,
                reasons = setOf(PoseObserverUnknownReason.PERSON_AMBIGUOUS),
            )
        }

        if (
            previousTimestamp != null &&
            batch.timestampMs - previousTimestamp > personLockConfig.maximumFrameGapMs
        ) {
            clearLock()
            val candidate = beginOrContinueAcquisition(batch.timestampMs, described)
            return update(
                batch = batch,
                selected = candidate,
                status = PoseObserverTrackingStatus.TRACK_DISCONTINUITY,
                reasons = buildSet {
                    add(PoseObserverUnknownReason.PERSON_TRACK_DISCONTINUITY)
                    if (invalidCandidateCount > 0) {
                        add(PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE)
                    }
                },
            )
        }

        val locked = lockedDescriptor
        if (locked != null && personTrackEpoch != null) {
            return acceptLocked(batch, described, locked, invalidCandidateCount)
        }

        val pending = pendingAcquisition
        if (pending != null) {
            val continuation = if (pending.sceneDescriptors.size == described.size) {
                matchTrackedPrimary(
                    previousPrimary = pending.descriptor,
                    previousScene = pending.sceneDescriptors,
                    current = described,
                )
            } else {
                PrimaryMatch.None
            }
            when (continuation) {
                PrimaryMatch.Ambiguous -> {
                    pendingAcquisition = null
                    return update(
                        batch = batch,
                        selected = null,
                        status = PoseObserverTrackingStatus.AMBIGUOUS,
                        reasons = setOf(PoseObserverUnknownReason.PERSON_AMBIGUOUS),
                    )
                }

                PrimaryMatch.None -> pendingAcquisition = null
                is PrimaryMatch.Unique -> {
                    val candidate = continuation.candidate
                    pendingAcquisition = PendingAcquisition(
                        startedAtMs = pending.startedAtMs,
                        descriptor = candidate.descriptor,
                        sceneDescriptors = sceneWithPrimaryFirst(candidate, described),
                    )
                    if (
                        batch.timestampMs - pending.startedAtMs >=
                        personLockConfig.acquisitionDwellMs
                    ) {
                        personTrackEpoch = observationSource.newPersonTrackEpoch()
                        lockedDescriptor = candidate.descriptor
                        lockedSceneDescriptors = sceneWithPrimaryFirst(candidate, described)
                        pendingAcquisition = null
                        return update(
                            batch = batch,
                            selected = candidate,
                            status = PoseObserverTrackingStatus.TRACKED,
                            reasons = emptySet(),
                        )
                    }
                    return update(
                        batch = batch,
                        selected = candidate,
                        status = PoseObserverTrackingStatus.ACQUIRING,
                        reasons = setOf(PoseObserverUnknownReason.PERSON_LOCK_ACQUIRING),
                    )
                }
            }
        }

        val acquisition = selectAcquisitionCandidate(described)
        return when (acquisition) {
            AcquisitionSelection.None -> {
                pendingAcquisition = null
                update(
                    batch = batch,
                    selected = null,
                    status = if (effectiveCandidateCount == 0) {
                        PoseObserverTrackingStatus.PERSON_NOT_FOUND
                    } else {
                        PoseObserverTrackingStatus.INSUFFICIENT_LANDMARKS
                    },
                    reasons = setOf(
                        if (effectiveCandidateCount == 0) {
                            PoseObserverUnknownReason.PERSON_NOT_FOUND
                        } else {
                            PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE
                        },
                    ),
                )
            }

            AcquisitionSelection.Ambiguous -> {
                pendingAcquisition = null
                update(
                    batch = batch,
                    selected = null,
                    status = PoseObserverTrackingStatus.AMBIGUOUS,
                    reasons = setOf(PoseObserverUnknownReason.PERSON_AMBIGUOUS),
                )
            }

            is AcquisitionSelection.Unique -> {
                val candidate = acquisition.candidate
                pendingAcquisition = PendingAcquisition(
                    startedAtMs = batch.timestampMs,
                    descriptor = candidate.descriptor,
                    sceneDescriptors = sceneWithPrimaryFirst(candidate, described),
                )
                update(
                    batch = batch,
                    selected = candidate,
                    status = PoseObserverTrackingStatus.ACQUIRING,
                    reasons = setOf(PoseObserverUnknownReason.PERSON_LOCK_ACQUIRING),
                )
            }
        }
    }

    private fun acceptLocked(
        batch: PoseCandidateBatch,
        described: List<DescribedCandidate>,
        previous: CandidateDescriptor,
        invalidCandidateCount: Int,
    ): PoseObserverUpdate {
        val match = matchTrackedPrimary(
            previousPrimary = previous,
            previousScene = lockedSceneDescriptors,
            current = described,
        )
        if (match == PrimaryMatch.None) {
            clearLock()
            val candidate = beginOrContinueAcquisition(batch.timestampMs, described)
            return update(
                batch = batch,
                selected = candidate,
                status = PoseObserverTrackingStatus.TRACK_DISCONTINUITY,
                reasons = buildSet {
                    add(PoseObserverUnknownReason.PERSON_TRACK_DISCONTINUITY)
                    if (invalidCandidateCount > 0) {
                        add(PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE)
                    }
                },
            )
        }
        if (match == PrimaryMatch.Ambiguous) {
            clearLock()
            pendingAcquisition = null
            return update(
                batch = batch,
                selected = null,
                status = PoseObserverTrackingStatus.AMBIGUOUS,
                reasons = setOf(PoseObserverUnknownReason.PERSON_AMBIGUOUS),
            )
        }

        val best = (match as PrimaryMatch.Unique).candidate
        lockedDescriptor = best.descriptor
        lockedSceneDescriptors = sceneWithPrimaryFirst(best, described)
        return update(
            batch = batch,
            selected = best,
            status = PoseObserverTrackingStatus.TRACKED,
            reasons = if (invalidCandidateCount > 0) {
                setOf(PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE)
            } else {
                emptySet()
            },
        )
    }

    private fun beginOrContinueAcquisition(
        timestampMs: Long,
        described: List<DescribedCandidate>,
    ): DescribedCandidate? {
        val selected = (selectAcquisitionCandidate(described) as? AcquisitionSelection.Unique)
            ?.candidate
        pendingAcquisition = selected?.let {
            PendingAcquisition(
                startedAtMs = timestampMs,
                descriptor = it.descriptor,
                sceneDescriptors = sceneWithPrimaryFirst(it, described),
            )
        }
        return selected
    }

    private fun matchTrackedPrimary(
        previousPrimary: CandidateDescriptor,
        previousScene: List<CandidateDescriptor>,
        current: List<DescribedCandidate>,
    ): PrimaryMatch {
        val matches = current
            .map { candidate -> candidate to associationCost(previousPrimary, candidate.descriptor) }
            .filter { (_, cost) -> cost.isFinite() }
            .sortedBy { (_, cost) -> cost }
        val best = matches.firstOrNull() ?: return PrimaryMatch.None
        val secondCurrentCost = matches.getOrNull(1)?.second
        if (
            secondCurrentCost != null &&
            secondCurrentCost - best.second < personLockConfig.minimumAssociationCostMargin
        ) {
            return PrimaryMatch.Ambiguous
        }
        val previousSecondaryCost = previousScene
            .asSequence()
            .filter { descriptor -> descriptor !== previousPrimary }
            .map { descriptor -> associationCost(descriptor, best.first.descriptor) }
            .filter(Double::isFinite)
            .minOrNull()
        if (
            previousSecondaryCost != null &&
            previousSecondaryCost <= best.second + personLockConfig.minimumAssociationCostMargin
        ) {
            return PrimaryMatch.Ambiguous
        }
        return PrimaryMatch.Unique(best.first)
    }

    private fun sceneWithPrimaryFirst(
        primary: DescribedCandidate,
        scene: List<DescribedCandidate>,
    ): List<CandidateDescriptor> = buildList {
        add(primary.descriptor)
        scene.filterNot { it === primary }.forEach { add(it.descriptor) }
    }

    /**
     * Keeps the candidates that could plausibly be the subject and drops plain scenery.
     *
     * The size reference is the locked subject whenever one is present. Scenery means "much
     * smaller than the person being measured", so a candidate *larger* than the subject is never
     * background: someone walking between the camera and a subject filming from a few metres
     * away looms larger than the subject, and under a largest-candidate reference the subject
     * themselves would fall below the ratio, be dropped as scenery, and hand the lock to the
     * passer-by. Only without a usable lock does the largest candidate serve as the reference.
     *
     * The size proxy is the diagonal of the landmark envelope rather than a torso length or a
     * bounding-box height: it is defined for every candidate regardless of per-landmark
     * confidence, and it does not collapse when the subject is horizontal, so a plank next to a
     * standing bystander cannot be mistaken for the smaller party.
     *
     * The reference candidate always survives its own floor, so a non-empty batch never becomes
     * empty here.
     */
    private fun foregroundCandidates(candidates: List<PoseFrame>): List<PoseFrame> {
        if (candidates.size <= 1) return candidates
        val envelopes = candidates.map(::landmarkEnvelopeDiagonal)
        val reference = lockedReferenceEnvelope(candidates, envelopes) ?: envelopes.max()
        if (reference <= GEOMETRY_EPSILON) return candidates
        val floor = reference * personLockConfig.backgroundEnvelopeRatioCeiling
        return candidates.filterIndexed { index, _ -> envelopes[index] >= floor }
    }

    /**
     * Envelope of the candidate the locked primary associates to, or null when no lock exists or
     * no candidate credibly continues it — in which case the caller falls back to the largest
     * candidate, which is also what re-acquisition would consider.
     */
    private fun lockedReferenceEnvelope(
        candidates: List<PoseFrame>,
        envelopes: List<Double>,
    ): Double? {
        val locked = lockedDescriptor ?: return null
        var bestEnvelope: Double? = null
        var bestCost = Double.POSITIVE_INFINITY
        for (index in candidates.indices) {
            val descriptor = CandidateDescriptor.from(candidates[index], personLockConfig)
                ?: continue
            val cost = associationCost(locked, descriptor)
            if (cost < bestCost) {
                bestCost = cost
                bestEnvelope = envelopes[index]
            }
        }
        return if (bestCost.isFinite()) bestEnvelope else null
    }

    private fun landmarkEnvelopeDiagonal(frame: PoseFrame): Double {
        val landmarks = frame.landmarks.values
        if (landmarks.size < 2) return 0.0
        val aspect = frame.imageAspectRatio
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (landmark in landmarks) {
            val x = landmark.x * aspect
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (landmark.y < minY) minY = landmark.y
            if (landmark.y > maxY) maxY = landmark.y
        }
        return hypot(maxX - minX, maxY - minY)
    }

    private fun selectAcquisitionCandidate(
        candidates: List<DescribedCandidate>,
    ): AcquisitionSelection {
        if (candidates.isEmpty()) return AcquisitionSelection.None
        if (candidates.size != 1) return AcquisitionSelection.Ambiguous
        return AcquisitionSelection.Unique(candidates.single())
    }

    private fun associationCost(
        previous: CandidateDescriptor,
        current: CandidateDescriptor,
    ): Double {
        val referenceScale = max(previous.bodyScale, current.bodyScale)
        if (referenceScale <= GEOMETRY_EPSILON) return Double.POSITIVE_INFINITY
        val centerDistance = previous.center.distanceTo(current.center) / referenceScale
        val scaleDelta = abs(ln(current.bodyScale / previous.bodyScale))
        val shared = previous.anchors.keys.intersect(current.anchors.keys)
        if (shared.size < personLockConfig.minimumSharedAnchorCount) {
            return Double.POSITIVE_INFINITY
        }
        val anchorDistances = shared
            .map { joint ->
                previous.anchors.getValue(joint).distanceTo(current.anchors.getValue(joint)) /
                    referenceScale
            }
            .sorted()
        val meanAnchorDistance = anchorDistances.average()
        val sharedRatios = previous.bodyRatios.keys.intersect(current.bodyRatios.keys)
        if (sharedRatios.size < personLockConfig.minimumBodyRatioCount) {
            return Double.POSITIVE_INFINITY
        }
        val meanBodyRatioLogDelta = sharedRatios
            .map { id -> abs(ln(current.bodyRatios.getValue(id) / previous.bodyRatios.getValue(id))) }
            .average()
        if (
            centerDistance > personLockConfig.maximumCenterDistanceBodyScales ||
            scaleDelta > personLockConfig.maximumScaleLogDelta ||
            meanAnchorDistance > personLockConfig.maximumMeanAnchorDistanceBodyScales ||
            meanBodyRatioLogDelta > personLockConfig.maximumMeanBodyRatioLogDelta
        ) {
            return Double.POSITIVE_INFINITY
        }
        return centerDistance * 0.35 +
            scaleDelta * 0.15 +
            meanAnchorDistance * 0.35 +
            meanBodyRatioLogDelta * 0.15
    }

    private fun update(
        batch: PoseCandidateBatch,
        selected: DescribedCandidate?,
        status: PoseObserverTrackingStatus,
        reasons: Set<PoseObserverUnknownReason>,
    ): PoseObserverUpdate {
        val selectedFrame = selected?.frame
        val viewDecision = selectedFrame?.let(viewQualifier::qualify) ?: PoseViewDecision.unknown()
        val epoch = if (status == PoseObserverTrackingStatus.TRACKED) personTrackEpoch else null
        val frame = selectedFrame ?: batch.emptyFrame()
        val qualifiedViewIds = stabilizedViewIds(
            timestampMs = frame.timestampMs,
            epoch = epoch,
            rawViewIds = viewDecision.qualifiedContractIds,
        )
        val qualifications: List<PoseViewQualification> = if (epoch == null) {
            emptyList()
        } else {
            qualifiedViewIds.map { viewContractId ->
                observationSource.qualifyView(
                    viewContractId = viewContractId,
                    personTrackEpoch = epoch,
                    frameTimestampMs = frame.timestampMs,
                )
            }
        }
        return PoseObserverUpdate(
            observation = observationSource.attest(
                frame = frame,
                personTrackEpoch = epoch,
                viewQualifications = qualifications,
                cameraGeometryEpoch = checkNotNull(cameraGeometryEpoch) {
                    "A camera geometry epoch must exist before an observer update is attested"
                },
            ),
            displayFrame = selectedFrame,
            trackingStatus = status,
            view = viewDecision.view,
            candidateCount = batch.rawCandidateCount,
            unknownReasons = reasons + viewDecision.reasons + if (
                epoch != null &&
                viewDecision.qualifiedContractIds.isNotEmpty() &&
                qualifiedViewIds.isEmpty()
            ) {
                setOf(PoseObserverUnknownReason.VIEW_QUALIFICATION_STABILIZING)
            } else {
                emptySet()
            },
        )
    }

    private fun clearLock() {
        lockedDescriptor = null
        lockedSceneDescriptors = emptyList()
        personTrackEpoch = null
        pendingAcquisition = null
        clearViewState()
        // Yaw smoothing and the lateral latch are continuity state for one observed person, so
        // they end exactly where the lock ends — and nowhere else. A one-frame visibility blip
        // that abstains from tokens must not erase the hysteresis it exists to provide.
        viewQualifier.reset()
    }

    private fun stabilizedViewIds(
        timestampMs: Long,
        epoch: PosePersonTrackEpoch?,
        rawViewIds: Set<String>,
    ): Set<String> {
        if (epoch == null || rawViewIds.isEmpty()) {
            clearViewState()
            return emptySet()
        }
        if (rawViewIds != pendingViewIds) {
            pendingViewIds = LinkedHashSet(rawViewIds)
            pendingViewStartedAtMs = timestampMs
            stableViewIds = emptySet()
            return emptySet()
        }
        val startedAt = pendingViewStartedAtMs ?: timestampMs.also {
            pendingViewStartedAtMs = it
        }
        if (timestampMs - startedAt >= viewQualifierConfig.qualificationDwellMs) {
            stableViewIds = LinkedHashSet(rawViewIds)
        }
        return stableViewIds
    }

    private fun clearViewState() {
        pendingViewIds = emptySet()
        pendingViewStartedAtMs = null
        stableViewIds = emptySet()
    }

    override fun close() {
        if (closed) return
        closed = true
        clearLock()
        cameraGeometryEpoch = null
        observationSource.close()
    }

    private data class PendingAcquisition(
        val startedAtMs: Long,
        val descriptor: CandidateDescriptor,
        val sceneDescriptors: List<CandidateDescriptor>,
    )

    private sealed interface PrimaryMatch {
        data object None : PrimaryMatch
        data object Ambiguous : PrimaryMatch
        data class Unique(val candidate: DescribedCandidate) : PrimaryMatch
    }

    private sealed interface AcquisitionSelection {
        data object None : AcquisitionSelection
        data object Ambiguous : AcquisitionSelection
        data class Unique(val candidate: DescribedCandidate) : AcquisitionSelection
    }
}

private class PoseViewQualifier(
    private val config: PoseViewQualifierConfig,
) {
    // Smoothing and the lateral latch are continuity state for one observed person. The observer
    // calls reset() whenever the person lock or camera geometry breaks.
    private var smoothedYawDegrees: Double? = null
    private var smoothedYawTimestampMs: Long? = null
    private var lateralLatched = false
    private var frontalLatched = false

    fun reset() {
        smoothedYawDegrees = null
        smoothedYawTimestampMs = null
        lateralLatched = false
        frontalLatched = false
    }

    fun qualify(frame: PoseFrame): PoseViewDecision {
        val normalized = frame.landmarks
        val nose = normalized[PoseJoint.NOSE]
        val pairSides = BILATERAL_VIEW_PAIRS.map { (leftJoint, rightJoint) ->
            listOfNotNull(normalized[leftJoint], normalized[rightJoint])
        }
        // Gate failures below are per-frame abstentions, not identity breaks: they keep the
        // smoothing and latch state, which the observer resets only when the person lock ends.
        if (nose == null || pairSides.any(List<PoseLandmark>::isEmpty)) {
            return PoseViewDecision.unknown(PoseObserverUnknownReason.REQUIRED_LANDMARK_MISSING)
        }
        // Occlusion-tolerant visibility gate: a true side pose self-occludes its far half, so the
        // nose plus the best side of every bilateral pair carries the requirement instead of all
        // thirteen joints individually.
        val lowConfidence = nose.confidence < config.minimumLandmarkConfidence ||
            pairSides.any { sides ->
                sides.maxOf(PoseLandmark::confidence) < config.minimumLandmarkConfidence
            }
        if (lowConfidence) {
            return PoseViewDecision.unknown(
                PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE,
            )
        }
        // A low-confidence far-side coordinate is a guess; only credible joints are held to the
        // framing and height gates.
        val credible = buildList {
            add(nose)
            pairSides.forEach { sides ->
                sides.forEach { landmark ->
                    if (landmark.confidence >= config.minimumLandmarkConfidence) add(landmark)
                }
            }
        }
        val outside = credible.any { point ->
            point.x !in config.frameMargin..(1.0 - config.frameMargin) ||
                point.y !in config.frameMargin..(1.0 - config.frameMargin)
        }
        if (outside) {
            return PoseViewDecision.unknown(PoseObserverUnknownReason.BODY_OUT_OF_FRAME)
        }
        val upperY = credibleYs(normalized, PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER).min()
        val lowerY = credibleYs(
            normalized,
            PoseJoint.LEFT_ANKLE,
            PoseJoint.RIGHT_ANKLE,
            PoseJoint.LEFT_HEEL,
            PoseJoint.RIGHT_HEEL,
            PoseJoint.LEFT_FOOT_INDEX,
            PoseJoint.RIGHT_FOOT_INDEX,
        ).max()
        val bodyHeight = lowerY - upperY
        if (bodyHeight < config.minimumBodyHeight) {
            return PoseViewDecision.unknown(PoseObserverUnknownReason.BODY_TOO_SMALL)
        }
        if (bodyHeight > config.maximumBodyHeight) {
            return PoseViewDecision.unknown(PoseObserverUnknownReason.BODY_TOO_LARGE)
        }

        val ids = linkedSetOf(FULL_BODY_PHASE_VIEW_CONTRACT_ID)
        val shoulderAxis = bilateralAxisYaw(
            frame,
            PoseJoint.LEFT_SHOULDER,
            PoseJoint.RIGHT_SHOULDER,
        )
        val hipAxis = bilateralAxisYaw(frame, PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP)
        val measuredYaw = when {
            shoulderAxis != null && hipAxis != null -> {
                // The disagreement veto only applies when both axes are strongly observed; a
                // weakly observed axis abstains from the veto but still contributes its weight.
                val bothStrong = shoulderAxis.weight >= config.minimumLandmarkConfidence &&
                    hipAxis.weight >= config.minimumLandmarkConfidence
                if (
                    bothStrong &&
                    abs(shoulderAxis.yawDegrees - hipAxis.yawDegrees) >
                    config.maximumBilateralYawDisagreementDegrees
                ) {
                    return PoseViewDecision(
                        view = PoseObserverView.UNKNOWN,
                        qualifiedContractIds = ids,
                        reasons = setOf(PoseObserverUnknownReason.VIEW_AMBIGUOUS),
                    )
                }
                (shoulderAxis.yawDegrees * shoulderAxis.weight + hipAxis.yawDegrees * hipAxis.weight) /
                    (shoulderAxis.weight + hipAxis.weight)
            }

            shoulderAxis != null -> shoulderAxis.yawDegrees
            hipAxis != null -> hipAxis.yawDegrees
            else -> return PoseViewDecision(
                view = PoseObserverView.UNKNOWN,
                qualifiedContractIds = ids,
                reasons = setOf(PoseObserverUnknownReason.WORLD_LANDMARKS_UNAVAILABLE),
            )
        }
        val yaw = smoothYaw(frame.timestampMs, measuredYaw)
        return when {
            yaw <= config.maximumFrontYawDegrees ||
                (frontalLatched && yaw <= config.frontalExitYawDegrees) -> {
                // Enter at the front threshold, stay until the exit threshold, exactly as the
                // lateral branch does and for the same reason.
                frontalLatched = true
                lateralLatched = false
                PoseViewDecision(
                    view = PoseObserverView.FRONTAL_AXIS,
                    // The token says the bilateral axis faces the camera; it does not say which
                    // way. A shoulder/hip axis cannot distinguish a chest from a back, so the
                    // unresolved reason travels with the token rather than being cleared by it.
                    // Nothing downstream needs the distinction: every measurement taken through
                    // this view is a rotation-invariant included angle.
                    qualifiedContractIds = ids + FRONTAL_AXIS_VIEW_CONTRACT_ID,
                    reasons = setOf(PoseObserverUnknownReason.FRONT_REAR_UNRESOLVED),
                )
            }

            yaw >= config.minimumSagittalYawDegrees ||
                (lateralLatched && yaw >= config.lateralExitYawDegrees) -> {
                // Enter at the sagittal threshold, stay until the exit threshold: without this
                // hysteresis a person at the boundary flaps the token set every few frames and
                // the identity-based dwell can never finish.
                lateralLatched = true
                frontalLatched = false
                PoseViewDecision(
                    view = PoseObserverView.LATERAL,
                    qualifiedContractIds = ids + FULL_BODY_LATERAL_VIEW_CONTRACT_ID,
                    reasons = emptySet(),
                )
            }

            else -> {
                lateralLatched = false
                frontalLatched = false
                PoseViewDecision(
                    view = PoseObserverView.OBLIQUE,
                    qualifiedContractIds = ids,
                    reasons = setOf(PoseObserverUnknownReason.VIEW_AMBIGUOUS),
                )
            }
        }
    }

    private fun smoothYaw(timestampMs: Long, sampleDegrees: Double): Double {
        val previous = smoothedYawDegrees
        val previousTs = smoothedYawTimestampMs
        val next = if (previous == null || previousTs == null || timestampMs <= previousTs) {
            sampleDegrees
        } else {
            val dtMs = (timestampMs - previousTs).toDouble()
            val alpha = 1.0 - exp(-dtMs / config.yawSmoothingTimeConstantMs.toDouble())
            previous + alpha * (sampleDegrees - previous)
        }
        smoothedYawDegrees = next
        smoothedYawTimestampMs = timestampMs
        return next
    }

    private fun credibleYs(
        normalized: Map<PoseJoint, PoseLandmark>,
        vararg joints: PoseJoint,
    ): List<Double> = joints.mapNotNull { joint ->
        normalized[joint]
            ?.takeIf { it.confidence >= config.minimumLandmarkConfidence }
            ?.y
    }

    private fun bilateralAxisYaw(
        frame: PoseFrame,
        leftJoint: PoseJoint,
        rightJoint: PoseJoint,
    ): AxisYaw? {
        val left = frame.worldLandmarks[leftJoint] ?: return null
        val right = frame.worldLandmarks[rightJoint] ?: return null
        val weight = min(left.confidence, right.confidence)
        if (weight < config.minimumAxisSideConfidence) return null
        val dx = right.x - left.x
        val dz = right.z - left.z
        if (hypot(dx, dz) <= GEOMETRY_EPSILON) return null
        return AxisYaw(
            yawDegrees = Math.toDegrees(atan2(abs(dz), abs(dx))),
            weight = weight,
        )
    }

    private data class AxisYaw(val yawDegrees: Double, val weight: Double)
}

private data class PoseViewDecision(
    val view: PoseObserverView,
    val qualifiedContractIds: Set<String>,
    val reasons: Set<PoseObserverUnknownReason>,
) {
    companion object {
        fun unknown(vararg reasons: PoseObserverUnknownReason): PoseViewDecision =
            PoseViewDecision(
                view = PoseObserverView.UNKNOWN,
                qualifiedContractIds = emptySet(),
                reasons = reasons.toSet(),
            )
    }
}

private data class DescribedCandidate(
    val frame: PoseFrame,
    val descriptor: CandidateDescriptor,
)

private data class CandidateDescriptor(
    val center: Point2,
    val bodyScale: Double,
    val anchors: Map<PoseJoint, Point2>,
    val bodyRatios: Map<String, Double>,
) {
    companion object {
        fun from(frame: PoseFrame, config: PosePersonLockConfig): CandidateDescriptor? {
            val aspect = frame.imageAspectRatio
            val anchors = TRACKING_ANCHORS.mapNotNull { joint ->
                val landmark = frame.landmarks[joint]
                    ?.takeIf { it.confidence >= config.minimumAnchorConfidence }
                    ?: return@mapNotNull null
                joint to Point2(landmark.x * aspect, landmark.y)
            }.toMap(LinkedHashMap())
            if (anchors.size < config.minimumAnchorCount) return null
            // A sideways pose self-occludes its far half, so the torso references use the best
            // available side of each pair rather than demanding all four shoulder/hip anchors.
            val shoulderRef = bestPairPoint(
                anchors,
                PoseJoint.LEFT_SHOULDER,
                PoseJoint.RIGHT_SHOULDER,
            ) ?: return null
            val hipRef = bestPairPoint(anchors, PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP)
                ?: return null
            val bodyScale = shoulderRef.distanceTo(hipRef)
            if (bodyScale <= GEOMETRY_EPSILON) return null
            val center = shoulderRef.midpoint(hipRef)
            val bodyRatios = bodyRatios(frame, config.minimumAnchorConfidence)
            if (bodyRatios.size < config.minimumBodyRatioCount) return null
            return CandidateDescriptor(
                center = center,
                bodyScale = bodyScale,
                anchors = Collections.unmodifiableMap(LinkedHashMap(anchors)),
                bodyRatios = Collections.unmodifiableMap(LinkedHashMap(bodyRatios)),
            )
        }

        private fun bestPairPoint(
            anchors: Map<PoseJoint, Point2>,
            leftJoint: PoseJoint,
            rightJoint: PoseJoint,
        ): Point2? {
            val left = anchors[leftJoint]
            val right = anchors[rightJoint]
            return when {
                left != null && right != null -> left.midpoint(right)
                left != null -> left
                right != null -> right
                else -> null
            }
        }

        private fun bodyRatios(
            frame: PoseFrame,
            minimumConfidence: Double,
        ): Map<String, Double> {
            fun point(joint: PoseJoint): Point3? = frame.worldLandmarks[joint]
                ?.takeIf { it.confidence >= minimumConfidence }
                ?.let { Point3(it.x, it.y, it.z) }

            fun pairPoint(leftJoint: PoseJoint, rightJoint: PoseJoint): Point3? {
                val left = point(leftJoint)
                val right = point(rightJoint)
                return when {
                    left != null && right != null -> left.midpoint(right)
                    left != null -> left
                    right != null -> right
                    else -> null
                }
            }

            val leftShoulder = point(PoseJoint.LEFT_SHOULDER)
            val rightShoulder = point(PoseJoint.RIGHT_SHOULDER)
            val leftHip = point(PoseJoint.LEFT_HIP)
            val rightHip = point(PoseJoint.RIGHT_HIP)
            val shoulderRef = pairPoint(PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER)
                ?: return emptyMap()
            val hipRef = pairPoint(PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP) ?: return emptyMap()
            val torsoLength = shoulderRef.distanceTo(hipRef)
            if (torsoLength <= GEOMETRY_EPSILON) return emptyMap()

            return buildMap {
                // Width ratios need both sides; a self-occluded side simply abstains.
                if (leftShoulder != null && rightShoulder != null) {
                    put("shoulder-width", leftShoulder.distanceTo(rightShoulder) / torsoLength)
                }
                if (leftHip != null && rightHip != null) {
                    put("hip-width", leftHip.distanceTo(rightHip) / torsoLength)
                }
                BODY_SEGMENTS.forEach { segment ->
                    val from = point(segment.from)
                    val to = point(segment.to)
                    if (from != null && to != null) {
                        val ratio = from.distanceTo(to) / torsoLength
                        if (ratio > GEOMETRY_EPSILON && ratio.isFinite()) put(segment.id, ratio)
                    }
                }
            }
        }
    }
}

private data class Point2(val x: Double, val y: Double) {
    fun distanceTo(other: Point2): Double = hypot(x - other.x, y - other.y)
    fun midpoint(other: Point2): Point2 = Point2((x + other.x) / 2.0, (y + other.y) / 2.0)
}

private data class Point3(val x: Double, val y: Double, val z: Double) {
    fun distanceTo(other: Point3): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun midpoint(other: Point3): Point3 = Point3(
        (x + other.x) / 2.0,
        (y + other.y) / 2.0,
        (z + other.z) / 2.0,
    )
}

private data class BodySegment(val id: String, val from: PoseJoint, val to: PoseJoint)

private val BODY_SEGMENTS = listOf(
    BodySegment("left-upper-arm", PoseJoint.LEFT_SHOULDER, PoseJoint.LEFT_ELBOW),
    BodySegment("right-upper-arm", PoseJoint.RIGHT_SHOULDER, PoseJoint.RIGHT_ELBOW),
    BodySegment("left-forearm", PoseJoint.LEFT_ELBOW, PoseJoint.LEFT_WRIST),
    BodySegment("right-forearm", PoseJoint.RIGHT_ELBOW, PoseJoint.RIGHT_WRIST),
    BodySegment("left-femur", PoseJoint.LEFT_HIP, PoseJoint.LEFT_KNEE),
    BodySegment("right-femur", PoseJoint.RIGHT_HIP, PoseJoint.RIGHT_KNEE),
    BodySegment("left-tibia", PoseJoint.LEFT_KNEE, PoseJoint.LEFT_ANKLE),
    BodySegment("right-tibia", PoseJoint.RIGHT_KNEE, PoseJoint.RIGHT_ANKLE),
)

private val TRACKING_ANCHORS = listOf(
    PoseJoint.LEFT_SHOULDER,
    PoseJoint.RIGHT_SHOULDER,
    PoseJoint.LEFT_HIP,
    PoseJoint.RIGHT_HIP,
    PoseJoint.LEFT_KNEE,
    PoseJoint.RIGHT_KNEE,
    PoseJoint.LEFT_ANKLE,
    PoseJoint.RIGHT_ANKLE,
)

/** Left/right pairs whose best side carries the full-body visibility gates. */
private val BILATERAL_VIEW_PAIRS = listOf(
    PoseJoint.LEFT_SHOULDER to PoseJoint.RIGHT_SHOULDER,
    PoseJoint.LEFT_HIP to PoseJoint.RIGHT_HIP,
    PoseJoint.LEFT_KNEE to PoseJoint.RIGHT_KNEE,
    PoseJoint.LEFT_ANKLE to PoseJoint.RIGHT_ANKLE,
    PoseJoint.LEFT_HEEL to PoseJoint.RIGHT_HEEL,
    PoseJoint.LEFT_FOOT_INDEX to PoseJoint.RIGHT_FOOT_INDEX,
)

private const val GEOMETRY_EPSILON = 1e-6

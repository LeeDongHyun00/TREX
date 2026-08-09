package com.example.trex_kotlin.camera

import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import com.example.trex_kotlin.pose.runtime.PosePersonTrackEpoch
import com.example.trex_kotlin.pose.runtime.PoseViewQualification
import java.util.Collections
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

internal const val FULL_BODY_PHASE_VIEW_CONTRACT_ID = "trex.view.full-body-any.v1"
internal const val FULL_BODY_LATERAL_VIEW_CONTRACT_ID = "trex.view.lateral-full-body.v1"

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
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
    val isMirrored: Boolean,
) {
    init {
        require(timestampMs >= 0L)
        require(rawCandidateCount >= candidates.size)
        require(imageWidth > 0 && imageHeight > 0)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(candidates.all { it.timestampMs == timestampMs })
        require(candidates.all { it.imageWidth == imageWidth && it.imageHeight == imageHeight })
        require(candidates.all { it.rotationDegrees == rotationDegrees })
        require(candidates.all { it.isMirrored == isMirrored })
    }

    fun emptyFrame(): PoseFrame = PoseFrame(
        timestampMs = timestampMs,
        landmarks = emptyMap(),
        worldLandmarks = emptyMap(),
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        rotationDegrees = rotationDegrees,
        isMirrored = isMirrored,
    )

    val rejectedCandidateCount: Int
        get() = rawCandidateCount - candidates.size
}

internal data class PosePersonLockConfig(
    val minimumAnchorConfidence: Double = 0.55,
    val minimumAnchorCount: Int = 6,
    val minimumSharedAnchorCount: Int = 4,
    val minimumBodyRatioCount: Int = 6,
    val acquisitionDwellMs: Long = 1_000L,
    val maximumFrameGapMs: Long = 250L,
    val maximumCenterDistanceBodyScales: Double = 0.85,
    val maximumScaleLogDelta: Double = 0.50,
    val maximumMeanAnchorDistanceBodyScales: Double = 0.80,
    val maximumMeanBodyRatioLogDelta: Double = 0.20,
    val minimumAssociationCostMargin: Double = 0.18,
) {
    init {
        require(minimumAnchorConfidence in 0.0..1.0)
        require(minimumAnchorCount in 4..TRACKING_ANCHORS.size)
        require(minimumSharedAnchorCount in 4..minimumAnchorCount)
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
            "personLockSchemaVersion" to "1",
            "implementationContractId" to "trex.primary-person-lock.algorithm.v1",
            "candidateMultiplicityPolicy" to "EXACTLY_ONE_RAW_AND_VALID_CANDIDATE",
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
    val maximumBilateralYawDisagreementDegrees: Double = 15.0,
    val qualificationDwellMs: Long = 300L,
) {
    init {
        require(minimumLandmarkConfidence in 0.0..1.0)
        require(frameMargin in 0.0..<0.5)
        require(minimumBodyHeight in 0.0..1.0)
        require(maximumBodyHeight in minimumBodyHeight..1.0)
        require(maximumFrontYawDegrees in 0.0..<minimumSagittalYawDegrees)
        require(minimumSagittalYawDegrees <= 90.0)
        require(maximumBilateralYawDisagreementDegrees in 0.0..90.0)
        require(qualificationDwellMs > 0L)
    }

    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "viewQualifierSchemaVersion" to "1",
            "implementationContractId" to "trex.body-view-qualifier.algorithm.v1",
            "minimumLandmarkConfidence" to minimumLandmarkConfidence.toString(),
            "frameMargin" to frameMargin.toString(),
            "minimumBodyHeight" to minimumBodyHeight.toString(),
            "maximumBodyHeight" to maximumBodyHeight.toString(),
            "maximumFrontYawDegrees" to maximumFrontYawDegrees.toString(),
            "minimumSagittalYawDegrees" to minimumSagittalYawDegrees.toString(),
            "maximumBilateralYawDisagreementDegrees" to
                maximumBilateralYawDisagreementDegrees.toString(),
            "qualificationDwellMs" to qualificationDwellMs.toString(),
            "fullBodyPhaseViewContractId" to FULL_BODY_PHASE_VIEW_CONTRACT_ID,
            "lateralViewContractId" to FULL_BODY_LATERAL_VIEW_CONTRACT_ID,
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
            ),
        ) { "Observation source view allowlist does not match the qualifier implementation" }
    }

    fun accept(batch: PoseCandidateBatch): PoseObserverUpdate {
        check(!closed) { "Pose observer is closed" }
        val previousTimestamp = lastTimestampMs
        require(previousTimestamp == null || batch.timestampMs > previousTimestamp) {
            "Observer timestamps must be strictly increasing"
        }
        lastTimestampMs = batch.timestampMs

        val described = batch.candidates.mapNotNull { frame ->
            CandidateDescriptor.from(frame, personLockConfig)?.let { descriptor ->
                DescribedCandidate(frame, descriptor)
            }
        }
        val invalidCandidateCount = batch.rejectedCandidateCount +
            (batch.candidates.size - described.size)

        if (batch.rawCandidateCount > 1) {
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
                    status = if (batch.rawCandidateCount == 0) {
                        PoseObserverTrackingStatus.PERSON_NOT_FOUND
                    } else {
                        PoseObserverTrackingStatus.INSUFFICIENT_LANDMARKS
                    },
                    reasons = setOf(
                        if (batch.rawCandidateCount == 0) {
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
    fun qualify(frame: PoseFrame): PoseViewDecision {
        val normalized = frame.landmarks
        val missing = FULL_BODY_JOINTS.filterNot(normalized::containsKey)
        if (missing.isNotEmpty()) {
            return PoseViewDecision.unknown(PoseObserverUnknownReason.REQUIRED_LANDMARK_MISSING)
        }
        val lowConfidence = FULL_BODY_JOINTS.any { joint ->
            normalized.getValue(joint).confidence < config.minimumLandmarkConfidence
        }
        if (lowConfidence) {
            return PoseViewDecision.unknown(
                PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE,
            )
        }
        val outside = FULL_BODY_JOINTS.any { joint ->
            val point = normalized.getValue(joint)
            point.x !in config.frameMargin..(1.0 - config.frameMargin) ||
                point.y !in config.frameMargin..(1.0 - config.frameMargin)
        }
        if (outside) {
            return PoseViewDecision.unknown(PoseObserverUnknownReason.BODY_OUT_OF_FRAME)
        }
        val upperY = listOf(PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER)
            .minOf { normalized.getValue(it).y }
        val lowerY = listOf(
            PoseJoint.LEFT_ANKLE,
            PoseJoint.RIGHT_ANKLE,
            PoseJoint.LEFT_HEEL,
            PoseJoint.RIGHT_HEEL,
            PoseJoint.LEFT_FOOT_INDEX,
            PoseJoint.RIGHT_FOOT_INDEX,
        ).maxOf { normalized.getValue(it).y }
        val bodyHeight = lowerY - upperY
        if (bodyHeight < config.minimumBodyHeight) {
            return PoseViewDecision.unknown(PoseObserverUnknownReason.BODY_TOO_SMALL)
        }
        if (bodyHeight > config.maximumBodyHeight) {
            return PoseViewDecision.unknown(PoseObserverUnknownReason.BODY_TOO_LARGE)
        }

        val ids = linkedSetOf(FULL_BODY_PHASE_VIEW_CONTRACT_ID)
        val shoulderYaw = bilateralAxisYaw(
            frame,
            PoseJoint.LEFT_SHOULDER,
            PoseJoint.RIGHT_SHOULDER,
        )
        val hipYaw = bilateralAxisYaw(frame, PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP)
        if (shoulderYaw == null || hipYaw == null) {
            return PoseViewDecision(
                view = PoseObserverView.UNKNOWN,
                qualifiedContractIds = ids,
                reasons = setOf(PoseObserverUnknownReason.WORLD_LANDMARKS_UNAVAILABLE),
            )
        }
        if (abs(shoulderYaw - hipYaw) > config.maximumBilateralYawDisagreementDegrees) {
            return PoseViewDecision(
                view = PoseObserverView.UNKNOWN,
                qualifiedContractIds = ids,
                reasons = setOf(PoseObserverUnknownReason.VIEW_AMBIGUOUS),
            )
        }
        val yaw = (shoulderYaw + hipYaw) / 2.0
        return when {
            yaw <= config.maximumFrontYawDegrees -> PoseViewDecision(
                // A shoulder/hip axis cannot distinguish front from rear. It is intentionally
                // diagnostic-only until a separately pinned face-orientation provider exists.
                view = PoseObserverView.FRONTAL_AXIS,
                qualifiedContractIds = ids,
                reasons = setOf(PoseObserverUnknownReason.FRONT_REAR_UNRESOLVED),
            )

            yaw >= config.minimumSagittalYawDegrees -> PoseViewDecision(
                view = PoseObserverView.LATERAL,
                qualifiedContractIds = ids + FULL_BODY_LATERAL_VIEW_CONTRACT_ID,
                reasons = emptySet(),
            )

            else -> PoseViewDecision(
                view = PoseObserverView.OBLIQUE,
                qualifiedContractIds = ids,
                reasons = setOf(PoseObserverUnknownReason.VIEW_AMBIGUOUS),
            )
        }
    }

    private fun bilateralAxisYaw(
        frame: PoseFrame,
        leftJoint: PoseJoint,
        rightJoint: PoseJoint,
    ): Double? {
        val left = frame.worldLandmarks[leftJoint] ?: return null
        val right = frame.worldLandmarks[rightJoint] ?: return null
        if (
            left.confidence < config.minimumLandmarkConfidence ||
            right.confidence < config.minimumLandmarkConfidence
        ) {
            return null
        }
        val dx = right.x - left.x
        val dz = right.z - left.z
        if (hypot(dx, dz) <= GEOMETRY_EPSILON) return null
        return Math.toDegrees(atan2(abs(dz), abs(dx)))
    }
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
            val leftShoulder = anchors[PoseJoint.LEFT_SHOULDER] ?: return null
            val rightShoulder = anchors[PoseJoint.RIGHT_SHOULDER] ?: return null
            val leftHip = anchors[PoseJoint.LEFT_HIP] ?: return null
            val rightHip = anchors[PoseJoint.RIGHT_HIP] ?: return null
            val shoulderMid = leftShoulder.midpoint(rightShoulder)
            val hipMid = leftHip.midpoint(rightHip)
            val bodyScale = shoulderMid.distanceTo(hipMid)
            if (bodyScale <= GEOMETRY_EPSILON) return null
            val center = shoulderMid.midpoint(hipMid)
            val bodyRatios = bodyRatios(frame, config.minimumAnchorConfidence)
            if (bodyRatios.size < config.minimumBodyRatioCount) return null
            return CandidateDescriptor(
                center = center,
                bodyScale = bodyScale,
                anchors = Collections.unmodifiableMap(LinkedHashMap(anchors)),
                bodyRatios = Collections.unmodifiableMap(LinkedHashMap(bodyRatios)),
            )
        }

        private fun bodyRatios(
            frame: PoseFrame,
            minimumConfidence: Double,
        ): Map<String, Double> {
            fun point(joint: PoseJoint): Point3? = frame.worldLandmarks[joint]
                ?.takeIf { it.confidence >= minimumConfidence }
                ?.let { Point3(it.x, it.y, it.z) }

            val leftShoulder = point(PoseJoint.LEFT_SHOULDER) ?: return emptyMap()
            val rightShoulder = point(PoseJoint.RIGHT_SHOULDER) ?: return emptyMap()
            val leftHip = point(PoseJoint.LEFT_HIP) ?: return emptyMap()
            val rightHip = point(PoseJoint.RIGHT_HIP) ?: return emptyMap()
            val torsoLength = leftShoulder.midpoint(rightShoulder)
                .distanceTo(leftHip.midpoint(rightHip))
            if (torsoLength <= GEOMETRY_EPSILON) return emptyMap()

            return buildMap {
                put("shoulder-width", leftShoulder.distanceTo(rightShoulder) / torsoLength)
                put("hip-width", leftHip.distanceTo(rightHip) / torsoLength)
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

private val FULL_BODY_JOINTS = setOf(
    PoseJoint.NOSE,
    PoseJoint.LEFT_SHOULDER,
    PoseJoint.RIGHT_SHOULDER,
    PoseJoint.LEFT_HIP,
    PoseJoint.RIGHT_HIP,
    PoseJoint.LEFT_KNEE,
    PoseJoint.RIGHT_KNEE,
    PoseJoint.LEFT_ANKLE,
    PoseJoint.RIGHT_ANKLE,
    PoseJoint.LEFT_HEEL,
    PoseJoint.RIGHT_HEEL,
    PoseJoint.LEFT_FOOT_INDEX,
    PoseJoint.RIGHT_FOOT_INDEX,
)

private const val GEOMETRY_EPSILON = 1e-6

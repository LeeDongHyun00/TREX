package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryEpoch
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import com.example.trex_kotlin.pose.runtime.PosePersonTrackEpoch
import java.util.Collections

private object PoseObservationResearchCapabilityMintAuthority
private val POSE_RESEARCH_SHA256 = Regex("^[0-9a-f]{64}$")

/** Capability identifiers that this research-only stream can prove from observations. */
internal object PoseObservationResearchCapabilities {
    const val POSE_2D = "trex.capability.pose-2d.v1"
    const val POSE_WORLD_RELATIVE = "trex.capability.pose-world-relative.v1"
    const val PRIMARY_PERSON_LOCK = "trex.capability.primary-person-lock.v1"
    const val TEMPORAL_POSE = "trex.capability.temporal-pose.v1"
    const val VIEW_QUALIFIED = "trex.capability.view-qualified.v1"
    const val LATERAL_VIEW_CONTRACT_ID = "trex.view.lateral-full-body.v1"

    val EXACT_CAPABILITY_IDS: List<String> = Collections.unmodifiableList(
        listOf(
            POSE_2D,
            POSE_WORLD_RELATIVE,
            PRIMARY_PERSON_LOCK,
            TEMPORAL_POSE,
            VIEW_QUALIFIED,
        ),
    )
}

/** A stream result is observation evidence only; it never evaluates exercise correctness. */
internal enum class PoseObservationResearchCapabilityEvidenceStatus {
    STABILIZING,
    RECEIPT_READY,
    REJECTED_RESET,
}

internal enum class PoseObservationResearchCapabilityRejectionReason {
    FOREIGN_SOURCE,
    SOURCE_CLOSED,
    PRIMARY_PERSON_LOCK_MISSING,
    LATERAL_VIEW_QUALIFICATION_MISSING,
    CAMERA_GEOMETRY_MISSING,
    NORMALIZED_LANDMARKS_INCOMPLETE,
    WORLD_LANDMARKS_INCOMPLETE,
    TIMESTAMP_NOT_STRICTLY_INCREASING,
    MAXIMUM_FRAME_GAP_EXCEEDED,
    PERSON_TRACK_EPOCH_DRIFT,
    CAMERA_GEOMETRY_EPOCH_DRIFT,
    CAMERA_GEOMETRY_CONTEXT_DRIFT,
}

/**
 * Immutable aggregate proof that two adjacent observations met the research stream contract.
 *
 * It exposes no landmark, observation, source, or person token. A private, non-serialized source
 * reference only prevents cross-lifecycle replay. This is neither a signature nor permission to
 * measure, shadow, decide, score, cue, or release.
 */
internal class PoseObservationResearchCapabilityReceipt internal constructor(
    private val source: PoseObservationSource,
    private val personTrackEpoch: PosePersonTrackEpoch,
    private val cameraGeometryEpoch: PoseCameraGeometryEpoch,
    val providerSchemaVersion: Int,
    val runtimeDomainId: String,
    val observationContractSha256: String,
    val modelArtifactSha256: String,
    val inferenceOptionsArtifactSha256: String,
    val preprocessingArtifactSha256: String,
    val landmarkSchemaArtifactSha256: String,
    val personLockArtifactSha256: String,
    val viewQualifierArtifactSha256: String,
    val lateralViewContractId: String,
    val maximumFrameGapMs: Long,
    val cameraGeometryContextSha256: String,
    val firstTimestampMs: Long,
    val secondTimestampMs: Long,
    val frameGapMs: Long,
    val firstObservationEvidenceSha256: String,
    val secondObservationEvidenceSha256: String,
    mintAuthority: Any,
) {
    val capabilityIds: List<String> = PoseObservationResearchCapabilities.EXACT_CAPABILITY_IDS
    val observationCount: Int = 2
    val normalizedLandmarkCount: Int = PoseJoint.entries.size
    val worldLandmarkCount: Int = PoseJoint.entries.size
    val grantsMeasurementAuthority: Boolean = false
    val grantsShadowAuthority: Boolean = false
    val grantsVerdictAuthority: Boolean = false
    val grantsScoreAuthority: Boolean = false
    val grantsCueAuthority: Boolean = false
    val grantsReleaseAuthority: Boolean = false

    val evidenceContractSha256: String = canonicalEvidenceContractSha256()
    val receiptSha256: String = canonicalReceiptSha256()

    private fun canonicalEvidenceContractSha256(): String = canonicalFieldsSha256(
        listOf(
            "poseObservationResearchCapabilityProviderSchemaVersion" to
                providerSchemaVersion.toString(),
            "runtimeDomainId" to runtimeDomainId,
            "observationContractSha256" to observationContractSha256,
            "modelArtifactSha256" to modelArtifactSha256,
            "inferenceOptionsArtifactSha256" to inferenceOptionsArtifactSha256,
            "preprocessingArtifactSha256" to preprocessingArtifactSha256,
            "landmarkSchemaArtifactSha256" to landmarkSchemaArtifactSha256,
            "personLockArtifactSha256" to personLockArtifactSha256,
            "viewQualifierArtifactSha256" to viewQualifierArtifactSha256,
            "lateralViewContractId" to lateralViewContractId,
            "maximumFrameGapMs" to maximumFrameGapMs.toString(),
            "requiredCoordinateSpaces" to "NORMALIZED_IMAGE,WORLD",
            "requiredNormalizedLandmarkCount" to PoseJoint.entries.size.toString(),
            "requiredWorldLandmarkCount" to PoseJoint.entries.size.toString(),
            "temporalWindowObservationCount" to observationCount.toString(),
        ),
    )

    private fun canonicalReceiptSha256(): String = canonicalFieldsSha256(
        listOf(
            "poseObservationResearchCapabilityReceiptSchemaVersion" to "1",
            "evidenceContractSha256" to evidenceContractSha256,
            "cameraGeometryContextSha256" to cameraGeometryContextSha256,
            "firstTimestampMs" to firstTimestampMs.toString(),
            "secondTimestampMs" to secondTimestampMs.toString(),
            "frameGapMs" to frameGapMs.toString(),
            "firstObservationEvidenceSha256" to firstObservationEvidenceSha256,
            "secondObservationEvidenceSha256" to secondObservationEvidenceSha256,
        ),
    )

    init {
        check(mintAuthority === PoseObservationResearchCapabilityMintAuthority) {
            "Only research capability evidence may mint a receipt"
        }
        require(providerSchemaVersion == 1)
        require(maximumFrameGapMs > 0L)
        require(lateralViewContractId ==
            PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID)
        require(firstTimestampMs >= 0L)
        require(secondTimestampMs > firstTimestampMs)
        require(frameGapMs == secondTimestampMs - firstTimestampMs)
        require(frameGapMs <= maximumFrameGapMs)
        require(firstObservationEvidenceSha256.matches(POSE_RESEARCH_SHA256))
        require(secondObservationEvidenceSha256.matches(POSE_RESEARCH_SHA256))
    }

    internal fun hasCanonicalProvenance(currentObservation: AttestedPoseObservation): Boolean =
        currentObservation.isFrom(source) &&
            source.isOpen &&
            currentObservation.personTrackEpoch === personTrackEpoch &&
            currentObservation.cameraGeometryEpoch === cameraGeometryEpoch &&
            cameraGeometryContextSha256 == cameraGeometryEpoch.contextArtifactSha256 &&
            currentObservation.frame.timestampMs == secondTimestampMs &&
            currentObservation.isViewQualified(lateralViewContractId) &&
            runtimeDomainId == source.contract.runtimeDomainId &&
            observationContractSha256 == source.contract.artifactSha256 &&
            modelArtifactSha256 == source.contract.modelArtifactSha256 &&
            inferenceOptionsArtifactSha256 ==
            source.contract.inferenceOptionsArtifactSha256 &&
            preprocessingArtifactSha256 ==
            source.contract.preprocessingArtifactSha256 &&
            landmarkSchemaArtifactSha256 ==
            source.contract.landmarkSchemaArtifactSha256 &&
            personLockArtifactSha256 == source.contract.personLockArtifactSha256 &&
            viewQualifierArtifactSha256 ==
            source.contract.viewQualifierArtifactSha256 &&
            providerSchemaVersion == 1 &&
            capabilityIds == PoseObservationResearchCapabilities.EXACT_CAPABILITY_IDS &&
            lateralViewContractId ==
            PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID &&
            observationCount == 2 &&
            normalizedLandmarkCount == PoseJoint.entries.size &&
            worldLandmarkCount == PoseJoint.entries.size &&
            frameGapMs in 1..maximumFrameGapMs &&
            evidenceContractSha256 == canonicalEvidenceContractSha256() &&
            receiptSha256 == canonicalReceiptSha256() &&
            canonicalObservationEvidenceSha256(
                observation = currentObservation,
                expectedSource = source,
                expectedPersonTrackEpoch = personTrackEpoch,
                expectedCameraGeometryEpoch = cameraGeometryEpoch,
            ) == secondObservationEvidenceSha256
}

internal class PoseObservationResearchCapabilityEvidenceUpdate internal constructor(
    val status: PoseObservationResearchCapabilityEvidenceStatus,
    val receipt: PoseObservationResearchCapabilityReceipt?,
    rejectionReasons: Set<PoseObservationResearchCapabilityRejectionReason>,
) {
    val rejectionReasons: Set<PoseObservationResearchCapabilityRejectionReason> =
        Collections.unmodifiableSet(LinkedHashSet(rejectionReasons.sortedBy { it.name }))

    init {
        when (status) {
            PoseObservationResearchCapabilityEvidenceStatus.STABILIZING -> {
                require(receipt == null && this.rejectionReasons.isEmpty())
            }
            PoseObservationResearchCapabilityEvidenceStatus.RECEIPT_READY -> {
                require(receipt != null && this.rejectionReasons.isEmpty())
            }
            PoseObservationResearchCapabilityEvidenceStatus.REJECTED_RESET -> {
                require(receipt == null && this.rejectionReasons.isNotEmpty())
            }
        }
    }
}

/**
 * Constant-state, serial research observer for source/person/view/geometry continuity.
 *
 * Each input costs O(33) to prove complete normalized and WORLD landmark maps; retained state is
 * O(1). Any invalid input clears the pending frame, so evidence cannot bridge a discontinuity.
 */
internal class PoseObservationResearchCapabilityEvidence(
    private val expectedSource: PoseObservationSource,
    private val maximumFrameGapMs: Long,
) {
    private var pending: PendingObservation? = null

    init {
        require(maximumFrameGapMs > 0L) { "maximumFrameGapMs must be positive" }
        require(
            expectedSource.contract.supportedCoordinateSpaces.containsAll(
                setOf(PoseCoordinateSpace.NORMALIZED_IMAGE, PoseCoordinateSpace.WORLD),
            ),
        ) { "Expected source must support normalized-image and WORLD landmarks" }
        require(
            PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID in
                expectedSource.contract.allowedViewContractIds,
        ) { "Expected source must allow the lateral full-body view contract" }
    }

    fun accept(
        observation: AttestedPoseObservation,
    ): PoseObservationResearchCapabilityEvidenceUpdate {
        val current = validateCurrent(observation)
        if (current.rejectionReasons.isNotEmpty()) {
            pending = null
            return rejected(current.rejectionReasons)
        }
        val currentPending = checkNotNull(current.pending)

        val previous = pending
        if (previous == null) {
            pending = currentPending
            return PoseObservationResearchCapabilityEvidenceUpdate(
                status = PoseObservationResearchCapabilityEvidenceStatus.STABILIZING,
                receipt = null,
                rejectionReasons = emptySet(),
            )
        }

        val continuityRejections = linkedSetOf<PoseObservationResearchCapabilityRejectionReason>()
        val timestampMs = observation.frame.timestampMs
        if (timestampMs <= previous.timestampMs) {
            continuityRejections +=
                PoseObservationResearchCapabilityRejectionReason
                    .TIMESTAMP_NOT_STRICTLY_INCREASING
        } else if (timestampMs - previous.timestampMs > maximumFrameGapMs) {
            continuityRejections +=
                PoseObservationResearchCapabilityRejectionReason.MAXIMUM_FRAME_GAP_EXCEEDED
        }
        if (currentPending.personTrackEpoch !== previous.personTrackEpoch) {
            continuityRejections +=
                PoseObservationResearchCapabilityRejectionReason.PERSON_TRACK_EPOCH_DRIFT
        }
        if (currentPending.cameraGeometryEpoch !== previous.cameraGeometryEpoch) {
            continuityRejections +=
                PoseObservationResearchCapabilityRejectionReason.CAMERA_GEOMETRY_EPOCH_DRIFT
        }
        if (currentPending.cameraGeometryContextSha256 != previous.cameraGeometryContextSha256) {
            continuityRejections +=
                PoseObservationResearchCapabilityRejectionReason.CAMERA_GEOMETRY_CONTEXT_DRIFT
        }
        if (continuityRejections.isNotEmpty()) {
            pending = null
            return rejected(continuityRejections)
        }

        pending = currentPending
        return PoseObservationResearchCapabilityEvidenceUpdate(
            status = PoseObservationResearchCapabilityEvidenceStatus.RECEIPT_READY,
            receipt = PoseObservationResearchCapabilityReceipt(
                source = expectedSource,
                personTrackEpoch = currentPending.personTrackEpoch,
                cameraGeometryEpoch = currentPending.cameraGeometryEpoch,
                providerSchemaVersion = PROVIDER_SCHEMA_VERSION,
                runtimeDomainId = expectedSource.contract.runtimeDomainId,
                observationContractSha256 = expectedSource.contract.artifactSha256,
                modelArtifactSha256 = expectedSource.contract.modelArtifactSha256,
                inferenceOptionsArtifactSha256 =
                    expectedSource.contract.inferenceOptionsArtifactSha256,
                preprocessingArtifactSha256 =
                    expectedSource.contract.preprocessingArtifactSha256,
                landmarkSchemaArtifactSha256 =
                    expectedSource.contract.landmarkSchemaArtifactSha256,
                personLockArtifactSha256 =
                    expectedSource.contract.personLockArtifactSha256,
                viewQualifierArtifactSha256 =
                    expectedSource.contract.viewQualifierArtifactSha256,
                lateralViewContractId =
                    PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
                maximumFrameGapMs = maximumFrameGapMs,
                cameraGeometryContextSha256 = currentPending.cameraGeometryContextSha256,
                firstTimestampMs = previous.timestampMs,
                secondTimestampMs = timestampMs,
                frameGapMs = timestampMs - previous.timestampMs,
                firstObservationEvidenceSha256 = previous.observationEvidenceSha256,
                secondObservationEvidenceSha256 = currentPending.observationEvidenceSha256,
                mintAuthority = PoseObservationResearchCapabilityMintAuthority,
            ),
            rejectionReasons = emptySet(),
        )
    }

    fun reset() {
        pending = null
    }

    private fun validateCurrent(
        observation: AttestedPoseObservation,
    ): CurrentValidation {
        val reasons = linkedSetOf<PoseObservationResearchCapabilityRejectionReason>()
        if (!observation.isFrom(expectedSource)) {
            reasons += PoseObservationResearchCapabilityRejectionReason.FOREIGN_SOURCE
        }
        if (!expectedSource.isOpen) {
            reasons += PoseObservationResearchCapabilityRejectionReason.SOURCE_CLOSED
        }
        val personTrackEpoch = observation.personTrackEpoch
        if (personTrackEpoch == null) {
            reasons +=
                PoseObservationResearchCapabilityRejectionReason.PRIMARY_PERSON_LOCK_MISSING
        }
        if (!observation.isViewQualified(
                PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
            )
        ) {
            reasons +=
                PoseObservationResearchCapabilityRejectionReason
                    .LATERAL_VIEW_QUALIFICATION_MISSING
        }
        val cameraGeometryEpoch = observation.cameraGeometryEpoch
        if (cameraGeometryEpoch == null) {
            reasons += PoseObservationResearchCapabilityRejectionReason.CAMERA_GEOMETRY_MISSING
        }
        if (!hasCompleteLandmarkMap(observation.frame.landmarks)) {
            reasons +=
                PoseObservationResearchCapabilityRejectionReason
                    .NORMALIZED_LANDMARKS_INCOMPLETE
        }
        if (!hasCompleteLandmarkMap(observation.frame.worldLandmarks)) {
            reasons +=
                PoseObservationResearchCapabilityRejectionReason.WORLD_LANDMARKS_INCOMPLETE
        }

        val pendingObservation = if (
            reasons.isEmpty() && personTrackEpoch != null && cameraGeometryEpoch != null
        ) {
            PendingObservation(
                timestampMs = observation.frame.timestampMs,
                personTrackEpoch = personTrackEpoch,
                cameraGeometryEpoch = cameraGeometryEpoch,
                cameraGeometryContextSha256 = cameraGeometryEpoch.contextArtifactSha256,
                observationEvidenceSha256 = checkNotNull(
                    canonicalObservationEvidenceSha256(
                        observation = observation,
                        expectedSource = expectedSource,
                        expectedPersonTrackEpoch = personTrackEpoch,
                        expectedCameraGeometryEpoch = cameraGeometryEpoch,
                    ),
                ),
            )
        } else {
            null
        }
        return CurrentValidation(reasons, pendingObservation)
    }

    private fun hasCompleteLandmarkMap(landmarks: Map<PoseJoint, *>): Boolean =
        landmarks.size == PoseJoint.entries.size && PoseJoint.entries.all(landmarks::containsKey)

    private fun rejected(
        reasons: Set<PoseObservationResearchCapabilityRejectionReason>,
    ) = PoseObservationResearchCapabilityEvidenceUpdate(
        status = PoseObservationResearchCapabilityEvidenceStatus.REJECTED_RESET,
        receipt = null,
        rejectionReasons = reasons,
    )

    private class CurrentValidation(
        val rejectionReasons: Set<PoseObservationResearchCapabilityRejectionReason>,
        val pending: PendingObservation?,
    )

    private class PendingObservation(
        val timestampMs: Long,
        val personTrackEpoch: PosePersonTrackEpoch,
        val cameraGeometryEpoch: PoseCameraGeometryEpoch,
        val cameraGeometryContextSha256: String,
        val observationEvidenceSha256: String,
    )

    private companion object {
        const val PROVIDER_SCHEMA_VERSION = 1
    }
}

/** Returns null unless the observation is an exact member of the expected evidence domain. */
private fun canonicalObservationEvidenceSha256(
    observation: AttestedPoseObservation,
    expectedSource: PoseObservationSource,
    expectedPersonTrackEpoch: PosePersonTrackEpoch,
    expectedCameraGeometryEpoch: PoseCameraGeometryEpoch,
): String? {
    if (!observation.isFrom(expectedSource) ||
        observation.personTrackEpoch !== expectedPersonTrackEpoch ||
        observation.cameraGeometryEpoch !== expectedCameraGeometryEpoch ||
        !observation.isViewQualified(
            PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
        ) ||
        observation.frame.landmarks.size != PoseJoint.entries.size ||
        observation.frame.worldLandmarks.size != PoseJoint.entries.size ||
        !PoseJoint.entries.all(observation.frame.landmarks::containsKey) ||
        !PoseJoint.entries.all(observation.frame.worldLandmarks::containsKey)
    ) {
        return null
    }
    val frame = observation.frame
    return canonicalFieldsSha256(
        buildList {
            add("poseObservationResearchFrameEvidenceSchemaVersion" to "1")
            add("observationContractSha256" to expectedSource.contract.artifactSha256)
            add(
                "cameraGeometryContextSha256" to
                    expectedCameraGeometryEpoch.contextArtifactSha256,
            )
            add(
                "viewContractId" to
                    PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
            )
            add("timestampMs" to frame.timestampMs.toString())
            add("imageWidth" to frame.imageWidth.toString())
            add("imageHeight" to frame.imageHeight.toString())
            add("rotationDegrees" to frame.rotationDegrees.toString())
            add("isMirrored" to frame.isMirrored.toString())
            PoseJoint.entries.forEach { joint ->
                appendLandmarkFields("normalized.${joint.mediaPipeIndex}", frame.landmarks.getValue(joint))
                appendLandmarkFields("world.${joint.mediaPipeIndex}", frame.worldLandmarks.getValue(joint))
            }
        },
    )
}

private fun MutableList<Pair<String, String>>.appendLandmarkFields(
    prefix: String,
    landmark: com.example.trex_kotlin.pose.PoseLandmark,
) {
    add("$prefix.x" to java.lang.Double.toHexString(landmark.x))
    add("$prefix.y" to java.lang.Double.toHexString(landmark.y))
    add("$prefix.z" to java.lang.Double.toHexString(landmark.z))
    add("$prefix.visibility" to java.lang.Double.toHexString(landmark.visibility))
    add("$prefix.presence" to java.lang.Double.toHexString(landmark.presence))
}

package com.example.trex_kotlin.pose.runtime

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import java.util.Collections

private val OBSERVATION_IDENTIFIER = Regex("^[a-z0-9][a-z0-9._:/-]*$")
private val OBSERVATION_SHA256 = Regex("^[0-9a-f]{64}$")

/**
 * Content-addressed identity of the runtime that is allowed to produce pose observations.
 *
 * This is deliberately narrower than a caller-provided capability set. It pins the model bytes,
 * preprocessing and landmark semantics used by calibration, plus the exact person-lock and view
 * qualifier artifacts that may mint dynamic evidence. Merely placing landmarks in a [PoseFrame]
 * does not prove any of these properties.
 */
class PoseObservationContract(
    val runtimeDomainId: String,
    val modelArtifactId: String,
    val modelArtifactSha256: String,
    val preprocessingContractId: String,
    val preprocessingArtifactSha256: String,
    val landmarkSchemaId: String,
    val landmarkSchemaArtifactSha256: String,
    supportedCoordinateSpaces: Set<PoseCoordinateSpace>,
    val phaseViewContractId: String,
    val personLockArtifactId: String,
    val personLockArtifactSha256: String,
    val viewQualifierArtifactId: String,
    val viewQualifierArtifactSha256: String,
) {
    val supportedCoordinateSpaces: Set<PoseCoordinateSpace> =
        Collections.unmodifiableSet(
            LinkedHashSet(supportedCoordinateSpaces.sortedBy(PoseCoordinateSpace::name)),
        )

    init {
        validateIdentifier("runtimeDomainId", runtimeDomainId)
        validateIdentifier("modelArtifactId", modelArtifactId)
        validateSha256("modelArtifactSha256", modelArtifactSha256)
        validateIdentifier("preprocessingContractId", preprocessingContractId)
        validateSha256("preprocessingArtifactSha256", preprocessingArtifactSha256)
        validateIdentifier("landmarkSchemaId", landmarkSchemaId)
        validateSha256("landmarkSchemaArtifactSha256", landmarkSchemaArtifactSha256)
        require(this.supportedCoordinateSpaces.isNotEmpty()) {
            "supportedCoordinateSpaces must not be empty"
        }
        validateIdentifier("phaseViewContractId", phaseViewContractId)
        validateIdentifier("personLockArtifactId", personLockArtifactId)
        validateSha256("personLockArtifactSha256", personLockArtifactSha256)
        validateIdentifier("viewQualifierArtifactId", viewQualifierArtifactId)
        validateSha256("viewQualifierArtifactSha256", viewQualifierArtifactSha256)
    }

    val artifactSha256: String = canonicalFieldsSha256(
        buildList {
            add("poseObservationContractSchemaVersion" to "1")
            add("runtimeDomainId" to runtimeDomainId)
            add("modelArtifactId" to modelArtifactId)
            add("modelArtifactSha256" to modelArtifactSha256)
            add("preprocessingContractId" to preprocessingContractId)
            add("preprocessingArtifactSha256" to preprocessingArtifactSha256)
            add("landmarkSchemaId" to landmarkSchemaId)
            add("landmarkSchemaArtifactSha256" to landmarkSchemaArtifactSha256)
            val coordinateSpaces = this@PoseObservationContract.supportedCoordinateSpaces
            add("supportedCoordinateSpaceCount" to coordinateSpaces.size.toString())
            coordinateSpaces.forEachIndexed { index, coordinateSpace ->
                add("supportedCoordinateSpace[$index]" to coordinateSpace.name)
            }
            add("phaseViewContractId" to phaseViewContractId)
            add("personLockArtifactId" to personLockArtifactId)
            add("personLockArtifactSha256" to personLockArtifactSha256)
            add("viewQualifierArtifactId" to viewQualifierArtifactId)
            add("viewQualifierArtifactSha256" to viewQualifierArtifactSha256)
        },
    )
}

/**
 * Opaque identity for one initialized observer lifecycle.
 *
 * Equality intentionally remains reference equality. Two sources using identical artifacts are
 * not interchangeable because their temporal person locks belong to different camera lifecycles.
 * Construction and minting stay module-internal until a candidate observer owns this boundary.
 */
class PoseObservationSource internal constructor(
    val contract: PoseObservationContract,
) {
    internal fun newPersonTrackEpoch(): PosePersonTrackEpoch = PosePersonTrackEpoch(this)

    internal fun qualifyView(viewContractId: String): PoseViewQualification {
        validateIdentifier("viewContractId", viewContractId)
        return PoseViewQualification(
            source = this,
            viewContractId = viewContractId,
        )
    }

    /** Internal mint used by the observer implementation and deterministic unit fixtures. */
    internal fun attest(
        frame: PoseFrame,
        personTrackEpoch: PosePersonTrackEpoch?,
        viewQualifications: Collection<PoseViewQualification>,
    ): AttestedPoseObservation = AttestedPoseObservation(
        source = this,
        frame = frame,
        personTrackEpoch = personTrackEpoch,
        viewQualifications = viewQualifications,
    )
}

/** Session-local, non-persistent identity continuity token minted only by its observer source. */
class PosePersonTrackEpoch internal constructor(
    internal val source: PoseObservationSource,
)

/** One view decision minted by the source's contract-pinned qualifier. */
class PoseViewQualification internal constructor(
    internal val source: PoseObservationSource,
    val viewContractId: String,
) {
    init {
        validateIdentifier("viewContractId", viewContractId)
    }
}

/**
 * A raw frame accompanied by source-bound, frame-time observation evidence.
 *
 * The constructor is internal and the class has no `copy` operation. Dynamic lock and view state
 * are represented by opaque source-bound tokens rather than by a caller-controlled capability
 * set. Landmark maps are snapshotted so the attestation cannot be changed after minting.
 */
class AttestedPoseObservation internal constructor(
    internal val source: PoseObservationSource,
    frame: PoseFrame,
    internal val personTrackEpoch: PosePersonTrackEpoch?,
    viewQualifications: Collection<PoseViewQualification>,
) {
    val frame: PoseFrame = frame.snapshot()

    private val viewQualificationSnapshot: List<PoseViewQualification> =
        Collections.unmodifiableList(ArrayList(viewQualifications))
    private val viewQualificationsByContractId: Map<String, PoseViewQualification>

    init {
        require(personTrackEpoch == null || personTrackEpoch.source === source) {
            "A person-track epoch must be minted by the observation source"
        }
        require(viewQualificationSnapshot.all { qualification -> qualification.source === source }) {
            "Every view qualification must be minted by the observation source"
        }
        val duplicateViewIds = viewQualificationSnapshot
            .groupingBy(PoseViewQualification::viewContractId)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateViewIds.isEmpty()) {
            "Duplicate view qualifications: ${duplicateViewIds.sorted().joinToString()}"
        }
        viewQualificationsByContractId = Collections.unmodifiableMap(
            viewQualificationSnapshot
                .sortedBy(PoseViewQualification::viewContractId)
                .associateByTo(LinkedHashMap(), PoseViewQualification::viewContractId),
        )
    }

    val hasPrimaryPersonLock: Boolean
        get() = personTrackEpoch != null

    internal fun isFrom(expectedSource: PoseObservationSource): Boolean = source === expectedSource

    internal fun isViewQualified(viewContractId: String): Boolean =
        viewContractId in viewQualificationsByContractId
}

private fun validateIdentifier(fieldName: String, value: String) {
    require(OBSERVATION_IDENTIFIER.matches(value)) {
        "$fieldName must be a lowercase, versioned identifier"
    }
}

private fun validateSha256(fieldName: String, value: String) {
    require(OBSERVATION_SHA256.matches(value)) {
        "$fieldName must be a lowercase SHA-256"
    }
}

private fun PoseFrame.snapshot(): PoseFrame = copy(
    landmarks = Collections.unmodifiableMap(LinkedHashMap(landmarks)),
    worldLandmarks = Collections.unmodifiableMap(LinkedHashMap(worldLandmarks)),
)

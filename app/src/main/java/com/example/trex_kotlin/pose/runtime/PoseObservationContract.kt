package com.example.trex_kotlin.pose.runtime

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

private val OBSERVATION_IDENTIFIER = Regex("^[a-z0-9][a-z0-9._:/-]*$")
private val OBSERVATION_SHA256 = Regex("^[0-9a-f]{64}$")
private object PoseCameraGeometryMintAuthority

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
    val inferenceOptionsContractId: String,
    val inferenceOptionsArtifactSha256: String,
    val preprocessingContractId: String,
    val preprocessingArtifactSha256: String,
    val landmarkSchemaId: String,
    val landmarkSchemaArtifactSha256: String,
    supportedCoordinateSpaces: Set<PoseCoordinateSpace>,
    val phaseViewContractId: String,
    allowedViewContractIds: Set<String>,
    val personLockArtifactId: String,
    val personLockArtifactSha256: String,
    val viewQualifierArtifactId: String,
    val viewQualifierArtifactSha256: String,
) {
    val supportedCoordinateSpaces: Set<PoseCoordinateSpace> =
        Collections.unmodifiableSet(
            LinkedHashSet(supportedCoordinateSpaces.sortedBy(PoseCoordinateSpace::name)),
        )
    val allowedViewContractIds: Set<String> =
        Collections.unmodifiableSet(LinkedHashSet(allowedViewContractIds.sorted()))

    init {
        validateIdentifier("runtimeDomainId", runtimeDomainId)
        validateIdentifier("modelArtifactId", modelArtifactId)
        validateSha256("modelArtifactSha256", modelArtifactSha256)
        validateIdentifier("inferenceOptionsContractId", inferenceOptionsContractId)
        validateSha256("inferenceOptionsArtifactSha256", inferenceOptionsArtifactSha256)
        validateIdentifier("preprocessingContractId", preprocessingContractId)
        validateSha256("preprocessingArtifactSha256", preprocessingArtifactSha256)
        validateIdentifier("landmarkSchemaId", landmarkSchemaId)
        validateSha256("landmarkSchemaArtifactSha256", landmarkSchemaArtifactSha256)
        require(this.supportedCoordinateSpaces.isNotEmpty()) {
            "supportedCoordinateSpaces must not be empty"
        }
        validateIdentifier("phaseViewContractId", phaseViewContractId)
        require(this.allowedViewContractIds.isNotEmpty()) {
            "allowedViewContractIds must not be empty"
        }
        this.allowedViewContractIds.forEach { viewContractId ->
            validateIdentifier("allowedViewContractId", viewContractId)
        }
        require(phaseViewContractId in this.allowedViewContractIds) {
            "phaseViewContractId must be present in allowedViewContractIds"
        }
        validateIdentifier("personLockArtifactId", personLockArtifactId)
        validateSha256("personLockArtifactSha256", personLockArtifactSha256)
        validateIdentifier("viewQualifierArtifactId", viewQualifierArtifactId)
        validateSha256("viewQualifierArtifactSha256", viewQualifierArtifactSha256)
    }

    val artifactSha256: String = canonicalFieldsSha256(
        buildList {
            add("poseObservationContractSchemaVersion" to "3")
            add(
                "cameraGeometryAttestationPolicy" to
                    "OPTIONAL_SOURCE_BOUND_CONTEXT_EPOCH_AND_FRAME_TIMESTAMP_RECEIPT",
            )
            add(
                "cameraGeometryFrameMatchPolicy" to
                    "EXACT_OUTPUT_DIMENSIONS_UPRIGHT_ROTATION_AND_DISPLAY_MIRROR",
            )
            add("runtimeDomainId" to runtimeDomainId)
            add("modelArtifactId" to modelArtifactId)
            add("modelArtifactSha256" to modelArtifactSha256)
            add("inferenceOptionsContractId" to inferenceOptionsContractId)
            add("inferenceOptionsArtifactSha256" to inferenceOptionsArtifactSha256)
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
            val viewContractIds = this@PoseObservationContract.allowedViewContractIds
            add("allowedViewContractIdCount" to viewContractIds.size.toString())
            viewContractIds.forEachIndexed { index, viewContractId ->
                add("allowedViewContractId[$index]" to viewContractId)
            }
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
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    internal fun newPersonTrackEpoch(): PosePersonTrackEpoch {
        requireOpen()
        return PosePersonTrackEpoch(this)
    }

    /** Starts one reference-identity epoch for an unchanged camera crop-to-output transform. */
    internal fun newCameraGeometryEpoch(
        context: PoseCameraGeometryContext,
    ): PoseCameraGeometryEpoch {
        requireOpen()
        require(context.preprocessingArtifactSha256 == contract.preprocessingArtifactSha256) {
            "Camera geometry preprocessing does not match the observation source contract"
        }
        return PoseCameraGeometryEpoch.mint(
            source = this,
            context = context,
            authority = PoseCameraGeometryMintAuthority,
        )
    }

    internal fun qualifyView(
        viewContractId: String,
        personTrackEpoch: PosePersonTrackEpoch,
        frameTimestampMs: Long,
    ): PoseViewQualification {
        requireOpen()
        validateIdentifier("viewContractId", viewContractId)
        require(viewContractId in contract.allowedViewContractIds) {
            "View contract is not allowed by this observation source"
        }
        require(personTrackEpoch.source === this) {
            "A view qualification must use this source's person-track epoch"
        }
        require(frameTimestampMs >= 0L) { "frameTimestampMs must be non-negative" }
        return PoseViewQualification(
            source = this,
            personTrackEpoch = personTrackEpoch,
            frameTimestampMs = frameTimestampMs,
            viewContractId = viewContractId,
        )
    }

    /**
     * Legacy mint for deterministic fixtures and unreleased runtimes without camera geometry.
     * Its result cannot prove camera-geometry continuity and must not enter a geometry-gated path.
     */
    internal fun attest(
        frame: PoseFrame,
        personTrackEpoch: PosePersonTrackEpoch?,
        viewQualifications: Collection<PoseViewQualification>,
    ): AttestedPoseObservation {
        requireOpen()
        return AttestedPoseObservation(
            source = this,
            frame = frame,
            personTrackEpoch = personTrackEpoch,
            viewQualifications = viewQualifications,
            cameraGeometryReceipt = null,
        )
    }

    /** Observer mint that binds geometry, source, and capture timestamp to the observation. */
    internal fun attest(
        frame: PoseFrame,
        personTrackEpoch: PosePersonTrackEpoch?,
        viewQualifications: Collection<PoseViewQualification>,
        cameraGeometryEpoch: PoseCameraGeometryEpoch,
    ): AttestedPoseObservation {
        requireOpen()
        require(cameraGeometryEpoch.source === this) {
            "A camera-geometry epoch must be minted by this observation source"
        }
        require(
            cameraGeometryEpoch.context.preprocessingArtifactSha256 ==
                contract.preprocessingArtifactSha256,
        ) {
            "Camera geometry preprocessing does not match the observation source contract"
        }
        val receipt = PoseCameraGeometryReceipt.mint(
            source = this,
            epoch = cameraGeometryEpoch,
            frameTimestampMs = frame.timestampMs,
            authority = PoseCameraGeometryMintAuthority,
        )
        return AttestedPoseObservation(
            source = this,
            frame = frame,
            personTrackEpoch = personTrackEpoch,
            viewQualifications = viewQualifications,
            cameraGeometryReceipt = receipt,
        )
    }

    override fun close() {
        closed.set(true)
    }

    internal val isOpen: Boolean
        get() = !closed.get()

    private fun requireOpen() {
        check(!closed.get()) { "Pose observation source is closed" }
    }
}

/** Session-local, non-persistent identity continuity token minted only by its observer source. */
class PosePersonTrackEpoch internal constructor(
    internal val source: PoseObservationSource,
)

/** Session-local reference identity for one unchanged camera geometry context. */
class PoseCameraGeometryEpoch private constructor(
    internal val source: PoseObservationSource,
    internal val context: PoseCameraGeometryContext,
) {
    internal val contextArtifactSha256: String
        get() = context.artifactSha256

    internal companion object {
        fun mint(
            source: PoseObservationSource,
            context: PoseCameraGeometryContext,
            authority: Any,
        ): PoseCameraGeometryEpoch {
            check(authority === PoseCameraGeometryMintAuthority) {
                "Only the observation source may mint a camera-geometry epoch"
            }
            return PoseCameraGeometryEpoch(source, context)
        }
    }
}

/** Source- and capture-time-bound receipt for one frame's camera geometry epoch. */
class PoseCameraGeometryReceipt private constructor(
    internal val source: PoseObservationSource,
    internal val epoch: PoseCameraGeometryEpoch,
    internal val frameTimestampMs: Long,
) {
    internal val contextArtifactSha256: String
        get() = epoch.contextArtifactSha256

    init {
        require(epoch.source === source)
        require(frameTimestampMs >= 0L)
    }

    internal companion object {
        fun mint(
            source: PoseObservationSource,
            epoch: PoseCameraGeometryEpoch,
            frameTimestampMs: Long,
            authority: Any,
        ): PoseCameraGeometryReceipt {
            check(authority === PoseCameraGeometryMintAuthority) {
                "Only the observation source may mint a camera-geometry receipt"
            }
            return PoseCameraGeometryReceipt(source, epoch, frameTimestampMs)
        }
    }
}

/** One view decision minted by the source's contract-pinned qualifier. */
class PoseViewQualification internal constructor(
    internal val source: PoseObservationSource,
    internal val personTrackEpoch: PosePersonTrackEpoch,
    internal val frameTimestampMs: Long,
    val viewContractId: String,
) {
    init {
        require(personTrackEpoch.source === source)
        require(frameTimestampMs >= 0L)
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
    internal val cameraGeometryReceipt: PoseCameraGeometryReceipt? = null,
) {
    val frame: PoseFrame = frame.snapshot()

    private val viewQualificationSnapshot: List<PoseViewQualification> =
        Collections.unmodifiableList(ArrayList(viewQualifications))
    private val viewQualificationsByContractId: Map<String, PoseViewQualification>

    init {
        require(personTrackEpoch == null || personTrackEpoch.source === source) {
            "A person-track epoch must be minted by the observation source"
        }
        require(cameraGeometryReceipt == null || cameraGeometryReceipt.source === source) {
            "A camera-geometry receipt must be minted by the observation source"
        }
        require(
            cameraGeometryReceipt == null ||
                cameraGeometryReceipt.frameTimestampMs == this.frame.timestampMs,
        ) {
            "A camera-geometry receipt must match the observation timestamp"
        }
        require(
            cameraGeometryReceipt == null ||
                cameraGeometryReceipt.epoch.context.preprocessingArtifactSha256 ==
                source.contract.preprocessingArtifactSha256,
        ) {
            "Camera geometry preprocessing must match the observation source contract"
        }
        require(
            cameraGeometryReceipt == null ||
                cameraGeometryReceipt.epoch.context.matchesOutputFrame(this.frame),
        ) {
            "Camera geometry must match the observation output frame"
        }
        require(viewQualificationSnapshot.all { qualification -> qualification.source === source }) {
            "Every view qualification must be minted by the observation source"
        }
        require(
            viewQualificationSnapshot.all { qualification ->
                qualification.personTrackEpoch === personTrackEpoch &&
                    qualification.frameTimestampMs == this.frame.timestampMs
            },
        ) {
            "Every view qualification must match the observation person epoch and timestamp"
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

    internal val cameraGeometryEpoch: PoseCameraGeometryEpoch?
        get() = cameraGeometryReceipt?.epoch

    internal fun isFrom(expectedSource: PoseObservationSource): Boolean = source === expectedSource

    internal fun isViewQualified(viewContractId: String): Boolean =
        viewContractId in viewQualificationsByContractId

    internal fun viewQualification(viewContractId: String): PoseViewQualification? =
        viewQualificationsByContractId[viewContractId]
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

package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseSide
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.feature.FeatureUnknownReason
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import java.util.Collections

/** Serial trace progress. A reset always leaves the trace empty and unstabilized. */
internal enum class GoodMorningKneeStabilityTraceStatus {
    STABILIZING,
    SAMPLE_APPENDED,
    RESET,
}

internal enum class GoodMorningKneeStabilityTraceResetReason {
    OBSERVATION_EVIDENCE_REJECTED,
    INVALID_CAPABILITY_RECEIPT,
    INVALID_RESEARCH_DIAGNOSTIC,
    SAMPLE_CAPACITY_EXCEEDED,
    TRACE_DURATION_EXCEEDED,
}

internal enum class GoodMorningKneeStabilityTraceBlocker {
    PHASE_SCOPE_UNAVAILABLE,
    GOLD_LABEL_UNAVAILABLE,
    CALIBRATION_ARTIFACT_UNAVAILABLE,
    REFERENCE_EVIDENCE_UNAVAILABLE,
    TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE,
    SHADOW_AUTHORIZATION_UNAVAILABLE,
    RELEASE_AUTHORIZATION_UNAVAILABLE,
}

/** Restricted in-memory geometry value object with no opaque runtime reference. */
internal class GoodMorningKneeStabilityGeometry internal constructor(
    val contextSha256: String,
    val sourceImageWidth: Int,
    val sourceImageHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRightExclusive: Int,
    val cropBottomExclusive: Int,
    val inputRotationDegrees: Int,
    val outputImageWidth: Int,
    val outputImageHeight: Int,
    val outputRotationDegrees: Int,
    val inferencePixelsMirrored: Boolean,
    val displayMirrored: Boolean,
    val preprocessingArtifactSha256: String,
) {
    internal constructor(context: PoseCameraGeometryContext) : this(
        contextSha256 = context.artifactSha256,
        sourceImageWidth = context.sourceImageWidth,
        sourceImageHeight = context.sourceImageHeight,
        cropLeft = context.cropLeft,
        cropTop = context.cropTop,
        cropRightExclusive = context.cropRightExclusive,
        cropBottomExclusive = context.cropBottomExclusive,
        inputRotationDegrees = context.inputRotationDegrees,
        outputImageWidth = context.outputImageWidth,
        outputImageHeight = context.outputImageHeight,
        outputRotationDegrees = context.outputRotationDegrees,
        inferencePixelsMirrored = context.inferencePixelsMirrored,
        displayMirrored = context.displayMirrored,
        preprocessingArtifactSha256 = context.preprocessingArtifactSha256,
    )
}

/** One side of one frame-local research sample. */
internal class GoodMorningKneeStabilitySideSample internal constructor(
    val side: PoseSide,
    val flexionDegrees: Double?,
    val rawConfidence: Double,
    val featureUnknownReason: FeatureUnknownReason?,
) {
    init {
        require(flexionDegrees == null || flexionDegrees in 0.0..180.0)
        require(rawConfidence.isFinite() && rawConfidence in 0.0..1.0)
        require(flexionDegrees == null || featureUnknownReason == null)
    }
}

/** One immutable, relative-time sample; no source timestamp or pose payload is retained. */
internal class GoodMorningKneeStabilityTraceSample internal constructor(
    val elapsedMs: Long,
    val capabilityReceiptSha256: String,
    val diagnosticProvenanceSha256: String,
    val left: GoodMorningKneeStabilitySideSample,
    val right: GoodMorningKneeStabilitySideSample,
) {
    val contentSha256: String = canonicalFieldsSha256(
        buildList {
            add("goodMorningKneeStabilityTraceSampleSchemaVersion" to "1")
            add("elapsedMs" to elapsedMs.toString())
            add("capabilityReceiptSha256" to capabilityReceiptSha256)
            add("diagnosticProvenanceSha256" to diagnosticProvenanceSha256)
            listOf(left, right).forEach { sample ->
                val prefix = sample.side.name
                add("$prefix.flexionDegrees" to
                    (sample.flexionDegrees?.let(java.lang.Double::toHexString) ?: ""))
                add("$prefix.rawConfidence" to
                    java.lang.Double.toHexString(sample.rawConfidence))
                add("$prefix.featureUnknownReason" to
                    (sample.featureUnknownReason?.name ?: ""))
            }
        },
    )

    init {
        require(elapsedMs > 0L)
        require(left.side == PoseSide.LEFT)
        require(right.side == PoseSide.RIGHT)
    }
}

/**
 * Immutable candidate evidence only. It cannot represent Gold, a criterion decision, or authority.
 */
internal class GoodMorningKneeStabilityCandidateSnapshot internal constructor(
    val schemaVersion: Int,
    val bindingId: String,
    val bindingPolicySha256: String,
    val runtimeDomainId: String,
    val observationContractSha256: String,
    val modelArtifactSha256: String,
    val inferenceOptionsArtifactSha256: String,
    val preprocessingArtifactSha256: String,
    val landmarkSchemaArtifactSha256: String,
    val personLockArtifactSha256: String,
    val viewQualifierArtifactSha256: String,
    val lateralViewContractId: String,
    val coordinateSpace: PoseCoordinateSpace,
    val traceContractSha256: String,
    val geometry: GoodMorningKneeStabilityGeometry,
    samples: List<GoodMorningKneeStabilityTraceSample>,
    blockers: Set<GoodMorningKneeStabilityTraceBlocker>,
) {
    val state: GoodMorningKneeFlexionResearchState =
        GoodMorningKneeFlexionResearchState.UNKNOWN
    val authority: GoodMorningKneeFlexionResearchAuthority =
        GoodMorningKneeFlexionResearchAuthority.NONE
    val isGold: Boolean = false
    val samples: List<GoodMorningKneeStabilityTraceSample> =
        Collections.unmodifiableList(ArrayList(samples))
    val blockers: Set<GoodMorningKneeStabilityTraceBlocker> =
        Collections.unmodifiableSet(LinkedHashSet(blockers.sortedBy { it.name }))
    val sampleCount: Int = this.samples.size
    val durationMs: Long = this.samples.last().elapsedMs
    val contentSha256: String = canonicalFieldsSha256(
        buildList {
            add("goodMorningKneeStabilityCandidateSnapshotSchemaVersion" to schemaVersion.toString())
            add("bindingId" to bindingId)
            add("bindingPolicySha256" to bindingPolicySha256)
            add("runtimeDomainId" to runtimeDomainId)
            add("observationContractSha256" to observationContractSha256)
            add("modelArtifactSha256" to modelArtifactSha256)
            add("inferenceOptionsArtifactSha256" to inferenceOptionsArtifactSha256)
            add("preprocessingArtifactSha256" to preprocessingArtifactSha256)
            add("landmarkSchemaArtifactSha256" to landmarkSchemaArtifactSha256)
            add("personLockArtifactSha256" to personLockArtifactSha256)
            add("viewQualifierArtifactSha256" to viewQualifierArtifactSha256)
            add("lateralViewContractId" to lateralViewContractId)
            add("coordinateSpace" to coordinateSpace.name)
            add("traceContractSha256" to traceContractSha256)
            add("cameraGeometryContextSha256" to geometry.contextSha256)
            add("state" to state.name)
            add("isGold" to isGold.toString())
            add("sampleCount" to sampleCount.toString())
            this@GoodMorningKneeStabilityCandidateSnapshot.samples.forEachIndexed { index, sample ->
                add("sample[$index].contentSha256" to sample.contentSha256)
            }
            add("blockerCount" to this@GoodMorningKneeStabilityCandidateSnapshot.blockers.size.toString())
            this@GoodMorningKneeStabilityCandidateSnapshot.blockers
                .forEachIndexed { index, blocker -> add("blocker[$index]" to blocker.name) }
            add("totalAuthority" to authority.totalAuthority.toString())
        },
    )

    init {
        require(schemaVersion == 1)
        require(bindingId == GoodMorningKneeFlexionResearchDiagnostic.BINDING_ID)
        require(bindingPolicySha256 ==
            GoodMorningKneeFlexionResearchDiagnostic.BINDING_POLICY_SHA256)
        require(lateralViewContractId ==
            PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID)
        require(coordinateSpace == PoseCoordinateSpace.WORLD)
        require(this.samples.size >= MINIMUM_SEALED_SAMPLE_COUNT)
        require(this.samples.zipWithNext().all { (first, second) ->
            second.elapsedMs > first.elapsedMs
        })
        require(REQUIRED_BLOCKERS.all(this.blockers::contains))
        require(authority.totalAuthority == 0)
    }

    private companion object {
        const val MINIMUM_SEALED_SAMPLE_COUNT = 2
        val REQUIRED_BLOCKERS = GoodMorningKneeStabilityTraceBlocker.entries.toSet()
    }
}

internal class GoodMorningKneeStabilityTraceUpdate internal constructor(
    val status: GoodMorningKneeStabilityTraceStatus,
    val appendedSample: GoodMorningKneeStabilityTraceSample?,
    val resetReason: GoodMorningKneeStabilityTraceResetReason?,
    evidenceRejectionReasons: Set<PoseObservationResearchCapabilityRejectionReason>,
    val retainedSampleCount: Int,
) {
    val evidenceRejectionReasons: Set<PoseObservationResearchCapabilityRejectionReason> =
        Collections.unmodifiableSet(
            LinkedHashSet(evidenceRejectionReasons.sortedBy { it.name }),
        )

    init {
        require(retainedSampleCount >= 0)
        when (status) {
            GoodMorningKneeStabilityTraceStatus.STABILIZING ->
                require(appendedSample == null && resetReason == null &&
                    this.evidenceRejectionReasons.isEmpty() && retainedSampleCount == 0)
            GoodMorningKneeStabilityTraceStatus.SAMPLE_APPENDED ->
                require(appendedSample != null && resetReason == null &&
                    this.evidenceRejectionReasons.isEmpty() && retainedSampleCount > 0)
            GoodMorningKneeStabilityTraceStatus.RESET ->
                require(appendedSample == null && resetReason != null && retainedSampleCount == 0)
        }
    }
}

/**
 * Serial, bounded and in-memory-only Good Morning trace.
 *
 * Input remains a production [AttestedPoseObservation]. Capability receipts and diagnostics are
 * owned internally, so no caller can pair foreign evidence with a frame. Each accept is O(1)
 * because the landmark schema is fixed at 33; retained memory is O(maxSamples).
 */
internal class GoodMorningKneeStabilityResearchTrace(
    expectedSource: PoseObservationSource,
    private val maximumSamples: Int,
    private val maximumTraceDurationMs: Long,
    maximumFrameGapMs: Long,
) : AutoCloseable {
    private var source: PoseObservationSource? = expectedSource
    private var evidence: PoseObservationResearchCapabilityEvidence? =
        PoseObservationResearchCapabilityEvidence(expectedSource, maximumFrameGapMs)
    private var diagnostic: GoodMorningKneeFlexionResearchDiagnostic? =
        GoodMorningKneeFlexionResearchDiagnostic(expectedSource)
    private var startTimestampMs: Long? = null
    private var geometry: GoodMorningKneeStabilityGeometry? = null
    private val samples: ArrayList<GoodMorningKneeStabilityTraceSample>
    private var closed = false

    private val runtimeDomainId = expectedSource.contract.runtimeDomainId
    private val observationContractSha256 = expectedSource.contract.artifactSha256
    private val modelArtifactSha256 = expectedSource.contract.modelArtifactSha256
    private val inferenceOptionsArtifactSha256 =
        expectedSource.contract.inferenceOptionsArtifactSha256
    private val preprocessingArtifactSha256 = expectedSource.contract.preprocessingArtifactSha256
    private val landmarkSchemaArtifactSha256 =
        expectedSource.contract.landmarkSchemaArtifactSha256
    private val personLockArtifactSha256 = expectedSource.contract.personLockArtifactSha256
    private val viewQualifierArtifactSha256 = expectedSource.contract.viewQualifierArtifactSha256
    private val traceContractSha256: String = canonicalFieldsSha256(
        listOf(
            "goodMorningKneeStabilityResearchTraceSchemaVersion" to "1",
            "bindingId" to GoodMorningKneeFlexionResearchDiagnostic.BINDING_ID,
            "bindingPolicySha256" to
                GoodMorningKneeFlexionResearchDiagnostic.BINDING_POLICY_SHA256,
            "runtimeDomainId" to runtimeDomainId,
            "observationContractSha256" to observationContractSha256,
            "maximumSamples" to maximumSamples.toString(),
            "maximumTraceDurationMs" to maximumTraceDurationMs.toString(),
            "maximumFrameGapMs" to maximumFrameGapMs.toString(),
            "coordinateSpace" to PoseCoordinateSpace.WORLD.name,
            "viewContractId" to
                PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
            "resultState" to GoodMorningKneeFlexionResearchState.UNKNOWN.name,
            "decisionAuthority" to "0",
        ),
    )

    init {
        require(maximumSamples in MINIMUM_SEALED_SAMPLE_COUNT..MAXIMUM_ALLOWED_SAMPLES)
        require(maximumTraceDurationMs in 1..MAXIMUM_ALLOWED_TRACE_DURATION_MS)
        require(maximumFrameGapMs in 1..MAXIMUM_ALLOWED_FRAME_GAP_MS)
        samples = ArrayList(maximumSamples)
    }

    internal val retainedSampleCount: Int
        get() = samples.size

    fun accept(observation: AttestedPoseObservation): GoodMorningKneeStabilityTraceUpdate {
        check(!closed) { "Research trace is closed" }
        val evidenceUpdate = checkNotNull(evidence).accept(observation)
        return when (evidenceUpdate.status) {
            PoseObservationResearchCapabilityEvidenceStatus.STABILIZING -> {
                if (samples.isNotEmpty() || startTimestampMs != null || geometry != null) {
                    reset(GoodMorningKneeStabilityTraceResetReason.INVALID_CAPABILITY_RECEIPT)
                } else {
                    startTimestampMs = observation.frame.timestampMs
                    GoodMorningKneeStabilityTraceUpdate(
                        status = GoodMorningKneeStabilityTraceStatus.STABILIZING,
                        appendedSample = null,
                        resetReason = null,
                        evidenceRejectionReasons = emptySet(),
                        retainedSampleCount = 0,
                    )
                }
            }

            PoseObservationResearchCapabilityEvidenceStatus.REJECTED_RESET -> reset(
                reason = GoodMorningKneeStabilityTraceResetReason.OBSERVATION_EVIDENCE_REJECTED,
                evidenceRejectionReasons = evidenceUpdate.rejectionReasons,
            )

            PoseObservationResearchCapabilityEvidenceStatus.RECEIPT_READY -> append(
                observation = observation,
                receipt = checkNotNull(evidenceUpdate.receipt),
            )
        }
    }

    /** Returns one immutable candidate snapshot and immediately clears all window state. */
    fun seal(): GoodMorningKneeStabilityCandidateSnapshot {
        check(!closed) { "Research trace is closed" }
        if (source?.isOpen != true) {
            clearWindow()
            error("Observation source is closed")
        }
        check(samples.size >= MINIMUM_SEALED_SAMPLE_COUNT) {
            "A trace requires at least two capability-ready samples"
        }
        val snapshot = GoodMorningKneeStabilityCandidateSnapshot(
            schemaVersion = 1,
            bindingId = GoodMorningKneeFlexionResearchDiagnostic.BINDING_ID,
            bindingPolicySha256 =
                GoodMorningKneeFlexionResearchDiagnostic.BINDING_POLICY_SHA256,
            runtimeDomainId = runtimeDomainId,
            observationContractSha256 = observationContractSha256,
            modelArtifactSha256 = modelArtifactSha256,
            inferenceOptionsArtifactSha256 = inferenceOptionsArtifactSha256,
            preprocessingArtifactSha256 = preprocessingArtifactSha256,
            landmarkSchemaArtifactSha256 = landmarkSchemaArtifactSha256,
            personLockArtifactSha256 = personLockArtifactSha256,
            viewQualifierArtifactSha256 = viewQualifierArtifactSha256,
            lateralViewContractId =
                PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
            coordinateSpace = PoseCoordinateSpace.WORLD,
            traceContractSha256 = traceContractSha256,
            geometry = checkNotNull(geometry),
            samples = samples,
            blockers = GoodMorningKneeStabilityTraceBlocker.entries.toSet(),
        )
        clearWindow()
        return snapshot
    }

    override fun close() {
        if (closed) return
        closed = true
        samples.clear()
        startTimestampMs = null
        geometry = null
        evidence?.reset()
        evidence = null
        diagnostic = null
        source = null
    }

    private fun append(
        observation: AttestedPoseObservation,
        receipt: PoseObservationResearchCapabilityReceipt,
    ): GoodMorningKneeStabilityTraceUpdate {
        if (!receipt.hasCanonicalProvenance(observation) ||
            receipt.cameraGeometryContextSha256 !=
            observation.cameraGeometryEpoch?.contextArtifactSha256
        ) {
            return reset(GoodMorningKneeStabilityTraceResetReason.INVALID_CAPABILITY_RECEIPT)
        }
        val startedAt = startTimestampMs
            ?: return reset(GoodMorningKneeStabilityTraceResetReason.INVALID_CAPABILITY_RECEIPT)
        val elapsedMs = observation.frame.timestampMs - startedAt
        if (elapsedMs <= 0L) {
            return reset(GoodMorningKneeStabilityTraceResetReason.INVALID_CAPABILITY_RECEIPT)
        }
        if (elapsedMs > maximumTraceDurationMs) {
            return reset(GoodMorningKneeStabilityTraceResetReason.TRACE_DURATION_EXCEEDED)
        }
        if (samples.size >= maximumSamples) {
            return reset(GoodMorningKneeStabilityTraceResetReason.SAMPLE_CAPACITY_EXCEEDED)
        }

        val output = checkNotNull(diagnostic).accept(observation, receipt)
        if (!isExactDiagnostic(output, observation, receipt)) {
            return reset(GoodMorningKneeStabilityTraceResetReason.INVALID_RESEARCH_DIAGNOSTIC)
        }
        val currentGeometry = GoodMorningKneeStabilityGeometry(
            checkNotNull(observation.cameraGeometryEpoch).context,
        )
        val establishedGeometry = geometry
        if (establishedGeometry == null) {
            geometry = currentGeometry
        } else if (establishedGeometry.contextSha256 != currentGeometry.contextSha256) {
            return reset(GoodMorningKneeStabilityTraceResetReason.INVALID_CAPABILITY_RECEIPT)
        }
        val sample = GoodMorningKneeStabilityTraceSample(
            elapsedMs = elapsedMs,
            capabilityReceiptSha256 = receipt.receiptSha256,
            diagnosticProvenanceSha256 = output.diagnosticProvenanceSha256,
            left = output.sideDiagnostics.getValue(PoseSide.LEFT).toTraceSample(),
            right = output.sideDiagnostics.getValue(PoseSide.RIGHT).toTraceSample(),
        )
        samples += sample
        return GoodMorningKneeStabilityTraceUpdate(
            status = GoodMorningKneeStabilityTraceStatus.SAMPLE_APPENDED,
            appendedSample = sample,
            resetReason = null,
            evidenceRejectionReasons = emptySet(),
            retainedSampleCount = samples.size,
        )
    }

    private fun isExactDiagnostic(
        output: GoodMorningKneeFlexionResearchOutput,
        observation: AttestedPoseObservation,
        receipt: PoseObservationResearchCapabilityReceipt,
    ): Boolean = output.state == GoodMorningKneeFlexionResearchState.UNKNOWN &&
        output.authority.totalAuthority == 0 &&
        output.observationTimestampMs == observation.frame.timestampMs &&
        output.capabilityReceiptSha256 == receipt.receiptSha256 &&
        output.coordinateSpace == PoseCoordinateSpace.WORLD &&
        GoodMorningKneeFlexionResearchBlocker.CAPABILITY_RECEIPT_UNAVAILABLE !in output.blockers &&
        output.sideDiagnostics.keys == PoseSide.entries.toSet()

    private fun GoodMorningKneeFlexionSideDiagnostic.toTraceSample() =
        GoodMorningKneeStabilitySideSample(
            side = side,
            flexionDegrees = flexionDegrees,
            rawConfidence = rawConfidence,
            featureUnknownReason = featureUnknownReason,
        )

    private fun reset(
        reason: GoodMorningKneeStabilityTraceResetReason,
        evidenceRejectionReasons: Set<PoseObservationResearchCapabilityRejectionReason> = emptySet(),
    ): GoodMorningKneeStabilityTraceUpdate {
        clearWindow()
        return GoodMorningKneeStabilityTraceUpdate(
            status = GoodMorningKneeStabilityTraceStatus.RESET,
            appendedSample = null,
            resetReason = reason,
            evidenceRejectionReasons = evidenceRejectionReasons,
            retainedSampleCount = 0,
        )
    }

    private fun clearWindow() {
        samples.clear()
        startTimestampMs = null
        geometry = null
        evidence?.reset()
    }

    private companion object {
        const val MAXIMUM_ALLOWED_SAMPLES = 2_048
        const val MAXIMUM_ALLOWED_TRACE_DURATION_MS = 10L * 60L * 1_000L
        const val MAXIMUM_ALLOWED_FRAME_GAP_MS = 1_000L
        const val MINIMUM_SEALED_SAMPLE_COUNT = 2
    }
}

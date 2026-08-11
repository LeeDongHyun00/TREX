package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import java.util.Collections

private val RESEARCH_ID = Regex("^[a-z0-9][a-z0-9._:/-]*\\.v[1-9][0-9]*$")
private val RESEARCH_SHA256 = Regex("^[0-9a-f]{64}$")

/** This artifact may describe research candidates, but it cannot authorize runtime execution. */
internal enum class BarbellSquatResearchArtifactUse {
    RESEARCH_CANDIDATE_ONLY,
}

/** Deliberately excludes a decoder, [com.example.trex_kotlin.pose.phase.PosePhaseEngine] binding, or session. */
internal enum class BarbellSquatResearchExecutionMode {
    SPECIFICATION_ONLY,
}

internal enum class BarbellSquatResearchPhaseState {
    READY,
    DESCENDING,
    BOTTOM,
    ASCENDING,
}

internal data class BarbellSquatResearchPhaseTransition(
    val from: BarbellSquatResearchPhaseState,
    val to: BarbellSquatResearchPhaseState,
    val completesCycle: Boolean,
) {
    init {
        require(from != to) { "A research phase transition must change state" }
    }
}

internal enum class BarbellSquatResearchWindowSemantics {
    START_INCLUSIVE_END_EXCLUSIVE,
}

internal enum class BarbellSquatResearchView(val contractId: String) {
    FRONT("trex.view.front-full-body.v1"),
    FRONT_OBLIQUE("trex.view.front-oblique-full-body.v1"),
    LATERAL("trex.view.lateral-full-body.v1"),
}

internal enum class BarbellSquatResearchCandidateAgreementPolicy {
    REQUIRE_ALL_SIMULTANEOUSLY_AVAILABLE_CANDIDATES_TO_AGREE,
}

internal enum class BarbellSquatResearchAgreementDimension {
    MOTION_DIRECTION,
    ORDERED_PHASE_PROPOSAL,
}

/** Non-causal normalization is prohibited even for exploratory collection. */
internal enum class BarbellSquatResearchProhibitedTemporalOperation {
    FUTURE_FRAME_LOOKAHEAD,
    FULL_CYCLE_QUANTILE_NORMALIZATION,
    FULL_CYCLE_EXTREMA_NORMALIZATION,
    READY_BASELINE_UPDATE_AFTER_FORWARD_CYCLE_EDGE,
}

/** Any occurrence invalidates state immediately; no partial phase evidence survives it. */
internal enum class BarbellSquatResearchResetCause {
    STARTED_OUTSIDE_READY,
    PERSON_TRACK_EPOCH_DISCONTINUITY,
    OBSERVATION_SOURCE_DISCONTINUITY,
    VIEW_CONTRACT_DISCONTINUITY_OR_UNQUALIFIED,
    CROP_RECT_DISCONTINUITY_OR_UNATTESTED,
    IMAGE_DIMENSION_DISCONTINUITY,
    ROTATION_DISCONTINUITY,
    MIRROR_METADATA_DISCONTINUITY,
    TIMESTAMP_NOT_STRICTLY_INCREASING,
    OBSERVATION_GAP_OUTSIDE_SEPARATELY_VALIDATED_CONTRACT,
    AVAILABLE_CANDIDATE_DISAGREEMENT,
}

/** A completed research record is forbidden when any of these conditions occurred. */
internal enum class BarbellSquatResearchDiscardCause {
    STARTED_OUTSIDE_READY,
    INCOMPLETE_ORDERED_CYCLE,
    PERSON_TRACK_EPOCH_DISCONTINUITY,
    OBSERVATION_SOURCE_DISCONTINUITY,
    VIEW_CONTRACT_DISCONTINUITY_OR_UNQUALIFIED,
    CROP_RECT_DISCONTINUITY_OR_UNATTESTED,
    IMAGE_DIMENSION_DISCONTINUITY,
    ROTATION_DISCONTINUITY,
    MIRROR_METADATA_DISCONTINUITY,
    TIMESTAMP_NOT_STRICTLY_INCREASING,
    OBSERVATION_GAP_OUTSIDE_SEPARATELY_VALIDATED_CONTRACT,
    AVAILABLE_CANDIDATE_DISAGREEMENT,
}

internal enum class BarbellSquatResearchValidationUse {
    PRIOR_VALIDATION_CONSUMED_NOT_READ_OR_REUSED_IN_TRAINING_PHASE_EXPERIMENT,
}

internal enum class BarbellSquatResearchPhaseSupervision {
    ACTIVE_MASK_WINDOW_PRIOR_ONLY_NOT_PHASE_GOLD,
}

internal enum class BarbellSquatResearchFrameTimeEvidence {
    NO_RELIABLE_FRAME_RATE_OR_FRAME_INTERVAL_GROUND_TRUTH,
}

/** Explicit reasons this artifact cannot be promoted or interpreted as a posture decision. */
internal enum class BarbellSquatResearchLimitation {
    OFFICIAL_VALIDATION_CONSUMED_DURING_DEVELOPMENT,
    ACTIVE_MASK_IS_NOT_PHASE_GOLD,
    NO_PHASE_GOLD,
    NO_RELIABLE_FRAME_RATE_OR_FRAME_INTERVAL_GROUND_TRUTH,
    NO_MEDIAPIPE_GOLD_DOMAIN_BRIDGE,
    TRAINING_SURROGATE_CONTINUATION_REJECTED,
    NO_RUNTIME_DECODER_PARAMETERS,
    NO_RUNTIME_PHASE_PROVIDER_BINDING,
    NO_AUTHORIZED_RUNTIME_PHASE_CALIBRATION_ARTIFACT,
    NO_SEPARATELY_AUTHENTICATED_CAMERA_GEOMETRY_ISSUER,
    NO_INDEPENDENT_PHASE_APPROVAL,
    NO_PRODUCT_RELEASE_AUTHORITY,
    NO_USER_DECISION_AUTHORITY,
}

/**
 * One scalar trajectory proposed for later phase-Gold research.
 *
 * It contains a formula identity, not an executable extractor. In particular, there is no target,
 * phase boundary, classification region, or fallback value in this type.
 */
internal class BarbellSquatResearchScalarCandidate(
    val candidateId: String,
    applicableViews: Set<BarbellSquatResearchView>,
    val coordinateDomainId: String,
    val formulaContractId: String,
    val normalizationContractId: String,
    val causalityContractId: String,
    val valueDefinition: String,
) {
    val applicableViews: Set<BarbellSquatResearchView> = immutableSet(
        applicableViews.sortedBy(BarbellSquatResearchView::contractId),
    )

    init {
        validateResearchId("candidateId", candidateId)
        require(this.applicableViews.isNotEmpty()) { "A scalar candidate requires a view" }
        validateResearchId("coordinateDomainId", coordinateDomainId)
        validateResearchId("formulaContractId", formulaContractId)
        validateResearchId("normalizationContractId", normalizationContractId)
        validateResearchId("causalityContractId", causalityContractId)
        require(valueDefinition.isNotBlank()) { "valueDefinition must not be blank" }
    }

    val contentSha256: String = canonicalFieldsSha256(
        buildList {
            add("researchScalarCandidateSchemaVersion" to "1")
            add("candidateId" to candidateId)
            add("coordinateDomainId" to coordinateDomainId)
            add("formulaContractId" to formulaContractId)
            add("normalizationContractId" to normalizationContractId)
            add("causalityContractId" to causalityContractId)
            add("valueDefinition" to valueDefinition)
            add("applicableViewCount" to this@BarbellSquatResearchScalarCandidate.applicableViews.size.toString())
            this@BarbellSquatResearchScalarCandidate.applicableViews.forEachIndexed { index, view ->
                add("applicableView[$index]" to view.contractId)
            }
        },
    )
}

internal data class BarbellSquatResearchEvidenceProvenance(
    val sourceArtifactPath: String,
    val sourceReportFingerprintSha256: String,
    val protocolArtifactSha256: String,
    val readinessArtifactSha256: String,
    val studiedSignalFamilyId: String,
    val studiedDecoderFamilyId: String,
    val studyCoordinateDomain: BarbellSquatPhaseResearchStudyCoordinateDomain,
    val studyViewRole: BarbellSquatPhaseResearchStudyViewRole,
    val officialValidationUse: BarbellSquatResearchValidationUse,
    val phaseSupervision: BarbellSquatResearchPhaseSupervision,
    val frameTimeEvidence: BarbellSquatResearchFrameTimeEvidence,
) {
    init {
        require(sourceArtifactPath == "docs/barbell-squat-phase-training-experiment.json") {
            "Research provenance must name the reviewed Training-only phase experiment"
        }
        require(RESEARCH_SHA256.matches(sourceReportFingerprintSha256)) {
            "sourceReportFingerprintSha256 must be a lowercase SHA-256"
        }
        require(RESEARCH_SHA256.matches(protocolArtifactSha256)) {
            "protocolArtifactSha256 must be a lowercase SHA-256"
        }
        require(RESEARCH_SHA256.matches(readinessArtifactSha256)) {
            "readinessArtifactSha256 must be a lowercase SHA-256"
        }
        validateResearchId("studiedSignalFamilyId", studiedSignalFamilyId)
        validateResearchId("studiedDecoderFamilyId", studiedDecoderFamilyId)
    }
}

/**
 * Immutable barbell-squat phase research artifact.
 *
 * The artifact fixes topology, causal scalar candidates, invalidation rules, and evidence limits.
 * It intentionally contains no numerical decoder parameters and has no method that consumes a
 * pose observation. A separately reviewed phase-Gold artifact would be required before any runtime
 * provider could be created.
 */
internal class BarbellSquatResearchPhaseContract internal constructor(
    val contractId: String,
    val artifactUse: BarbellSquatResearchArtifactUse,
    val executionMode: BarbellSquatResearchExecutionMode,
    cyclePath: List<BarbellSquatResearchPhaseState>,
    transitions: List<BarbellSquatResearchPhaseTransition>,
    val cycleScopeStartPolicyId: String,
    val cycleScopeEndPolicyId: String,
    val windowSemantics: BarbellSquatResearchWindowSemantics,
    scalarCandidates: Collection<BarbellSquatResearchScalarCandidate>,
    val candidateAgreementPolicy: BarbellSquatResearchCandidateAgreementPolicy,
    agreementDimensions: Set<BarbellSquatResearchAgreementDimension>,
    prohibitedTemporalOperations: Set<BarbellSquatResearchProhibitedTemporalOperation>,
    resetCauses: Set<BarbellSquatResearchResetCause>,
    discardCauses: Set<BarbellSquatResearchDiscardCause>,
    val evidenceProvenance: BarbellSquatResearchEvidenceProvenance,
    limitations: Set<BarbellSquatResearchLimitation>,
) {
    val cyclePath: List<BarbellSquatResearchPhaseState> = immutableList(cyclePath)
    val transitions: List<BarbellSquatResearchPhaseTransition> = immutableList(transitions)
    val scalarCandidates: List<BarbellSquatResearchScalarCandidate> = immutableList(
        scalarCandidates.sortedBy(BarbellSquatResearchScalarCandidate::candidateId),
    )
    val agreementDimensions: Set<BarbellSquatResearchAgreementDimension> = immutableSet(
        agreementDimensions.sortedBy { it.name },
    )
    val prohibitedTemporalOperations: Set<BarbellSquatResearchProhibitedTemporalOperation> =
        immutableSet(prohibitedTemporalOperations.sortedBy { it.name })
    val resetCauses: Set<BarbellSquatResearchResetCause> = immutableSet(
        resetCauses.sortedBy { it.name },
    )
    val discardCauses: Set<BarbellSquatResearchDiscardCause> = immutableSet(
        discardCauses.sortedBy { it.name },
    )
    val limitations: Set<BarbellSquatResearchLimitation> = immutableSet(
        limitations.sortedBy { it.name },
    )

    val artifactSha256: String = canonicalFieldsSha256(
        buildList {
            add("barbellSquatResearchPhaseContractSchemaVersion" to "2")
            add("contractId" to contractId)
            add("artifactUse" to artifactUse.name)
            add("executionMode" to executionMode.name)
            add("cycleScopeStartPolicyId" to cycleScopeStartPolicyId)
            add("cycleScopeEndPolicyId" to cycleScopeEndPolicyId)
            add("windowSemantics" to windowSemantics.name)
            add("cyclePathCount" to this@BarbellSquatResearchPhaseContract.cyclePath.size.toString())
            this@BarbellSquatResearchPhaseContract.cyclePath.forEachIndexed { index, state ->
                add("cyclePath[$index]" to state.name)
            }
            add("transitionCount" to this@BarbellSquatResearchPhaseContract.transitions.size.toString())
            this@BarbellSquatResearchPhaseContract.transitions.forEachIndexed { index, edge ->
                add("transition[$index].from" to edge.from.name)
                add("transition[$index].to" to edge.to.name)
                add("transition[$index].completesCycle" to edge.completesCycle.toString())
            }
            add("scalarCandidateCount" to this@BarbellSquatResearchPhaseContract.scalarCandidates.size.toString())
            this@BarbellSquatResearchPhaseContract.scalarCandidates.forEachIndexed { index, candidate ->
                add("scalarCandidate[$index].candidateId" to candidate.candidateId)
                add("scalarCandidate[$index].contentSha256" to candidate.contentSha256)
            }
            add("candidateAgreementPolicy" to candidateAgreementPolicy.name)
            appendSortedEnums("agreementDimension", this@BarbellSquatResearchPhaseContract.agreementDimensions)
            appendSortedEnums(
                "prohibitedTemporalOperation",
                this@BarbellSquatResearchPhaseContract.prohibitedTemporalOperations,
            )
            appendSortedEnums("resetCause", this@BarbellSquatResearchPhaseContract.resetCauses)
            appendSortedEnums("discardCause", this@BarbellSquatResearchPhaseContract.discardCauses)
            add("evidence.sourceArtifactPath" to evidenceProvenance.sourceArtifactPath)
            add(
                "evidence.sourceReportFingerprintSha256" to
                    evidenceProvenance.sourceReportFingerprintSha256,
            )
            add("evidence.protocolArtifactSha256" to evidenceProvenance.protocolArtifactSha256)
            add("evidence.readinessArtifactSha256" to evidenceProvenance.readinessArtifactSha256)
            add("evidence.studiedSignalFamilyId" to evidenceProvenance.studiedSignalFamilyId)
            add("evidence.studiedDecoderFamilyId" to evidenceProvenance.studiedDecoderFamilyId)
            add("evidence.studyCoordinateDomain" to evidenceProvenance.studyCoordinateDomain.name)
            add("evidence.studyViewRole" to evidenceProvenance.studyViewRole.name)
            add("evidence.officialValidationUse" to evidenceProvenance.officialValidationUse.name)
            add("evidence.phaseSupervision" to evidenceProvenance.phaseSupervision.name)
            add("evidence.frameTimeEvidence" to evidenceProvenance.frameTimeEvidence.name)
            appendSortedEnums("limitation", this@BarbellSquatResearchPhaseContract.limitations)
        },
    )

    init {
        validateResearchId("contractId", contractId)
        require(artifactUse == BarbellSquatResearchArtifactUse.RESEARCH_CANDIDATE_ONLY)
        require(executionMode == BarbellSquatResearchExecutionMode.SPECIFICATION_ONLY)
        require(this.cyclePath == REQUIRED_CYCLE_PATH) {
            "Research topology must remain READY -> DESCENDING -> BOTTOM -> ASCENDING -> READY"
        }
        require(this.transitions == REQUIRED_TRANSITIONS) {
            "Research transitions must exactly follow the ordered cycle path"
        }
        require(this.transitions.count(BarbellSquatResearchPhaseTransition::completesCycle) == 1)
        require(this.transitions.last().completesCycle) {
            "Only ASCENDING -> READY may complete a cycle"
        }
        validateResearchId("cycleScopeStartPolicyId", cycleScopeStartPolicyId)
        validateResearchId("cycleScopeEndPolicyId", cycleScopeEndPolicyId)
        require(cycleScopeStartPolicyId == REQUIRED_SCOPE_START_POLICY_ID)
        require(cycleScopeEndPolicyId == REQUIRED_SCOPE_END_POLICY_ID)
        require(
            windowSemantics == BarbellSquatResearchWindowSemantics.START_INCLUSIVE_END_EXCLUSIVE,
        )
        require(this.scalarCandidates.map { it.candidateId }.toSet().size == this.scalarCandidates.size) {
            "Research scalar candidate ids must be unique"
        }
        require(this.scalarCandidates.size == 2)
        requireFrontCandidate(this.scalarCandidates.single { it.candidateId == FRONT_CANDIDATE_ID })
        requireLateralCandidate(this.scalarCandidates.single { it.candidateId == LATERAL_CANDIDATE_ID })
        require(
            candidateAgreementPolicy ==
                BarbellSquatResearchCandidateAgreementPolicy
                    .REQUIRE_ALL_SIMULTANEOUSLY_AVAILABLE_CANDIDATES_TO_AGREE,
        )
        require(this.agreementDimensions == REQUIRED_AGREEMENT_DIMENSIONS)
        require(this.prohibitedTemporalOperations == REQUIRED_PROHIBITED_TEMPORAL_OPERATIONS)
        require(this.resetCauses == REQUIRED_RESET_CAUSES)
        require(this.discardCauses == REQUIRED_DISCARD_CAUSES)
        require(evidenceProvenance.studiedSignalFamilyId == LATERAL_CANDIDATE_ID) {
            "The Training-only phase experiment evaluated only the lateral signal family"
        }
        require(
            evidenceProvenance.studiedDecoderFamilyId ==
                BarbellSquatPhaseResearchReadiness.STUDIED_DECODER_FAMILY_ID,
        )
        require(
            evidenceProvenance.studyCoordinateDomain ==
                BarbellSquatPhaseResearchStudyCoordinateDomain
                    .AIHUB_TRIANGULATED_3D_NOT_MEDIAPIPE_WORLD,
        )
        require(
            evidenceProvenance.studyViewRole ==
                BarbellSquatPhaseResearchStudyViewRole
                    .LATERAL_CANDIDATE_ONLY_NOT_AI_HUB_CAMERA_VIEW_QUALIFICATION,
        )
        require(
            evidenceProvenance.officialValidationUse ==
                BarbellSquatResearchValidationUse
                    .PRIOR_VALIDATION_CONSUMED_NOT_READ_OR_REUSED_IN_TRAINING_PHASE_EXPERIMENT,
        )
        require(
            evidenceProvenance.phaseSupervision ==
                BarbellSquatResearchPhaseSupervision.ACTIVE_MASK_WINDOW_PRIOR_ONLY_NOT_PHASE_GOLD,
        )
        require(
            evidenceProvenance.frameTimeEvidence ==
                BarbellSquatResearchFrameTimeEvidence
                    .NO_RELIABLE_FRAME_RATE_OR_FRAME_INTERVAL_GROUND_TRUTH,
        )
        require(this.limitations == REQUIRED_LIMITATIONS)
    }

    internal companion object {
        const val FRONT_CANDIDATE_ID =
            "trex.research-phase-signal.barbell-squat.pelvis-ankle-screen-separation.v1"
        const val LATERAL_CANDIDATE_ID =
            "trex.research-phase-signal.barbell-squat.bilateral-knee-flexion-median.v1"

        private const val REQUIRED_SCOPE_START_POLICY_ID =
            "trex.research-cycle-scope.ready-to-descending-boundary.v1"
        private const val REQUIRED_SCOPE_END_POLICY_ID =
            "trex.research-cycle-scope.ascending-to-ready-boundary.v1"

        private val REQUIRED_CYCLE_PATH = listOf(
            BarbellSquatResearchPhaseState.READY,
            BarbellSquatResearchPhaseState.DESCENDING,
            BarbellSquatResearchPhaseState.BOTTOM,
            BarbellSquatResearchPhaseState.ASCENDING,
            BarbellSquatResearchPhaseState.READY,
        )
        private val REQUIRED_TRANSITIONS = REQUIRED_CYCLE_PATH.zipWithNext().mapIndexed {
                index,
                (from, to),
            ->
            BarbellSquatResearchPhaseTransition(
                from = from,
                to = to,
                completesCycle = index == REQUIRED_CYCLE_PATH.size - 2,
            )
        }
        private val REQUIRED_AGREEMENT_DIMENSIONS = setOf(
            BarbellSquatResearchAgreementDimension.MOTION_DIRECTION,
            BarbellSquatResearchAgreementDimension.ORDERED_PHASE_PROPOSAL,
        )
        private val REQUIRED_PROHIBITED_TEMPORAL_OPERATIONS =
            BarbellSquatResearchProhibitedTemporalOperation.entries.toSet()
        private val REQUIRED_RESET_CAUSES = BarbellSquatResearchResetCause.entries.toSet()
        private val REQUIRED_DISCARD_CAUSES = BarbellSquatResearchDiscardCause.entries.toSet()
        private val REQUIRED_LIMITATIONS = BarbellSquatResearchLimitation.entries.toSet()

        private const val REPOSITORY_DRIFT_PIN_SHA256 =
            "7ca4751630f7625ffb8c7858ed74f3a921aa6ce91666f9a9ed60e827a9374352"

        val CURRENT: BarbellSquatResearchPhaseContract = createCurrent().also { artifact ->
            check(artifact.artifactSha256 == REPOSITORY_DRIFT_PIN_SHA256) {
                "Barbell-squat research phase artifact drifted: " +
                    "expected=$REPOSITORY_DRIFT_PIN_SHA256, actual=${artifact.artifactSha256}"
            }
        }

        internal fun createCurrent(
            evidenceProvenance: BarbellSquatResearchEvidenceProvenance = currentEvidence(),
        ): BarbellSquatResearchPhaseContract = BarbellSquatResearchPhaseContract(
            contractId = "trex.research-phase-contract.barbell-squat.v2",
            artifactUse = BarbellSquatResearchArtifactUse.RESEARCH_CANDIDATE_ONLY,
            executionMode = BarbellSquatResearchExecutionMode.SPECIFICATION_ONLY,
            cyclePath = REQUIRED_CYCLE_PATH,
            transitions = REQUIRED_TRANSITIONS,
            cycleScopeStartPolicyId = REQUIRED_SCOPE_START_POLICY_ID,
            cycleScopeEndPolicyId = REQUIRED_SCOPE_END_POLICY_ID,
            windowSemantics =
                BarbellSquatResearchWindowSemantics.START_INCLUSIVE_END_EXCLUSIVE,
            scalarCandidates = listOf(frontCandidate(), lateralCandidate()),
            candidateAgreementPolicy =
                BarbellSquatResearchCandidateAgreementPolicy
                    .REQUIRE_ALL_SIMULTANEOUSLY_AVAILABLE_CANDIDATES_TO_AGREE,
            agreementDimensions = REQUIRED_AGREEMENT_DIMENSIONS,
            prohibitedTemporalOperations = REQUIRED_PROHIBITED_TEMPORAL_OPERATIONS,
            resetCauses = REQUIRED_RESET_CAUSES,
            discardCauses = REQUIRED_DISCARD_CAUSES,
            evidenceProvenance = evidenceProvenance,
            limitations = REQUIRED_LIMITATIONS,
        )

        private fun frontCandidate() = BarbellSquatResearchScalarCandidate(
            candidateId = FRONT_CANDIDATE_ID,
            applicableViews = setOf(
                BarbellSquatResearchView.FRONT,
                BarbellSquatResearchView.FRONT_OBLIQUE,
            ),
            coordinateDomainId = "trex.coordinate.normalized-upright-image-y.v1",
            formulaContractId =
                "trex.research-formula.pelvis-to-bilateral-ankle-screen-vertical-separation.v1",
            normalizationContractId =
                "trex.research-normalization.causal-ready-prefix-running-mean-frozen-at-first-edge.v1",
            causalityContractId =
                "trex.research-causality.past-and-current-ready-only-no-lookahead.v1",
            valueDefinition =
                "abs(mean(leftHipY,rightHipY)-mean(leftAnkleY,rightAnkleY)) / " +
                    "causalReadyPrefixRunningMeanFrozenAtReadyToDescending",
        )

        private fun lateralCandidate() = BarbellSquatResearchScalarCandidate(
            candidateId = LATERAL_CANDIDATE_ID,
            applicableViews = setOf(BarbellSquatResearchView.LATERAL),
            coordinateDomainId = "trex.coordinate.mediapipe-world-relative.v1",
            formulaContractId =
                "trex.research-formula.median-left-right-knee-flexion-angle.v1",
            normalizationContractId = "trex.research-normalization.none.v1",
            causalityContractId = "trex.research-causality.frame-local.v1",
            valueDefinition =
                "medianEvenAsArithmeticMean(" +
                    "supplementaryAngle(includedAngle(leftHip,leftKnee,leftAnkle))," +
                    "supplementaryAngle(includedAngle(rightHip,rightKnee,rightAnkle)))",
        )

        private fun currentEvidence() = BarbellSquatResearchEvidenceProvenance(
            sourceArtifactPath = "docs/barbell-squat-phase-training-experiment.json",
            sourceReportFingerprintSha256 =
                BarbellSquatPhaseResearchReadiness.CURRENT.reportArtifactSha256,
            protocolArtifactSha256 =
                BarbellSquatPhaseResearchReadiness.CURRENT.protocolArtifactSha256,
            readinessArtifactSha256 =
                BarbellSquatPhaseResearchReadiness.CURRENT.artifactSha256,
            studiedSignalFamilyId =
                BarbellSquatPhaseResearchReadiness.CURRENT.studiedSignalFamilyId,
            studiedDecoderFamilyId =
                BarbellSquatPhaseResearchReadiness.CURRENT.studiedDecoderFamilyId,
            studyCoordinateDomain =
                BarbellSquatPhaseResearchReadiness.CURRENT.studyCoordinateDomain,
            studyViewRole =
                BarbellSquatPhaseResearchReadiness.CURRENT.studyViewRole,
            officialValidationUse =
                BarbellSquatResearchValidationUse
                    .PRIOR_VALIDATION_CONSUMED_NOT_READ_OR_REUSED_IN_TRAINING_PHASE_EXPERIMENT,
            phaseSupervision =
                BarbellSquatResearchPhaseSupervision.ACTIVE_MASK_WINDOW_PRIOR_ONLY_NOT_PHASE_GOLD,
            frameTimeEvidence =
                BarbellSquatResearchFrameTimeEvidence
                    .NO_RELIABLE_FRAME_RATE_OR_FRAME_INTERVAL_GROUND_TRUTH,
        )

        private fun requireFrontCandidate(candidate: BarbellSquatResearchScalarCandidate) {
            require(
                candidate.applicableViews == setOf(
                    BarbellSquatResearchView.FRONT,
                    BarbellSquatResearchView.FRONT_OBLIQUE,
                ),
            )
            require(
                candidate.formulaContractId ==
                    "trex.research-formula.pelvis-to-bilateral-ankle-screen-vertical-separation.v1",
            )
            require(
                candidate.normalizationContractId ==
                    "trex.research-normalization.causal-ready-prefix-running-mean-frozen-at-first-edge.v1",
            )
            require(
                candidate.causalityContractId ==
                    "trex.research-causality.past-and-current-ready-only-no-lookahead.v1",
            )
        }

        private fun requireLateralCandidate(candidate: BarbellSquatResearchScalarCandidate) {
            require(candidate.applicableViews == setOf(BarbellSquatResearchView.LATERAL))
            require(
                candidate.formulaContractId ==
                    "trex.research-formula.median-left-right-knee-flexion-angle.v1",
            )
            require(
                candidate.normalizationContractId == "trex.research-normalization.none.v1",
            )
            require(candidate.causalityContractId == "trex.research-causality.frame-local.v1")
        }
    }
}

private fun validateResearchId(fieldName: String, value: String) {
    require(RESEARCH_ID.matches(value)) {
        "$fieldName must be a lowercase versioned identifier"
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <T : Enum<T>> MutableList<Pair<String, String>>.appendSortedEnums(
    fieldName: String,
    values: Collection<T>,
) {
    val names = values.map { it.name }.sorted()
    add("${fieldName}Count" to names.size.toString())
    names.forEachIndexed { index, name -> add("$fieldName[$index]" to name) }
}

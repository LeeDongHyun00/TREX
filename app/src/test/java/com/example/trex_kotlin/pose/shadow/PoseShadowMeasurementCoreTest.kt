package com.example.trex_kotlin.pose.shadow

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseShadowMeasurementCoreTest {
    @Test
    fun requestHashCommitsEveryExecutionIdentityAndRejectsLooseIdentifiers() {
        val request = request()
        val featureDrift = request(featureSpecSha256 = sha('e'))
        val providerDrift = request(providerSha256 = sha('f'))

        assertNotEquals(request.contentSha256, featureDrift.contentSha256)
        assertNotEquals(request.contentSha256, providerDrift.contentSha256)
        assertEquals(64, request.contentSha256.length)

        assertThrows(IllegalArgumentException::class.java) {
            request(sourceConditionId = "short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(bindingPolicySha256 = "A".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShadowFeatureRuntimeIdentity("unversioned", sha('1'), sha('2'))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShadowSideRuntimePolicy(
                kind = ShadowSidePolicyKind.BILATERAL_INDEPENDENT,
                sideChannels = setOf(ShadowScalarSide.LEFT),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(maximumSamplesPerCycle = 2_049)
        }
    }

    @Test
    fun exactGeneratedPolicyDimensionsRejectEveryWellFormedDrift() {
        val wrongBindingId = "aihub-binding-sha256-${sha('f')}"
        val extraCapabilityIds = REQUIRED_CAPABILITY_IDS + "trex.capability.extra.v1"
        val missingCapabilityIds = REQUIRED_CAPABILITY_IDS.dropLast(1)
        val midlinePolicy = ShadowSideRuntimePolicy(
            kind = ShadowSidePolicyKind.MIDLINE,
            sideChannels = setOf(ShadowScalarSide.MIDLINE),
        )

        listOf<() -> Unit>(
            { request(bindingId = wrongBindingId) },
            { request(bindingPolicySha256 = sha('d')) },
            { request(measurementConstructId = "trex.measurement.other.v1") },
            { request(phaseRoleId = "trex.phase-role.concentric.v1") },
            { request(selectedViewContractId = "trex.view.lateral-full-body.v1") },
            { request(requiredCapabilityIds = missingCapabilityIds) },
            { request(requiredCapabilityIds = extraCapabilityIds) },
            { request(sideRuntimePolicy = midlinePolicy) },
        ).forEachIndexed { index, mutation ->
            assertThrows("policy mutation $index", IllegalArgumentException::class.java) {
                mutation()
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            request(
                exercise = AiHubExercise.UPRIGHT_ROW,
                sourceConditionId =
                    "aihub-exact-sha256-1e470212e158b6887bd0ad647c8bd8fa5759364dfc967c00eeeed233f862732f",
                bindingId =
                    "aihub-binding-sha256-97764e2aa7aa402fd4e96b5a43dccea72714746e270f6b664ca39a179c3ce5cc",
                bindingPolicySha256 =
                    "9b37db83d63d1db27f67469c49039af99413c510e0b525ebc2aa31c080b6e1f9",
                measurementConstructId = "trex.measurement.elbow-wrist-lead.v1",
                phaseRoleId = "trex.phase-role.concentric.v1",
                selectedViewContractId = "trex.view.front-full-body.v1",
                requiredCapabilityIds = listOf(
                    "trex.capability.pose-2d.v1",
                    "trex.capability.pose-world-relative.v1",
                    "trex.capability.primary-person-lock.v1",
                    "trex.capability.temporal-pose.v1",
                    "trex.capability.view-qualified.v1",
                ),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            ShadowSideRuntimePolicy(
                kind = ShadowSidePolicyKind.BILATERAL_INDEPENDENT,
                sideChannels = setOf(ShadowScalarSide.LEFT, ShadowScalarSide.RIGHT),
                roleResolverContractId = "trex.side-resolver.illegal.v1",
                roleResolverArtifactSha256 = sha('e'),
            )
        }
    }

    @Test
    fun bundledAuthorityIsStructurallyEmptyAndCannotOpenForgedOrDriftedGrant() {
        val request = request()

        assertEquals(0, VerifiedShadowExecutionAuthorization.bundledEntryCount)
        assertEquals(
            "7339aa3aa9f47841089298d38d190839786f4097d9d32a1e74b9508e791e1dfe",
            VerifiedShadowExecutionAuthorization.bundledAllowlistSha256,
        )
        assertNull(VerifiedShadowExecutionAuthorization.resolve(request))
        assertNull(PoseShadowMeasurementKernel.open(request, null))
        assertTrue(
            VerifiedShadowExecutionAuthorization::class.java.declaredConstructors.all { ctor ->
                Modifier.isPrivate(ctor.modifiers)
            },
        )

        val constructor = VerifiedShadowExecutionAuthorization::class.java.declaredConstructors
            .single { ctor -> ctor.parameterCount == 0 }
        constructor.isAccessible = true
        val forged = constructor.newInstance() as VerifiedShadowExecutionAuthorization

        assertNull(PoseShadowMeasurementKernel.open(request, forged))
        assertNull(PoseShadowMeasurementKernel.open(request(featureSpecSha256 = sha('8')), forged))
    }

    @Test
    fun halfOpenAdjacentCyclesAssignBoundaryInputOnlyToTheNextCycle() {
        val fixture = Fixture()
        val kernel = kernel(fixture.request)
        kernel.beginCycle(0L)
        kernel.accept(fixture.input(0L, 10.0, 20.0))
        kernel.accept(fixture.input(50L, 20.0, 30.0))
        kernel.accept(fixture.input(100L, 100.0, 200.0))

        val first = assertNotNullAggregate(kernel.completeCycle(fixture.scope(0L, 100L)))
        assertEquals(2, first.inputCount)
        assertEquals(15.0, first.channel(ShadowScalarSide.LEFT).mean!!, 1e-9)
        assertEquals(25.0, first.channel(ShadowScalarSide.RIGHT).mean!!, 1e-9)

        kernel.beginCycle(100L)
        kernel.accept(fixture.input(150L, 200.0, 300.0))
        val second = assertNotNullAggregate(kernel.completeCycle(fixture.scope(100L, 200L)))
        assertEquals(2, second.inputCount)
        assertEquals(150.0, second.channel(ShadowScalarSide.LEFT).mean!!, 1e-9)
        assertNotEquals(first.provenanceSha256, second.provenanceSha256)
    }

    @Test
    fun duplicateTimestampUsesFirstInputWhileTimeReversalDropsWholeCycleAndRecovers() {
        val fixture = Fixture()
        val kernel = kernel(fixture.request)
        kernel.beginCycle(0L)
        kernel.accept(fixture.input(0L, 10.0, 20.0))
        kernel.accept(fixture.input(0L, 999.0, 999.0))
        kernel.accept(fixture.input(20L, 20.0, 30.0))
        val duplicate = assertNotNullAggregate(kernel.completeCycle(fixture.scope(0L, 40L)))
        assertEquals(2, duplicate.inputCount)
        assertEquals(15.0, duplicate.channel(ShadowScalarSide.LEFT).mean!!, 1e-9)

        kernel.beginCycle(100L)
        kernel.accept(fixture.input(120L, 1.0, 2.0))
        kernel.accept(fixture.input(110L, 3.0, 4.0))
        assertNull(kernel.completeCycle(fixture.scope(100L, 150L)))

        kernel.beginCycle(200L)
        kernel.accept(fixture.input(200L, 5.0, 6.0))
        assertNotNull(kernel.completeCycle(fixture.scope(200L, 220L)))
    }

    @Test
    fun sourcePersonViewAndProviderDiscontinuitiesEachDropTheWholeCycle() {
        val mutations: List<(Fixture, Long) -> ShadowSampledScalarInput> = listOf(
            { fixture, timestamp ->
                val otherSource = sourceToken(
                    fixture.request.observationRuntimeIdentity.runtimeDomainId,
                    fixture.request.observationRuntimeIdentity.observationContractArtifactSha256,
                )
                val otherPerson = personToken(otherSource)
                fixture.input(timestamp, 3.0, 4.0, otherSource, otherPerson)
            },
            { fixture, timestamp ->
                fixture.input(
                    timestamp,
                    3.0,
                    4.0,
                    fixture.source,
                    personToken(fixture.source),
                )
            },
            { fixture, timestamp ->
                fixture.input(timestamp, 3.0, 4.0, viewContractId = "trex.view.other.v1")
            },
            { fixture, timestamp ->
                fixture.input(
                    timestamp,
                    3.0,
                    4.0,
                    providers = ShadowCapabilityProviderArtifacts(
                        mapOf(CAPABILITY_ID to sha('f')),
                    ),
                )
            },
        )

        mutations.forEachIndexed { index, mutation ->
            val fixture = Fixture()
            val kernel = kernel(fixture.request)
            kernel.beginCycle(0L)
            kernel.accept(fixture.input(0L, 1.0, 2.0))
            kernel.accept(mutation(fixture, 20L))
            assertNull("mutation $index", kernel.completeCycle(fixture.scope(0L, 40L)))
        }
    }

    @Test
    fun gapTimeoutOverflowAndExplicitAbandonAllRecoverAtTheNextCleanCycle() {
        val fixture = Fixture(maximumSamplesPerCycle = 3)
        val kernel = kernel(fixture.request)

        kernel.beginCycle(0L)
        kernel.accept(fixture.input(0L, 1.0, 1.0))
        kernel.accept(fixture.input(101L, 2.0, 2.0))
        assertNull(kernel.completeCycle(fixture.scope(0L, 120L)))

        kernel.beginCycle(200L)
        kernel.accept(fixture.input(200L, 1.0, 1.0))
        assertTrue(kernel.expireAt(1_201L))
        assertNull(kernel.completeCycle(fixture.scope(200L, 1_202L)))

        kernel.beginCycle(2_000L)
        repeat(4) { index ->
            kernel.accept(fixture.input(2_000L + index * 10L, index.toDouble(), 1.0))
        }
        assertNull(kernel.completeCycle(fixture.scope(2_000L, 2_050L)))

        kernel.beginCycle(3_000L)
        kernel.accept(fixture.input(3_000L, 9.0, 10.0))
        assertNull(kernel.completeCycle(fixture.scope(3_000L, 3_020L)))

        kernel.beginCycle(4_000L)
        kernel.accept(fixture.input(4_000L, 11.0, 12.0))
        assertNotNull(kernel.completeCycle(fixture.scope(4_000L, 4_020L)))

        kernel.beginCycle(5_000L)
        kernel.accept(fixture.input(5_000L, 13.0, 14.0))
        kernel.abandonCycle()
        kernel.beginCycle(6_000L)
        kernel.accept(fixture.input(6_000L, 15.0, 16.0))
        assertNotNull(kernel.completeCycle(fixture.scope(6_000L, 6_020L)))
    }

    @Test
    fun nonIncreasingBoundaryAndEarlierExpiryDiscardWithoutThrowingAndRecover() {
        val fixture = Fixture()
        val kernel = kernel(fixture.request)

        kernel.beginCycle(100L)
        kernel.accept(fixture.input(100L, 1.0, 2.0))
        assertNull(kernel.completeCycle(fixture.scope(100L, 100L)))

        kernel.beginCycle(200L)
        kernel.accept(fixture.input(200L, 3.0, 4.0))
        assertTrue(kernel.expireAt(199L))
        assertNull(kernel.completeCycle(fixture.scope(200L, 220L)))

        kernel.beginCycle(300L)
        kernel.accept(fixture.input(300L, 5.0, 6.0))
        assertNotNull(kernel.completeCycle(fixture.scope(300L, 320L)))
    }

    @Test
    fun phaseScopeIdentityAndBothBoundaryGapsAreFailClosed() {
        val fixture = Fixture()

        val wrongPhaseKernel = kernel(fixture.request)
        wrongPhaseKernel.beginCycle(0L)
        wrongPhaseKernel.accept(fixture.input(0L, 1.0, 2.0))
        assertNull(
            wrongPhaseKernel.completeCycle(
                fixture.scope(0L, 20L, phaseArtifactSha256 = sha('f')),
            ),
        )

        val wrongPersonKernel = kernel(fixture.request)
        wrongPersonKernel.beginCycle(100L)
        wrongPersonKernel.accept(fixture.input(100L, 1.0, 2.0))
        assertNull(
            wrongPersonKernel.completeCycle(
                fixture.scope(100L, 120L, person = personToken(fixture.source)),
            ),
        )

        val lateFirstSampleKernel = kernel(fixture.request)
        lateFirstSampleKernel.beginCycle(200L)
        lateFirstSampleKernel.accept(fixture.input(301L, 1.0, 2.0))
        assertNull(lateFirstSampleKernel.completeCycle(fixture.scope(200L, 320L)))

        val earlyLastSampleKernel = kernel(fixture.request)
        earlyLastSampleKernel.beginCycle(400L)
        earlyLastSampleKernel.accept(fixture.input(400L, 1.0, 2.0))
        assertNull(earlyLastSampleKernel.completeCycle(fixture.scope(400L, 501L)))
    }

    @Test
    fun aggregateContainsOnlyImmutableCountsCoverageAndSummaryScalars() {
        val fixture = Fixture()
        val kernel = kernel(fixture.request)
        kernel.beginCycle(0L)
        kernel.accept(fixture.input(0L, 2.0, 8.0))
        kernel.accept(
            fixture.input(
                timestampMs = 20L,
                left = ShadowScalarChannelInput.abstained(
                    ShadowScalarAbstention.QUALITY_UNAVAILABLE,
                ),
                right = ShadowScalarChannelInput.measured(10.0),
            ),
        )
        val aggregate = assertNotNullAggregate(kernel.completeCycle(fixture.scope(0L, 40L)))
        val left = aggregate.channel(ShadowScalarSide.LEFT)

        assertEquals(2, left.inputCount)
        assertEquals(1, left.measuredCount)
        assertEquals(1, left.abstentionCount)
        assertEquals(0.5, left.coverage, 0.0)
        assertEquals(2.0, left.minimum!!, 0.0)
        assertEquals(2.0, left.maximum!!, 0.0)
        assertEquals(
            mapOf(ShadowScalarAbstention.QUALITY_UNAVAILABLE to 1),
            left.abstentionCounts,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (aggregate.channelAggregates as MutableMap<ShadowScalarSide, ShadowScalarAggregate>)
                .clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (left.abstentionCounts as MutableMap<ShadowScalarAbstention, Int>).clear()
        }

        val overflowSafeKernel = kernel(fixture.request)
        overflowSafeKernel.beginCycle(100L)
        overflowSafeKernel.accept(fixture.input(100L, Double.MAX_VALUE, 1.0))
        overflowSafeKernel.accept(fixture.input(120L, -Double.MAX_VALUE, 2.0))
        assertNull(overflowSafeKernel.completeCycle(fixture.scope(100L, 140L)))
        overflowSafeKernel.beginCycle(200L)
        overflowSafeKernel.accept(fixture.input(200L, 3.0, 4.0))
        assertNotNull(overflowSafeKernel.completeCycle(fixture.scope(200L, 220L)))
    }

    @Test
    fun publicTypeSurfaceHasNoDecisionOrIoDependencies() {
        val coreTypes = listOf(
            ShadowMeasurementExecutionRequest::class.java,
            VerifiedShadowExecutionAuthorization::class.java,
            ShadowSourceContinuityToken::class.java,
            ShadowPersonContinuityToken::class.java,
            ShadowQualifiedViewToken::class.java,
            VerifiedShadowCompletedCycleScope::class.java,
            PoseShadowMeasurementKernel::class.java,
            ShadowSampledScalarInput::class.java,
            ShadowScalarAggregate::class.java,
            ShadowCompletedCycleAggregate::class.java,
        )
        val forbiddenNameFragments = setOf(
            "verdict",
            "score",
            "cue",
            "feedback",
            "evaluate",
            "criterion",
        )
        val publicNames = coreTypes.flatMap { type ->
            type.declaredMethods.filter { method -> Modifier.isPublic(method.modifiers) }
                .map { method -> method.name.lowercase() }
        }
        assertTrue(publicNames.none { name ->
            forbiddenNameFragments.any { fragment -> fragment in name }
        })

        val referencedTypeNames = coreTypes.flatMap { type ->
            buildList {
                type.declaredFields.forEach { field -> add(field.type.name) }
                type.declaredMethods.forEach { method ->
                    add(method.returnType.name)
                    method.parameterTypes.forEach { parameter -> add(parameter.name) }
                }
            }
        }
        assertTrue(referencedTypeNames.none { name ->
            name.startsWith("java.io.") ||
                name.startsWith("java.net.") ||
                name.startsWith("android.") ||
                "serialization" in name.lowercase()
        })
        assertFalse(coreTypes.any { type -> type.name.contains("runtime.PoseExercise") })
        listOf(
            ShadowSourceContinuityToken::class.java,
            ShadowPersonContinuityToken::class.java,
            ShadowQualifiedViewToken::class.java,
            VerifiedShadowCompletedCycleScope::class.java,
        ).forEach { type ->
            assertTrue(type.declaredConstructors.all { constructor ->
                Modifier.isPrivate(constructor.modifiers)
            })
        }
    }

    private class Fixture(
        maximumSamplesPerCycle: Int = 4,
    ) {
        val request = request(maximumSamplesPerCycle = maximumSamplesPerCycle)
        val source = sourceToken(
            request.observationRuntimeIdentity.runtimeDomainId,
            request.observationRuntimeIdentity.observationContractArtifactSha256,
        )
        val person = personToken(source)

        fun scope(
            startTimestampMs: Long,
            endTimestampMs: Long,
            phaseArtifactSha256: String = request.phaseRuntimeIdentity.phaseArtifactSha256,
            source: ShadowSourceContinuityToken = this.source,
            person: ShadowPersonContinuityToken = this.person,
        ): VerifiedShadowCompletedCycleScope = scopeToken(
            phaseArtifactSha256 = phaseArtifactSha256,
            startTimestampMs = startTimestampMs,
            endTimestampMs = endTimestampMs,
            source = source,
            person = person,
        )

        fun input(
            timestampMs: Long,
            left: Double,
            right: Double,
            source: ShadowSourceContinuityToken = this.source,
            person: ShadowPersonContinuityToken = this.person,
            viewContractId: String = request.viewRuntimeIdentity.selectedViewContractId,
            providers: ShadowCapabilityProviderArtifacts = request.capabilityProviderArtifacts,
        ): ShadowSampledScalarInput = input(
            timestampMs = timestampMs,
            left = ShadowScalarChannelInput.measured(left),
            right = ShadowScalarChannelInput.measured(right),
            source = source,
            person = person,
            viewContractId = viewContractId,
            providers = providers,
        )

        fun input(
            timestampMs: Long,
            left: ShadowScalarChannelInput,
            right: ShadowScalarChannelInput,
            source: ShadowSourceContinuityToken = this.source,
            person: ShadowPersonContinuityToken = this.person,
            viewContractId: String = request.viewRuntimeIdentity.selectedViewContractId,
            providers: ShadowCapabilityProviderArtifacts = request.capabilityProviderArtifacts,
        ): ShadowSampledScalarInput {
            val qualifiedView = qualifiedViewToken(
                source = source,
                person = person,
                timestampMs = timestampMs,
                viewContractId = viewContractId,
                qualifierArtifactId = request.viewRuntimeIdentity.viewQualifierArtifactId,
                qualifierArtifactSha256 = request.viewRuntimeIdentity.viewQualifierArtifactSha256,
            )
            return ShadowSampledScalarInput(
                timestampMs = timestampMs,
                source = source,
                person = person,
                qualifiedView = qualifiedView,
                capabilityProviderArtifacts = providers,
                channels = mapOf(
                    ShadowScalarSide.LEFT to left,
                    ShadowScalarSide.RIGHT to right,
                ),
            )
        }
    }

    private fun ShadowCompletedCycleAggregate.channel(
        side: ShadowScalarSide,
    ): ShadowScalarAggregate = requireNotNull(channelAggregates[side])

    private fun assertNotNullAggregate(
        aggregate: ShadowCompletedCycleAggregate?,
    ): ShadowCompletedCycleAggregate {
        assertNotNull(aggregate)
        return requireNotNull(aggregate)
    }

    private fun kernel(
        request: ShadowMeasurementExecutionRequest,
    ): PoseShadowMeasurementKernel {
        val constructor = PoseShadowMeasurementKernel::class.java.declaredConstructors
            .single { constructor ->
                constructor.parameterTypes.contentEquals(
                    arrayOf(ShadowMeasurementExecutionRequest::class.java),
                )
            }
        constructor.isAccessible = true
        return constructor.newInstance(request) as PoseShadowMeasurementKernel
    }

    private companion object {
        const val SOURCE_CONDITION_ID =
            "aihub-exact-sha256-48ecac06f2184af84c3a7f6885ecdbd53fb1a025887dde6fe52686a878862bc6"
        const val BINDING_ID =
            "aihub-binding-sha256-4d64a50373e5da088b53e2f71324aad49d6311eb793867ce89588d13a6b98d84"
        const val CAPABILITY_ID = "trex.capability.anatomical-segment-frame.v1"

        fun sha(character: Char): String = character.toString().repeat(64)

        fun sourceToken(
            runtimeDomainId: String,
            observationContractArtifactSha256: String,
        ): ShadowSourceContinuityToken {
            val constructor = ShadowSourceContinuityToken::class.java.declaredConstructors
                .single { constructor ->
                    constructor.parameterTypes.contentEquals(
                        arrayOf(String::class.java, String::class.java),
                    )
                }
            constructor.isAccessible = true
            return constructor.newInstance(
                runtimeDomainId,
                observationContractArtifactSha256,
            ) as ShadowSourceContinuityToken
        }

        fun personToken(
            source: ShadowSourceContinuityToken,
        ): ShadowPersonContinuityToken {
            val constructor = ShadowPersonContinuityToken::class.java.declaredConstructors
                .single { constructor ->
                    constructor.parameterTypes.contentEquals(
                        arrayOf(ShadowSourceContinuityToken::class.java),
                    )
                }
            constructor.isAccessible = true
            return constructor.newInstance(source) as ShadowPersonContinuityToken
        }

        fun qualifiedViewToken(
            source: ShadowSourceContinuityToken,
            person: ShadowPersonContinuityToken,
            timestampMs: Long,
            viewContractId: String,
            qualifierArtifactId: String,
            qualifierArtifactSha256: String,
        ): ShadowQualifiedViewToken {
            val constructor = ShadowQualifiedViewToken::class.java.declaredConstructors
                .single { constructor ->
                    constructor.parameterTypes.contentEquals(
                        arrayOf(
                            ShadowSourceContinuityToken::class.java,
                            ShadowPersonContinuityToken::class.java,
                            Long::class.javaPrimitiveType,
                            String::class.java,
                            String::class.java,
                            String::class.java,
                        ),
                    )
                }
            constructor.isAccessible = true
            return constructor.newInstance(
                source,
                person,
                timestampMs,
                viewContractId,
                qualifierArtifactId,
                qualifierArtifactSha256,
            ) as ShadowQualifiedViewToken
        }

        fun scopeToken(
            phaseArtifactSha256: String,
            startTimestampMs: Long,
            endTimestampMs: Long,
            source: ShadowSourceContinuityToken,
            person: ShadowPersonContinuityToken,
        ): VerifiedShadowCompletedCycleScope {
            val constructor = VerifiedShadowCompletedCycleScope::class.java.declaredConstructors
                .single { constructor ->
                    constructor.parameterTypes.contentEquals(
                        arrayOf(
                            String::class.java,
                            Long::class.javaPrimitiveType,
                            Long::class.javaPrimitiveType,
                            ShadowSourceContinuityToken::class.java,
                            ShadowPersonContinuityToken::class.java,
                        ),
                    )
                }
            constructor.isAccessible = true
            return constructor.newInstance(
                phaseArtifactSha256,
                startTimestampMs,
                endTimestampMs,
                source,
                person,
            ) as VerifiedShadowCompletedCycleScope
        }

        fun request(
            exercise: AiHubExercise = AiHubExercise.BARBELL_SQUAT,
            sourceConditionId: String = SOURCE_CONDITION_ID,
            bindingId: String = BINDING_ID,
            bindingPolicySha256: String =
                "54382de2a077b889d08cba249c381313027802fa4f3096984174ae66eca687ea",
            measurementConstructId: String =
                "trex.measurement.knee-foot-heading-projection.v1",
            phaseRoleId: String = "trex.phase-role.full-cycle.v1",
            selectedViewContractId: String = "trex.view.front-full-body.v1",
            requiredCapabilityIds: List<String> = REQUIRED_CAPABILITY_IDS,
            sideRuntimePolicy: ShadowSideRuntimePolicy = ShadowSideRuntimePolicy(
                kind = ShadowSidePolicyKind.BILATERAL_INDEPENDENT,
                sideChannels = setOf(ShadowScalarSide.LEFT, ShadowScalarSide.RIGHT),
            ),
            featureSpecSha256: String = sha('b'),
            providerSha256: String = sha('c'),
            maximumSamplesPerCycle: Int = 4,
        ): ShadowMeasurementExecutionRequest {
            val privacyId = "trex.privacy.shadow-aggregate-only.v1"
            return ShadowMeasurementExecutionRequest(
                exercise = exercise,
                sourceConditionId = sourceConditionId,
                bindingId = bindingId,
                bindingPolicySha256 = bindingPolicySha256,
                policyRegistrySha256 = AiHubCriterionPolicyCatalog.REGISTRY_SHA256,
                contentIdentity = ShadowMeasurementContentIdentity(
                    exerciseManifestId = "trex.shadow-manifest.barbell-squat.v1",
                    exerciseManifestSha256 = sha('1'),
                    bindingPlanId = "trex.shadow-plan.knee-foot.v1",
                    bindingPlanSha256 = sha('2'),
                    measurementConstructId = measurementConstructId,
                    measurementConstructSha256 = sha('3'),
                ),
                featureRuntimeIdentity = ShadowFeatureRuntimeIdentity(
                    featureContractId = "trex.feature.knee-foot-projection.v1",
                    featureSpecSha256 = featureSpecSha256,
                    featureRuntimeContractSha256 = sha('4'),
                ),
                observationRuntimeIdentity = ShadowObservationRuntimeIdentity(
                    runtimeDomainId = "trex.runtime.mediapipe-pose.v1",
                    observationContractArtifactSha256 = sha('5'),
                ),
                phaseRuntimeIdentity = ShadowPhaseRuntimeIdentity(
                    phaseRoleId = phaseRoleId,
                    phaseArtifactSha256 = sha('6'),
                ),
                viewRuntimeIdentity = ShadowViewRuntimeIdentity(
                    selectedViewContractId = selectedViewContractId,
                    viewQualifierArtifactId = "trex.qualifier.front-view.v1",
                    viewQualifierArtifactSha256 = sha('7'),
                ),
                capabilityProviderArtifacts = ShadowCapabilityProviderArtifacts(
                    requiredCapabilityIds.associateWith { capabilityId ->
                        if (capabilityId == CAPABILITY_ID) providerSha256 else sha('c')
                    },
                ),
                sideRuntimePolicy = sideRuntimePolicy,
                measurementRuntimeContract = ShadowMeasurementRuntimeContract(
                    contractId = "trex.runtime.shadow-completed-cycle.v1",
                    maximumSampleGapMs = 100L,
                    maximumCycleDurationMs = 1_000L,
                    maximumSamplesPerCycle = maximumSamplesPerCycle,
                ),
                outputPrivacyContract = ShadowOutputPrivacyContract(
                    contractId = privacyId,
                    repositoryDriftPinSha256 = ShadowOutputPrivacyContract.contentSha256(
                        privacyId,
                    ),
                    retention = ShadowOutputRetention.IN_MEMORY_ONLY,
                    detail = ShadowOutputDetail.AGGREGATES_ONLY,
                    rawPoseRetentionAllowed = false,
                    timestampSeriesAllowed = false,
                    persistentStorageAllowed = false,
                    networkExportAllowed = false,
                ),
            )
        }

        val REQUIRED_CAPABILITY_IDS = listOf(
            CAPABILITY_ID,
            "trex.capability.pose-2d.v1",
            "trex.capability.pose-world-relative.v1",
            "trex.capability.primary-person-lock.v1",
            "trex.capability.temporal-pose.v1",
            "trex.capability.view-qualified.v1",
        )
    }
}

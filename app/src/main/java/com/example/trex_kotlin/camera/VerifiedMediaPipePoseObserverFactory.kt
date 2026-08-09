package com.example.trex_kotlin.camera

import android.content.Context
import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Actual delegate selected after configured fallback has completed. */
internal enum class ResolvedPoseDelegate(val contractValue: String) {
    CPU("cpu"),
    GPU("gpu"),
}

enum class PoseObserverArtifactFailure {
    UNSUPPORTED_CONFIGURATION,
    MODEL_LENGTH_MISMATCH,
    MODEL_DIGEST_MISMATCH,
    MODEL_READ_FAILED,
}

internal class PoseObserverArtifactVerificationException(
    val failure: PoseObserverArtifactFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.name, cause)

/**
 * Verified model bytes and fixed observer profile.
 *
 * The direct buffer is private and retained for the complete MediaPipe task lifecycle. The same
 * bytes that were hashed are passed to MediaPipe; the backend cannot silently reopen a different
 * asset path. This receipt proves bundled-byte consistency inside the signed APK, not remote
 * authenticity or runtime release authorization.
 */
internal class VerifiedMediaPipePoseObserverProfile private constructor(
    val config: PoseCameraConfig,
    private val verifiedModelBuffer: ByteBuffer,
) {
    val modelArtifactSha256: String = EXPECTED_MODEL_SHA256
    val modelByteCount: Int = verifiedModelBuffer.limit()

    fun modelBufferForBackend(): ByteBuffer = verifiedModelBuffer.asReadOnlyBuffer().apply {
        position(0)
        limit(modelByteCount)
    }

    fun createObserver(resolvedDelegate: ResolvedPoseDelegate): MediaPipePoseObserver {
        require(
            when (config.delegate) {
                PoseCameraDelegate.Cpu -> resolvedDelegate == ResolvedPoseDelegate.CPU
                PoseCameraDelegate.Gpu -> resolvedDelegate == ResolvedPoseDelegate.GPU
                PoseCameraDelegate.GpuWithCpuFallback -> true
            },
        ) { "Resolved delegate is impossible for the verified delegate policy" }
        val personLockConfig = PosePersonLockConfig()
        val viewQualifierConfig = PoseViewQualifierConfig()
        val contract = PoseObservationContract(
            runtimeDomainId = runtimeDomainId(resolvedDelegate),
            modelArtifactId = MODEL_ARTIFACT_ID,
            modelArtifactSha256 = modelArtifactSha256,
            inferenceOptionsContractId = INFERENCE_OPTIONS_CONTRACT_ID,
            inferenceOptionsArtifactSha256 = inferenceOptionsArtifactSha256(
                config = config,
                resolvedDelegate = resolvedDelegate,
            ),
            preprocessingContractId = PREPROCESSING_CONTRACT_ID,
            preprocessingArtifactSha256 = PREPROCESSING_ARTIFACT_SHA256,
            landmarkSchemaId = LANDMARK_SCHEMA_ID,
            landmarkSchemaArtifactSha256 = LANDMARK_SCHEMA_ARTIFACT_SHA256,
            supportedCoordinateSpaces = setOf(
                PoseCoordinateSpace.NORMALIZED_IMAGE,
                PoseCoordinateSpace.WORLD,
            ),
            phaseViewContractId = FULL_BODY_PHASE_VIEW_CONTRACT_ID,
            allowedViewContractIds = setOf(
                FULL_BODY_PHASE_VIEW_CONTRACT_ID,
                FULL_BODY_LATERAL_VIEW_CONTRACT_ID,
            ),
            personLockArtifactId = PERSON_LOCK_ARTIFACT_ID,
            personLockArtifactSha256 = personLockConfig.artifactSha256,
            viewQualifierArtifactId = VIEW_QUALIFIER_ARTIFACT_ID,
            viewQualifierArtifactSha256 = viewQualifierConfig.artifactSha256,
        )
        return MediaPipePoseObserver(
            observationSource = PoseObservationSource(contract),
            personLockConfig = personLockConfig,
            viewQualifierConfig = viewQualifierConfig,
        )
    }

    companion object {
        internal const val EXPECTED_MODEL_BYTE_COUNT = 9_398_198
        internal const val EXPECTED_MODEL_SHA256 =
            "4eaa5eb7a98365221087693fcc286334cf0858e2eb6e15b506aa4a7ecdcec4ad"
        internal const val MODEL_ARTIFACT_ID = "mediapipe.pose-landmarker.full.task.v1"
        internal const val INFERENCE_OPTIONS_CONTRACT_ID =
            "trex.mediapipe-pose-landmarker.video-options.v1"
        internal const val PREPROCESSING_CONTRACT_ID =
            "trex.camerax-rgba-crop-rotate-upright.v1"
        internal const val LANDMARK_SCHEMA_ID = "mediapipe.pose-landmarker.33.v1"
        internal const val PERSON_LOCK_ARTIFACT_ID = "trex.primary-person.temporal-lock.v1"
        internal const val VIEW_QUALIFIER_ARTIFACT_ID = "trex.body-view.qualifier.v1"

        internal val PREPROCESSING_ARTIFACT_SHA256: String = canonicalFieldsSha256(
            listOf(
                "preprocessingSchemaVersion" to "1",
                "cameraXVersion" to "1.6.1",
                "inputFormat" to "RGBA_8888",
                "planePolicy" to "REQUIRE_PIXEL_STRIDE_4_COPY_VISIBLE_ROW_BYTES",
                "cropPolicy" to "IMAGE_PROXY_CROP_RECT_BEFORE_ROTATION",
                "rotationPolicy" to "IMAGE_INFO_ROTATION_BILINEAR_UPRIGHT",
                "inferenceMirrorPolicy" to "NEVER_MIRROR_PIXELS",
                "displayMirrorPolicy" to "FRONT_PREVIEW_METADATA_ONLY",
                "timestampPolicy" to "CAMERAX_SENSOR_NANOSECONDS_TO_FLOOR_MILLISECONDS",
                "subMillisecondTimestampCollisionPolicy" to "DROP_WITHOUT_EVIDENCE",
                "sensorTimestampDiscontinuityPolicy" to "STOP_OBSERVER_SOURCE",
                "imageProxyOwnershipPolicy" to "CLOSE_ON_EVERY_ACCESS_PATH",
                "backpressurePolicy" to "KEEP_ONLY_LATEST",
                "requestedResolution" to "640x480",
                "resolutionFallback" to "CLOSEST_HIGHER_THEN_LOWER_RECORDED_PER_FRAME",
                "viewPortPolicy" to "REQUIRED_SHARED_PREVIEW_ANALYSIS_VIEWPORT",
            ),
        )

        internal val LANDMARK_SCHEMA_ARTIFACT_SHA256: String = canonicalFieldsSha256(
            buildList {
                add("landmarkSchemaVersion" to "1")
                add("candidateCardinalityPolicy" to "EXACTLY_33_NORMALIZED_AND_WORLD")
                add("candidatePairingPolicy" to "NORMALIZED_AND_WORLD_SAME_RESULT_INDEX")
                add("rejectedCandidateMultiplicityPolicy" to "PRESERVE_RAW_CANDIDATE_COUNT")
                add("missingConfidencePolicy" to "ZERO_NOT_OBSERVED")
                add("nonFiniteCoordinatePolicy" to "REJECT_CANDIDATE")
                add("landmarkCount" to PoseJoint.entries.size.toString())
                PoseJoint.entries.sortedBy(PoseJoint::mediaPipeIndex).forEachIndexed { index, joint ->
                    add("landmark[$index]" to "${joint.name}:${joint.mediaPipeIndex}")
                }
            },
        )

        fun verify(context: Context, config: PoseCameraConfig): VerifiedMediaPipePoseObserverProfile {
            validateApprovedConfig(config)
            val input = try {
                context.applicationContext.assets.open(config.modelAssetName)
            } catch (error: FileNotFoundException) {
                throw error
            } catch (error: Exception) {
                throw PoseObserverArtifactVerificationException(
                    PoseObserverArtifactFailure.MODEL_READ_FAILED,
                    error,
                )
            }
            return try {
                input.use { stream -> verifyModelStream(stream, config) }
            } catch (error: PoseObserverArtifactVerificationException) {
                throw error
            } catch (error: Exception) {
                throw PoseObserverArtifactVerificationException(
                    PoseObserverArtifactFailure.MODEL_READ_FAILED,
                    error,
                )
            }
        }

        internal fun verifyModelStream(
            input: InputStream,
            config: PoseCameraConfig = PoseCameraConfig(),
        ): VerifiedMediaPipePoseObserverProfile {
            validateApprovedConfig(config)
            val digest = MessageDigest.getInstance("SHA-256")
            val modelBuffer = ByteBuffer.allocateDirect(EXPECTED_MODEL_BYTE_COUNT)
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            var byteCount = 0
            try {
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    if (read == 0) continue
                    if (byteCount + read > EXPECTED_MODEL_BYTE_COUNT) {
                        throw PoseObserverArtifactVerificationException(
                            PoseObserverArtifactFailure.MODEL_LENGTH_MISMATCH,
                        )
                    }
                    digest.update(chunk, 0, read)
                    modelBuffer.put(chunk, 0, read)
                    byteCount += read
                }
            } catch (error: PoseObserverArtifactVerificationException) {
                throw error
            } catch (error: Exception) {
                throw PoseObserverArtifactVerificationException(
                    PoseObserverArtifactFailure.MODEL_READ_FAILED,
                    error,
                )
            }
            if (byteCount != EXPECTED_MODEL_BYTE_COUNT) {
                throw PoseObserverArtifactVerificationException(
                    PoseObserverArtifactFailure.MODEL_LENGTH_MISMATCH,
                )
            }
            val actualSha256 = digest.digest().toLowerHex()
            if (actualSha256 != EXPECTED_MODEL_SHA256) {
                throw PoseObserverArtifactVerificationException(
                    PoseObserverArtifactFailure.MODEL_DIGEST_MISMATCH,
                )
            }
            modelBuffer.flip()
            return VerifiedMediaPipePoseObserverProfile(
                config = config,
                verifiedModelBuffer = modelBuffer,
            )
        }

        private fun validateApprovedConfig(config: PoseCameraConfig) {
            val approved = PoseCameraConfig(delegate = config.delegate)
            if (config != approved) {
                throw PoseObserverArtifactVerificationException(
                    PoseObserverArtifactFailure.UNSUPPORTED_CONFIGURATION,
                )
            }
        }

        private fun runtimeDomainId(delegate: ResolvedPoseDelegate): String =
            "trex.mediapipe-pose-full.video-${delegate.contractValue}.v1"

        private fun inferenceOptionsArtifactSha256(
            config: PoseCameraConfig,
            resolvedDelegate: ResolvedPoseDelegate,
        ): String = canonicalFieldsSha256(
            listOf(
                "inferenceOptionsSchemaVersion" to "1",
                "mediaPipeTasksVisionVersion" to "0.10.29",
                "runningMode" to "VIDEO",
                "configuredDelegatePreference" to config.delegate.contractValue,
                "resolvedDelegate" to resolvedDelegate.contractValue,
                "delegateFallbackPolicy" to config.delegate.fallbackContractValue,
                "numberOfPoses" to config.numberOfPoses.toString(),
                "minPoseDetectionConfidenceBits" to config.minPoseDetectionConfidence.rawHex(),
                "minPosePresenceConfidenceBits" to config.minPosePresenceConfidence.rawHex(),
                "minTrackingConfidenceBits" to config.minTrackingConfidence.rawHex(),
                "segmentationMasksEnabled" to "false",
            ),
        )
    }
}

private val PoseCameraDelegate.contractValue: String
    get() = when (this) {
        PoseCameraDelegate.Cpu -> "cpu-only"
        PoseCameraDelegate.Gpu -> "gpu-only"
        PoseCameraDelegate.GpuWithCpuFallback -> "gpu-with-cpu-fallback"
    }

private val PoseCameraDelegate.fallbackContractValue: String
    get() = when (this) {
        PoseCameraDelegate.Cpu,
        PoseCameraDelegate.Gpu,
        -> "none"
        PoseCameraDelegate.GpuWithCpuFallback ->
            "gpu-task-creation-then-cpu-task-creation-only-inference-failure-stops-source"
    }

private fun Float.rawHex(): String = toRawBits().toUInt().toString(16).padStart(8, '0')

private fun ByteArray.toLowerHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        this@toLowerHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}

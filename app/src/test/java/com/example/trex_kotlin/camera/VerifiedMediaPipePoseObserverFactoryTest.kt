package com.example.trex_kotlin.camera

import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.SequenceInputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedMediaPipePoseObserverFactoryTest {
    @Test
    fun bundledModelBytesMatchPinnedLengthAndDigestAndRemainReadOnlyToBackend() {
        val asset = modelAssetFile()
        assertEquals(
            VerifiedMediaPipePoseObserverProfile.EXPECTED_MODEL_BYTE_COUNT.toLong(),
            asset.length(),
        )
        assertEquals(
            VerifiedMediaPipePoseObserverProfile.EXPECTED_MODEL_SHA256,
            sha256(asset),
        )

        val profile = FileInputStream(asset).use { input ->
            VerifiedMediaPipePoseObserverProfile.verifyModelStream(input)
        }
        val backendBuffer = profile.modelBufferForBackend()
        assertTrue(backendBuffer.isDirect)
        assertTrue(backendBuffer.isReadOnly)
        assertEquals(0, backendBuffer.position())
        assertEquals(profile.modelByteCount, backendBuffer.remaining())
    }

    @Test
    fun shortLongCorruptOrCustomConfigurationFailsClosed() {
        val short = assertThrows(PoseObserverArtifactVerificationException::class.java) {
            VerifiedMediaPipePoseObserverProfile.verifyModelStream(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            )
        }
        assertEquals(PoseObserverArtifactFailure.MODEL_LENGTH_MISMATCH, short.failure)

        val asset = modelAssetFile()
        val long = assertThrows(PoseObserverArtifactVerificationException::class.java) {
            SequenceInputStream(FileInputStream(asset), ByteArrayInputStream(byteArrayOf(0))).use {
                VerifiedMediaPipePoseObserverProfile.verifyModelStream(it)
            }
        }
        assertEquals(PoseObserverArtifactFailure.MODEL_LENGTH_MISMATCH, long.failure)

        val corruptBytes = asset.readBytes().also { bytes ->
            bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0x01).toByte()
        }
        val corrupt = assertThrows(PoseObserverArtifactVerificationException::class.java) {
            VerifiedMediaPipePoseObserverProfile.verifyModelStream(
                ByteArrayInputStream(corruptBytes),
            )
        }
        assertEquals(PoseObserverArtifactFailure.MODEL_DIGEST_MISMATCH, corrupt.failure)

        val custom = assertThrows(PoseObserverArtifactVerificationException::class.java) {
            VerifiedMediaPipePoseObserverProfile.verifyModelStream(
                ByteArrayInputStream(byteArrayOf()),
                PoseCameraConfig(numberOfPoses = 3),
            )
        }
        assertEquals(PoseObserverArtifactFailure.UNSUPPORTED_CONFIGURATION, custom.failure)
    }

    @Test
    fun actualCpuAndGpuDelegatesProduceDifferentPinnedRuntimeDomains() {
        val profile = FileInputStream(modelAssetFile()).use { input ->
            VerifiedMediaPipePoseObserverProfile.verifyModelStream(input)
        }
        val cpu = profile.createObserver(ResolvedPoseDelegate.CPU)
        val gpu = profile.createObserver(ResolvedPoseDelegate.GPU)
        try {
            val cpuContract = cpu.observationSource.contract
            val gpuContract = gpu.observationSource.contract
            assertNotEquals(cpuContract.runtimeDomainId, gpuContract.runtimeDomainId)
            assertNotEquals(
                cpuContract.inferenceOptionsArtifactSha256,
                gpuContract.inferenceOptionsArtifactSha256,
            )
            assertNotEquals(cpuContract.artifactSha256, gpuContract.artifactSha256)
            assertEquals(
                VerifiedMediaPipePoseObserverProfile.EXPECTED_MODEL_SHA256,
                cpuContract.modelArtifactSha256,
            )
            assertEquals(
                VerifiedMediaPipePoseObserverProfile.PREPROCESSING_CONTRACT_ID,
                cpuContract.preprocessingContractId,
            )
            assertEquals(
                VerifiedMediaPipePoseObserverProfile.PREPROCESSING_ARTIFACT_SHA256,
                cpuContract.preprocessingArtifactSha256,
            )
            assertEquals(
                setOf(
                    FULL_BODY_PHASE_VIEW_CONTRACT_ID,
                    FULL_BODY_LATERAL_VIEW_CONTRACT_ID,
                ),
                cpuContract.allowedViewContractIds,
            )
            assertFalse(cpuContract.allowedViewContractIds.any { "front" in it || "rear" in it })
        } finally {
            cpu.close()
            gpu.close()
        }
    }

    @Test
    fun preprocessingContractPinsTheExactCameraGeometryProvider() {
        assertEquals(
            "trex.camerax-rgba-crop-rotate-upright.v2",
            VerifiedMediaPipePoseObserverProfile.PREPROCESSING_CONTRACT_ID,
        )
        assertEquals(
            "trex.camerax-image-proxy.geometry-context-provider.v1",
            VerifiedMediaPipePoseObserverProfile.CAMERA_GEOMETRY_PROVIDER_CONTRACT_ID,
        )
        assertEquals(
            PoseCameraGeometryContext.SCHEMA_VERSION,
            VerifiedMediaPipePoseObserverProfile.CAMERA_GEOMETRY_CONTEXT_SCHEMA_VERSION,
        )
        assertEquals(
            "e81a27d8cc17c8a27a5860d7a3cbff2a19d764ca2b053302c0a5c4f18e16a9c8",
            VerifiedMediaPipePoseObserverProfile.CAMERA_GEOMETRY_PROVIDER_ARTIFACT_SHA256,
        )
        assertEquals(
            "37e938c6627823683c6f764ec3cfda7b620aca5f6cc4439371645dfbdde0467b",
            VerifiedMediaPipePoseObserverProfile.PREPROCESSING_ARTIFACT_SHA256,
        )
    }

    @Test
    fun configuredDelegatePreferenceIsPartOfTheInferenceContract() {
        val asset = modelAssetFile()
        val cpuOnly = FileInputStream(asset).use { input ->
            VerifiedMediaPipePoseObserverProfile.verifyModelStream(
                input,
                PoseCameraConfig(delegate = PoseCameraDelegate.Cpu),
            )
        }.createObserver(ResolvedPoseDelegate.CPU)
        val fallback = FileInputStream(asset).use { input ->
            VerifiedMediaPipePoseObserverProfile.verifyModelStream(
                input,
                PoseCameraConfig(delegate = PoseCameraDelegate.GpuWithCpuFallback),
            )
        }.createObserver(ResolvedPoseDelegate.CPU)
        try {
            assertNotEquals(
                cpuOnly.observationSource.contract.inferenceOptionsArtifactSha256,
                fallback.observationSource.contract.inferenceOptionsArtifactSha256,
            )
        } finally {
            cpuOnly.close()
            fallback.close()
        }
    }

    @Test
    fun impossibleResolvedDelegateForVerifiedPolicyIsRejected() {
        val asset = modelAssetFile()
        val cpuOnly = FileInputStream(asset).use { input ->
            VerifiedMediaPipePoseObserverProfile.verifyModelStream(
                input,
                PoseCameraConfig(delegate = PoseCameraDelegate.Cpu),
            )
        }
        val gpuOnly = FileInputStream(asset).use { input ->
            VerifiedMediaPipePoseObserverProfile.verifyModelStream(
                input,
                PoseCameraConfig(delegate = PoseCameraDelegate.Gpu),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            cpuOnly.createObserver(ResolvedPoseDelegate.GPU)
        }
        assertThrows(IllegalArgumentException::class.java) {
            gpuOnly.createObserver(ResolvedPoseDelegate.CPU)
        }
    }

    @Test
    fun cameraConfigRequiresMultiPersonSentinelCapacity() {
        assertThrows(IllegalArgumentException::class.java) { PoseCameraConfig(numberOfPoses = 1) }
        assertThrows(IllegalArgumentException::class.java) { PoseCameraConfig(numberOfPoses = 5) }
        assertEquals(2, PoseCameraConfig().numberOfPoses)
    }

    private fun modelAssetFile(): File {
        val candidates = listOf(
            File("src/main/assets/${PoseCameraConfig.DEFAULT_POSE_MODEL_ASSET}"),
            File("app/src/main/assets/${PoseCameraConfig.DEFAULT_POSE_MODEL_ASSET}"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate the bundled pose model from ${File(".").absolutePath}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

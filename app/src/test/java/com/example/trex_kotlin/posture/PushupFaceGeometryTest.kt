package com.example.trex_kotlin.posture

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class PushupFaceGeometryTest {
    private fun matrix(yawFromCamera: Double, roll: Double = 0.0): FloatArray {
        val y = Math.toRadians(yawFromCamera); val r = Math.toRadians(roll)
        val cy = cos(y).toFloat(); val sy = sin(y).toFloat()
        val cr = cos(r).toFloat(); val sr = sin(r).toFloat()
        // 열 우선 Rz(roll) * Ry(yaw). 평행이동은 방향값에 영향을 주지 않는다.
        return floatArrayOf(cr*cy,sr*cy,-sy,0f,-sr,cr,0f,0f,cr*sy,sr*sy,cy,0f,2f,3f,4f,1f)
    }
    private fun xy(): FloatArray = FloatArray(66) { .5f }.apply {
        this[0]=.42f;this[1]=.3f
        this[14]=.4f;this[15]=.28f;this[16]=.44f;this[17]=.28f
        this[22]=.4f;this[23]=.45f;this[24]=.44f;this[25]=.45f
    }
    private fun vis() = FloatArray(33) { .9f }

    @Test fun frontProfileAndBackHaveDistinctDirections() {
        assertEquals(90f,PushupFaceGeometry.yawDegrees(matrix(0.0))!!,.001f)
        assertEquals(0f,PushupFaceGeometry.yawDegrees(matrix(90.0))!!,.001f)
        assertEquals(-90f,PushupFaceGeometry.yawDegrees(matrix(180.0))!!,.001f)
    }
    @Test fun mirrorAndImageRollPreserveLensFacingAngle() {
        for (roll in listOf(-130.0,0.0,75.0,180.0)) {
            assertEquals(45f,PushupFaceGeometry.yawDegrees(matrix(45.0,roll))!!,.001f)
            assertEquals(45f,PushupFaceGeometry.yawDegrees(matrix(-45.0,roll))!!,.001f)
        }
    }
    @Test fun rowAndColumnStorageProduceTheSameDirection() {
        val column=matrix(60.0,35.0)
        val row=FloatArray(16) { column[(it%4)*4+it/4] }
        assertEquals(30f,PushupFaceGeometry.yawDegrees(column)!!,.001f)
        assertEquals(30f,PushupFaceGeometry.yawDegrees(row)!!,.001f)
    }
    @Test fun invalidOrNonFiniteMatricesAreUnobserved() {
        assertNull(PushupFaceGeometry.yawDegrees(FloatArray(15)))
        assertNull(PushupFaceGeometry.yawDegrees(FloatArray(16)))
        assertNull(PushupFaceGeometry.yawDegrees(matrix(0.0).apply { this[0]=Float.NaN }))
        assertNull(PushupFaceGeometry.yawDegrees(matrix(0.0).apply { this[14]=Float.POSITIVE_INFINITY }))
    }
    @Test fun failedObservationNeverBecomesAProfileOrReusesSuccess() {
        val measured=PushupFaceGeometry.observedFeatures(true,matrix(0.0))
        assertEquals(1f,measured[PushupFaceGeometry.FOUND]!!,0f)
        assertEquals(90f,measured[PushupFaceGeometry.YAW]!!,.001f)
        assertTrue(PushupFaceGeometry.observedFeatures(false,matrix(0.0)).isEmpty())
        assertTrue(PushupFaceGeometry.observedFeatures(true,null).isEmpty())
        assertTrue(PushupFaceGeometry.observedFeatures(true,FloatArray(16)).isEmpty())
    }
    @Test fun cropUsesActualPixelAspectAndKeepsHeadAtItsCenter() {
        val crop=PushupFaceGeometry.crop(xy(),vis(),1000,500)!!
        assertEquals(420f,crop.centerX,.001f)
        assertEquals(143.33333f,crop.centerY,.001f)
        assertEquals(255f,crop.side,.001f)
        assertEquals(0f,crop.rotationDegrees,.001f)
        assertEquals(256f,crop.side*crop.scale,.001f)
    }
    @Test fun imageRollAndMirrorKeepCropSizeAndRotateNeckUpward() {
        val points=xy().apply {
            this[14]=.35f;this[15]=.4f;this[16]=.35f;this[17]=.44f
            this[0]=.33f;this[1]=.42f
            this[22]=.52f;this[23]=.4f;this[24]=.52f;this[25]=.44f
        }
        val original=PushupFaceGeometry.crop(points,vis(),1000,1000)!!
        val reflected=points.copyOf().apply { for (i in 0..32) this[i*2]=1f-this[i*2] }
        val mirror=PushupFaceGeometry.crop(reflected,vis(),1000,1000)!!
        assertEquals(90f,original.rotationDegrees,.001f)
        assertEquals(-90f,mirror.rotationDegrees,.001f)
        assertEquals(original.side,mirror.side,.001f)
        assertEquals(1000f-original.centerX,mirror.centerX,.001f)
    }
    @Test fun hiddenFarSideDoesNotPreventVisibleHeadCrop() {
        val visibility=vis().apply { this[8]=0f;this[12]=0f }
        assertNotNull(PushupFaceGeometry.crop(xy(),visibility,1000,1000))
        visibility[7]=0f
        assertNull(PushupFaceGeometry.crop(xy(),visibility,1000,1000))
    }
    @Test fun hiddenShouldersAndOffscreenHeadPreventCrop() {
        assertNull(PushupFaceGeometry.crop(xy(),vis().apply { this[11]=0f;this[12]=0f },1000,1000))
        assertNull(PushupFaceGeometry.crop(xy().apply { this[14]=-1f;this[16]=Float.NaN },vis(),1000,1000))
        assertNull(PushupFaceGeometry.crop(xy(),vis().apply { this[0]=Float.NaN;this[7]=Float.POSITIVE_INFINITY },1000,1000))
    }
    @Test fun invalidFrameAndCollapsedNeckPreventCrop() {
        assertNull(PushupFaceGeometry.crop(FloatArray(65),vis(),1000,1000))
        assertNull(PushupFaceGeometry.crop(xy(),FloatArray(32),1000,1000))
        assertNull(PushupFaceGeometry.crop(xy(),vis(),0,1000))
        assertNull(PushupFaceGeometry.crop(FloatArray(66){.5f},vis(),1000,1000))
    }
}

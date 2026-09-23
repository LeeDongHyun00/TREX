package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class ActionInputTest {
    private fun fixture()=javaClass.classLoader!!.getResourceAsStream("action_input_fixture.txt")!!
        .bufferedReader().readLines().map { line -> line.trim().split(" ").map(String::toFloat).toFloatArray() }

    @Test fun pythonNormalizationParityUsesSyntheticCoordinates() {
        val f=fixture()
        assertArrayEquals(f[4],ActionInputBuilder.encode(f[1],f[2],f[3],f[0][0].toInt(),f[0][1].toInt()),.00001f)
    }
    @Test fun translationAndBodyScaleDoNotChangeCenteredCoordinates() {
        val f=fixture(); val a=ActionInputBuilder.encode(f[1],f[2],f[3],640,480)
        val moved=f[1].map { it*.7f+.1f }.toFloatArray()
        assertArrayEquals(a,ActionInputBuilder.encode(moved,f[2],f[3],640,480),.00001f)
    }
    @Test fun missingTorsoAndNonFiniteJointsAreNotNormalPositions() {
        val f=fixture(); val v=f[2].copyOf(); v[23]=0f; v[24]=0f
        assertTrue(ActionInputBuilder.encode(f[1],v,f[3],640,480).all { it==0f })
        val xy=f[1].copyOf(); xy[30]=Float.NaN
        val x=ActionInputBuilder.encode(xy,f[2],f[3],640,480)
        assertTrue(x.all { it.isFinite() }); assertEquals(0f,x[15*4+3],0f)
    }
    @Test fun windowSeparatesEpochGapAndRejectsDuplicateTime() {
        val w=ActionWindow(); val a=FloatArray(132){1f}
        assertTrue(w.add(100,1,a)); assertFalse(w.add(100,1,a)); assertEquals(1,w.size)
        w.add(200,1,a); assertEquals(2,w.size)
        w.add(1000,1,a); assertEquals(1,w.size)
        w.add(1100,2,a); assertEquals(1,w.size)
        assertFalse(w.add(1200,2,FloatArray(132))); assertEquals(0,w.size)
    }
    @Test fun shortPrefixHasOnlyPastFramesAndNoRepeatedPadding() {
        val w=ActionWindow(); w.add(100,1,FloatArray(132){1f}); w.add(200,1,FloatArray(132){2f})
        val buffer=FloatArray(16*132); w.copyInto(buffer)
        assertTrue(buffer.take(14*132).all{it==0f})
        assertEquals(1f,buffer[14*132],0f); assertEquals(2f,buffer[15*132],0f)
    }
    @Test fun inputIsIndependentOfSelectedExercise() {
        // API에 선택 종목 인자가 없으며 좌우 채널은 합치지 않는다.
        val f=fixture(); val x=ActionInputBuilder.encode(f[1],f[2],f[3],640,480)
        assertNotEquals(x[11*4],x[12*4]); assertEquals(132,x.size)
    }
}

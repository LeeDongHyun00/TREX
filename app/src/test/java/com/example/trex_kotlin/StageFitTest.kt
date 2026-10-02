package com.example.trex_kotlin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageFitTest {
    private val standing = BodyBox(0.3f, 0.1f, 0.7f, 0.9f)
    private val squatting = BodyBox(0.25f, 0.4f, 0.75f, 0.9f)

    @Test fun locksOnceAfterOneSecondAndKeepsItThroughTheSet() {
        val f = StageFitController()
        assertNull(f.update(0, standing))
        assertNull(f.update(999, standing))
        assertEquals(standing, f.update(1000, standing))
        // 하강해서 상자가 반으로 줄어도 배율은 그대로 — 동작을 왜곡하지 않는다
        assertEquals(standing, f.update(3000, squatting))
    }

    @Test fun shortOcclusionKeepsTheLockButLosingThePersonUnlocksAndRelocks() {
        val f = StageFitController()
        f.update(0, standing); f.update(1000, standing)
        assertEquals(standing, f.update(1500, null))
        assertFalse(f.lost)
        assertEquals(standing, f.update(2900, null))
        assertNull(f.update(3000, null))
        assertTrue(f.lost)
        // 돌아오면 다시 1초 뒤 새 상자로 잠근다
        assertNull(f.update(4000, squatting))
        assertFalse(f.lost)
        assertEquals(squatting, f.update(5000, squatting))
    }

    @Test fun flickerBeforeTheLockRestartsTheSettleWindow() {
        val f = StageFitController()
        f.update(0, standing)
        f.update(500, null)
        assertNull(f.update(1200, standing))
        assertEquals(standing, f.update(2200, standing))
    }
}

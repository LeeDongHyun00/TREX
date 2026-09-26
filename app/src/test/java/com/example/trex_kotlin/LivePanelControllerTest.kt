package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

class LivePanelControllerTest {
    @Test fun countdownStartsImmersiveButSkipNeedsOneSecondOfFullBody() {
        val p = LivePanelController()
        p.beginSession(0, false)
        assertFalse(p.visible)
        p.beginSession(100, true)
        assertTrue(p.update(100, .5f, false, false))
        assertTrue(p.update(10000, .2f, false, false))
        assertTrue(p.update(11000, .2f, false, true))
        assertTrue(p.update(11999, .2f, false, true))
        assertFalse(p.update(12000, .2f, false, true))
    }
    @Test fun incompleteBodyRestartsSkipWindowAndManualOpenRemainsUsable() {
        val p = LivePanelController()
        p.beginSession(0, true)
        p.update(0, .2f, false, true)
        p.update(900, .2f, false, false)
        p.update(1000, .2f, false, true)
        assertTrue(p.update(1999, .2f, false, true))
        assertFalse(p.update(2000, .2f, false, true))
        p.reveal(2100)
        assertTrue(p.update(3200, .2f, false, true))
    }
    @Test fun stableExerciseHidesAndShortOcclusionDoesNotFlashPanel() {
        val p = LivePanelController()
        assertTrue(p.update(0, .2f, false))
        assertFalse(p.update(3000, .2f, false))
        assertFalse(p.update(3200, null, false))
        assertFalse(p.update(3600, .2f, false))
        assertFalse(p.update(4000, null, false))
        assertTrue(p.update(5500, null, false))
    }
    @Test fun approachShowsPanelButSingleLargeFrameDoesNot() {
        val p = LivePanelController()
        p.update(0, .2f, false);p.update(3000, .2f, false)
        assertFalse(p.update(3100, .4f, false))
        assertFalse(p.update(3400, .2f, false))
        p.update(3500, .4f, false)
        assertTrue(p.update(4200, .4f, false))
    }
    @Test fun manualAccessAndPauseKeepControlsReachable() {
        val p = LivePanelController()
        p.update(0, .2f, false);p.update(3000, .2f, false)
        p.reveal(4000)
        p.update(4000, .2f, false)
        assertTrue(p.update(11000, .2f, false))
        assertFalse(p.update(12000, .2f, false))
        assertTrue(p.update(12100, .2f, true))
        assertTrue(p.update(15000, .2f, true))
    }
    @Test fun noUsableJointsNeverAutomaticallyHidesControls() {
        val p = LivePanelController()
        assertTrue(p.update(0, null, false));assertTrue(p.update(20000, null, false))
    }
}

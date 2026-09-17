package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ObservationEpochTest {
    @Test fun cameraSwitchDropsAnInferenceAlreadyInFlight() {
        val epoch = ObservationEpoch()
        val oldCamera = epoch.ticket()
        var reps = 4
        epoch.invalidate { /* 이미 확정한 횟수는 보존한다. */ }
        assertFalse(epoch.applyIfCurrent(oldCamera) { reps++ })
        assertEquals(4, reps)
        assertTrue(epoch.applyIfCurrent(epoch.ticket()) { reps++ })
        assertEquals(5, reps)
    }

    @Test fun rapidPauseAndResumeStillRejectsPrePauseFrame() {
        val epoch = ObservationEpoch()
        val beforePause = epoch.ticket()
        epoch.invalidate()
        epoch.invalidate()
        assertFalse(epoch.applyIfCurrent(beforePause) { fail("일시정지 전 프레임") })
    }

    @Test fun resetAndResultConsumptionCannotInterleave() {
        val epoch = ObservationEpoch()
        val consuming = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resetting = CountDownLatch(1)
        val resetFinished = AtomicBoolean(false)
        val ticket = epoch.ticket()
        val consumer = Thread {
            epoch.applyIfCurrent(ticket) {
                consuming.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }
        }
        consumer.start()
        assertTrue(consuming.await(2, TimeUnit.SECONDS))
        val resetter = Thread {
            resetting.countDown()
            epoch.invalidate { resetFinished.set(true) }
        }
        resetter.start()
        assertTrue(resetting.await(2, TimeUnit.SECONDS))
        assertFalse(resetFinished.get())
        release.countDown()
        consumer.join(2000)
        resetter.join(2000)
        assertTrue(resetFinished.get())
        assertFalse(epoch.applyIfCurrent(ticket) { fail("초기화 이전 결과") })
    }
}

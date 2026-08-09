package com.example.trex_kotlin.camera

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseCameraTerminalCleanupTest {
    @Test
    fun concurrentTerminalClaimsRunCleanupExactlyOnce() {
        val cleanup = PoseCameraTerminalCleanup()
        val callers = 16
        val ready = CountDownLatch(callers)
        val start = CountDownLatch(1)
        val completed = CountDownLatch(callers)
        val cleanupCount = AtomicInteger(0)

        repeat(callers) {
            Thread {
                ready.countDown()
                start.await()
                cleanup.terminate({ cleanupCount.incrementAndGet() })
                completed.countDown()
            }.start()
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(1, cleanupCount.get())
        assertFalse(cleanup.terminate({ cleanupCount.incrementAndGet() }))
        assertFalse(cleanup.runIfActive { cleanupCount.incrementAndGet() })
        assertEquals(1, cleanupCount.get())
    }

    @Test
    fun throwingCleanupAndCallbacksCannotStrandLaterTerminalSteps() {
        val cleanup = PoseCameraTerminalCleanup()
        val events = Collections.synchronizedList(mutableListOf<String>())

        assertTrue(
            cleanup.terminate(
                {
                    events += "clear-analyzer"
                    error("clear failed")
                },
                { events += "null-and-unbind-owned-use-cases" },
                {
                    events += "close-observer"
                    error("observer close failed")
                },
                { events += "close-landmarker" },
                { events += "shutdown-executor" },
                {
                    events += "error-callback"
                    error("callback failed")
                },
                { events += "stopped-callback" },
            ),
        )

        assertEquals(
            listOf(
                "clear-analyzer",
                "null-and-unbind-owned-use-cases",
                "close-observer",
                "close-landmarker",
                "shutdown-executor",
                "error-callback",
                "stopped-callback",
            ),
            events,
        )
    }

    @Test
    fun readyCallbackFailureLeavesGateAvailableForTerminalCleanup() {
        val cleanup = PoseCameraTerminalCleanup()
        val failure = IllegalStateException("ready callback failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            cleanup.runIfActive { throw failure }
        }

        assertTrue(thrown === failure)
        assertTrue(cleanup.terminate({}))
    }
}

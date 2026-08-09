package com.example.trex_kotlin.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraCaptureTimestampGateTest {
    @Test
    fun increasingSensorTimesAreFlooredWithoutFabricatingEvidenceTime() {
        val gate = CameraCaptureTimestampGate()

        assertEquals(10L, gate.accept(10_100_000L))
        assertNull(gate.accept(10_900_000L))
        assertEquals(11L, gate.accept(11_000_000L))
    }

    @Test
    fun duplicateRegressionOrNegativeSensorTimeFailsClosed() {
        val gate = CameraCaptureTimestampGate()
        assertEquals(20L, gate.accept(20_000_000L))

        assertThrows(IllegalArgumentException::class.java) { gate.accept(20_000_000L) }
        assertThrows(IllegalArgumentException::class.java) { gate.accept(19_000_000L) }
        assertThrows(IllegalArgumentException::class.java) {
            CameraCaptureTimestampGate().accept(-1L)
        }
    }
}

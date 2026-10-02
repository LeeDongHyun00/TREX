package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BreathingTest {
    @Test fun squatInhalesGoingDownAndExhalesComingUp() {
        assertEquals(Breathing.INHALE, Breathing.word("바벨 스쿼트", -1))
        assertEquals(Breathing.EXHALE, Breathing.word("바벨 스쿼트", 1))
    }
    @Test fun curlExhalesTowardContraction() {
        assertEquals(Breathing.EXHALE, Breathing.word("덤벨 컬", -1))
        assertEquals(Breathing.INHALE, Breathing.word("덤벨 컬", 1))
    }
    @Test fun unknownDirectionSaysNothing() { assertNull(Breathing.word("바벨 스쿼트", 0)) }
}

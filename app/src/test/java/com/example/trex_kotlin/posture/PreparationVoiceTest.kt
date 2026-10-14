package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 준비 안내 문장 계획(spec §101, 10-08 '설명이 끝나야 카운트다운') — 음절·예측 길이·ESS/PLACE/DET 선택·카운트 음성 방식·종목별 예산. */
class PreparationVoiceTest {
    @Test fun syllablesCountHangulAndKoreanNumberReading() {
        assertEquals(17, PrepSpeech.syllables("무릎을 접은 채 골반 높이까지 올려야 세요."))
        assertEquals(15, PrepSpeech.syllables("앞무릎을 80도 가까이 굽혀 주세요."))   // 80 = 팔십(2)
        assertEquals(3, PrepSpeech.syllables("45"))     // 사십오
        assertEquals(1, PrepSpeech.syllables("10"))     // 십
        assertEquals(1, PrepSpeech.syllables("100"))    // 백
        assertEquals(0, PrepSpeech.syllables("abc, ..."))
        assertEquals(410L + 194L * 17, PrepSpeech.predictMs("무릎을 접은 채 골반 높이까지 올려야 세요."))
    }

    @Test fun essentialLinesStayWithinTheBudgetAndFinishBeforeTheEarliestStart() {
        // 이미 준비된 사용자의 최선 시작 ≈ 5.4 s — ESS(≤ 24음절 ≈ 5.07 s)는 전 종목 시작 전에 끝난다
        for ((name, line) in ESSENTIAL_LINES) {
            assertTrue("$name ESS ${PrepSpeech.syllables(line)}음절", PrepSpeech.syllables(line) <= 24)
            assertTrue("$name ESS 길이", PrepSpeech.predictMs(line) <= 5_400L)
        }
        for ((name, lines) in DETAIL_LINES) for (l in lines) assertTrue("$name DET '$l' ${PrepSpeech.syllables(l)}음절", PrepSpeech.syllables(l) <= 22)
        for ((name, l) in PLACE_LINES) assertTrue("$name PLACE", PrepSpeech.syllables(l) <= 32)
    }

    @Test fun planSaysEssentialFirstPlaceOnlyWhenUnsettledAndDetailOnlyIfItFinishesInTime() {
        val lines = listOf(PrepLine(PrepKind.ESS, "핵심."), PrepLine(PrepKind.PLACE, "자리."), PrepLine(PrepKind.DET, "세부 문장 하나."))
        // 아직 자리 안 맞음, 카운트 아님, 시작까지 여유 → ESS, PLACE, DET 순
        assertEquals(0, PreparationVoicePlan.nextLine(lines, 0, 0, settledRecommended = false, counting = false, started = false, earliestStartMs = 20_000))
        assertEquals(1, PreparationVoicePlan.nextLine(lines, 1, 0, settledRecommended = false, counting = false, started = false, earliestStartMs = 20_000))
        assertEquals(2, PreparationVoicePlan.nextLine(lines, 2, 0, settledRecommended = false, counting = false, started = false, earliestStartMs = 20_000))
        // 자리 맞음 → PLACE 건너뜀
        assertEquals(2, PreparationVoicePlan.nextLine(lines, 1, 0, settledRecommended = true, counting = false, started = false, earliestStartMs = 20_000))
        // 카운트 중 → PLACE·DET 모두 안 함(ESS 는 늘)
        assertNull(PreparationVoicePlan.nextLine(lines, 1, 0, settledRecommended = false, counting = true, started = false, earliestStartMs = 20_000))
        assertEquals(0, PreparationVoicePlan.nextLine(lines, 0, 0, settledRecommended = true, counting = true, started = false, earliestStartMs = 0))
        // DET 는 가장 이른 시작 전에 끝날 때만
        assertNull(PreparationVoicePlan.nextLine(lines, 2, 10_000, settledRecommended = true, counting = false, started = false, earliestStartMs = 10_500))
        // 시작됐으면 아무것도
        assertNull(PreparationVoicePlan.nextLine(lines, 0, 0, settledRecommended = false, counting = false, started = true, earliestStartMs = 20_000))
    }

    @Test fun countdownIsTonesWhileTheIntroPlaysAndNumbersOtherwise() {
        assertEquals(CountdownVoice.Mode.TONE, CountdownVoice.mode(introPlayingAtEntry = true, ttsReady = true, muted = false))
        assertEquals(CountdownVoice.Mode.SPEAK, CountdownVoice.mode(introPlayingAtEntry = false, ttsReady = true, muted = false))
        assertEquals(CountdownVoice.Mode.TONE, CountdownVoice.mode(introPlayingAtEntry = false, ttsReady = false, muted = false))
        assertEquals(CountdownVoice.Mode.NONE, CountdownVoice.mode(introPlayingAtEntry = true, ttsReady = true, muted = true))
    }

    @Test fun profilesPlanEssentialThenPlaceThenDetailAndKeepTheFullTextForTheScreen() {
        val kneeUp = ExerciseProfiles.all.first { it.name == "스탠딩 니업" }
        val lines = kneeUp.preparationLines()
        assertEquals(PrepKind.ESS, lines.first().kind); assertEquals("무릎을 접은 채 골반 높이까지 올려야 세요.", lines.first().text)
        assertEquals(PrepKind.PLACE, lines[1].kind)
        assertTrue(lines.drop(2).all { it.kind == PrepKind.DET })
        assertTrue(kneeUp.countingConditions!!.contains("1회예요"))
        // 약속 문장은 음성 계획에 없다(화면 고정 문구로) — 화면 전문(preparationInstruction)엔 남는다
        assertTrue(lines.none { it.text.contains("3초") }); assertTrue(kneeUp.preparationInstruction.contains("3초"))
        // 사이드 런지만 옆 공간·가로 전체
        assertEquals(0.9f, ExerciseProfiles.all.first { it.name == "사이드 런지" }.lateralReach)
        assertTrue(ExerciseProfiles.all.first { it.name == "사이드 런지" }.stageFullWidth)
        assertNull(kneeUp.lateralReach)
    }
}

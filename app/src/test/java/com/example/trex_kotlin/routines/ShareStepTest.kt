package com.example.trex_kotlin.routines

import com.example.trex_kotlin.community.CommunityGate
import org.junit.Assert.assertEquals
import org.junit.Test

/** 공유 창이 무엇을 보일지 — 커뮤니티 상태를 받는 동안에는 "준비를 마치면…" 대신 기다림(실기기에서 잠깐 잘못된 안내가 보였다). */
class ShareStepTest {

    @Test
    fun stepFollowsWorkoutsAccountAndGate() {
        assertEquals(ShareStep.NoWorkouts, shareStep(hasWorkouts = false, signedIn = true, gate = CommunityGate.Ready(false)))
        assertEquals(ShareStep.NeedLogin, shareStep(hasWorkouts = true, signedIn = false, gate = CommunityGate.NotSignedIn))
        assertEquals(ShareStep.Loading, shareStep(hasWorkouts = true, signedIn = true, gate = CommunityGate.Loading))
        assertEquals(ShareStep.Suspended, shareStep(hasWorkouts = true, signedIn = true, gate = CommunityGate.Suspended(false)))
        assertEquals(ShareStep.NeedCommunitySetup, shareStep(hasWorkouts = true, signedIn = true, gate = CommunityGate.NeedNickname))
        assertEquals(ShareStep.NeedCommunitySetup, shareStep(hasWorkouts = true, signedIn = true, gate = CommunityGate.NeedTerms))
        assertEquals(ShareStep.Ready, shareStep(hasWorkouts = true, signedIn = true, gate = CommunityGate.Ready(false)))
    }
}

package com.example.trex_kotlin.community

import org.junit.Assert.assertEquals
import org.junit.Test

/** 커뮤니티 탭 진입 게이트 — 로그인 설정·계정·서버의 내 상태로 정해진다(설계 §5 진입, 계획 3 Review Focus 1·5). */
class CommunityGateTest {
    private val profile = CommunityProfile("abc123def456", "티렉스", null)
    private fun me(profile: CommunityProfile? = this.profile, terms: Boolean = true, admin: Boolean = false, suspended: Boolean = false) =
        CommunityMe(profile, terms, admin, suspended)

    @Test
    fun 게이트는_계정과_설정으로_정해진다() {
        // iPhone 에서 웹 클라이언트 ID 가 빈 빌드 — 계정이 남아 있어도 안내만
        assertEquals(CommunityGate.NoLogin, communityGate(loginAvailable = false, signedIn = true, me = me()))
        assertEquals(CommunityGate.NotSignedIn, communityGate(loginAvailable = true, signedIn = false, me = null))
        assertEquals(CommunityGate.Loading, communityGate(loginAvailable = true, signedIn = true, me = null))
        assertEquals(CommunityGate.NeedNickname, communityGate(true, true, me(profile = null, terms = false)))
        assertEquals(CommunityGate.NeedTerms, communityGate(true, true, me(terms = false)))
        assertEquals(CommunityGate.Ready(admin = false), communityGate(true, true, me()))
        assertEquals(CommunityGate.Ready(admin = true), communityGate(true, true, me(admin = true)))
        assertEquals(CommunityGate.Suspended(admin = false), communityGate(true, true, me(suspended = true)))
    }

    @Test
    fun 로그아웃하면_예전_내_상태는_무시한다() {
        assertEquals(CommunityGate.NotSignedIn, communityGate(loginAvailable = true, signedIn = false, me = me()))
    }

    @Test
    fun 새_이용규칙은_정지보다_먼저_묻는다() {
        // 서버가 읽기에도 동의를 요구하니(requireReader) 정지된 사람도 동의부터
        assertEquals(CommunityGate.NeedTerms, communityGate(true, true, me(terms = false, suspended = true)))
    }

    @Test
    fun 글을_쓸_수_있는지() {
        assertEquals(true, CommunityGate.Ready(admin = false).canWrite)
        assertEquals(false, CommunityGate.Suspended(admin = false).canWrite)
        assertEquals(false, CommunityGate.NeedTerms.canWrite)
    }
}

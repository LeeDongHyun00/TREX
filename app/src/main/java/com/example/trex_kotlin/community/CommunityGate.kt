package com.example.trex_kotlin.community

/**
 * 커뮤니티 탭에 들어올 때 무엇을 보일지(설계 §5 진입). 커뮤니티만 서버 계정이 필요하다 — 다른 탭은 계정 없이도 그대로 쓴다.
 * 정지된 사용자는 읽기만 되고([canWrite] 거짓) 쓰기 버튼은 "이용이 정지된 계정이에요"를 보인다.
 */
sealed interface CommunityGate {
    /** 이 빌드에 서버 주소가 없다 — "이 기기에서는 아직 커뮤니티를 쓸 수 없어요". */
    data object NoLogin : CommunityGate
    /** 계정을 연결하지 않았다 — 내 정보의 계정 연결로 안내한다. */
    data object NotSignedIn : CommunityGate
    /** 서버의 내 상태를 아직 받지 못했다. */
    data object Loading : CommunityGate
    data object NeedNickname : CommunityGate
    /** 이용규칙 동의(만 14세 이상 확인 포함) — 새 버전이 나오면 다시 묻는다. */
    data object NeedTerms : CommunityGate
    data class Ready(val admin: Boolean) : CommunityGate
    data class Suspended(val admin: Boolean) : CommunityGate

    val canWrite: Boolean get() = this is Ready

    val isAdmin: Boolean get() = (this as? Ready)?.admin == true || (this as? Suspended)?.admin == true
}

/**
 * [loginAvailable] = 서버 주소가 있는지(아이디로 가입할 수 있다, D13), [signedIn] = 서버 계정이 있는지, [me] = 서버의 내 상태(null 이면 아직 모름).
 * 동의는 정지보다 먼저 — 서버가 읽기에도 동의를 요구한다.
 */
fun communityGate(loginAvailable: Boolean, signedIn: Boolean, me: CommunityMe?): CommunityGate = when {
    !loginAvailable -> CommunityGate.NoLogin
    !signedIn -> CommunityGate.NotSignedIn
    me == null -> CommunityGate.Loading
    me.profile == null -> CommunityGate.NeedNickname
    !me.termsAccepted -> CommunityGate.NeedTerms
    me.suspended -> CommunityGate.Suspended(me.admin)
    else -> CommunityGate.Ready(me.admin)
}

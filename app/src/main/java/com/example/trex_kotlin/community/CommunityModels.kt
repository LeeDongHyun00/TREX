package com.example.trex_kotlin.community

/*
 * 커뮤니티 서버 응답(설계 docs/superpowers/specs/2026-10-28-community-design.md §6) — 서버 DTO 와 같은 이름·의미.
 * 시각은 epoch ms. 다른 사용자에게 보이는 식별자는 publicId·닉네임·프로필 사진뿐이다(구글 실명·이메일은 오지 않는다).
 */

data class CommunityProfile(val publicId: String, val nickname: String, val avatarUrl: String?)

/** 커뮤니티 진입 상태 — 프로필이 없으면 닉네임부터, 이용규칙에 동의하지 않았으면 동의부터. */
data class CommunityMe(val profile: CommunityProfile?, val termsAccepted: Boolean, val admin: Boolean, val suspended: Boolean)

/** 글 사진 한 장 — [url] 은 S3 서명 주소(수명 1시간)라 캐시 키로 쓰지 않는다. */
data class PostImage(val url: String, val width: Int, val height: Int)

data class Post(
    val id: Long,
    val author: CommunityProfile,
    val body: String?,
    val images: List<PostImage>,
    val likeCount: Long,
    val likedByMe: Boolean,
    val commentCount: Long,
    val createdAt: Long,
    val hidden: Boolean,
    /** 숨긴 글을 작성자·관리자가 볼 때만 — "숨김 처리됨 · 사유". */
    val hiddenReason: String?,
    val mine: Boolean,
)

/** 글 한 쪽 — 다음 쪽 커서([nextBefore]), 없으면 끝. */
data class PostPage(val items: List<Post>, val nextBefore: Long?)

data class Comment(
    val id: Long,
    val author: CommunityProfile,
    val body: String,
    val createdAt: Long,
    val hidden: Boolean,
    val hiddenReason: String?,
    val mine: Boolean,
)

/** 댓글 한 쪽(오래된 순) — 더 오래된 댓글 커서([nextBefore]). */
data class CommentPage(val items: List<Comment>, val nextBefore: Long?)

data class UserProfile(val profile: CommunityProfile, val postCount: Long, val blockedByMe: Boolean)

enum class ReportTarget { POST, COMMENT, USER }

/** 신고 사유 — 이름은 서버 enum 과 같다. */
enum class ReportReason(val label: String) {
    SPAM("스팸·광고"),
    ABUSE("욕설·혐오"),
    SEXUAL_VIOLENT("음란·폭력"),
    PRIVACY("개인정보 노출"),
    RIGHTS("권리 침해"),
    OTHER("기타"),
    ;

    companion object {
        /** 서버가 사유를 늘려도 옛 앱이 죽지 않게 모르는 값은 기타로. */
        fun from(name: String): ReportReason = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** 내가 한 신고 — 처리 결과([resolution] "KEPT"·"REMOVED")를 앱 안에서 보인다. */
data class MyReport(
    val id: Long,
    val target: ReportTarget,
    val targetId: String,
    val reason: ReportReason,
    val createdAt: Long,
    val resolved: Boolean,
    val resolution: String?,
)

/** 관리자 신고함 한 줄(대상별로 묶임). [hidden] 은 글·댓글 숨김, 사용자면 정지. */
data class AdminReport(
    val target: ReportTarget,
    val targetId: String,
    val preview: String,
    val reportCount: Long,
    val reasons: List<ReportReason>,
    val hidden: Boolean,
    val firstReportedAt: Long,
)

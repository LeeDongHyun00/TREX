package com.example.trex_kotlin.community

/**
 * 피드(또는 한 사람의 글 목록) 상태 — 순수 값이라 JVM 테스트가 잠근다(계획 3 Task 3).
 *
 * [posts] 는 서버가 확인한 글, [pendingLikes] 는 사용자가 바라는 좋아요(아직 서버가 확인하지 않음). 화면은 [shown] 으로
 * 둘을 합쳐 그린다 — 누르는 즉시 바뀌고, 실패하면 서버 값으로 돌아간다. 빠르게 여러 번 눌러도 보내는 쪽은 한 번에 하나만
 * 보내고, 돌아오면 [likeToSend] 로 아직 다른지 다시 본다(설계 §5 좋아요, Review Focus 2).
 */
data class FeedState(
    val posts: List<Post> = emptyList(),
    val nextBefore: Long? = null,
    val end: Boolean = false,
    /** 첫 쪽을 받은 시각(epoch ms) — 사진 서명 주소의 수명(1시간) 판단용. null 이면 받은 적 없다. */
    val loadedAt: Long? = null,
    val pendingLikes: Map<Long, Boolean> = emptyMap(),
) {
    fun withFirstPage(page: PostPage, now: Long): FeedState =
        FeedState(page.items.distinctBy { it.id }, page.nextBefore, page.nextBefore == null, now, pendingLikes.filterKeys { id -> page.items.any { it.id == id } })

    /** 다음 쪽 — 넘기는 사이 앞쪽 글이 지워지면 이미 본 글이 다시 오니 같은 id 는 한 번만(Review Focus 3). */
    fun withNextPage(page: PostPage): FeedState {
        val seen = posts.mapTo(HashSet()) { it.id }
        return copy(posts = posts + page.items.filter { seen.add(it.id) }, nextBefore = page.nextBefore, end = page.nextBefore == null)
    }

    fun shown(id: Long): Post? = posts.firstOrNull { it.id == id }?.let(::shown)

    fun shown(post: Post): Post {
        val want = pendingLikes[post.id] ?: return post
        if (want == post.likedByMe) return post
        return post.copy(likedByMe = want, likeCount = (post.likeCount + if (want) 1 else -1).coerceAtLeast(0))
    }

    fun pendingLike(id: Long): Boolean? = pendingLikes[id]

    /** 서버에 보낼 좋아요 — 바라는 값이 서버가 아는 값과 다를 때만. */
    fun likeToSend(id: Long): Boolean? {
        val want = pendingLikes[id] ?: return null
        return want.takeIf { want != posts.firstOrNull { it.id == id }?.likedByMe }
    }

    fun tapLike(id: Long): FeedState {
        val current = shown(id) ?: return this
        return copy(pendingLikes = pendingLikes + (id to !current.likedByMe))
    }

    /** 서버가 [liked] 를 확인했다. 그사이 다시 눌러 바라는 값이 다르면 남겨 두고 이어서 보낸다. */
    fun likeConfirmed(id: Long, liked: Boolean): FeedState {
        val updated = posts.map { post ->
            if (post.id != id || post.likedByMe == liked) post
            else post.copy(likedByMe = liked, likeCount = (post.likeCount + if (liked) 1 else -1).coerceAtLeast(0))
        }
        return copy(posts = updated, pendingLikes = if (pendingLikes[id] == liked) pendingLikes - id else pendingLikes)
    }

    /** 보내기가 실패했다 — 서버가 아는 값으로 돌아간다. */
    fun likeFailed(id: Long): FeedState = copy(pendingLikes = pendingLikes - id)

    /** 보낼 것이 없으면(바라는 값 = 서버 값) 정리한다 — 보내는 쪽이 멈추기 전에 부른다. */
    fun settleLike(id: Long): FeedState = if (likeToSend(id) == null) copy(pendingLikes = pendingLikes - id) else this

    fun removed(id: Long): FeedState = copy(posts = posts.filterNot { it.id == id }, pendingLikes = pendingLikes - id)

    /** 서버에서 새로 받은 글로 갈아 끼운다 — 화면 값([shown])을 넣지 않는다(확인 안 된 좋아요가 확인된 것처럼 굳는다). */
    fun replaced(post: Post): FeedState = copy(posts = posts.map { if (it.id == post.id) post else it })

    /** 상세 화면에서 댓글을 달거나 지웠다 — 댓글 수만 바꾼다. */
    fun commentsChanged(id: Long, delta: Int): FeedState =
        copy(posts = posts.map { if (it.id == id) it.copy(commentCount = (it.commentCount + delta).coerceAtLeast(0)) else it })

    /** 사진 서명 주소가 곧 만료된다(1시간) — 탭에 돌아오면 새로 받는다. */
    fun isStale(now: Long): Boolean = loadedAt == null || now - loadedAt >= STALE_AFTER_MS

    companion object {
        const val STALE_AFTER_MS = 50 * 60_000L
    }
}

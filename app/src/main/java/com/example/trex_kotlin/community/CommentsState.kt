package com.example.trex_kotlin.community

/**
 * 글 상세의 댓글 목록(계획 3 Task 4) — 서버는 최근 50개를 오래된 순으로 주고, 더 오래된 것은 [olderBefore] 커서로 준다.
 * "이전 댓글 보기"는 앞에, 내가 쓴 댓글은 끝에 붙는다. 같은 id 는 한 번만.
 */
data class CommentsState(
    val items: List<Comment> = emptyList(),
    val olderBefore: Long? = null,
) {
    val hasOlder: Boolean get() = olderBefore != null

    fun withFirstPage(page: CommentPage): CommentsState = CommentsState(page.items.distinctBy { it.id }, page.nextBefore)

    fun withOlderPage(page: CommentPage): CommentsState {
        val seen = items.mapTo(HashSet()) { it.id }
        return CommentsState(page.items.filter { seen.add(it.id) } + items, page.nextBefore)
    }

    fun added(comment: Comment): CommentsState =
        if (items.any { it.id == comment.id }) this else copy(items = items + comment)

    fun removed(id: Long): CommentsState = copy(items = items.filterNot { it.id == id })

    companion object {
        /** 서버 CommentService.MAX_BODY 와 같다. */
        const val MAX_BODY = 500
    }
}

fun canSendComment(text: String): Boolean = text.isNotBlank() && codePointLength(text.trim()) <= CommentsState.MAX_BODY

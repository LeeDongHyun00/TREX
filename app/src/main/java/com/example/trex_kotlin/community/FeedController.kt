package com.example.trex_kotlin.community

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.NetworkException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 글 목록 하나(전체 피드 또는 한 사람의 글)의 받기·좋아요·지우기 — 상태 계산은 [FeedState], 여기는 서버 호출 순서만.
 * 좋아요는 글마다 한 번에 하나만 보내고, 돌아오면 아직 바라는 값과 다른지 다시 본다(빠른 연타에도 마지막 값으로 맞는다).
 * 오류 문구는 [message] — 서버가 거절하면 서버 문구, 연결 실패면 "네트워크에 연결하지 못했어요".
 */
class FeedController(
    private val load: suspend (before: Long?) -> PostPage,
    private val setLike: suspend (postId: Long, liked: Boolean) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    var state by mutableStateOf(FeedState())
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private val sendingLikes = HashSet<Long>()

    /** 처음·당겨서 새로고침·사진 주소가 곧 만료될 때([FeedState.isStale]). */
    suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            state = state.withFirstPage(load(null), now())
            message = null
        } catch (e: Exception) {
            report(e)
        } finally {
            refreshing = false
        }
    }

    /** 목록 끝에 닿았다 — 다음 쪽. 끝이거나 받는 중이면 아무것도 하지 않는다. */
    suspend fun loadMore() {
        val before = state.nextBefore ?: return
        if (loading || state.end) return
        loading = true
        try {
            state = state.withNextPage(load(before))
        } catch (e: Exception) {
            report(e)
        } finally {
            loading = false
        }
    }

    fun toggleLike(postId: Long) {
        state = state.tapLike(postId)
        if (!sendingLikes.add(postId)) return
        scope.launch {
            try {
                while (true) {
                    val want = state.likeToSend(postId) ?: break
                    try {
                        setLike(postId, want)
                        state = state.likeConfirmed(postId, want)
                    } catch (e: Exception) {
                        state = state.likeFailed(postId)
                        report(e)
                        break
                    }
                }
                state = state.settleLike(postId)
            } finally {
                sendingLikes.remove(postId)
            }
        }
    }

    fun removed(postId: Long) { state = state.removed(postId) }

    fun replaced(post: Post) { state = state.replaced(post) }

    fun clearMessage() { message = null }

    /** 계정이 바뀌었다 — 앞 계정의 목록·좋아요 표시를 버린다. 보내는 중인 좋아요는 돌아와도 빈 목록이라 아무것도 바꾸지 않는다. */
    fun reset() {
        state = FeedState()
        message = null
    }

    private fun report(e: Exception) {
        message = when (e) {
            is ApiException -> e.message // ApiException 이 NetworkException 의 하위라 먼저 본다
            is NetworkException -> "네트워크에 연결하지 못했어요"
            else -> throw e
        }
    }
}

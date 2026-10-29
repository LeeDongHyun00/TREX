package com.example.trex_kotlin.community

/**
 * 글쓰기 입력(설계 §5 글쓰기) — 사진 0~5장과 본문(2,000자), 둘 중 하나는 있어야 올린다. 올리기가 실패해도(네트워크·429)
 * 입력은 그대로 두고 서버 문구만 보인다(Review Focus 4). [P] 는 사진 타입(앱은 FoodPhoto 또는 오운완 카드, 테스트는 문자열).
 */
data class ComposeState<P>(
    val body: String = "",
    val photos: List<P> = emptyList(),
    val posting: Boolean = false,
    val error: String? = null,
    /** 오운완 카드를 첫 사진으로 올린다(사진 한 칸을 쓴다, 계획 3 Task 8). */
    val card: Boolean = false,
) {
    val photosLeft: Int get() = MAX_PHOTOS - photos.size - (if (card) 1 else 0)

    val bodyToSend: String? get() = body.trim().ifEmpty { null }

    val canPost: Boolean get() = !posting && (bodyToSend != null || photos.isNotEmpty() || card)

    /** 본문은 서버처럼 글자(코드 포인트) 수로 자른다 — 이모지를 반으로 가르지 않는다. 고치기 시작하면 실패 문구를 내린다. */
    fun withBody(text: String): ComposeState<P> = copy(body = takeCodePoints(text, MAX_BODY), error = null)

    fun addPhotos(more: List<P>): ComposeState<P> = copy(photos = photos + more.take(photosLeft), error = null)

    fun withoutCard(): ComposeState<P> = copy(card = false)

    fun removePhoto(index: Int): ComposeState<P> = copy(photos = photos.filterIndexed { i, _ -> i != index })

    fun started(): ComposeState<P> = copy(posting = true, error = null)

    fun failed(message: String): ComposeState<P> = copy(posting = false, error = message)

    companion object {
        /** 서버 PostService.MAX_IMAGES·MAX_BODY 와 같다. */
        const val MAX_PHOTOS = 5
        const val MAX_BODY = 2000
    }
}

/** 서버(Java `codePointCount`)와 같은 글자 수 — 이모지 같은 서로게이트 쌍은 한 글자. */
fun codePointLength(text: String): Int = text.count { !it.isLowSurrogate() }

internal fun takeCodePoints(text: String, max: Int): String {
    var count = 0
    for (i in text.indices) {
        if (text[i].isLowSurrogate()) continue
        if (count == max) return text.substring(0, i)
        count++
    }
    return text
}

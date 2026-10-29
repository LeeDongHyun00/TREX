package com.example.trex_kotlin.community

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.material3.Text as UserText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.AssetImageCache
import com.example.trex_kotlin.rememberAssetImageDecoder
import com.example.trex_kotlin.Trex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** 카드 ⋯ 메뉴 한 줄 — [destructive] 면 빨간 글자(삭제·차단). */
class PostMenuItem(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * 피드 카드(설계 §2 피드, 인스타그램 벤치마킹) — 작성자 줄, 사진(좌우로 넘기고 점, 두 번 탭하면 좋아요), 하트·댓글, 좋아요 수,
 * 본문(길면 접기), 댓글 수, 시각. 앱 문구는 공룡 말투([com.example.trex_kotlin.TrexText]), 사용자 글·닉네임은 원문([UserText]).
 * [post] 는 좋아요 낙관적 갱신이 합쳐진 값([FeedState.shown]).
 */
@Composable
fun PostCard(
    post: Post,
    now: Long,
    onLike: () -> Unit,
    onOpen: () -> Unit,
    onAuthor: () -> Unit,
    menu: List<PostMenuItem>,
    modifier: Modifier = Modifier,
    expandedBody: Boolean = false,
) {
    val c = Trex.c
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = c.surface) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(onClick = onAuthor), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(post.author, 34)
                    Column(Modifier.padding(start = 10.dp)) {
                        UserText(post.author.nickname, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(timeAgo(post.createdAt, now), color = c.text3, fontSize = 12.sp)
                    }
                }
                if (menu.isNotEmpty()) MenuButton(menu)
            }
            if (post.hidden) HiddenBadge(post.hiddenReason, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
            if (post.images.isNotEmpty()) Photos(post, onDoubleTap = { if (!post.likedByMe) onLike() })
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLike) {
                    Icon(
                        if (post.likedByMe) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (post.likedByMe) "좋아요 취소" else "좋아요",
                        tint = if (post.likedByMe) HeartRed else c.text,
                    )
                }
                IconButton(onClick = onOpen) { Icon(Icons.Rounded.ChatBubbleOutline, "댓글", tint = c.text) }
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (post.likeCount > 0) Text("좋아요 ${post.likeCount}개", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                post.body?.let { PostBody(it, expandedBody) }
                if (post.commentCount > 0) Text(
                    "댓글 ${post.commentCount}개 보기", color = c.text3, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onOpen),
                )
            }
        }
    }
}

/** 기본 프로필 그림 — 출처·라이선스는 같은 폴더의 NOTICE.txt·CHROMIUM-LICENSE.txt. */
internal const val DEFAULT_AVATAR = "community/trex_avatar.png"

private val defaultAvatarCache = AssetImageCache(1)

/** 기본 공룡 — 88px 픽셀 그림이라 줄이지 않고 읽어 픽셀 그대로 키운다(흐리게 번지지 않게). */
@Composable
private fun DefaultAvatar(modifier: Modifier) {
    val decoder = rememberAssetImageDecoder()
    val bitmap by produceState(defaultAvatarCache.get(DEFAULT_AVATAR)) {
        if (value == null) value = withContext(Dispatchers.IO) {
            decoder.decode(DEFAULT_AVATAR, 1).also { defaultAvatarCache.put(DEFAULT_AVATAR, it) }
        }
    }
    bitmap?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None) }
}

/** 하트 빨강 — 테마의 오류 색([com.example.trex_kotlin.TrexColors.err])은 경고 뜻이라 따로 둔다. */
private val HeartRed = Color(0xFFED4956)

@Composable
internal fun Avatar(profile: CommunityProfile, sizeDp: Int) {
    val c = Trex.c
    val modifier = Modifier.size(sizeDp.dp).clip(CircleShape)
    val url = profile.avatarUrl
    if (url != null) {
        RemoteImage(url, contentDescription = null, modifier = modifier, maxDimension = 160)
    } else {
        // 기본 그림 — Chromium 오프라인 공룡(BSD-3, assets/community/NOTICE.txt). 회색 공룡이 어두운 테마에서도 보이게 밝은 바탕.
        Box(modifier.background(c.primaryWash), contentAlignment = Alignment.Center) {
            DefaultAvatar(Modifier.padding((sizeDp * 0.18f).dp).fillMaxSize())
        }
    }
}

@Composable
internal fun HiddenBadge(reason: String?, modifier: Modifier = Modifier) {
    val c = Trex.c
    Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = c.warnWash) {
        Text(
            "숨김 처리됨 · ${reason ?: "신고 누적"}", color = c.warn, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun MenuButton(items: List<PostMenuItem>) {
    val c = Trex.c
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreHoriz, "더 보기", tint = c.text2) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.sheet) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { UserText(item.label, color = if (item.destructive) c.err else c.text) },
                    onClick = { open = false; item.onClick() },
                )
            }
        }
    }
}

/** 사진 — 첫 사진 비율(세로 4:5 ~ 가로 1.91:1 로 자름)로 자리를 잡고 좌우로 넘긴다. 두 번 탭하면 하트가 커졌다 사라진다. */
@Composable
private fun Photos(post: Post, onDoubleTap: () -> Unit) {
    val c = Trex.c
    val first = post.images.first()
    val ratio = (first.width.toFloat() / first.height.coerceAtLeast(1)).coerceIn(0.8f, 1.91f)
    val pager = rememberPagerState { post.images.size }
    var burst by remember { mutableIntStateOf(0) }
    val heart = remember { Animatable(0f) }
    LaunchedEffect(burst) {
        if (burst == 0) return@LaunchedEffect
        heart.snapTo(0.6f)
        heart.animateTo(1.15f, tween(160))
        heart.animateTo(1f, tween(120))
        kotlinx.coroutines.delay(350)
        heart.animateTo(0f, tween(180))
    }
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(c.surface2)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().pointerInput(post.id) {
                detectTapGestures(onDoubleTap = { burst++; onDoubleTap() })
            },
            key = { post.images[it].url.substringBefore('?') },
        ) { page ->
            RemoteImage(post.images[page].url, contentDescription = "사진 ${page + 1}/${post.images.size}", modifier = Modifier.fillMaxSize())
        }
        if (heart.value > 0f) Icon(
            Icons.Rounded.Favorite, null, tint = Color.White,
            modifier = Modifier.align(Alignment.Center).size(96.dp).scale(heart.value).alpha(heart.value.coerceAtMost(1f)),
        )
        if (post.images.size > 1) Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            shape = RoundedCornerShape(999.dp), color = Color.Black.copy(alpha = 0.55f),
        ) {
            Text("${pager.currentPage + 1}/${post.images.size}", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
        }
    }
    if (post.images.size > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
        repeat(post.images.size) { i ->
            Box(Modifier.padding(horizontal = 3.dp).size(6.dp).clip(CircleShape).background(if (i == pager.currentPage) c.primary else c.track))
        }
    }
}

/** 본문 — 피드에서는 세 줄까지, 넘치면 "더 보기"로 펼친다. 상세에서는 다 보인다. */
@Composable
private fun PostBody(body: String, expandedAlways: Boolean) {
    val c = Trex.c
    var expanded by remember(body) { mutableStateOf(expandedAlways) }
    var overflows by remember(body) { mutableStateOf(false) }
    UserText(
        body, color = c.text, fontSize = 14.sp, lineHeight = 20.sp,
        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
    )
    if (!expanded && overflows) Text("더 보기", color = c.text3, fontSize = 13.sp, modifier = Modifier.clickable { expanded = true })
}


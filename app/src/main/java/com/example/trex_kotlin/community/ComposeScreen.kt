package com.example.trex_kotlin.community

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.AppViewModel
import com.example.trex_kotlin.Cta
import com.example.trex_kotlin.DField
import com.example.trex_kotlin.FoodPhoto
import com.example.trex_kotlin.GhostButton
import com.example.trex_kotlin.LocalTrexNavSpace
import com.example.trex_kotlin.PlatformBackHandler
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.WashBanner
import com.example.trex_kotlin.encodeJpeg
import com.example.trex_kotlin.isOutOfMemory
import com.example.trex_kotlin.rememberCameraPermission
import com.example.trex_kotlin.rememberFoodCamera
import com.example.trex_kotlin.rememberFoodGallery
import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.NetworkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 글쓰기(설계 §2 목표 2·§5) — 글만·사진만·둘 다. 사진은 갤러리(최대 5장)나 카메라, 올릴 때 긴 변 1080 JPEG 로 다시 만든다
 * (EXIF 가 따라가지 않는다). 올리기가 실패하면(네트워크·429) 입력을 그대로 두고 서버 문구를 보인다(Review Focus 4).
 * [card] 는 운동 완료 화면의 "오운완 올리기" — 미리보기로 그린 카드를 떠서 첫 사진으로 올린다(계획 3 Task 8).
 */
@Composable
fun ComposeScreen(app: AppViewModel, onClose: () -> Unit, onPosted: (Post) -> Unit, card: WorkoutCardData? = null) {
    val c = Trex.c
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ComposeState<FoodPhoto>(card = card != null)) }
    var cardTitle by remember { mutableStateOf(card?.title.orEmpty()) }
    val shownCard = card?.withTitle(cardTitle)
    val cardLayer = rememberGraphicsLayer()
    var loadingPhotos by remember { mutableStateOf(false) }
    var cameraOpen by remember { mutableStateOf(false) }

    val openGallery = rememberFoodGallery(maxItems = ComposeState.MAX_PHOTOS) { loaders ->
        if (loaders.isEmpty()) return@rememberFoodGallery
        scope.launch {
            loadingPhotos = true
            // 디코딩(방향 반영)은 무거워서 IO 에서. 남은 칸보다 많이 골랐으면 앞에서부터.
            val wanted = loaders.take(state.photosLeft)
            val photos = withContext(Dispatchers.IO) { wanted.mapNotNull { runCatching { it.load() }.getOrNull() } }
            state = state.addPhotos(photos)
            if (photos.size < wanted.size) state = state.failed("일부 사진을 읽지 못했어요")
            loadingPhotos = false
        }
    }

    PlatformBackHandler(enabled = cameraOpen) { cameraOpen = false }
    if (cameraOpen) {
        CameraCapture(
            onPhoto = { state = state.addPhotos(listOf(it)); cameraOpen = false },
            onClose = { cameraOpen = false },
        )
        return
    }

    fun submit() {
        if (!state.canPost) return
        val snapshot = state
        state = state.started()
        scope.launch {
            try {
                val cardImage = if (snapshot.card) cardLayer.toImageBitmap() else null
                val jpegs = withContext(Dispatchers.IO) { listOfNotNull(cardImage?.let { encodeJpeg(it) }) + snapshot.photos.map { it.jpeg() } }
                val post = app.community.createPost(snapshot.bodyToSend, jpegs)
                app.communityFeed.posted(post)
                onPosted(post)
            } catch (e: ApiException) {
                state = state.failed(e.message ?: "올리지 못했어요") // 서버 문구 그대로(예: 잠시 뒤에 다시 올려 주세요)
            } catch (_: NetworkException) {
                state = state.failed("네트워크에 연결하지 못했어요. 입력한 내용은 그대로 있어요.")
            } catch (e: Throwable) {
                if (!isOutOfMemory(e)) throw e
                state = state.failed("사진이 너무 커서 준비하지 못했어요. 사진 수를 줄여 주세요.")
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, enabled = !state.posting) { Icon(Icons.Rounded.Close, "닫기", tint = c.text) }
            Text("새 게시물", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            // Cta 는 안쪽이 fillMaxWidth 라 남은 폭을 다 가져가 제목이 세로로 깨진다(실기기 2026-10-29) — 내용 폭만 쓰는 알약
            val canSubmit = state.canPost && !loadingPhotos
            Surface(
                onClick = ::submit,
                enabled = canSubmit,
                shape = RoundedCornerShape(999.dp),
                color = if (canSubmit) c.primary else c.track,
            ) {
                Text(
                    if (state.posting) "올리는 중…" else "올리기",
                    color = if (canSubmit) Color.White else c.text3, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = LocalTrexNavSpace.current),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 사진 식단의 "기기 안에서만" 과 헷갈리지 않게(설계 §2 반목표·§7)
            WashBanner("서버에 저장되고 모든 사용자에게 공개돼요", Icons.Rounded.Info)
            state.error?.let { Banner(it, c.errWash, c.err) }
            BasicTextField(
                value = state.body,
                onValueChange = { state = state.withBody(it) },
                enabled = !state.posting,
                textStyle = TextStyle(color = c.text, fontSize = 16.sp, lineHeight = 23.sp),
                cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 140.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).padding(16.dp),
                    ) {
                        if (state.body.isEmpty()) Text("오운완, 루틴, 대회 소식… 자유롭게 써 보세요", color = c.text3, fontSize = 16.sp)
                        inner()
                    }
                },
            )
            Text(
                "${codePointLength(state.body)} / ${ComposeState.MAX_BODY}", color = c.text3, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.End),
            )
            if (shownCard != null && state.card) DField(
                value = cardTitle,
                onValueChange = { cardTitle = takeCodePoints(it, WorkoutCardData.MAX_TITLE) },
                placeholder = WorkoutCardData.DEFAULT_TITLE,
                label = "카드 제목",
            )
            if (shownCard != null && state.card) Box {
                // 이 미리보기를 그대로 떠서 올린다 — 숫자만(자세 판정 없음, 설계 §3)
                WorkoutCard(
                    shownCard,
                    Modifier.clip(RoundedCornerShape(18.dp)).drawWithContent {
                        cardLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(cardLayer)
                    },
                )
                if (!state.posting) Surface(
                    onClick = { state = state.withoutCard() },
                    shape = CircleShape, color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp),
                ) { Icon(Icons.Rounded.Close, "카드 빼기", tint = Color.White, modifier = Modifier.padding(5.dp)) }
            }
            Text("사진 ${state.photos.size + (if (state.card) 1 else 0)}/${ComposeState.MAX_PHOTOS}", color = c.text2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(state.photos) { index, photo ->
                    Box(Modifier.size(96.dp)) {
                        Image(photo.image, "사진 ${index + 1}", Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                        if (!state.posting) Surface(
                            onClick = { state = state.removePhoto(index) },
                            shape = CircleShape, color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp),
                        ) { Icon(Icons.Rounded.Close, "사진 빼기", tint = Color.White, modifier = Modifier.padding(4.dp)) }
                    }
                }
                if (loadingPhotos) item {
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.primaryText, modifier = Modifier.size(24.dp))
                    }
                }
            }
            if (state.photosLeft > 0 && !state.posting) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("갤러리", openGallery, Modifier.weight(1f), icon = Icons.Rounded.PhotoLibrary)
                GhostButton("카메라", { cameraOpen = true }, Modifier.weight(1f), icon = Icons.Rounded.PhotoCamera)
            }
        }
    }
}

/** 글쓰기 카메라 — 사진 식단 카메라와 같은 부품. 찍은 사진은 올리기 전까지 기기 안에만 있다. */
@Composable
private fun CameraCapture(onPhoto: (FoodPhoto) -> Unit, onClose: () -> Unit) {
    val c = Trex.c
    val permission = rememberCameraPermission()
    val camera = rememberFoodCamera()
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(c.bg).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = LocalTrexNavSpace.current)) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (permission.granted) {
                camera.Preview(Modifier.fillMaxSize())
            } else if (permission.asked) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("카메라 권한이 필요해요", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    GhostButton("권한 다시 요청", { permission.request() }, Modifier.padding(top = 16.dp).fillMaxWidth())
                }
            }
        }
        (camera.error ?: error)?.let { Box(Modifier.padding(top = 10.dp)) { WashBanner(it, Icons.Rounded.Info, warnTone = true) } }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("뒤로", onClose, Modifier.weight(1f), height = 52.dp)
            Cta(
                text = "촬영", icon = Icons.Rounded.PhotoCamera, height = 52.dp, modifier = Modifier.weight(2f),
                enabled = permission.granted && !capturing && camera.error == null,
                onClick = {
                    capturing = true
                    error = null
                    camera.capture(
                        onPhoto = { capturing = false; onPhoto(it) },
                        onError = { capturing = false; error = "촬영에 실패했어요. 다시 시도해 주세요." },
                    )
                },
            )
        }
    }
}

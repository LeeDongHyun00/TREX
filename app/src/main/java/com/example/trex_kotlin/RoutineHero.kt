package com.example.trex_kotlin

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val routineImages = LruCache<String, ImageBitmap>(3)

/** 홈/운동에 같은 요약을 표시한다. 사진은 장식이며 운동 자세의 정답으로 쓰지 않는다. */
@Composable
internal fun RoutineHero(overview: RoutineOverview, onOpen: (() -> Unit)? = null) {
    val assets = LocalContext.current.applicationContext.assets
    val imageKey = overview.focus.image
    val bitmap by produceState<ImageBitmap?>(routineImages.get(imageKey), imageKey) {
        value = withContext(Dispatchers.IO) {
            routineImages.get(imageKey) ?: assets.open("routine/$imageKey.png").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })!!.asImageBitmap()
            }.also { routineImages.put(imageKey, it) }
        }
    }
    val ink = Color(0xFF20291B)
    val paper = Color(0xFFF7F4EE)
    Surface(shape = RoundedCornerShape(26.dp), color = paper, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(Modifier.fillMaxWidth().heightIn(min = if (onOpen != null) 164.dp else 196.dp)) {
                Crossfade(bitmap, animationSpec = tween(220), modifier = Modifier.matchParentSize(), label = "routine-image") { loaded ->
                    if (loaded != null && overview.count > 0) Image(loaded, null, Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit, alignment = Alignment.CenterEnd)
                }
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                    0f to paper, .36f to paper.copy(alpha = .92f), .63f to paper.copy(alpha = .05f), 1f to Color.Transparent)))
                Column(Modifier.fillMaxWidth(.56f).padding(start = 20.dp, top = 22.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("오늘의 루틴", color = Color(0xFF617846), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(overview.focus.title, color = ink, fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(overview.detail, color = Color(0xFF606857), fontSize = 13.sp,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                if (onOpen != null) TextButton(onClick = onOpen) {
                    Text("운동하기", color = Color(0xFF466429), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

package com.example.trex_kotlin

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class BrandFrame(val image: ImageBitmap, val origin: IntOffset, val extent: IntSize)

/** 원본 PNG는 보존하고 그릴 때만 투명 여백을 제외한다. 디코딩·알파 경계 탐색은 화면 스레드 밖에서 한다. */
@Composable
fun TrexBrandMark(modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val frame by produceState<BrandFrame?>(null, resources) {
        value = withContext(Dispatchers.Default) {
            val bitmap = BitmapFactory.decodeResource(resources, R.drawable.login_animation_frame_16,
                BitmapFactory.Options().apply { inSampleSize = 2 })
            var left = bitmap.width; var top = bitmap.height; var right = 0; var bottom = 0
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.forEachIndexed { i, color ->
                if ((color ushr 24) > 8) {
                    val x = i % bitmap.width; val y = i / bitmap.width
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
            if (right < left || bottom < top) null else BrandFrame(bitmap.asImageBitmap(), IntOffset(left, top), IntSize(right - left + 1, bottom - top + 1))
        }
    }
    Canvas(modifier.semantics { contentDescription = "TREX 공룡 로고" }) {
        frame?.let { logo ->
            val scale = minOf(size.width / logo.extent.width, size.height / logo.extent.height)
            val width = (logo.extent.width * scale).toInt(); val height = (logo.extent.height * scale).toInt()
            drawImage(logo.image, srcOffset = logo.origin, srcSize = logo.extent,
                dstOffset = IntOffset(((size.width - width) / 2).toInt(), ((size.height - height) / 2).toInt()), dstSize = IntSize(width, height))
        }
    }
}

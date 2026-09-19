package com.example.trex_kotlin

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.example.trex_kotlin.posture.MP_LANDMARK_COUNT
import com.example.trex_kotlin.posture.POSE_CONNECTIONS
import com.example.trex_kotlin.posture.PoseSample

/** 카탈로그 별칭은 과거 기록 진입만 지원하고 새 선택 목록은 고유 26종이다. */
val postureExerciseMap: Map<String, String> = buildMap {
    for (p in com.trex.engine.ExerciseCatalog.profiles) put(p.exercise, p.exercise)
    for (alias in listOf("기본 스쿼트", "런지", "오버헤드 프레스", "푸쉬업", "니 푸쉬업", "레그 레이즈", "힙 쓰러스트", "시저 크로스", "Y 레이즈"))
        com.trex.engine.ExerciseCatalog.canonical(alias)?.let { put(alias, it) }
}
fun Workout.postureSupported(): Boolean = com.trex.engine.ExerciseCatalog.canonical(name) != null

@Composable
internal fun LivePoseOverlay(
    sample: PoseSample,
    mirror: Boolean,
    tint: Color,
    /** 확인할 부위 — 서서 ship 위반, 바닥 지속 변화(참고). */
    highlight: Set<Int> = emptySet(),
    /** 미보정(beta) 규칙 위반 — 노랗게. 말하지 않는 판정이므로 붉은색과 같은 확신을 주면 안 된다 (spec §31). */
    provisional: Set<Int> = emptySet(),
    visibilityCut: Float = 0.5f,
    plankSide: Int? = null,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (!sample.detected || sample.imageWidth <= 0) return@Canvas
        val imgW = sample.imageWidth.toFloat()
        val imgH = sample.imageHeight.toFloat()
        // PreviewView 가 FIT_CENTER 이므로 여기서도 min — max(FILL)를 쓰면 스켈레톤이 어긋난다
        val scale = minOf(size.width / imgW, size.height / imgH)
        val drawW = imgW * scale
        val drawH = imgH * scale
        val dx = (size.width - drawW) / 2f
        val dy = (size.height - drawH) / 2f

        fun point(i: Int): Offset {
            val px = dx + sample.normalizedXy[i * 2] * drawW
            val py = dy + sample.normalizedXy[i * 2 + 1] * drawH
            return Offset(if (mirror) size.width - px else px, py)
        }

        val warn = Color(0xFFFF5A5A)
        plankSide?.let { side ->
            val shoulder = if(side == 0) 11 else 12
            val ankle = if(side == 0) 27 else 28
            drawLine(Color(0xFF58D8C7),point(shoulder),point(ankle),strokeWidth=3.dp.toPx(),
                pathEffect=androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(),7.dp.toPx())))
        }
        val provisionalColor = Color(0xFFFFC24B)   // 미보정 규칙 = 호박색. 붉은색과 같은 확신을 주지 않는다
        POSE_CONNECTIONS.forEach { (a, b) ->
            if (sample.visibility[a] >= visibilityCut && sample.visibility[b] >= visibilityCut) {
                val hot = a in highlight && b in highlight   // 위반 부위의 연결선은 붉게 (수정할점 #1)
                val soft = !hot && a in provisional && b in provisional
                drawLine(
                    color = when {
                        hot -> warn.copy(alpha = 0.9f)
                        soft -> provisionalColor.copy(alpha = 0.75f)
                        else -> tint.copy(alpha = 0.55f)
                    },
                    start = point(a),
                    end = point(b),
                    strokeWidth = if (hot) 7f else if (soft) 5f else 4f,
                    cap = StrokeCap.Round,
                )
            }
        }
        for (i in 0 until MP_LANDMARK_COUNT) {
            if (sample.visibility[i] < visibilityCut) continue
            val hot = i in highlight
            val soft = !hot && i in provisional
            if (hot) drawCircle(warn.copy(alpha = 0.22f), radius = 16f, center = point(i))
            drawCircle(
                color = when {
                    hot -> warn
                    soft -> provisionalColor
                    else -> Color.White.copy(alpha = 0.85f)
                },
                radius = if (hot) 7f else if (soft) 6f else 4f,
                center = point(i),
            )
        }
    }
}

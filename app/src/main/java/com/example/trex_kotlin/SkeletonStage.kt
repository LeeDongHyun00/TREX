package com.example.trex_kotlin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.trex_kotlin.posture.CoachEvent
import com.example.trex_kotlin.posture.FloorChain
import com.example.trex_kotlin.posture.FormDirection
import com.example.trex_kotlin.posture.FormMotion
import com.example.trex_kotlin.posture.MP_LANDMARK_COUNT
import com.example.trex_kotlin.posture.MotionFrame
import com.example.trex_kotlin.posture.MotionKind
import com.example.trex_kotlin.posture.MotionOrigin
import com.example.trex_kotlin.posture.MotionPick
import com.example.trex_kotlin.posture.MotionSpec
import com.example.trex_kotlin.posture.OnsetKind
import com.example.trex_kotlin.posture.POSE_CONNECTIONS
import com.example.trex_kotlin.posture.PoseSample
import com.example.trex_kotlin.posture.RepFormCheck
import com.example.trex_kotlin.posture.StepSide
import com.example.trex_kotlin.posture.WindowMotions
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 화면에 켜진 교정 단서 하나(docs/LIVE_SCREEN_REDESIGN.md §3.2, spec §101) — "COACH 에서 말한 교정 문장 하나의 그림". 한 회에 하나, 음성 문장을 만든 사건이 만든다(문턱 = 음성).
 * [anchors] = 출발 관절(쪽·카메라 쪽 팔을 푼 뒤). [targets] = [MotionKind.TOWARD_TARGET] 의 목표 관절. [gravity] = 위아래를 중력 기준으로(바닥). [chain] = 보이는 쪽 사슬만(화면이 가시성으로 고른다).
 * [soft] = 참고색(바닥 — 사용자 결정 U7 '바닥 색은 참고색'). [origin] 별로 지우는 규칙이 다르다: 통과한 회는 REPFORM 만 지운다(한 다리 계열의 센 회는 beta 검사뿐이라 늘 correct 라 다른 다리의 기각 화살표를 지웠다).
 * [startedAt] 부터 [PULSE_MS] 동안 맥동, 그 뒤 정지. [ttlMs] 지나면 지운다.
 */
internal data class MotionCue(val kind: MotionKind, val anchors: Set<Int>, val startedAt: Long,
                              val origin: MotionOrigin = MotionOrigin.REPFORM, val targets: Set<Int> = emptySet(),
                              val gravity: Boolean = false, val chain: Boolean = false, val soft: Boolean = false,
                              val ttlMs: Long = MotionSpec.DEFAULT_TTL_MS) {
    companion object {
        const val PULSE_MS = 1800L
        const val PERIOD_MS = 600L

        /** 반복 검사 사건 — [side] = 그 회의 앞다리(런지 깊이는 뒷무릎 SUPPORT), [view] 로 카메라 쪽 팔(컬 NEAR)과 좌우 가능 여부를 푼다. */
        fun of(check: RepFormCheck, direction: FormDirection, highlight: Set<Int>, now: Long, side: StepSide? = null, view: String? = null): MotionCue? {
            val m = check.motion ?: return null
            val spec = MotionSpec(m, MotionOrigin.REPFORM, direction, side = side, near = FormMotion.nearSide(view), lateralOk = MotionSpec.lateralOk(view))
            val kind = spec.kind ?: return null
            val anchors = spec.anchors.let { a -> if (m.pick == MotionPick.ALL) a.intersect(highlight).ifEmpty { a } else a }
            return MotionCue(kind, anchors, now, MotionOrigin.REPFORM, spec.targets)
        }

        /** 엔진이 만든 그림 명세(판별 기각·플랭크 멈춤·시선·가동 범위·처음부터 알림) — [soft] 는 바닥(참고색). 어휘가 없으면 null(문장만). */
        fun of(spec: MotionSpec, now: Long, soft: Boolean = false): MotionCue? {
            val kind = spec.kind ?: return null
            return MotionCue(kind, spec.anchors, now, spec.origin, spec.targets, gravity = spec.motion.frame == MotionFrame.GRAVITY,
                chain = spec.motion.pick == MotionPick.CHAIN || spec.motion.targetPick == MotionPick.CHAIN, soft = soft, ttlMs = spec.ttlMs)
        }

        /** 세트 창 규칙 사건 — 규칙 id 별 표([WindowMotions], spec §101)로 고른다. 교정됨(RECOVERED)은 화살표를 지운다. */
        fun ofWindow(event: CoachEvent, now: Long): MotionCue? {
            if (event.kind == OnsetKind.RECOVERED) return null
            val m = WindowMotions.forRule(event.rule.id, event.direction) ?: return null
            return MotionCue(m.kindFor(event.direction) ?: return null, m.anchor.landmarks, now, MotionOrigin.WINDOW)
        }
    }
}

/** 유령 다리의 관절(§101) — [0] 왼다리(무릎·발목·뒤꿈치·발끝), [1] 오른다리. */
private val GHOST_LEG = arrayOf(intArrayOf(25, 27, 29, 31), intArrayOf(26, 28, 30, 32))
private const val GHOST_MS = 2000L

private val WARN = Color(0xFFFF5A5A)          // ship 위반 = 말하는 판정
private val PROVISIONAL = Color(0xFFFFC24B)   // beta = 말하지 않는 판정, '참고'
private val FRAME_GLOW = Color(0xFFFFC24B)
private val ALIGN = Color(0xFF58D8C7)

/**
 * 검은 무대 위의 뼈대(docs/LIVE_SCREEN_REDESIGN.md §2). [dark] 면 영상을 덮고 검은 배경·프레임 테두리·못 보는 관절(점선)·잘린 변 발광을 그리고,
 * [lock] 이 있으면 그 경계 상자를 기준으로 배율을 **세트 동안 고정**한다(프레임마다 몸에 맞추면 하강에서 뼈대가 커진다 — §2.2).
 * [dark] 가 아니면(준비·영상 복귀·사용자 토글) 종전처럼 영상 위 오버레이다. 미러·뷰는 화면 좌표에서 처리하므로 화살표 방향이 저절로 맞는다.
 */
@Composable
internal fun SkeletonStage(
    sample: PoseSample,
    mirror: Boolean,
    dark: Boolean,
    lock: BodyBox?,
    highlight: Set<Int> = emptySet(),
    provisional: Set<Int> = emptySet(),
    visibilityCut: Float = 0.5f,
    plankSide: Int? = null,
    cue: MotionCue? = null,
    /** 가로 전체(사이드 런지, §101) — 배율 ≤ 1·가로 이동 없음. 스탠스가 이미지 폭의 3/4 이라 배율 1.46 이면 편 다리가 화면 밖에 그려졌다(관절 67.5 % → 2.6 %). */
    fullWidth: Boolean = false,
    /** 가장자리 유령 다리(서서 하는 종목, §101) — 무릎·발목·뒤꿈치·발끝 x ∉ [0.04, 0.96] 인 샘플 뒤 2 s 동안 그 다리를 점선·빈 원으로, 보간 없이 그린다. 가장자리의 가시성은 믿을 수 없다(밖 77프레임 중 60이 ≥ 0.5). */
    edgeGhost: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 맥동 시계 — 화살표가 켜진 뒤 PULSE_MS 동안만 프레임마다 돈다. 그 뒤엔 정지 화살표(재구성 없음)
    var pulseMs by remember(cue?.startedAt) { mutableLongStateOf(MotionCue.PULSE_MS) }
    LaunchedEffect(cue?.startedAt) {
        if (cue == null) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            val t = withFrameMillis { it } - start
            pulseMs = t
            if (t >= MotionCue.PULSE_MS) break
        }
    }
    // 화면용 좌표 보간(§2.4) — 추론 샘플 사이의 뼈대 끊김을 줄인다. 새 샘플이 오면 직전 화면 좌표에서 새 좌표로
    // 샘플 간격(80~400 ms)에 걸쳐 프레임마다 옮긴다. **표시만** 부드럽다 — 판정·카운터·로그는 원래 샘플 그대로. 한 간격만큼 늦게 보인다
    val display = remember { FloatArray(MP_LANDMARK_COUNT * 2) }
    val from = remember { FloatArray(MP_LANDMARK_COUNT * 2) }
    val anim = remember { longArrayOf(0L, 0L, 0L) }   // t0 · 지속 · 직전 도착
    // 관절 게이트(§101·§101c): 가시성 이력(≥ cut+0.10 실선, < cut−0.10 점선)에 더해 다리 관절은 더 높은 문턱, 믿을 수 없는 관절의 순간 이동은 한 샘플 보류 — 사선 런지의 먼 다리가 '깨지지' 않게
    val gate = remember { JointGate(visibilityCut) }
    // 가장자리 유령 다리(§101): [0] 왼다리 · [1] 오른다리의 유령 종료 시각
    val ghostUntil = remember { longArrayOf(0L, 0L) }
    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sample) {
        if (!sample.detected || sample.normalizedXy.size < display.size) return@LaunchedEffect
        val now = withFrameMillis { it }
        gate.update(sample.normalizedXy, sample.visibility, if (sample.imageHeight > 0) sample.imageWidth.toFloat() / sample.imageHeight else 1f)
        val target = gate.target
        if (edgeGhost) for (s in 0..1) {
            val out = GHOST_LEG[s].any { i -> val x = sample.normalizedXy[i * 2]; x.isFinite() && (x < 0.04f || x > 0.96f) }
            if (out) ghostUntil[s] = now + GHOST_MS
        }
        val gap = now - anim[2]
        anim[2] = now
        val fresh = anim[0] == 0L || gap > 900L   // 첫 샘플·오래 끊긴 뒤는 바로 놓는다
        anim[0] = now; anim[1] = gap.coerceIn(80L, 400L)
        System.arraycopy(display, 0, from, 0, from.size)
        if (fresh) { System.arraycopy(target, 0, display, 0, display.size); frameTick = now; return@LaunchedEffect }
        // 유령 다리는 보간하지 않는다 — 가설이 뒤집히는 발목을 선형으로 옮기면 '쓸고 지나가는 다리' 가 된다(10-08 사이드 런지)
        for (s in 0..1) if (now < ghostUntil[s]) for (i in GHOST_LEG[s]) { from[i * 2] = target[i * 2]; from[i * 2 + 1] = target[i * 2 + 1] }
        while (true) {
            val t = withFrameMillis { it }
            val p = ((t - anim[0]).toFloat() / anim[1]).coerceIn(0f, 1f)
            for (i in display.indices) display[i] = from[i] + (target[i] - from[i]) * p
            frameTick = t
            if (p >= 1f) break
        }
    }
    Canvas(modifier.then(if (dark) Modifier.background(Color.Black) else Modifier)) {
        @Suppress("UNUSED_VARIABLE") val redraw = frameTick   // 보간 프레임마다 다시 그린다
        if (!sample.detected || sample.imageWidth <= 0) return@Canvas
        val imgW = sample.imageWidth.toFloat()
        val imgH = sample.imageHeight.toFloat()
        // PreviewView 가 FIT_CENTER 이므로 여기서도 min — max(FILL)를 쓰면 영상 위 오버레이가 어긋난다
        val s0 = min(size.width / imgW, size.height / imgH)
        val drawW = imgW * s0
        val drawH = imgH * s0
        val dx = (size.width - drawW) / 2f
        val dy = (size.height - drawH) / 2f

        // 잠긴 경계 상자 → 배율·중심. 몸 중심을 화면 46 % 높이에 두어 아래 띠(큰 숫자)를 비운다
        var zoom = 1f
        var bx = size.width / 2f; var by = size.height / 2f
        var tx = bx; var ty = by
        if (dark && lock != null) {
            val boxH = max(lock.height * drawH, 1f)
            val boxW = max(lock.width * drawW, 1f)
            zoom = min(0.70f * size.height / boxH, 0.82f * size.width / boxW).coerceIn(1f, 2.2f)
            bx = dx + lock.centerX * drawW; by = dy + lock.centerY * drawH
            tx = size.width / 2f; ty = size.height * 0.46f
            // 가로 전체(§101 사이드 런지): 카메라 프레임 좌우 변이 화면 안에 오도록 배율을 누르고 가로 이동을 없앤다
            if (fullWidth) { zoom = min(zoom, size.width / drawW); tx = bx }
        }
        fun map(p: Offset): Offset {
            val q = if (zoom == 1f && tx == bx && ty == by) p else Offset(tx + (p.x - bx) * zoom, ty + (p.y - by) * zoom)
            return Offset(if (mirror) size.width - q.x else q.x, q.y)
        }
        val xy = if (anim[0] == 0L) sample.normalizedXy else display
        fun raw(i: Int): Offset? {
            val x = xy[i * 2]; val y = xy[i * 2 + 1]
            if (!x.isFinite() || !y.isFinite()) return null
            return Offset(dx + x * drawW, dy + y * drawH)
        }
        fun point(i: Int): Offset? = raw(i)?.let(::map)
        fun ghost(i: Int) = edgeGhost && ((i in GHOST_LEG[0] && frameTick < ghostUntil[0]) || (i in GHOST_LEG[1] && frameTick < ghostUntil[1]))
        fun visible(i: Int) = (if (anim[0] == 0L) sample.visibility[i] >= visibilityCut else gate.shown[i]) && !ghost(i)
        // 이미지 안(0..1)에 있는 관절만 화살표 앵커가 된다 — 화면 밖으로 외삽된 관절에 붙이면 거짓 확신(§101)
        fun inImage(i: Int): Boolean { val x = xy[i * 2]; val y = xy[i * 2 + 1]; return x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f }
        val body = 11 until MP_LANDMARK_COUNT

        // 카메라 프레임 테두리 — "폰이 보는 세상" 을 영상 없이 보여 준다(§2.1)
        val a = map(Offset(dx, dy)); val b = map(Offset(dx + drawW, dy + drawH))
        val frame = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
        if (dark) {
            drawRoundRect(Color.White.copy(alpha = 0.18f), topLeft = frame.topLeft, size = frame.size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            // 잘린 쪽 발광 — 보이는 관절이 프레임 가장자리에 닿으면 그 변이 호박색(어느 쪽으로 움직여야 하는지 말 없이 보인다)
            var l = false; var r = false; var t = false; var btm = false
            for (i in body) if (visible(i)) {
                val x = sample.normalizedXy[i * 2]; val y = sample.normalizedXy[i * 2 + 1]
                if (x <= 0.02f) l = true; if (x >= 0.98f) r = true; if (y <= 0.02f) t = true; if (y >= 0.98f) btm = true
            }
            if (mirror) { val s = l; l = r; r = s }
            val bar = 6.dp.toPx()
            // 발광 막대는 늘 화면 안에(§101) — 프레임이 화면 밖으로 나간 배율에서는 프레임과 화면의 교집합 변에 그린다
            val vf = Rect(max(frame.left, 0f), max(frame.top, 0f), min(frame.right, size.width), min(frame.bottom, size.height))
            if (l) drawRect(FRAME_GLOW.copy(alpha = .85f), Offset(vf.left, vf.top), Size(bar, vf.height))
            if (r) drawRect(FRAME_GLOW.copy(alpha = .85f), Offset(vf.right - bar, vf.top), Size(bar, vf.height))
            if (t) drawRect(FRAME_GLOW.copy(alpha = .85f), Offset(vf.left, vf.top), Size(vf.width, bar))
            if (btm) drawRect(FRAME_GLOW.copy(alpha = .85f), Offset(vf.left, vf.bottom - bar), Size(vf.width, bar))
        }

        plankSide?.let { side ->
            val sh = point(if (side == 0) 11 else 12); val an = point(if (side == 0) 27 else 28)
            if (sh != null && an != null) drawLine(ALIGN, sh, an, strokeWidth = 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 7.dp.toPx())))
        }

        val lineW = (if (dark) 4.dp else 3.dp).toPx()
        val ghost = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 8.dp.toPx()))
        POSE_CONNECTIONS.forEach { (p, q) ->
            if (p < 11 || q < 11) return@forEach   // 얼굴은 그리지 않는다 — 머리는 원 하나(아래)
            val pa = point(p) ?: return@forEach; val pb = point(q) ?: return@forEach
            val both = visible(p) && visible(q)
            if (both) {
                val hot = p in highlight && q in highlight   // 위반 부위의 연결선은 붉게
                val soft = !hot && p in provisional && q in provisional
                drawLine(
                    color = when { hot -> WARN.copy(alpha = 0.9f); soft -> PROVISIONAL.copy(alpha = 0.75f); else -> Color.White.copy(alpha = if (dark) 0.9f else 0.55f) },
                    start = pa, end = pb, strokeWidth = if (hot) lineW * 1.8f else if (soft) lineW * 1.3f else lineW, cap = StrokeCap.Round,
                )
            } else if (dark) {
                // 못 보는 관절로 가는 선은 점선 — "앱이 지금 못 보는 곳"(원칙 #5 의 화면판). 잘리면 다리가 점선으로 끝난다
                drawLine(Color.White.copy(alpha = 0.25f), pa, pb, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = ghost)
            }
        }
        // 머리 — 코를 중심으로 귀 간격만큼의 원. 목은 어깨 중점까지
        if (visible(0)) point(0)?.let { nose ->
            val e1 = point(7); val e2 = point(8)
            val rad = max(if (e1 != null && e2 != null && visible(7) && visible(8)) hypot(e1.x - e2.x, e1.y - e2.y) / 2f else 0f, 9.dp.toPx())
            // 시선 지적(§101d) 동안 머리 원은 붉은 테두리 — 말한 문장의 그림(화살표)과 함께 '교정 가시성'
            val gazeHot = cue != null && cue.origin == MotionOrigin.GAZE
            drawCircle(if (gazeHot) WARN.copy(alpha = 0.95f) else Color.White.copy(alpha = if (dark) 0.9f else 0.55f), rad, nose, style = Stroke(if (gazeHot) lineW * 1.6f else lineW))
            val s1 = point(11); val s2 = point(12)
            if (s1 != null && s2 != null && visible(11) && visible(12)) {
                val mid = Offset((s1.x + s2.x) / 2f, (s1.y + s2.y) / 2f)
                val d = hypot(mid.x - nose.x, mid.y - nose.y)
                if (d > rad) drawLine(Color.White.copy(alpha = if (dark) 0.9f else 0.55f),
                    Offset(nose.x + (mid.x - nose.x) * rad / d, nose.y + (mid.y - nose.y) * rad / d), mid, lineW, StrokeCap.Round)
            }
        }
        for (i in body) {
            val p = point(i) ?: continue
            if (!visible(i)) {
                if (dark) drawCircle(Color.White.copy(alpha = 0.35f), 5.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                continue
            }
            val hot = i in highlight
            val soft = !hot && i in provisional
            if (hot) drawCircle(WARN.copy(alpha = 0.22f), 16.dp.toPx() * 0.6f, p)
            drawCircle(when { hot -> WARN; soft -> PROVISIONAL; else -> Color.White.copy(alpha = 0.9f) },
                (if (hot) 6.dp else if (soft) 5.dp else 3.5.dp).toPx(), p)
        }

        // 교정 단서(§3.3·§101) — 위반 관절에서 고칠 방향으로. 앵커가 안 보이거나 이미지 밖이면 그리지 않는다(못 보는 관절에 화살표를 붙이면 거짓 확신).
        // 영상 위에서도 같은 좌표계로 그린다(종전 `&& dark` 는 영상 토글을 켠 뒤 모든 종목에서 화살표를 없앴다 — 10-08 놓친 원인)
        if (cue != null) {
            val hipL = point(23).takeIf { visible(23) }; val hipR = point(24).takeIf { visible(24) }
            val shL = point(11).takeIf { visible(11) }; val shR = point(12).takeIf { visible(12) }
            val torso = when {
                hipL != null && hipR != null -> Offset((hipL.x + hipR.x) / 2f, (hipL.y + hipR.y) / 2f)
                hipL != null -> hipL; hipR != null -> hipR
                shL != null && shR != null -> Offset((shL.x + shR.x) / 2f, (shL.y + shR.y) / 2f)
                else -> Offset(size.width / 2f, size.height / 2f)
            }
            // 보이는 쪽 사슬(바닥 CHAIN) — 앵커 후보 중 가시성 합이 큰 쪽(홀수 = MediaPipe 왼쪽)
            fun chainPick(set: Set<Int>): Set<Int> {
                if (!cue.chain || set.none { it % 2 == 1 } || set.none { it % 2 == 0 }) return set
                val l = set.filter { it % 2 == 1 }.sumOf { sample.visibility[it].toDouble() }
                val r = set.filter { it % 2 == 0 }.sumOf { sample.visibility[it].toDouble() }
                return set.filter { (it % 2 == 1) == (l >= r) }.toSet()
            }
            val anchors = chainPick(cue.anchors).filter { it in 0 until MP_LANDMARK_COUNT && visible(it) && inImage(it) }
            val targetPts = chainPick(cue.targets).filter { it in 0 until MP_LANDMARK_COUNT && visible(it) && inImage(it) }.mapNotNull { point(it) }
            val target = if (targetPts.isEmpty()) torso else Offset(targetPts.map { it.x }.average().toFloat(), targetPts.map { it.y }.average().toFloat())
            // 위 방향 — 바닥은 중력 위의 화면 성분(폰을 눕히면 화면 위 ≠ 중력 위, §101), 서서는 화면 위. 미러면 x 가 뒤집힌다
            val up = (if (cue.gravity) FloorChain.gravityUp(sample.up, sample.upFromGravity, sample.upFlipped) else null)
                ?.let { g -> val n = hypot(g.x, g.y); if (n < 0.3f) null else Offset((if (mirror) -g.x else g.x) / n, -g.y / n) } ?: Offset(0f, -1f)
            val color = if (cue.soft) PROVISIONAL else WARN
            val phase = if (pulseMs >= MotionCue.PULSE_MS) 0f else (pulseMs % MotionCue.PERIOD_MS).toFloat() / MotionCue.PERIOD_MS
            val alpha = 1f - 0.55f * phase
            val len = size.height * 0.09f
            for (i in anchors) {
                val p = point(i) ?: continue
                when (cue.kind) {
                    // 점(MARK) — 방향을 화면에 그릴 수 없는 지적(앞뒤·굽힘·흔들림): 고리가 맥동만 한다. 방향을 지어내지 않는다(원칙 #5)
                    MotionKind.MARK -> drawCircle(color.copy(alpha = alpha), 13.dp.toPx() + phase * 5.dp.toPx(), p, style = Stroke(4.dp.toPx()))
                    MotionKind.ROTATE_IN, MotionKind.ROTATE_OUT -> {
                        val leftOfMid = p.x < torso.x
                        val toward = (cue.kind == MotionKind.ROTATE_IN) == leftOfMid   // 안쪽 회전 = 정중선 쪽 끝에 화살촉
                        drawRotateArrow(p, 22.dp.toPx(), headAtRight = toward, color = color.copy(alpha = alpha), width = 4.dp.toPx(), head = 9.dp.toPx())
                    }
                    else -> {
                        val dir = when (cue.kind) {
                            MotionKind.TOWARD_MIDLINE -> Offset(if (torso.x >= p.x) 1f else -1f, 0f)
                            MotionKind.AWAY_MIDLINE -> Offset(if (torso.x >= p.x) -1f else 1f, 0f)
                            MotionKind.UP -> up
                            MotionKind.DOWN -> Offset(-up.x, -up.y)
                            MotionKind.TOWARD_TARGET -> { val d = hypot(target.x - p.x, target.y - p.y); if (d < 1f) up else Offset((target.x - p.x) / d, (target.y - p.y) / d) }
                            else -> { val d = hypot(torso.x - p.x, torso.y - p.y); if (d < 1f) up else Offset((torso.x - p.x) / d, (torso.y - p.y) / d) }
                        }
                        val gap = 14.dp.toPx() + phase * len * 0.3f
                        val start = Offset(p.x + dir.x * gap, p.y + dir.y * gap)
                        val end = Offset(start.x + dir.x * len, start.y + dir.y * len)
                        drawArrow(start, end, color.copy(alpha = alpha), 5.dp.toPx(), 11.dp.toPx())
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawArrow(start: Offset, end: Offset, color: Color, width: Float, head: Float) {
    drawLine(color, start, end, width, StrokeCap.Round)
    val ang = atan2(end.y - start.y, end.x - start.x)
    for (s in floatArrayOf(-1f, 1f)) {
        val a = ang + s * 2.5f   // ≈ 143° — 화살촉이 뒤로 벌어진다
        drawLine(color, end, Offset(end.x + cos(a) * head, end.y + sin(a) * head), width, StrokeCap.Round)
    }
}

/** 관절 위를 지나는 호(200°→340°)와 한쪽 끝의 화살촉 — 발끝 회전. */
private fun DrawScope.drawRotateArrow(center: Offset, r: Float, headAtRight: Boolean, color: Color, width: Float, head: Float) {
    drawArc(color, 200f, 140f, useCenter = false, topLeft = Offset(center.x - r, center.y - r), size = Size(2 * r, 2 * r),
        style = Stroke(width, cap = StrokeCap.Round))
    val endDeg = if (headAtRight) 340f else 200f
    val rad = Math.toRadians(endDeg.toDouble()).toFloat()
    val tip = Offset(center.x + cos(rad) * r, center.y + sin(rad) * r)
    // 접선 방향(호를 따라 진행하는 쪽)
    val tangent = if (headAtRight) rad + (Math.PI / 2).toFloat() else rad - (Math.PI / 2).toFloat()
    for (s in floatArrayOf(-1f, 1f)) {
        val a = tangent + Math.PI.toFloat() + s * 0.6f
        drawLine(color, tip, Offset(tip.x + cos(a) * head, tip.y + sin(a) * head), width, StrokeCap.Round)
    }
}

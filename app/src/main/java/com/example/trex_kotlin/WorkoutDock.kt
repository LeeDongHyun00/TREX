package com.example.trex_kotlin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.example.trex_kotlin.TrexText as Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.geometry.lerp as lerpRect
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.unit.lerp as lerpUnit

/*
 * 운동 탭 조작부 자리 바꾸기(§139, 사용자 요청 2026-11-07) — 운동 탭 리모컨(뒤로·운동 기록·운동 시작) 오른쪽의 '교체' 를 누르면
 * 머리줄의 루틴·내 루틴·공유가 아래 내비로, 내비의 운동 기록·운동 시작이 머리줄로 날아가 자리를 바꾼다. 상태는 기기에 남는다.
 *
 * 두 자리(머리줄 = WorkoutTabScreen 의 LazyColumn 첫 줄, 내비 = MorphNav)는 서로 다른 레이아웃이라 버튼을 직접 옮길 수 없다.
 * 그래서 FLIP 꼴로 그린다: 버튼마다 지금 자리(루트 좌표)와 겉모습을 적어 두고([dockAnchor]), 배치가 바뀌면 떠난 자리에서
 * 새 자리까지 '유령'([DockFlightLayer])이 스프링으로 날아간다. 도착할 때까지 새 자리의 진짜 버튼은 투명하다.
 * 유령은 두 겉모습 사이를 보간한다 — 머리줄의 아이콘이 내비의 알약으로 자라며 내려가고, 운동 시작 알약은 줄어들며 올라간다.
 * 날아가는 동안 살짝 떠오른다(크기·그림자, 가운데서 최대). 출발은 방향마다 34 ms 씩 엇갈린다.
 *
 * 글자가 큰 설정(1.3배 초과)은 내비가 아이콘 위 글자 배치라 유령 모양과 맞지 않는다 — 날리지 않고 바로 바꾼다.
 * 시스템 애니메이션 배율이 0 이면 스프링이 바로 끝난다(Compose 의 MotionDurationScale).
 */

/** 자리를 바꾸는 다섯 버튼 — 선언 순서가 유령의 겹침 순서다(운동 시작이 맨 위). */
enum class DockItem { Routines, Library, Share, Record, Start }

/** 버튼이 놓이는 두 자리 — 운동 탭 머리줄과 아래 내비(리모컨). */
enum class DockHost { Header, Nav }

/**
 * 지금 배치 — 버튼마다 놓인 자리(null = 어디에도 없음). [workoutRemote] = 운동 탭에서 내비가 리모컨으로 펼쳐져 있다.
 * 리모컨이 아니면(내비가 탭 다섯 개) 운동 기록·운동 시작은 어디에도 없고(예전과 같다) 루틴·내 루틴·공유는 머리줄이다 —
 * 교체 상태여도 접힌 내비에는 넣을 자리가 없어서 위로 돌아온다.
 */
fun dockPlacement(swapped: Boolean, workoutRemote: Boolean): Map<DockItem, DockHost?> {
    val actionsUp = swapped && workoutRemote
    return DockItem.entries.associateWith { item ->
        when (item) {
            DockItem.Record, DockItem.Start -> when {
                !workoutRemote -> null
                actionsUp -> DockHost.Header
                else -> DockHost.Nav
            }
            else -> if (actionsUp) DockHost.Nav else DockHost.Header
        }
    }
}

/**
 * 배치가 바뀔 때 버튼마다 할 움직임. 머리줄 ↔ 내비는 날기, 머리줄 → 없음은 그 자리에서 사라지기, 없음 → 머리줄은 나타나기.
 * 내비 ↔ 없음은 움직임이 없다 — 리모컨이 접히고 펴질 때 내비의 알약이 제자리에서 모양을 바꾸는 기존 전환(MorphNav)이 맡는다.
 */
enum class DockMotion { None, Fly, Vanish, Appear }

fun dockMotion(from: DockHost?, to: DockHost?): DockMotion = when {
    from == to -> DockMotion.None
    from != null && to != null -> DockMotion.Fly
    from == DockHost.Header -> DockMotion.Vanish
    to == DockHost.Header -> DockMotion.Appear
    else -> DockMotion.None
}

/** 한 자리에서의 겉모습 — 유령이 출발·도착 두 겉모습 사이를 보간한다. 투명은 같은 색의 알파 0 으로 적는다(검정 투명과 섞이면 회색이 낀다). */
@Immutable
data class DockLook(
    val bg: Color,
    val line: Color,
    val fg: Color,
    val iconSize: Dp,
    val label: String?,
    val labelSize: TextUnit,
    val gap: Dp,
    val shadow: Dp,
)

fun DockItem.icon(): ImageVector = when (this) {
    DockItem.Routines -> Icons.Rounded.CalendarMonth
    DockItem.Library -> Icons.Rounded.FolderOpen
    DockItem.Share -> Icons.Rounded.Share
    DockItem.Record -> Icons.Rounded.BarChart
    DockItem.Start -> Icons.Rounded.PlayArrow
}

/** 내비 알약의 글자(머리줄에서는 아이콘만 — 운동 시작은 예외). */
fun DockItem.navLabel(): String = when (this) {
    DockItem.Routines -> "루틴"
    DockItem.Library -> "내 루틴"
    DockItem.Share -> "공유"
    DockItem.Record -> "운동 기록"
    DockItem.Start -> "운동 시작"
}

/** 머리줄 아이콘 버튼(48 dp 터치 영역, 24 dp 아이콘, 배경 없음). */
@Composable
fun dockHeaderIconLook(): DockLook {
    val c = Trex.c
    return DockLook(bg = c.surface.copy(alpha = 0f), line = c.line.copy(alpha = 0f), fg = c.primaryText,
        iconSize = 24.dp, label = null, labelSize = 12.5.sp, gap = 0.dp, shadow = 0.dp)
}

/** 머리줄의 운동 시작 알약(높이 40 dp). 좁은 화면은 "시작" 만. */
@Composable
fun dockHeaderStartLook(label: String): DockLook {
    val c = Trex.c
    return DockLook(bg = c.primary, line = c.primary, fg = Color.White,
        iconSize = 18.dp, label = label, labelSize = 14.sp, gap = 6.dp, shadow = 2.dp)
}

/** 내비 알약(높이 56 dp) — MorphNav 의 슬롯과 같은 색·그림자. 운동 시작만 primary. */
@Composable
fun dockNavLook(item: DockItem, enabled: Boolean = true): DockLook {
    val c = Trex.c
    val primary = item == DockItem.Start
    return DockLook(
        bg = if (primary) c.primary else c.surface,
        line = if (primary) c.primary else c.line,
        fg = if (primary) Color.White else if (enabled) c.primaryText else c.text3,
        iconSize = 19.dp, label = item.navLabel(), labelSize = 12.5.sp, gap = 7.dp, shadow = if (primary) 3.dp else 1.dp,
    )
}

internal fun DockLook.lerpTo(to: DockLook, t: Float): DockLook {
    // 배경·테두리는 투명한 쪽 끝 25 % 안에서만 바뀐다 — 오래 반투명하면 아래 그림자가 비쳐 회색 원처럼 보였다(에뮬레이터 프레임 확인).
    // 머리줄 아이콘에서 출발하면 곧바로 흰 알약이 되고, 머리줄로 가면 도착 직전에 배경이 빠진다
    val surfaceT = when {
        bg.alpha < to.bg.alpha -> (t / 0.25f).coerceIn(0f, 1f)
        bg.alpha > to.bg.alpha -> ((t - 0.75f) / 0.25f).coerceIn(0f, 1f)
        else -> t
    }
    return DockLook(
        bg = lerpColor(bg, to.bg, surfaceT), line = lerpColor(line, to.line, surfaceT), fg = lerpColor(fg, to.fg, t),
        iconSize = lerpUnit(iconSize, to.iconSize, t), label = if (t < 0.5f) label else to.label,
        labelSize = lerpUnit(labelSize, to.labelSize, t), gap = lerpUnit(gap, to.gap, t), shadow = lerpUnit(shadow, to.shadow, t),
    )
}

/** 날고 있는 버튼 하나. [from] = 출발 자리(나타나기는 null), [to] = 도착 자리(사라지기는 null). */
@Stable
class DockFlight internal constructor(
    val item: DockItem,
    internal val from: Rect?,
    internal val fromLook: DockLook?,
    val to: DockHost?,
) {
    internal val progress = Animatable(0f, visibilityThreshold = 0.0005f)
    // 마지막으로 그린 자리·모양 — 공중에서 다시 누르면 여기서 되돌아간다(상태 아님: 유령이 매 프레임 쓴다)
    internal var lastRect: Rect? = from
    internal var lastLook: DockLook? = fromLook
    internal var job: Job? = null
}

/**
 * 운동 탭 조작부의 자리 상태 — MainTabs 가 하나 만들어 머리줄·내비·유령 층에 넘긴다.
 * [libraryOpen]·[sharing] 은 내 루틴·루틴 공유 창 — 머리줄에서 누르든 내비에서 누르든 운동 탭이 같은 창을 연다.
 */
@Stable
class WorkoutDock(initialSwapped: Boolean, private val onSwappedChange: (Boolean) -> Unit = {}) {
    /** 참 = 리모컨에서 운동 기록·운동 시작이 머리줄, 루틴·내 루틴·공유가 내비. */
    var swapped by mutableStateOf(initialSwapped)
        private set
    var libraryOpen by mutableStateOf(false)
    var sharing by mutableStateOf<Pair<String?, List<Workout>>?>(null)

    internal class Anchor(var rect: Rect, var look: DockLook)

    // 버튼 × 자리 → 마지막으로 놓인 사각형(루트 좌표)·겉모습. 상태가 아니다 — 자리가 바뀔 때마다 다시 구성하지 않게 유령이 직접 읽는다.
    // 머리줄이 목록 밖으로 스크롤돼 사라져도 마지막 자리(화면 위쪽 밖)가 남아, 그쪽으로 날아가거나 그쪽에서 내려온다.
    private val anchors = Array(DockItem.entries.size) { arrayOfNulls<Anchor>(DockHost.entries.size) }
    internal val flights = mutableStateMapOf<DockItem, DockFlight>()

    fun toggle() {
        swapped = !swapped
        onSwappedChange(swapped)
    }

    /** 이 버튼의 유령이 날고 있다 — 진짜 버튼(출발·도착 어느 쪽이든)은 감춘다. */
    fun isFlying(item: DockItem): Boolean = flights.containsKey(item)

    internal fun place(item: DockItem, host: DockHost, rect: Rect, look: DockLook) {
        val a = anchors[item.ordinal][host.ordinal]
        if (a == null) anchors[item.ordinal][host.ordinal] = Anchor(rect, look) else { a.rect = rect; a.look = look }
    }

    internal fun anchor(item: DockItem, host: DockHost): Anchor? = anchors[item.ordinal][host.ordinal]

    /**
     * [before] → [after] 로 배치가 바뀌었다 — 옮겨 가는 버튼마다 유령을 띄운다. [animate] 가 거짓이면(큰 글자) 바로 바꾼다.
     * 공중에 있던 버튼은 지금 그려진 자리에서 새 목적지로 꺾는다(빠르게 두 번 눌러도 튀지 않는다).
     */
    fun fly(before: Map<DockItem, DockHost?>, after: Map<DockItem, DockHost?>, scope: CoroutineScope, animate: Boolean) {
        val order = IntArray(DockHost.entries.size)   // 도착 방향마다 출발 차례 — 엇갈려 뜬다
        for (item in FLIGHT_ORDER) {
            val from = before[item]
            val to = after[item]
            if (from == to) continue
            val airborne = flights[item]
            airborne?.job?.cancel()
            // 공중에 있던 버튼은 목적지가 있으면 거기로 꺾고, 없으면(리모컨이 접힘) 그 자리에서 사라진다
            val motion = when {
                airborne != null && to != null -> DockMotion.Fly
                airborne != null -> DockMotion.Vanish
                else -> dockMotion(from, to)
            }
            val start = airborne?.lastRect ?: from?.let { anchor(item, it) }?.rect
            val startLook = airborne?.lastLook ?: from?.let { anchor(item, it) }?.look
            val possible = when (motion) {
                DockMotion.Fly, DockMotion.Vanish -> start != null && startLook != null
                DockMotion.Appear -> true
                DockMotion.None -> false
            }
            if (!animate || !possible) { flights.remove(item); continue }
            val flight = if (motion == DockMotion.Appear) DockFlight(item, null, null, to) else DockFlight(item, start, startLook, to)
            flights[item] = flight
            val wait = if (motion == DockMotion.Fly && to != null) STAGGER_MS * order[to.ordinal]++ else 0L
            flight.job = scope.launch {
                if (wait > 0) delay(wait)
                flight.progress.animateTo(1f, if (motion == DockMotion.Fly) FLY_SPEC else FADE_SPEC)
                if (flights[item] === flight) flights.remove(item)
            }
        }
    }

    private companion object {
        // 출발 차례 — 올라가는 쪽은 운동 시작이 앞장서고, 내려가는 쪽은 왼쪽부터
        val FLIGHT_ORDER = listOf(DockItem.Start, DockItem.Record, DockItem.Routines, DockItem.Library, DockItem.Share)
        const val STAGGER_MS = 34L
        // SwiftUI 기본 스프링(반응 0.5 s·감쇠 0.8 안팎)에 가까운 값 — 끝에서 아주 조금 넘었다가 앉는다
        val FLY_SPEC = spring(dampingRatio = 0.78f, stiffness = 160f, visibilityThreshold = 0.0005f)
        val FADE_SPEC = spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = 0.0005f)
    }
}

/**
 * 머리줄·내비의 버튼에 붙인다 — 자리·겉모습을 적고([record] 가 거짓이면 적지 않는다: 접힌 내비 슬롯),
 * 이 버튼의 유령이 날고 있으면 진짜 버튼을 감춘다(떠나는 쪽·도착하는 쪽 모두 — 겹쳐 보이지 않게).
 */
fun Modifier.dockAnchor(dock: WorkoutDock?, item: DockItem, host: DockHost, look: DockLook, record: Boolean = true): Modifier =
    if (dock == null) this
    else this
        .then(if (record) Modifier.onGloballyPositioned { dock.place(item, host, Rect(it.positionInRoot(), it.size.toSize()), look) } else Modifier)
        .graphicsLayer { alpha = if (dock.isFlying(item)) 0f else 1f }

/**
 * 유령 층 — MainTabs 의 맨 위(내비 위, 시트 아래)에 화면 크기로 둔다. 손가락 입력을 받지 않는다(포인터 수식자 없음).
 * 층을 벗어난 유령(목록 밖 머리줄 쪽)은 가장자리에서 잘린다.
 */
@Composable
fun DockFlightLayer(dock: WorkoutDock, modifier: Modifier = Modifier) {
    val origin = remember { mutableStateOf(Offset.Zero) }
    Box(modifier.clipToBounds().onGloballyPositioned { origin.value = it.positionInRoot() }) {
        for (item in DockItem.entries) {
            val flight = dock.flights[item] ?: continue
            key(flight) { DockGhost(dock, flight, origin) }
        }
    }
}

@Composable
private fun DockGhost(dock: WorkoutDock, f: DockFlight, origin: State<Offset>) {
    val p = f.progress.value
    val s = p.coerceIn(0f, 1f)
    val target = f.to?.let { dock.anchor(f.item, it) }
    // 도착 자리를 아직(또는 끝내) 모르면 날지 않고 그 자리에서 사라진다 — 허공으로 가지 않게
    val motion = when {
        f.from == null -> DockMotion.Appear
        f.to != null && target != null -> DockMotion.Fly
        else -> DockMotion.Vanish
    }
    val fromRect = f.from ?: target?.rect ?: return
    val fromLook = f.fromLook ?: target?.look ?: return
    val toLook = if (motion == DockMotion.Fly) target!!.look else fromLook
    val rect = when (motion) {
        DockMotion.Fly -> lerpRect(fromRect, target!!.rect, p)
        DockMotion.Appear -> target?.rect ?: return
        else -> fromRect
    }
    val look = if (motion == DockMotion.Fly) fromLook.lerpTo(toLook, s) else fromLook
    f.lastRect = rect
    f.lastLook = look
    // 떠오름은 비행의 가운데 70 % 에서만 — 양 끝(배경이 차오르거나 빠지는 구간, lerpTo)에는 그림자·확대가 없다
    val lift = if (motion == DockMotion.Fly) sin(PI.toFloat() * ((s - 0.15f) / 0.7f).coerceIn(0f, 1f)) else 0f
    val alpha = when (motion) { DockMotion.Appear -> s; DockMotion.Vanish -> 1f - s; else -> 1f }
    val scale = when (motion) {
        DockMotion.Fly -> 1f + 0.07f * lift
        DockMotion.Appear -> 0.82f + 0.18f * s
        else -> 1f - 0.18f * s
    }
    // 떠오른 만큼 그림자 — 배경이 반투명하면 그림자가 비쳐 회색 원처럼 보여(에뮬레이터 프레임 확인) 배경 알파의 세제곱으로 줄인다.
    // 머리줄 아이콘(배경 없음) 쪽 끝에서는 그림자도 없다
    val bgAlpha = look.bg.alpha
    val elevation = (look.shadow + 14.dp * lift) * (bgAlpha * bgAlpha * bgAlpha)
    val density = LocalDensity.current
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .offset { IntOffset((rect.left - origin.value.x).roundToInt(), (rect.top - origin.value.y).roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .shadow(elevation, shape, clip = false)
            .background(look.bg, shape)
            .border(1.dp, look.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(f.item.icon(), contentDescription = null, tint = look.fg, modifier = Modifier.size(look.iconSize))
            GhostLabel(fromLook, toLook, if (motion == DockMotion.Fly) s else 0f, look.fg)
        }
    }
}

/** 유령의 글자 — 같은 글자면 크기만 바뀌고, 다르면(없음 ↔ 있음, "운동 시작" ↔ "시작") 자리 폭을 보간하며 서로 교차한다. */
@Composable
private fun GhostLabel(from: DockLook, to: DockLook, s: Float, color: Color) {
    val a = from.label
    val b = to.label
    if (a == null && b == null) return
    if (a == b) {
        Spacer(Modifier.width(lerpUnit(from.gap, to.gap, s)))
        Text(a!!, color = color, fontSize = lerpUnit(from.labelSize, to.labelSize, s), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        return
    }
    Layout(
        content = {
            Text(a.orEmpty(), color = color, fontSize = from.labelSize, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                modifier = Modifier.graphicsLayer { alpha = if (a == null) 0f else 1f - s })
            Text(b.orEmpty(), color = color, fontSize = to.labelSize, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                modifier = Modifier.graphicsLayer { alpha = if (b == null) 0f else s })
        },
        modifier = Modifier.clipToBounds(),
    ) { measurables, _ ->
        val loose = Constraints()
        val pa = measurables[0].measure(loose)
        val pb = measurables[1].measure(loose)
        val ga = if (a == null) 0 else from.gap.roundToPx()
        val gb = if (b == null) 0 else to.gap.roundToPx()
        val wa = if (a == null) 0 else ga + pa.width
        val wb = if (b == null) 0 else gb + pb.width
        val w = (wa + (wb - wa) * s).roundToInt().coerceAtLeast(0)
        val h = maxOf(if (a == null) 0 else pa.height, if (b == null) 0 else pb.height)
        layout(w, h) {
            if (a != null) pa.place(ga, (h - pa.height) / 2)
            if (b != null) pb.place(gb, (h - pb.height) / 2)
        }
    }
}

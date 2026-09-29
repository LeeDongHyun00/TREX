package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.ceil

/** 촬영 범위만 확인한다. 방향의 정답·자세의 옳고 그름·개인 기준을 판정하지 않는다. [anchors] = 몸통(어깨·골반) 테두리 — 움직임·안정 판단용. */
data class CaptureFrame(val ready: Boolean, val message: String, val anchors: List<Float> = emptyList(), val facing: List<Float> = emptyList())

/**
 * 준비 확인이 요구하는 몸 범위(§89). 팔 운동(컬·레이즈·프레스·랫풀·딥스)은 상체만 — 다리를 요구하면 가까이 찍은 사용자가 영원히 통과하지 못했다
 * (09-25 덤벨 컬 세트: 무릎 y 1.2~2.5, 화면 아래 바깥). 그 밖(서서 하는 하체·전신, 바닥)은 전신.
 */
enum class FramingRegion { UPPER, FULL }

/**
 * 시작 전 촬영 범위 확인(§89). 그 운동의 판정에 쓰는 관절이 화면 안에 보이는가만 묻는다.
 * - 관절: 머리(코·귀 중 하나) + 어깨·팔꿈치·손목·골반(상체) + 무릎·발목(전신). **발끝(31·32)은 요구하지 않는다** — 사선 가시도 중앙값 0.03~0.04,
 *   정면에서도 25 % 프레임이 0.55 아래였고, 쓰는 검사는 스쿼트 발끝 방향 하나뿐이며 그 검사는 발끝이 없으면 유보된다.
 * - 사선(B·D·바닥 사선)은 **가까운 쪽 사슬 전부 + 먼 쪽 어깨·골반(전신이면 발목도)** — 먼 쪽 팔다리는 몸통에 가려 가시도가 낮다.
 *   옆(SIDE·바닥 옆)은 한쪽 사슬 전부(좌우를 섞어 가림을 통과시키지 않는다).
 * - 가장자리: 머리·손목은 4 %, 그 밖은 2 % 안쪽, 발목은 화면 아래 끝까지 허용(폰을 바닥 가까이 두면 발목이 y 0.99~1.01 에 걸렸다).
 * - 못 보면 **무엇이 어디로 잘렸는지** 말한다(발이 아래로 잘림 → 폰을 뒤로/낮게, 왼손이 안 보임 …).
 * 재현(38세트 첫 40프레임): 사선 통과 3 %·옆 0 %였고, 덤벨 컬 33 % → 74 %, 옆 0 → 45 %, 스쿼트 74 → 79 %.
 */
object CaptureFraming {
    const val MIN_VISIBILITY = .5f
    private val HEAD = listOf(0, 7, 8)
    private val LEFT = listOf(11, 13, 15, 23, 25, 27)     // 어깨·팔꿈치·손목·골반·무릎·발목
    private val RIGHT = listOf(12, 14, 16, 24, 26, 28)
    private val HANDS = setOf(13, 14, 15, 16)
    private val LEGS = setOf(25, 26, 27, 28)
    private val ANKLES = setOf(27, 28)
    private val TORSO = setOf(11, 12, 23, 24)

    private fun required(chain: List<Int>, region: FramingRegion) = if (region == FramingRegion.UPPER) chain.take(4) else chain
    private fun farRequired(chain: List<Int>, region: FramingRegion) =
        if (region == FramingRegion.UPPER) listOf(chain[0], chain[3]) else listOf(chain[0], chain[3], chain[5])

    fun inspect(sample: PoseSample, capture: CapturePosition, floor: Boolean, region: FramingRegion = FramingRegion.FULL): CaptureFrame {
        if (sample.features.isEmpty() || sample.normalizedXy.size < 66 || sample.visibility.size < 33)
            return CaptureFrame(false, "몸이 화면에 보이게 자리 잡아 주세요.")
        val xy = sample.normalizedXy
        fun finite(i: Int) = xy[i * 2].isFinite() && xy[i * 2 + 1].isFinite()
        fun vis(i: Int) = sample.visibility[i].takeIf { it.isFinite() } ?: 0f
        fun seen(i: Int) = vis(i) >= MIN_VISIBILITY && finite(i)
        val head = HEAD.firstOrNull(::seen)
            ?: return CaptureFrame(false, if (HEAD.any { finite(it) && xy[it * 2 + 1] < 0f }) "머리가 화면 위로 잘렸어요. 한 걸음 뒤로 가 주세요." else "얼굴이 보이게 서 주세요.")
        val reg = if (floor) FramingRegion.FULL else region
        val side = capture == CapturePosition.SIDE || capture == CapturePosition.FLOOR_SIDE
        val oblique = capture == CapturePosition.RIGHT_FRONT || capture == CapturePosition.LEFT_FRONT || capture == CapturePosition.FLOOR_FRONT
        val l = required(LEFT, reg); val r = required(RIGHT, reg)
        fun score(chain: List<Int>) = chain.sumOf { vis(it).toDouble() }
        val need: List<Int> = when {
            // 옆: 한쪽 사슬 전부 — 둘 다 모자라면 더 잘 보이는 쪽의 빠진 관절을 말한다
            side -> listOf(l, r).filter { c -> c.all(::seen) }.maxByOrNull(::score) ?: listOf(l, r).maxBy(::score)
            oblique -> if (score(l) >= score(r)) l + farRequired(RIGHT, reg) else r + farRequired(LEFT, reg)
            else -> l + r
        }
        val missing = need.filterNot(::seen)
        if (missing.isNotEmpty()) return CaptureFrame(false, missingMessage(missing, ::finite, xy))
        for (i in listOf(head) + need) {
            val x = xy[i * 2]; val y = xy[i * 2 + 1]
            val m = if (i == head || i in HANDS) .04f else .02f
            val bottom = if (i in ANKLES) 1f else 1f - m
            if (y > bottom) return CaptureFrame(false, if (i in LEGS) FEET_CUT else "몸이 화면 아래에 걸려요. 휴대폰을 조금 뒤로 두세요.")
            if (y < m) return CaptureFrame(false, if (i == head) "머리가 화면 위에 걸려요. 한 걸음 뒤로 가 주세요." else "몸이 화면 위에 걸려요. 한 걸음 뒤로 가 주세요.")
            if (x < m || x > 1f - m) return CaptureFrame(false, if (i in HANDS) "손이 화면 가장자리에 걸려요. 한 걸음 뒤로 가 주세요." else "몸이 화면 가장자리에 걸려요. 가운데로 조금 옮겨 주세요.")
        }
        val pts = listOf(head) + need
        val xs = pts.map { xy[it * 2] }; val ys = pts.map { xy[it * 2 + 1] }
        val width = xs.max() - xs.min(); val height = ys.max() - ys.min()
        val minSize = if (reg == FramingRegion.UPPER) .2f else .35f
        if ((if (floor) maxOf(width, height) else height) < minSize)
            return CaptureFrame(false, "몸이 조금 더 크게 보이도록 가까이 와 주세요.")
        // 안정·움직임은 몸통으로만 본다 — 손목·발끝이 테두리에 들어가면 팔만 움직여도 다시 쟀다
        val torso = need.filter { it in TORSO }
        val tx = torso.map { xy[it * 2] }; val ty = torso.map { xy[it * 2 + 1] }
        // 좌우 가시성이 바뀌어도 안정성 비교의 길이/의미를 일정하게 유지한다.
        val facing = if (floor) emptyList() else listOfNotNull(sample.features["view_cos"], sample.features["view_sin"])
        return CaptureFrame(true, "촬영 범위가 확인됐어요.", listOf(tx.min(), ty.min(), tx.max(), ty.max()),
            facing.takeIf { it.size == 2 && it.all(Float::isFinite) }.orEmpty())
    }

    private const val FEET_CUT = "발이 화면 아래로 잘렸어요. 휴대폰을 조금 뒤로 두거나 낮춰 주세요."

    /** 빠진 관절 → 고칠 수 있는 말. 우선순위 = 다리(화면 밖) → 손 → 몸통. 좌우는 사용자 기준(MediaPipe 왼쪽 = 해부학 왼쪽). */
    private fun missingMessage(missing: List<Int>, finite: (Int) -> Boolean, xy: FloatArray): String {
        fun below(i: Int) = finite(i) && xy[i * 2 + 1] > 1f
        fun outside(i: Int) = finite(i) && (xy[i * 2] < 0f || xy[i * 2] > 1f || xy[i * 2 + 1] < 0f)
        val legs = missing.filter { it in LEGS }
        if (legs.isNotEmpty()) return if (legs.any(::below)) FEET_CUT else "다리가 가려지지 않게 서 주세요."
        val hands = missing.filter { it in HANDS }
        if (hands.isNotEmpty()) {
            if (hands.any(::outside)) return "손이 화면 밖으로 나갔어요. 한 걸음 뒤로 가 주세요."
            val left = hands.any { it % 2 == 1 }; val right = hands.any { it % 2 == 0 }
            return (if (left && right) "양손이" else if (left) "왼손이" else "오른손이") + " 안 보여요. 팔이 몸에 가려지지 않게 해 주세요."
        }
        return "몸통이 가려졌어요. 휴대폰 쪽으로 몸이 보이게 서 주세요."
    }
}

enum class PreparationPhase { IDLE, WAITING, COUNTDOWN, STARTED }

/**
 * 검증된 서서 정면/전방 사선만 보조 확인한다. 바닥·순수 측면·불명은 임의로 각도를 인증하지 않는다.
 * §89: **분명히 틀린 방향만** 막는다 — 사선 요청에 반대쪽 사선·옆(55° 넘게, `RepFormEvaluator.REP_OBLIQUE_MAX_DEG`), 정면 요청에 30° 넘게 돌아섬.
 * MediaPipe 는 사선을 작게 읽어(45° → 20~33°) 정면 경계(16.4°) 근처에서 흔들린다 — 09-29 22:34 덤벨 컬: 요 −18~−23° 인데 8프레임 평균이 경계를 넘나들어
 * 카운트다운 "1" 에서 막히고 처음으로 돌아가기를 네 번 되풀이했다. 반복 검사는 반복마다 그 반복의 뷰로 검사를 고르므로 조금 덜 돌아선 것은 시작을 막을 이유가 아니다.
 * 방향이 [MISMATCH_HOLD_MS] 넘게 이어서 틀릴 때만 막고, 한 프레임 가림으로 창을 비우지 않는다. 카운트다운 중에는 쓰지 않는다(호출하는 쪽, 몸을 돌리면 움직임 판단이 멈춘다).
 */
class CaptureDirectionWindow(private val capture: CapturePosition, private val floor: Boolean) {
    private val frames = ArrayDeque<Map<String, Float>>()
    private var mismatchSince: Long? = null
    fun clear() { frames.clear(); mismatchSince = null }
    fun check(frame: CaptureFrame, features: Map<String, Float>, now: Long = 0L): CaptureFrame {
        if (floor || capture !in listOf(CapturePosition.FRONT, CapturePosition.RIGHT_FRONT, CapturePosition.LEFT_FRONT)) return frame
        if (!frame.ready || !features.containsKey("view_cos") || !features.containsKey("view_sin")) return frame
        frames.addLast(features);while(frames.size>8)frames.removeFirst()
        val estimate=ViewEstimator.estimate(frames.toList()) ?: return frame
        if(estimate.r<.9f || estimate.cls==ViewEstimator.ViewClass.UNKNOWN) { mismatchSince=null;return frame }
        if (matches(estimate.yawDeg)) { mismatchSince=null;return frame }
        val since = mismatchSince ?: now.also { mismatchSince = it }
        return if (now - since >= MISMATCH_HOLD_MS) frame.copy(ready=false,message=capture.placement) else frame
    }
    private fun matches(yaw: Float): Boolean {
        val a = abs(yaw); val bSide = yaw * ViewEstimator.B_SIGN > 0f
        val wrongSide = when (capture) { CapturePosition.RIGHT_FRONT -> !bSide; CapturePosition.LEFT_FRONT -> bSide; else -> false }
        return when (capture) {
            CapturePosition.RIGHT_FRONT, CapturePosition.LEFT_FRONT ->
                a <= RepFormEvaluator.REP_OBLIQUE_MAX_DEG && !(wrongSide && a > ViewEstimator.FRONT_MAX_DEG)
            else -> a <= FRONT_TOLERANCE_DEG
        }
    }
    companion object {
        const val MISMATCH_HOLD_MS = 2_000L
        /** 정면 요청에서 막는 요(°) — 이만큼 넘게 돌아서야 '틀린 방향'. 그 아래는 반복 검사가 사선 검사로 본다. */
        const val FRONT_TOLERANCE_DEG = 30f
    }
}

data class PreparationState(
    val phase: PreparationPhase = PreparationPhase.IDLE,
    val seconds: Int = 0, val progress: Float = 0f,
    val message: String = "몸이 화면에 잡히면 3초 뒤 시작해요.",
    val trackingHold: Boolean = false, val restarts: Int = 0,
)

/**
 * 잠깐 가려지면 남은 시간을 보존한다. 계속 가려지면([RESTART_AFTER_MS]) 안내로 돌아가며 카운트를 새로 준다.
 * §89: 자동 카운트다운 3초(안내를 이미 들었다), 끊김 허용 2.5초(1.2초면 몸을 조금만 돌려도 처음으로 돌아갔다).
 * [holdStart] 동안은 범위·안정을 재기만 하고 카운트다운에 들어가지 않는다 — 안내 음성과 판정을 겹치고, 음성이 끝났을 때 이미 준비돼 있으면 바로 센다.
 */
class CapturePreparationController(@Suppress("UNUSED_PARAMETER") floor: Boolean = false) {
    var state = PreparationState(); private set
    private var armedAt = 0L
    private var stableAt: Long? = null
    private var anchor = emptyList<Float>()
    private var facing = emptyList<Float>()
    private var lastFrameAt = Long.MIN_VALUE
    private var invalidAt: Long? = null
    private var recoveryAt: Long? = null
    private var automatic = true
    private var startHeld = false
    private var remainingMs = COUNTDOWN_MS
    private var durationMs = COUNTDOWN_MS
    private var lastTickAt = 0L
    private var frameAccepted = false
    /** 카운트다운을 멈춘 이유(무엇이 안 보였나·움직였나) — 멈춘 동안 화면·음성에, 처음으로 돌아가면 그 안내에 붙인다(§89 후속). */
    private var holdReason: String? = null
    /** 자동 판정 중인가(직접 시작 카운트가 아닌가). */
    val isAutomatic: Boolean get() = automatic
    fun cancel() { state=PreparationState();startHeld=false;resetObservation() }
    private fun resetObservation() {
        stableAt=null;anchor=emptyList();facing=emptyList();lastFrameAt=Long.MIN_VALUE
        invalidAt=null;recoveryAt=null;frameAccepted=false;holdReason=null
    }
    fun arm(now: Long) {
        resetObservation();armedAt=now;lastTickAt=now;automatic=true;startHeld=false
        state=PreparationState(PreparationPhase.WAITING)
    }
    /** 안내 음성 중에는 카운트다운에 들어가지 않는다(범위·안정은 계속 잰다). 풀리면 다음 프레임에서 바로 들어간다. */
    fun holdStart(hold: Boolean) { startHeld = hold }
    fun manual(now: Long, seconds: Int) {
        resetObservation();automatic=false;startHeld=false;lastTickAt=now
        durationMs=seconds.coerceIn(5,30)*1000L;remainingMs=durationMs
        state=PreparationState(PreparationPhase.COUNTDOWN,(remainingMs/1000).toInt(),message="카운트가 끝나면 운동을 시작해 주세요.")
    }
    private fun restart() {
        val count=state.restarts+1
        // 왜 처음으로 돌아갔는지 말한다 — 이유 없이 "다시 자리 잡아 주세요" 만 들으면 무엇을 고칠지 모른다(사용자 보고 2026-09-29)
        val why=holdReason ?: "몸이 잘 보이지 않았어요."
        stableAt=null;anchor=emptyList();facing=emptyList();invalidAt=null;recoveryAt=null;holdReason=null
        state=PreparationState(PreparationPhase.WAITING,message="$why 다시 자리 잡으면 3초를 셉니다.",restarts=count)
    }
    /** 카운트다운 중 몸이 움직였거나 돌아섰으면 그 이유, 아니면 null. */
    private fun movedReason(frame: CaptureFrame): String? {
        val shifted=anchor.size==frame.anchors.size && anchor.indices.any { abs(anchor[it]-frame.anchors[it])>.075f }
        val turned=facing.size==2 && frame.facing.size==2 && facing.zip(frame.facing).sumOf { (a,b)->(a*b).toDouble() } < .906
        return when {
            turned -> "몸의 방향이 바뀌었어요. 안내한 방향으로 서 주세요."
            shifted -> "몸이 많이 움직였어요. 제자리에 서 주세요."
            else -> null
        }
    }
    fun observe(now: Long, frame: CaptureFrame) {
        if(!automatic || now<armedAt || state.phase !in listOf(PreparationPhase.WAITING,PreparationPhase.COUNTDOWN))return
        if(lastFrameAt!=Long.MIN_VALUE && now<=lastFrameAt)return
        lastFrameAt=now
        val movedWhy=if(frame.ready && state.phase==PreparationPhase.COUNTDOWN) movedReason(frame) else null
        frameAccepted=frame.ready && movedWhy==null
        if(!frameAccepted) {
            stableAt=null;recoveryAt=null
            if(state.phase==PreparationPhase.COUNTDOWN) {
                if(invalidAt==null)invalidAt=now
                val why=movedWhy ?: frame.message
                holdReason=why
                state=state.copy(trackingHold=true,message=why)
            } else state=state.copy(message=frame.message)
            return
        }
        invalidAt=null
        if(state.phase==PreparationPhase.COUNTDOWN) {
            if(state.trackingHold) {
                if(recoveryAt==null)recoveryAt=now
                if(now-recoveryAt!!>=300)state=state.copy(trackingHold=false,message="곧 시작해요.")
            }
            return
        }
        val stable=anchor.size==frame.anchors.size && anchor.isNotEmpty() && anchor.indices.all { abs(anchor[it]-frame.anchors[it])<=.05f }
        if(!stable) { anchor=frame.anchors;facing=frame.facing;stableAt=now }
        if(!startHeld && now-(stableAt ?: now)>=400) {
            remainingMs=COUNTDOWN_MS;durationMs=COUNTDOWN_MS;lastTickAt=now
            state=state.copy(phase=PreparationPhase.COUNTDOWN,seconds=(COUNTDOWN_MS/1000).toInt(),progress=0f,message="곧 시작해요.")
        } else if (startHeld) state=state.copy(message=frame.message)
    }
    fun tick(now: Long): PreparationState {
        val delta=(now-lastTickAt).coerceAtLeast(0)
        val elapsed=if(automatic)delta.coerceAtMost(500)else delta;lastTickAt=now
        if(automatic && state.phase==PreparationPhase.COUNTDOWN) {
            if(lastFrameAt==Long.MIN_VALUE || now-lastFrameAt>750) {
                if(invalidAt==null)invalidAt=if(lastFrameAt==Long.MIN_VALUE)now else lastFrameAt+750
                frameAccepted=false;recoveryAt=null
                if(holdReason==null)holdReason="몸이 화면에서 벗어났어요."
                state=state.copy(trackingHold=true,message=holdReason!!)
            }
            if(invalidAt?.let{now-it>=RESTART_AFTER_MS}==true) { restart();return state }
        }
        if(state.phase==PreparationPhase.COUNTDOWN && (!automatic || (frameAccepted && !state.trackingHold))) {
            remainingMs=(remainingMs-elapsed).coerceAtLeast(0)
            state=state.copy(phase=if(remainingMs==0L)PreparationPhase.STARTED else PreparationPhase.COUNTDOWN,
                seconds=ceil(remainingMs/1000.0).toInt(),progress=1f-remainingMs.toFloat()/durationMs)
        }
        return state
    }
    companion object {
        /** 자동 카운트다운(§89) — 안내를 이미 들었고 범위·안정이 확인된 뒤라 3초. 직접 시작은 [manual] 의 5~30초 그대로. */
        const val COUNTDOWN_MS = 3_000L
        /** 카운트다운 중 판정이 이만큼 끊기면 안내로 돌아간다(§89, 종전 1.2 s). 그 사이는 멈췄다가 이어서 센다. */
        const val RESTART_AFTER_MS = 2_500L
    }
}

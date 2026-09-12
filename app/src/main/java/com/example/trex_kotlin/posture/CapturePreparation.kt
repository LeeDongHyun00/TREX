package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.ceil

/** 촬영 범위만 확인한다. 방향의 정답·자세의 옳고 그름·개인 기준을 판정하지 않는다. */
data class CaptureFrame(val ready: Boolean, val message: String, val anchors: List<Float> = emptyList(), val facing: List<Float> = emptyList())

object CaptureFraming {
    fun inspect(sample: PoseSample, capture: CapturePosition, floor: Boolean): CaptureFrame {
        fun missing() = CaptureFrame(false, "머리와 손발이 모두 보이게 자리 잡아 주세요.")
        if (sample.features.isEmpty() || sample.normalizedXy.size < 66 || sample.visibility.size < 33) return missing()
        fun visible(i: Int) = sample.visibility[i].isFinite() && sample.visibility[i] >= .55f &&
            sample.normalizedXy[i*2].isFinite() && sample.normalizedXy[i*2+1].isFinite()
        val head = listOf(0, 7, 8).firstOrNull(::visible) ?: return missing()
        val left = listOf(11, 13, 15, 23, 25, 27, 31)
        val right = listOf(12, 14, 16, 24, 26, 28, 32)
        val side = capture == CapturePosition.SIDE || capture == CapturePosition.FLOOR_SIDE
        // 옆모습에서는 같은 쪽 관절 사슬을 확인한다. 좌우를 섞어 가림을 통과시키지 않는다.
        val body = if (side) listOf(left, right).filter { chain -> chain.all(::visible) }
            .maxByOrNull { chain -> chain.sumOf { sample.visibility[it].toDouble() } } ?: return missing()
        else (left + right).also { if (!it.all(::visible)) return missing() }
        val points = (listOf(head) + body).flatMap { listOf(sample.normalizedXy[it*2], sample.normalizedXy[it*2+1]) }
        if (points.any { it < .04f || it > .96f }) return CaptureFrame(false, "한 걸음 뒤로 이동해 주세요.")
        val xs = points.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = points.filterIndexed { i, _ -> i % 2 == 1 }
        val width = xs.max() - xs.min(); val height = ys.max() - ys.min()
        if ((if (floor) maxOf(width, height) else height) < .35f)
            return CaptureFrame(false, "몸이 조금 더 크게 보이도록 가까이 와 주세요.")
        // 좌우 가시성이 바뀌어도 안정성 비교의 길이/의미를 일정하게 유지한다.
        val facing = if (floor) emptyList() else listOfNotNull(sample.features["view_cos"], sample.features["view_sin"])
        return CaptureFrame(true, "촬영 범위가 확인됐어요.", listOf(xs.min(), ys.min(), xs.max(), ys.max()),
            facing.takeIf { it.size == 2 && it.all(Float::isFinite) }.orEmpty())
    }
}

enum class PreparationPhase { IDLE, WAITING, COUNTDOWN, STARTED }

/** 검증된 서서 정면/전방 사선만 보조 확인한다. 바닥·순수 측면·불명은 임의로 각도를 인증하지 않는다. */
class CaptureDirectionWindow(private val capture: CapturePosition, private val floor: Boolean) {
    private val frames = ArrayDeque<Map<String, Float>>()
    fun clear() = frames.clear()
    fun check(frame: CaptureFrame, features: Map<String, Float>): CaptureFrame {
        if (floor || capture !in listOf(CapturePosition.FRONT, CapturePosition.RIGHT_FRONT, CapturePosition.LEFT_FRONT)) return frame
        if (!frame.ready || !features.containsKey("view_cos") || !features.containsKey("view_sin")) { clear();return frame }
        frames.addLast(features);while(frames.size>8)frames.removeFirst()
        val estimate=ViewEstimator.estimate(frames.toList()) ?: return frame
        if(estimate.r<.9f || estimate.cls==ViewEstimator.ViewClass.UNKNOWN)return frame
        val expected=when(capture) {
            CapturePosition.RIGHT_FRONT->ViewEstimator.ViewClass.B
            CapturePosition.LEFT_FRONT->ViewEstimator.ViewClass.D
            else->ViewEstimator.ViewClass.C
        }
        return if(estimate.cls==expected)frame else frame.copy(ready=false,message=capture.placement)
    }
}

data class PreparationState(
    val phase: PreparationPhase = PreparationPhase.IDLE,
    val seconds: Int = 0, val progress: Float = 0f,
    val message: String = "몸이 화면에 잡히면 5초 뒤 시작해요.",
    val trackingHold: Boolean = false, val restarts: Int = 0,
)

/** 잠깐 가려지면 남은 시간을 보존한다. 계속 가려지면 안내로 돌아가며 5초를 새로 준다. */
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
    private var remainingMs = 5000L
    private var durationMs = 5000L
    private var lastTickAt = 0L
    private var frameAccepted = false
    fun cancel() { state=PreparationState();resetObservation() }
    private fun resetObservation() {
        stableAt=null;anchor=emptyList();facing=emptyList();lastFrameAt=Long.MIN_VALUE
        invalidAt=null;recoveryAt=null;frameAccepted=false
    }
    fun arm(now: Long) {
        resetObservation();armedAt=now;lastTickAt=now;automatic=true
        state=PreparationState(PreparationPhase.WAITING)
    }
    fun manual(now: Long, seconds: Int) {
        resetObservation();automatic=false;lastTickAt=now
        durationMs=seconds.coerceIn(5,30)*1000L;remainingMs=durationMs
        state=PreparationState(PreparationPhase.COUNTDOWN,(remainingMs/1000).toInt(),message="카운트가 끝나면 운동을 시작해 주세요.")
    }
    private fun restart() {
        val count=state.restarts+1
        stableAt=null;anchor=emptyList();facing=emptyList();invalidAt=null;recoveryAt=null
        state=PreparationState(PreparationPhase.WAITING,message="안내한 방향으로 다시 자리 잡아 주세요.",restarts=count)
    }
    private fun moved(frame: CaptureFrame): Boolean {
        val shifted=anchor.size==frame.anchors.size && anchor.indices.any { abs(anchor[it]-frame.anchors[it])>.075f }
        val turned=facing.size==2 && frame.facing.size==2 && facing.zip(frame.facing).sumOf { (a,b)->(a*b).toDouble() } < .906
        return shifted || turned
    }
    fun observe(now: Long, frame: CaptureFrame) {
        if(!automatic || now<armedAt || state.phase !in listOf(PreparationPhase.WAITING,PreparationPhase.COUNTDOWN))return
        if(lastFrameAt!=Long.MIN_VALUE && now<=lastFrameAt)return
        lastFrameAt=now
        frameAccepted=frame.ready && !(state.phase==PreparationPhase.COUNTDOWN && moved(frame))
        if(!frameAccepted) {
            stableAt=null;recoveryAt=null
            if(state.phase==PreparationPhase.COUNTDOWN) {
                if(invalidAt==null)invalidAt=now
                state=state.copy(trackingHold=true,message=if(frame.ready)"몸의 방향을 다시 맞춰 주세요." else frame.message)
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
        if(now-(stableAt ?: now)>=400) {
            remainingMs=5000;durationMs=5000;lastTickAt=now
            state=state.copy(phase=PreparationPhase.COUNTDOWN,seconds=5,progress=0f,message="곧 시작해요.")
        }
    }
    fun tick(now: Long): PreparationState {
        val delta=(now-lastTickAt).coerceAtLeast(0)
        val elapsed=if(automatic)delta.coerceAtMost(500)else delta;lastTickAt=now
        if(automatic && state.phase==PreparationPhase.COUNTDOWN) {
            if(lastFrameAt==Long.MIN_VALUE || now-lastFrameAt>750) {
                if(invalidAt==null)invalidAt=if(lastFrameAt==Long.MIN_VALUE)now else lastFrameAt+750
                frameAccepted=false;recoveryAt=null
                state=state.copy(trackingHold=true,message="몸이 다시 보이면 이어서 시작해요.")
            }
            if(invalidAt?.let{now-it>=1200}==true) { restart();return state }
        }
        if(state.phase==PreparationPhase.COUNTDOWN && (!automatic || (frameAccepted && !state.trackingHold))) {
            remainingMs=(remainingMs-elapsed).coerceAtLeast(0)
            state=state.copy(phase=if(remainingMs==0L)PreparationPhase.STARTED else PreparationPhase.COUNTDOWN,
                seconds=ceil(remainingMs/1000.0).toInt(),progress=1f-remainingMs.toFloat()/durationMs)
        }
        return state
    }
}

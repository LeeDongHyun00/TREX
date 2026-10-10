package com.example.trex_kotlin

import android.media.ToneGenerator
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.trex_kotlin.posture.AlignmentSnapshot
import com.example.trex_kotlin.posture.FloorGaze
import com.example.trex_kotlin.posture.FloorGazeVoice
import com.example.trex_kotlin.posture.FloorChain
import com.example.trex_kotlin.posture.FloorCycleTracker
import com.example.trex_kotlin.posture.FloorFeatureExtractor
import com.example.trex_kotlin.posture.FloorProfile
import com.example.trex_kotlin.posture.MotionSpec
import com.example.trex_kotlin.posture.MotionOrigin
import com.example.trex_kotlin.posture.PlankGazeVoice
import com.example.trex_kotlin.posture.FloorReasons
import com.example.trex_kotlin.posture.HoldAnnounce
import com.example.trex_kotlin.posture.HoldVoice
import com.example.trex_kotlin.posture.PlankHoldClock
import com.example.trex_kotlin.posture.PlankHoldEvent
import com.example.trex_kotlin.posture.PlankHoldSnapshot
import com.example.trex_kotlin.posture.PoseSample
import com.example.trex_kotlin.posture.PostureScope
import com.example.trex_kotlin.posture.RejectCueGate
import com.example.trex_kotlin.posture.RepCounter
import com.example.trex_kotlin.posture.SpeechCoach

/**
 * 플랭크 HUD(spec §99, `docs/FLOOR_FAMILY_DESIGN.md` §6) — 큰 숫자 = 카메라가 확인한 시간 / 목표, 멈춘 동안 회색, 아래 상태 줄과 작게 경과.
 * [camera] 가 false 면 시계 폴백(카메라 미확인) — HUD 는 종전 남은 시간을 보인다.
 */
@Immutable
internal data class HoldHud(val heldSec: Int, val wallSec: Int, val stopped: Boolean, val status: String?, val camera: Boolean) {
    /**
     * 남은 시간 다리([HoldBridge])가 분석 소식 20 s 끊김으로 벽시계로 넘어갔을 때의 HUD — 시계 자신은 그 사실을 모른다(분석 스레드가 멈췄다). 숫자는 종전 남은 시간,
     * 상태 줄은 폴백과 같은 '카메라 미확인'(리뷰 2026-10-07 — 전에는 멈춘 카메라 시간 0:00 회색인 채로 세트가 벽시계로 끝났다).
     */
    fun bridgedToClock(): HoldHud = copy(stopped = false, status = CLOCK_STATUS, camera = false)

    companion object { const val CLOCK_STATUS = "카메라 미확인 · 시계로 재요" }
}

/**
 * 바닥 계열 범위 문장의 세트 시작 음성(설계 §6 — Q1 이 '침묵' 이라 지금 들리던 참고 음성이 사라지는 것을 '교정 없음' 퇴행으로 받아들이지 않게) — 종목마다
 * [REPEAT_AFTER_MS] 에 한 번(준비 안내 `PreparationIntro` 와 같은 주기). 앱 프로세스 수명.
 * 30분 간격은 **실제로 말했을 때** 시작한다([mark]) — 플랭크는 "시간을 재기 시작해요" 뒤에 붙여 말하므로 그 사건이 오지 않은 세트(폴백·확인 전 ✓)에서 미리 소진하면
 * 한 번도 들리지 않은 문장이 30분 동안 다시 나오지 않았다(리뷰 2026-10-07).
 */
internal object FloorScopeVoice {
    private const val REPEAT_AFTER_MS = 30 * 60_000L
    private val spokenAt = HashMap<String, Long>()
    /** 지금 말할 범위 문장(간격 안이면 null) — 소진하지 않는다. */
    @Synchronized fun peek(exercise: String, now: Long): String? {
        val line = PostureScope.floorLine(exercise) ?: return null
        if (spokenAt[exercise]?.let { now - it <= REPEAT_AFTER_MS } == true) return null
        return line
    }
    /** 말했다 — 간격을 시작한다. */
    @Synchronized fun mark(exercise: String, now: Long) { spokenAt[exercise] = now }
    /** 바로 말할 때 — [peek] + [mark]. */
    @Synchronized fun take(exercise: String, now: Long): String? = peek(exercise, now)?.also { mark(exercise, now) }
    /** 테스트용 — 기록을 지운다. */
    @Synchronized fun forget(exercise: String) { spokenAt.remove(exercise) }
}

/**
 * 바닥 계열(spec §99, 설계 §8 PostureLive (2)·(5)·(6)·(9))의 라이브 상태 — `PostureLiveSessionScreen` 의 지역 변수를 늘리지 않으려고(dex 레지스터 →
 * 기기 VerifyError, CLAUDE.md 2026-10-02) 한 홀더에 모은다. 분석 스레드가 쓰고 화면이 읽는 값([holdHud]·[rejectNote])은 Compose 상태다.
 *  - 종목은 **세트 시작 시점 값**([startSet]) — 이 화면은 종목이 바뀌어도 재생성되지 않을 수 있다(CLAUDE.md). 전에는 바닥 피처의 종목을 비교 추적기에서 받아
 *    추적기가 생기기 전엔 "" 였다.
 *  - 준비 프레임: 세 종목은 관측 층 피처를 별도 추출기로 계산해 누운 기준 시드에 쓴다(세트 추출기의 쪽 잠금·접지선 이력을 준비 동작으로 오염시키지 않는다).
 *  - 플랭크 유지 시계를 판정 칸(300 ms)마다 돌리고(사람이 없어도), 사건을 음성·틱으로, 인정 시간을 HUD·[HoldBridge] 로 낸다.
 */
internal class FloorLiveState {
    /** 이 세트의 종목(AIHub 이름). */
    @Volatile var exercise: String = ""
        private set
    @Volatile var clock: PlankHoldClock? = null
        private set
    /** 이 세트의 운동 id(`…::set:N`) — [HoldBridge] 키. */
    @Volatile var workoutKey: String? = null
        private set
    @Volatile private var targetMs = 0L
    @Volatile private var bridge: HoldBridge? = null
    @Volatile private var guideQuietUntil = 0L
    private val prepExtractor = FloorFeatureExtractor()
    /** 세트 추출기(라이브 화면이 세트 구성 때 알린다) — 세트 마감이 쪽 잠금 교체 수를 읽는다. */
    @Volatile var workExtractor: FloorFeatureExtractor? = null
    /** 숫자 읽기 설정(화면 토글의 거울 — 분석 스레드가 플랭크 시간 읽기에 쓴다). 바닥 종목 기본 켬(화면을 못 본다). */
    @Volatile var speakNumbers = false
    /** 바닥 피드백의 관측 문장 음성을 쓸 것인가 — 바닥 계열 세 종목은 신호 결측 안내·유지 시계가 맡아 끈다(같은 말을 두 번 하지 않게). */
    val legacyVoice: Boolean get() = FloorChain.Kind.of(exercise) == null
    /** 판별 기각의 틱·이유 간격(설계 §4.4) — `RejectionCues` 가 쓴다. 말한 사유의 다음 센 회는 "교정됐어요"(§100, [RejectCueGate.takeRecovery]). */
    val rejectGate = RejectCueGate()
    private val holdGate = RejectCueGate(HoldVoice.STOP_GAP_MS)   // 멈춤마다 말한다(§101c) — 사유별 2 s
    private var announcedMs = 0L
    /** 플랭크 멈춤의 이유를 **말했으면** 그 사유(§100) — 재개 때 `HoldVoice.resumeCue`. 말하지 않은 멈춤(6 s 간격 안)·폴백·새 세트는 null. */
    @Volatile private var stopSpoken: String? = null
    /** 시선 음성(§100·§101c) — 플랭크는 정렬 고개 항목 + 화면 봄, 크런치·레그 레이즈는 센 회의 화면 봄(`faceCam`). COACH 만. */
    val plankGaze = PlankGazeVoice()
    val floorGaze = FloorGazeVoice()
    /** 플랭크 판정 칸의 시선 상태(§101d) — 옆얼굴 검출 이력을 세트마다 둔다. 반복 종목은 추적기 안에 있다. */
    /** 화면 칩(§101d 교정 가시성): "시선 · 정면/화면 쪽/반대쪽/측정 중". 분석 스레드가 쓰고 화면이 읽는다. */
    var gazeNote by mutableStateOf<String?>(null)
        private set
    /** 말한 문장의 그림(§101 화살표) — 분석 스레드가 [takeCue] 로 한 번 읽고 비운다. [cueClear] 는 재개·교정에서 지우라는 표시. 홀더 필드라 화면 지역 변수가 늘지 않는다. */
    @Volatile private var cueOut: MotionSpec? = null
    @Volatile private var cueClear = false
    fun takeCue(): MotionSpec? = cueOut?.also { cueOut = null }
    fun takeCueClear(): Boolean = cueClear.also { cueClear = false }

    var holdHud by mutableStateOf<HoldHud?>(null)
    /** HUD '세지 않은 동작 n · {사유}'(크런치·레그 레이즈 CYCLE 분기). */
    var rejectNote by mutableStateOf<String?>(null)
    /** 세트 마감([finishSet])에 굳힌 시계 사본 — 리포트·로그가 읽는다. 다리가 벽시계로 넘어갔으면 출처도 clock. */
    @Volatile var finishedHold: PlankHoldSnapshot? = null
        private set

    /** 이 세트([workoutKey])를 아직 시작하지 않았으면 [startSet] — 준비·WORK 효과가 둘 다 부른다(두 번째는 아무것도 안 한다). */
    fun ensureSet(exercise: String, floor: Boolean, workoutKey: String, targetMs: Long) {
        if (this.workoutKey == workoutKey && this.exercise == exercise) return
        startSet(exercise, floor, workoutKey, targetMs)
    }

    /** 이 세트의 쪽 잠금 교체 수(바닥 계열 세 종목) — 세트 마감·로그. */
    fun chainSwitches(): Int = workExtractor?.chainSwitches ?: 0

    /** 세트 시작(메인 스레드). [targetMs] = 시간 목표(플랭크), 아니면 0. 숫자 읽기는 바닥 기본값(켬)으로 — 화면 토글이 고친다. */
    fun startSet(exercise: String, floor: Boolean, workoutKey: String, targetMs: Long) {
        this.exercise = exercise; this.workoutKey = workoutKey; this.targetMs = targetMs; speakNumbers = floor
        synchronized(prepExtractor) { prepExtractor.reset() }
        rejectGate.reset(); synchronized(holdGate) { holdGate.reset(); announcedMs = 0L; stopSpoken = null; plankGaze.reset(); floorGaze.reset() }
        gazeNote = null
        cueOut = null; cueClear = false
        clock = if (floor && FloorChain.Kind.of(exercise) == FloorChain.Kind.PLANK) PlankHoldClock() else null
        holdHud = clock?.let { hud(it) }
        rejectNote = null
        finishedHold = null
    }

    /** 준비 단계의 판정 칸 — 바닥 계열 세 종목이면 관측 층 피처(누운 기준 시드 재료, 설계 §4.2 '준비 시드'), 아니면 null. */
    fun prepFeatures(s: PoseSample, now: Long): Map<String, Float>? {
        if (!s.detected || FloorChain.Kind.of(exercise) == null) return null
        return synchronized(prepExtractor) {
            prepExtractor.computeForExercise(exercise, s.normalizedXy, s.visibility, s.imageWidth, s.imageHeight,
                FloorChain.gravityUp(s.up, s.upFromGravity, s.upFlipped), now)
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * WORK 시작(메인 스레드) — 플랭크는 시계 시작(폴백 20 s 의 기준)과 [HoldBridge] 묶기, 세 종목은 범위 문장(30분에 한 번).
     * 반복 종목은 바로 대기열로 말하고, 플랭크는 "시간을 재기 시작해요" 뒤에 붙인다(보류된 시작 안내가 범위 문장 뒤에서 버려지지 않게) — 30분 간격은 그때 시작한다.
     */
    fun startWork(now: Long, bridge: HoldBridge?, speech: SpeechCoach, silent: Boolean) {
        val c = clock
        if (c != null) {
            c.start(now)
            val key = workoutKey
            if (HoldBridge.ENABLED && bridge != null && key != null) { this.bridge = bridge; bridge.bind(key) }
        }
        // §101c(사용자 결정 2026-10-09 오후 "초반에 다른 운동과 달리 시험 대사들이 추가되어 있는데 제거해"): 범위 문장("…은 볼 수 없어요")은 더는 말하지 않는다 — 준비 화면의 세는 조건 카드에만 남는다(원칙 #5 의 화면판)
    }

    /**
     * 세트 마감(`PostureLive` finalize — ✓·자동 넘김·종료 요청) — 시계 사본을 굳히고([finishedHold]), 남은 시간 다리를 풀고, HUD 를 종전 남은 시간으로 돌린다.
     * 종료 확인에서 '취소' 하면 세트는 이어지지만 분석 루프는 마감 뒤라 시계를 더 부르지 않는다 — 다리를 묶어 두면 카메라 모드로 20 s 동안 0 을 넘기다 벽시계로 갔고
     * HUD 는 마지막 카메라 시간에 멈췄다(리뷰 2026-10-07). 풀면 그 뒤는 벽시계(종전)다. 다리가 이미 벽시계로 넘어갔으면(분석 소식 20 s 끊김) 리포트 출처도 clock 이다.
     */
    fun finishSet() {
        finishedHold = clock?.snapshot()?.let { s ->
            if (s.source == PlankHoldClock.SOURCE_CAMERA && bridge?.clockFor(workoutKey) == true) s.copy(source = PlankHoldClock.SOURCE_CLOCK) else s
        }
        release()
        holdHud = null
    }

    /** HUD 가 그릴 플랭크 상태 — 다리가 분석 소식 끊김으로 벽시계로 넘어갔으면 '카메라 미확인'(시계 자신은 모른다, [HoldHud.bridgedToClock]). */
    fun hudNow(): HoldHud? = holdHud?.let { h -> if (h.camera && bridge?.clockFor(workoutKey) == true) h.bridgedToClock() else h }

    fun pause(now: Long) { clock?.pause(now) }

    /** 재개 — 시계가 일시정지를 건너뛰고, 신호 결측 안내는 [FloorCycleTracker.SIGNAL_LOST_MS] 동안 쉰다(멈춘 동안 끊긴 신호를 재개 직후 말하지 않게). */
    fun resume(now: Long) { clock?.resume(now); guideQuietUntil = now + FloorCycleTracker.SIGNAL_LOST_MS }

    /** 화면을 떠남 — 브리지를 푼다(같은 계획으로 다시 시작한 세션이 옛 인정 시간을 읽지 않게). */
    fun release() { workoutKey?.let { k -> bridge?.release(k) } }

    /** 정렬 검사(`PlankAlignmentTracker`)가 이 칸을 볼 것인가 — 시계가 HOLD 이고 이 칸에 멈춤 사유가 없을 때만(설계 §5 'HOLD 칸만'). 시계가 없으면 true. */
    fun alignmentGate(features: Map<String, Float>): Boolean =
        clock?.let { it.phase == PlankHoldClock.Phase.HOLD && PlankHoldClock.stopReason(features) == null } ?: true

    /**
     * 플랭크 판정 칸(분석 스레드) — [features] = 그 칸의 바닥 피처, 사람이 안 잡혔으면 null(= 화면 밖). 사건을 말하고(두 모드, `speakLatest`, 멈춤은 사유별 6 s),
     * 10 s 마다 인정 시간을(숫자 읽기 설정, 대기열) 읽고, HUD·브리지를 갱신한다. [silent] = 음소거·검증 모드.
     */
    fun holdFrame(now: Long, features: Map<String, Float>?, speech: SpeechCoach, tone: ToneGenerator?, silent: Boolean) {
        val c = clock ?: return
        val events = c.onFrame(now, features)
        for (e in events) {
            if (e.kind == PlankHoldEvent.Kind.FALLBACK) workoutKey?.let { k -> bridge?.publish(k, c.heldMs, camera = false) }
            // 폴백 뒤에는 시간이 벽시계로 간다 — 시작·멈춤·재개·안내를 말하면 시간이 멈춘 줄 안다(폴백 안내만, HoldVoice)
            if (silent || !HoldVoice.voiced(e.kind, c.source)) continue
            when (e.kind) {
                PlankHoldEvent.Kind.START -> {
                    tone(tone, ToneGenerator.TONE_PROP_ACK)
                    speech.speakLatest(PlankHoldClock.START_CUE)   // 범위 문장은 붙이지 않는다(§101c)
                }
                PlankHoldEvent.Kind.RESUME -> {
                    tone(tone, ToneGenerator.TONE_PROP_ACK)
                    // 이유를 말한 자세 멈춤(무릎·골반·솟음)의 재개 = 교정(§100). 그 밖의 재개는 종전처럼 톤만(재잘거림 방지). 교정은 들린 지적만(§101 장부). 화살표는 지운다
                    synchronized(holdGate) { HoldVoice.resumeCue(stopSpoken) { speech.recoverable("hold:$it") }.also { stopSpoken = null } }?.let { speech.speakLatest(it, setScoped = it == PlankHoldClock.RESUME_CONTINUE) }
                    cueClear = true
                }
                PlankHoldEvent.Kind.STOP -> {
                    // 말할 문장이 없는 멈춤(not_prone — 5 s 뒤 한 번 말한다)은 틱도 없다
                    val cue = synchronized(holdGate) { HoldVoice.stopCue(holdGate, now, e.reason, c).also { stopSpoken = if (it.speech != null) e.reason else null } }
                    if (cue.tick) tone(tone, ToneGenerator.TONE_PROP_NACK)
                    cue.speech?.let { speech.speakLatest(it, heardKey = "hold:${e.reason}") }
                    // 말한 멈춤의 화살표(§101) — 재개까지, 상한 60 s
                    if (cue.speech != null) PlankHoldClock.motionFor(e.reason)?.let { cueOut = MotionSpec(it, MotionOrigin.HOLD, ttlMs = MotionSpec.HOLD_TTL_MS) }
                }
                PlankHoldEvent.Kind.FALLBACK -> { synchronized(holdGate) { stopSpoken = null }; PlankHoldClock.cueFor(e)?.let(speech::speakLatest) }
                PlankHoldEvent.Kind.REMIND, PlankHoldEvent.Kind.HINT -> PlankHoldClock.cueFor(e)?.let(speech::speakLatest)
            }
        }
        val camera = c.source == PlankHoldClock.SOURCE_CAMERA
        workoutKey?.let { k -> bridge?.publish(k, c.heldMs, camera) }
        val shown = if (camera) c.heldMs else c.wallMs
        val say = synchronized(holdGate) { HoldAnnounce.between(announcedMs, shown, targetMs).also { announcedMs = maxOf(announcedMs, shown) } }
        if (say != null && !silent) {
            if (speakNumbers && speech.ready) speech.speak(say.text, flush = false, key = "n")
            else tone(tone, if (say.countdown) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK)
        }
        holdHud = hud(c)
    }

    /**
     * 크런치·레그 레이즈 판정 칸(분석 스레드, 사람이 없어도) — 신호 결측(3 s, 15 s 간격)·누운 기준 없음(세트에 한 번) 안내를 말하고 그 문장을 돌려준다(화면).
     * 카운터 락([lock])을 잡고 추적기를 부른다.
     */
    fun repGuide(now: Long, rc: RepCounter?, lock: Any, speech: SpeechCoach, silent: Boolean): String? {
        val ft = rc?.floorTracker ?: return null
        if (now < guideQuietUntil) return null
        val cue = synchronized(lock) { ft.guideCue(now) } ?: return null
        if (!silent) speech.speakLatest(cue)
        return cue
    }

    /** 새 판별 기각 — HUD 줄을 갱신한다. */
    fun onRejected(profile: FloorProfile?, total: Int, reason: String?) {
        rejectNote = "세지 않은 동작 $total · " + FloorReasons.repLabel(profile, reason)
    }

    /**
     * 플랭크 시선(§100) — 판정 칸마다 정렬 스냅샷의 고개 항목을 [PlankGazeVoice] 에 넘긴다(COACH 만, 음소거·검증·일시정지면 상태도 움직이지 않는다).
     * 정렬 추적기가 HOLD 칸·측면에서만 판정하므로 멈춘 동안·무릎을 댄 동안은 조용하다. 말한 문장을 돌려준다(화면).
     */
    fun gazeFrame(now: Long, snapshot: AlignmentSnapshot, features: Map<String, Float>?, speech: SpeechCoach, silent: Boolean, coach: Boolean): String? {
        if (clock == null) return null
        val gaze = features?.let { FloorGaze.classify(it) } ?: FloorGaze.UNKNOWN
        if (!coach || silent) { gazeNote = null; return null }
        val cue = synchronized(plankGaze) { plankGaze.frame(now, snapshot, gaze) { speech.recoverable("gaze:plank") } }
        gazeNote = gazeChip(gaze, plankGaze.lastWrong != FloorGaze.UNKNOWN, snapshot.held && plankGaze.wrongForMs(now) > 0L)
        if (cue == null) return null
        if (PlankGazeVoice.isRecovery(cue)) { speech.speakLatest(cue); cueClear = true }
        else { speech.speakLatest(cue, heardKey = "gaze:plank"); cueOut = MotionSpec(PlankGazeVoice.motionFor(plankGaze.lastSide), MotionOrigin.GAZE) }
        return cue
    }

    /** 크런치·레그 레이즈 시선(§101c) — 센 회가 나온 프레임에서 마지막 센 회의 시선(`FloorRep.gaze`)을 [FloorGazeVoice] 에 넘긴다(COACH 만, 횟수는 막지 않는다). 말한 문장을 돌려준다(화면). */
    fun gazeRep(now: Long, rc: RepCounter?, speech: SpeechCoach, silent: Boolean, coach: Boolean): String? {
        val ft = rc?.floorTracker ?: return null
        if (!coach || silent) return null
        val rep = ft.repDetail.lastOrNull() ?: return null
        val cue = synchronized(floorGaze) { floorGaze.rep(now, rep, ft.profile) { speech.recoverable("gaze:floor") } }
        gazeNote = gazeChip(rep.gaze, floorGaze.lastWrong != FloorGaze.UNKNOWN, floorGaze.wrongStreak > 0)
        if (cue == null) return null
        if (FloorGazeVoice.isRecovery(cue)) { speech.speakLatest(cue); cueClear = true }
        else { speech.speakLatest(cue, heardKey = "gaze:floor"); cueOut = MotionSpec(FloorGazeVoice.motionFor(ft.profile), MotionOrigin.GAZE) }
        return cue
    }

    /** 시선 칩 문구(§101d) — 상태 + 지적 중이면 '교정 중', 틀린 시선이 이어지는 중이면 '돌아옴 대기'. 모름은 '측정 중', '화면 밖' = 얼굴이 카메라를 향하지 않음(정면·반대쪽 구분 불가, 10-10). */
    private fun gazeChip(state: Int, spoken: Boolean, wrongOngoing: Boolean): String =
        "시선 · " + FloorGaze.label(state) + when { spoken -> " · 교정 중"; wrongOngoing && FloorGaze.wrong(state) -> " · 돌아옴 대기"; else -> "" }

    /**
     * 바닥 피처 맵에 얼굴 메시 피처(§101d `face_*`)를 얹는다 — 바닥 경로는 `FloorChain` 피처만 새로 만들어 분석기 샘플의 `face_found`·`face_yaw` 가 빠졌다(10-09 19:00 세트: 얼굴 모델은 돌았는데 로그·시선에 키 없음).
     * `PostureLiveSessionScreen` 의 지역 변수를 늘리지 않으려고 여기 둔다.
     */
    fun withFace(floor: Map<String, Float>, sample: Map<String, Float>): Map<String, Float> {
        val found = sample[FloorGaze.FOUND] ?: return floor
        val out = HashMap<String, Float>(floor.size + 3)
        out.putAll(floor)
        out[FloorGaze.FOUND] = found
        sample[FloorGaze.YAW]?.let { out[FloorGaze.YAW] = it }
        sample[FloorGaze.INFER_MS]?.let { out[FloorGaze.INFER_MS] = it }
        sample[FloorGaze.ROTATION]?.let { out[FloorGaze.ROTATION] = it }
        sample[FloorGaze.SIZE]?.let { out[FloorGaze.SIZE] = it }
        return out
    }

    /** 바닥 반복 종목의 횟수 줄(HUD countNote) — 세지 않은 동작 + 시선 칩. */
    val floorNote: String? get() = listOfNotNull(rejectNote, gazeNote).joinToString(" · ").ifEmpty { null }

    private fun hud(c: PlankHoldClock): HoldHud {
        val camera = c.source == PlankHoldClock.SOURCE_CAMERA
        val status = if (!camera) HoldHud.CLOCK_STATUS else listOfNotNull(FloorReasons.holdStatus(c.phase, c.reason), gazeNote).joinToString(" · ").ifEmpty { null }
        return HoldHud((c.heldMs / 1000L).toInt(), (c.wallMs / 1000L).toInt(), c.phase != PlankHoldClock.Phase.HOLD, status, camera)
    }

    private fun tone(t: ToneGenerator?, kind: Int) { runCatching { t?.startTone(kind, 70) } }
}

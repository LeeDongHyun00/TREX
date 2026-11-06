package com.example.trex_kotlin.posture

import com.example.trex_kotlin.locked
import com.example.trex_kotlin.nowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlin.concurrent.Volatile

/**
 * 플랫폼 음성 엔진(§108c) — Android TextToSpeech(`AndroidSpeech.kt`) / iPhone AVSpeechSynthesizer(ios-ui `IosSpeechEngine`).
 * 무엇을 언제 말할지(준비 전 대기·대기열·들림 장부·세트 끝 꼬리·감시 시한)는 [SpeechCoach] 가 두 플랫폼 같게 정하고, 엔진은 말하기와 그 사건만 맡는다.
 * 사건 콜백은 아무 스레드에서나 올 수 있다.
 */
interface SpeechEngine {
    /** 엔진을 준비한다 — 끝나면 [Events.onInit]. 생성 자체가 실패하면 그 자리에서 onInit(false, …) 를 부른다. */
    fun start(events: Events)
    /** 한 문장을 낸다. [flush] = 재생 중인 말을 끊고 이것만. 결과 코드 [SUCCESS] 가 아니면(또는 null) 사건이 오지 않는다. */
    fun speak(text: String, flush: Boolean, id: String): Int?
    fun stop()
    /** 엔진과 기록 스레드를 닫는다. */
    fun shutdown()
    /** 발화 동안만 다른 소리(헬스장 음악)를 낮춘다 — 실패해도 발화는 그대로 간다. */
    fun requestFocus()
    fun abandonFocus()
    /** 발화 id — 엔진 사건이 이 값으로 돌아온다. */
    fun nextUtteranceId(): String
    /** 판정·화면 상태와 실제 TTS 시작을 구별하는 개발 기록 한 줄([feedbackTraceLine])을 그날 파일에 비동기로 붙인다. 실패는 삼킨다. */
    fun trace(atMs: Long, kind: String, text: String, utteranceId: String?, muted: Boolean, ready: Boolean)

    interface Events {
        /** 준비 끝 — [ready] 면 한국어로 말할 수 있다. 못 쓰면 [error](개발 기록)·[unavailable](화면 문구). */
        fun onInit(ready: Boolean, error: String?, unavailable: String?)
        fun onStart(id: String?)
        /** 끝남·끊김·오류 — [kind] = tts_done | tts_stop | tts_error, [detail] 은 기록용. */
        fun onFinished(id: String?, kind: String, detail: String)
    }

    companion object {
        /** 낸 문장이 엔진에 들어갔다(Android TextToSpeech.SUCCESS 와 같은 값). */
        const val SUCCESS = 0
    }
}

/**
 * 세션 스피커 — 한국어 음성, 초기화 비동기. 두 플랫폼이 이 클래스를 같이 쓴다(§108c — Android 판의 정책 코드를 그대로 옮겼다).
 *
 * 세 가지를 함께 다룬다(§31 라이브 신뢰성):
 *  - **준비 전 큐**: 초기화가 비동기라 세트 시작 안내가 통째로 버려졌다. ready 전 요청은 최대 [MAX_PENDING] 건
 *    담아 두고, 초기화 직후 **[PENDING_TTL_MS] 이내 요청만** 순서대로 말한다 — 더 오래된 안내는 이미 지난 상황이라
 *    지금 말하면 오히려 방해다.
 *  - **오디오 포커스**: 헬스장 음악 위로 들려야 한다. 발화 동안만 낮추고 마지막 발화가 끝나면 놓는다(엔진 몫).
 *  - **상태 노출**: 한국어 음성이 없으면 영원히 무음인데 화면이 이유를 말하지 못했다 → [unavailableReason].
 */
class SpeechCoach(private val engine: SpeechEngine) {

    private data class Pending(val text: String, val atMs: Long)

    private val lock = SynchronizedObject()

    /** 판정/화면 상태와 실제 TTS 시작을 구별하는 개발 로그. 음성 파형·영상은 저장하지 않는다. */
    fun traceFeedback(kind: String, text: String, utteranceId: String? = null) {
        runCatching { engine.trace(nowMillis(), kind, text, utteranceId, muted, ready) }
    }
    /** ready 전 대기 큐 (오래된 것부터). */
    private val pending = ArrayList<Pending>()
    /** 아직 끝나지 않은 발화 id — 비면 오디오 포커스를 놓는다. */
    private val speaking = LinkedHashSet<String>()

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var muted: Boolean = false

    @Volatile
    var lastError: String? = null
        private set

    /** 초기화 콜백이 한 번이라도 돌았는지 — "아직 준비 중" 과 "못 씀" 을 가르는 유일한 근거. */
    @Volatile
    private var initialized = false

    @Volatile
    private var unavailable: String? = null

    /** 초기화가 끝났는데 음성을 못 쓰는 이유(한국어). 아직 초기화 중이거나 정상이면 null — 화면 배너용. */
    val unavailableReason: String? get() = if (initialized) unavailable else null

    private val events = object : SpeechEngine.Events {
        override fun onInit(ready: Boolean, error: String?, unavailable: String?) {
            this@SpeechCoach.ready = ready
            if (error != null) lastError = error
            if (unavailable != null) this@SpeechCoach.unavailable = unavailable
            initialized = true
            flushPending()
        }
        override fun onStart(id: String?) { traceFeedback("tts_start", "", id); locked(lock) { if (id == currentId) queue.onStart(nowMillis()) } }
        override fun onFinished(id: String?, kind: String, detail: String) { traceFeedback(kind, detail, id); finished(id) }
    }

    /**
     * 말한다(spec §101 `SpeechQueue`). [flush] = 경계 안내(BOUNDARY — 재생 중인 말을 끊고 지금 이것만, 단 세트 끝 꼬리가 돌면 꼬리 뒤에), 아니면 카운트(COUNT — [key] 가 있으면 같은 키의 최신만).
     * 아직 준비 전이면 큐에 담아 뒀다가 초기화 직후 말한다 — 세트 시작 안내가 통째로 사라지지 않도록.
     */
    fun speak(text: String, flush: Boolean = true, key: String? = null) {
        traceFeedback("speech_requested",text)
        if (muted) return
        if (!ready) {
            // 초기화가 끝났는데도 못 쓰는 상태면 영원히 못 말한다 — 담아 둘 이유가 없다
            if (initialized) return
            locked(lock) {
                if (flush) pending.clear()          // flush = "지금 이것만" 이라는 뜻
                pending += Pending(text, nowMillis())
                while (pending.size > MAX_PENDING) pending.removeAt(0)
            }
            return
        }
        val now = nowMillis()
        val sub = locked(lock) { queue.request(SpeechQueue.Item(if (flush) SpeechQueue.Kind.BOUNDARY else SpeechQueue.Kind.COUNT, text, key = key, atMs = now), now) }
        if (sub != null) submit(sub) else traceFeedback("speech_queued", text)
    }

    /** 말하는 중이거나 말할 것이 남아 있는가 — **보류 코칭을 포함한다**(§101 — 종전엔 보류분을 빼서 자동 진행이 보류된 교정 문장을 기다리지 않았다). 감시 타이머도 여기서 돈다. */
    val isSpeaking: Boolean get() {
        val now = nowMillis()
        val sub = locked(lock) { queue.watchdog(now)?.also { traceFeedback("tts_timeout", "", currentId) } }
        if (sub != null) submit(sub)
        return locked(lock) { pending.isNotEmpty() || queue.busy }
    }

    /** 앱 쪽 대기열(§101) — 한 번에 하나만 엔진에 낸다. [lock] 으로 지킨다. */
    private val queue = SpeechQueue()
    /** 엔진에 낸 발화 id — 리스너가 이것만 대기열 사건으로 본다. */
    private var currentId: String? = null

    /** 세트 끝 꼬리가 돌고 있는가(자동 진행 뒤 마지막 말이 아직 남았다). */
    val tailActive: Boolean get() = locked(lock) { queue.tailActive(nowMillis()) }
    val tailRemainingMs: Long get() = locked(lock) { queue.tailRemainingMs(nowMillis()) }

    /** 목표 도달 — 대기 카운트를 정리하고 옛 화면의 새 발화를 봉인한다(t0+300 ms 뒤). [dropCounts] 는 시간 목표. */
    fun beginSetEnd(t0: Long, dropCounts: Boolean) { traceFeedback("set_end", if (dropCounts) "timed" else "reps"); locked(lock) { queue.beginSetEnd(t0, dropCounts) } }

    /** 자동 진행으로 화면을 넘기며 — 멈추지 않고 남은 말을 꼬리로 보호한다(상한 10 s). 다음 종목 안내는 꼬리 뒤에 붙는다. */
    fun handOff() {
        val now = nowMillis()
        traceFeedback("speech_handoff", "")
        locked(lock) { queue.handOff(now) }
    }

    /** 재생 중인 말만 남기고 대기·보류를 비운다(준비 화면 진입 — 직전 세트 꼬리는 지키고 묵은 대기는 버린다). */
    fun stopKeepingTail() { locked(lock) { pending.clear(); queue.stopKeepingCurrent() } }

    /** 이 지적(장부 키)의 교정 문장을 내도 되는가 — 재생을 시작한 지적만(§101 들림 장부). 시작도 못 한 지적은 거둔다. */
    fun recoverable(key: String): Boolean = locked(lock) { queue.recoverable(key) }.also { if (!it) traceFeedback("recovery_unheard", key) }

    /**
     * 코칭 문장용(§62c 후속 10) — 끊지도(QUEUE_FLUSH), 줄 세우지도(QUEUE_ADD) 않는다. 오늘 코칭 문장 72개 중 26개가 다음 코칭 문장에 잘렸고(반복 2~3 s 간격에
     * 문장은 5~7 s), 줄을 세우면 몇 회 뒤의 말이 된다. 말하는 중이면 보류분으로 쥐고 끝나면 말한다 — [HELD_TTL_MS] 넘게 묵은 보류분은 버린다(그 회는 지났다).
     * flush 발화(촬영 안내·세트 경계)는 보류분도 지운다.
     */
    fun speakLatest(text: String, heardKey: String? = null, setScoped: Boolean = false) {
        traceFeedback("speech_requested", text)
        if (muted) return
        if (!ready) {
            if (initialized) return
            locked(lock) { pending += Pending(text, nowMillis()); while (pending.size > MAX_PENDING) pending.removeAt(0) }
            return
        }
        val now = nowMillis()
        val sub = locked(lock) { queue.request(SpeechQueue.Item(SpeechQueue.Kind.COACH, text, heardKeys = listOfNotNull(heardKey), setScoped = setScoped, atMs = now), now) }
        if (sub != null) submit(sub) else traceFeedback("speech_held", text)
    }

    /** 사용자 조작용 전부 정지(수동 세트 끝·건너뛰기·나가기·일시정지·음소거) — 대기열·꼬리·장부를 비운다. */
    fun stop() {
        locked(lock) {
            pending.clear()
            speaking.clear()
            queue.stop(); currentId = null
        }
        runCatching { engine.stop() }
        engine.abandonFocus()
    }

    fun shutdown() {
        traceFeedback("shutdown","")
        locked(lock) {
            pending.clear()
            speaking.clear()
        }
        engine.shutdown()
        ready = false
    }

    /** 대기열이 고른 문장 하나를 엔진에 낸다 — flush 면 재생 중인 말을 끊는다(끊긴 발화는 끝남 대신 끊김 사건이 온다). */
    private fun submit(sub: SpeechQueue.Submit) {
        val id = engine.nextUtteranceId()
        traceFeedback("tts_submit", sub.item.text, id)
        locked(lock) {
            if (sub.flush) speaking.clear()
            speaking += id; currentId = id
        }
        engine.requestFocus()
        // 음성은 원본 존댓말을 읽는다. 화면의 공룡 어미 변환은 TrexText에서만 적용한다.
        val rc = runCatching { engine.speak(sub.item.text, sub.flush, id) }.getOrNull()
        // 발화가 시작조차 못 하면 사건이 안 오므로 여기서 포커스를 정리한다
        if (rc != SpeechEngine.SUCCESS) { traceFeedback("tts_rejected","code=$rc",id); finished(id) }
    }

    /** 초기화 직후 대기 큐를 흘려보낸다. 오래된 요청은 이미 지난 상황이라 버린다. */
    private fun flushPending() {
        val now = nowMillis()
        val due = locked(lock) {
            val out = pending.filter { now - it.atMs <= PENDING_TTL_MS }
            pending.clear()
            out
        }
        if (!ready || muted) return
        // 준비 전 요청은 순서대로 경계 안내처럼 — 첫 것만 flush
        for ((i, p) in due.withIndex()) speak(p.text, flush = i == 0)
    }

    private fun finished(utteranceId: String?) {
        val id = utteranceId ?: return
        val now = nowMillis()
        val next = locked(lock) {
            speaking.remove(id)
            if (id != currentId) return@locked null   // 끊긴(대체된) 발화의 뒤늦은 콜백
            currentId = null
            // 꼬리가 상한을 넘겼으면 꼬리의 카운트·보류분만 버린다 — 꼬리 뒤에 줄 세운 다음 세트의 안내(준비 첫 설명)는 이어서 낸다(종전 queue.stop() 은 그것까지 지웠다)
            if (queue.tailExpired(now)) { traceFeedback("tail_expired", ""); queue.expireTail() }
            queue.onDone(now)
        }
        // 대기열의 다음 문장이 있으면 이어서(엔진 콜백 스레드에서 제출해도 된다), 없으면 포커스를 놓는다
        if (next != null) submit(next) else if (locked(lock) { currentId == null }) engine.abandonFocus()
    }

    companion object {
        /** 대기 큐 최대 건수 — 밀린 안내를 몰아서 읽으면 오히려 방해다. */
        const val MAX_PENDING = 3
        /** 대기 요청 유효 시간(ms) — 이보다 오래된 안내는 이미 지난 상황. */
        const val PENDING_TTL_MS = 10_000L
        /** 코칭 보류분의 수명(ms, §62c 후속 10) — 반복 한두 회 안. 넘으면 그 회는 이미 지났다(`SpeechQueue.COACH_TTL_MS` 와 같은 수). */
        const val HELD_TTL_MS = SpeechQueue.COACH_TTL_MS
    }

    // 엔진 준비는 모든 필드가 정해진 뒤에 — 엔진이 그 자리에서 onInit 을 불러도(생성 실패·즉시 준비) 대기열이 이미 있다
    init {
        engine.start(events)
    }
}

/** 개발 기록 한 줄(JSON) — 두 플랫폼 엔진이 같은 형식으로 쓴다(`posture_logs/feedback-yyyyMMdd.jsonl`, 기기 시간대 날짜). */
fun feedbackTraceLine(atMs: Long, kind: String, text: String, utteranceId: String?, muted: Boolean, ready: Boolean): String =
    org.json.JSONObject().put("t_ms",atMs).put("kind",kind).put("text",text)
        .put("utterance_id",utteranceId).put("muted",muted).put("ready",ready).toString()

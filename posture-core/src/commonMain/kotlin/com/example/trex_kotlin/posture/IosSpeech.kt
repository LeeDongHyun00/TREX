package com.example.trex_kotlin.posture

/**
 * iOS 발화 경계(spec §107f) — Android `SpeechCoach` 의 **대기열 정책만** 공통 `SpeechQueue` 로 돌리고 TTS 엔진(AVSpeechSynthesizer)은 Swift 가 든다.
 * 왜 Swift 쪽 "최신 한 문장 보류" 로 두지 않는가: §101 의 들림 장부(지적이 재생을 시작한 뒤에만 교정 문장), 세트 끝 꼬리(마지막 교정을 자르지 않음), 쪽별 카운트의 같은 키 최신만,
 * 번갈아 내기가 전부 앱 쪽 대기열에서만 가능하고(엔진 대기열은 항목을 개별로 뺄 수 없다), Android 와 iOS 가 다른 발화 정책을 가지면 같은 판정이 다른 말로 들린다.
 *
 * 호출은 엔진 직렬 큐 한 곳에서만. [drain] 이 돌려주는 제출(`text`·`flush`)을 Swift 가 **한 번에 하나만** 엔진에 내고, 재생 시작·종결을 [onStart]·[onDone] 으로 돌려준다.
 * 준비 전([ready] = false, 음성 없음)이면 Android 처럼 숫자·문장 대신 호출자가 톤을 낸다. 음소거면 요청을 버린다.
 */
class IosSpeech {
    private val queue = SpeechQueue()
    private val out = ArrayList<SpeechQueue.Submit>()
    private var current: SpeechQueue.Submit? = null
    var muted = false
    /** 한국어 음성이 준비됐는가 — Swift 가 `AVSpeechSynthesisVoice(language: "ko-KR")` 유무로 정한다. */
    var ready = true

    /** 경계 안내(BOUNDARY, [flush] = 재생 중인 말을 끊고 지금 이것만 — 세트 끝 꼬리가 돌면 꼬리 뒤) 또는 카운트(COUNT, [key] 가 있으면 같은 키의 최신만). */
    fun speak(text: String, now: Long, flush: Boolean = true, key: String? = null) {
        if (muted || !ready || text.isEmpty()) return
        queue.request(SpeechQueue.Item(if (flush) SpeechQueue.Kind.BOUNDARY else SpeechQueue.Kind.COUNT, text, key = key, atMs = now), now)?.let(::submit)
    }

    /** 코칭 문장(COACH) — 끊지도 줄 세우지도 않고 보류 하나, 나중 것이 덮는다(§62c 후속 10). [heardKey] 는 들림 장부 키, [setScoped] 는 세트가 끝나면 틀린 말. */
    fun speakLatest(text: String, now: Long, heardKey: String? = null, setScoped: Boolean = false) {
        if (muted || !ready || text.isEmpty()) return
        queue.request(SpeechQueue.Item(SpeechQueue.Kind.COACH, text, heardKeys = listOfNotNull(heardKey), setScoped = setScoped, atMs = now), now)?.let(::submit)
    }

    private fun submit(sub: SpeechQueue.Submit) { current = sub; out += sub }

    /** 말하는 중이거나 말할 것이 남아 있는가 — 보류 코칭을 포함한다(§101). 감시 타이머도 여기서 돈다. */
    fun busy(now: Long): Boolean { queue.watchdog(now)?.let(::submit); return queue.busy }
    fun tailActive(now: Long): Boolean = queue.tailActive(now)
    fun tailRemainingMs(now: Long): Long = queue.tailRemainingMs(now)
    /** 이 지적의 교정 문장을 내도 되는가(§101 들림 장부). */
    fun recoverable(key: String): Boolean = queue.recoverable(key)

    /** Swift: 엔진이 재생을 시작했다. */
    fun onStart(now: Long) { queue.onStart(now) }
    /** Swift: 엔진이 끝냈다(완료·중단·오류) — 다음 제출은 [drain] 으로 나간다. 꼬리 상한을 넘겼으면 전부 멈춘다. */
    fun onDone(now: Long) {
        current = null
        if (queue.tailExpired(now)) { queue.stop(); return }
        queue.onDone(now)?.let(::submit)
    }
    /** 감시 타이머 — 종결 콜백이 누락됐으면 끝난 것으로 보고 다음을 제출한다. 엔진 tick 마다. */
    fun watchdog(now: Long) { queue.watchdog(now)?.let(::submit) }

    /** 목표 도달(t0) — 대기 카운트 정리·봉인(§101 U5). [dropCounts] 는 시간 목표. */
    fun beginSetEnd(t0: Long, dropCounts: Boolean) = queue.beginSetEnd(t0, dropCounts)
    /** 자동 진행·정상 시작으로 화면을 넘기며 — 남은 말을 꼬리로 보호한다(상한 10 s). */
    fun handOff(now: Long) = queue.handOff(now)
    /** 사용자 조작용 전부 정지(수동 세트 끝·건너뛰기·나가기·일시정지·음소거) — Swift 도 엔진을 즉시 멈춘다. */
    fun stop() { queue.stop(); current = null; out.clear() }
    /** 재생 중인 말만 남기고 비운다(준비 화면 진입). */
    fun stopKeepingCurrent() = queue.stopKeepingCurrent()

    /** 이번 호출에서 엔진에 낼 제출들(순서대로, 보통 0~1개). 비운다. */
    fun drain(): List<SpeechQueue.Submit> = ArrayList(out).also { out.clear() }
    /** 진단용 — 엔진에 있는 문장. */
    val playing: SpeechQueue.Item? get() = queue.playing
}

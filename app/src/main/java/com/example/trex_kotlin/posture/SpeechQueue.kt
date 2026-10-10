package com.example.trex_kotlin.posture

/**
 * 앱 쪽 발화 대기열 정책(spec §101 F2, 10-08 보고 '세트 끝 교정 멘트가 잘린다') — 순수 Kotlin, 안드로이드 무의존. `SpeechCoach` 가 이 결정에 따라 TTS 엔진에 **한 번에 하나만** 제출한다.
 *
 * 왜 엔진 대기열(QUEUE_ADD)이 아닌가: 엔진에 들어간 항목은 개별로 뺄 수 없고 `tts.stop()` 은 재생 중인 말까지 지운다. 그래서 묵은 카운트 정리·번갈아 내기·전달 확인·세트 끝 꼬리 보호는
 * 앱 쪽 대기열에서만 가능하다. 10-08: 목표 도달 자동 진행이 발화를 최대 2 s 만 기다리고 `speech.stop()` 으로 넘어가 §100 교정 문장(2.4~3.6 s)이 매번 잘렸고(스쿼트 2.19 s·컬 2.14 s 재생 뒤 끊김),
 * 한 다리 계열은 쪽별 카운트가 계속 쌓여 보류된 교정 문장이 굶었다(니업 카운트 지연 4.3~4.8 s).
 *
 * 발화 종류:
 *  - [Kind.BOUNDARY](flush 안내): 꼬리가 없으면 다 비우고 즉시. 꼬리가 있으면 꼬리 밖 항목만 비우고 **꼬리 뒤에** 말한다(다음 종목 안내가 마지막 교정을 자르지 않게).
 *  - [Kind.COUNT](key): 같은 key 의 **최신 것만** 남긴다(사용자 결정 U4). key 가 없으면 FIFO. [STALE_COUNT_MS] 넘게 묵은 중간 숫자는 버린다(가장 새 것은 유지).
 *  - [Kind.COACH]: 보류 하나, 나중 것이 덮는다(§62c 후속 10). 수명 [COACH_TTL_MS].
 * 다음 발화 선택은 **번갈아**: 방금 COACH 였고 대기 카운트가 있으면 카운트, 아니면 수명 안의 보류 COACH, 아니면 가장 오래된 카운트.
 *
 * 세트 끝(사용자 결정 U5 — 화면은 ≤ 2 s 에 넘기고 마지막 말은 끝까지, 상한 10 s):
 *  - [beginSetEnd]: 대기 카운트는 가장 새 것 하나만(시간 목표는 전부 버림), 세트가 끝나면 틀린 말이 되는 줄(`setScoped` — "이제 왼쪽 동작을…")을 뺀다, t0+[SEAL_AFTER_MS] 뒤 BOUNDARY 가 아닌 새 요청을 봉인한다.
 *  - [handOff]: 남은 것을 '꼬리' 로 보호한다 — 수명 무시, 상한 t0+[TAIL_MAX_MS]. 봉인은 꼬리가 끝나거나 다음 BOUNDARY 가 오면 풀린다.
 *  - 수동 '세트 끝'·건너뛰기·나가기·일시정지·음소거는 [stop] — 즉시 전부 정지.
 *
 * 들림 장부(heardKey → [Status]): 교정 문장("교정됐어요")은 짝 지적이 **재생을 시작한 뒤에만** 낸다([recoverable]). 시작도 못 한 지적은 사용자가 이미 고친 것이므로 지적과 교정을 둘 다 거둔다(withdraw).
 * 10-08: 못 들은 지적의 교정이 25건 중 4건이었다. 감시 타이머([PrepSpeech.watchdogMs] 과 같은 식: 예상 길이 1.5배 + 2 s)로 종결 콜백 누락을 메운다.
 */
class SpeechQueue {
    enum class Kind { BOUNDARY, COUNT, COACH }
    enum class Status { REQUESTED, STARTED, DONE, DROPPED }

    /** 대기열 항목 — [heardKeys] 는 이 문장이 전달하는 지적의 장부 키, [setScoped] 는 세트가 끝나면 틀린 말이 되는 줄. */
    data class Item(val kind: Kind, val text: String, val key: String? = null, val heardKeys: List<String> = emptyList(), val setScoped: Boolean = false, val atMs: Long = 0L)

    /** 엔진에 제출할 것 — [flush] 면 재생 중인 말을 끊는다(BOUNDARY, 꼬리 없음). */
    data class Submit(val item: Item, val flush: Boolean)

    private var current: Item? = null
    private var currentStartedAt: Long? = null
    private var currentSubmittedAt = 0L
    private val counts = ArrayList<Item>()
    private var held: Item? = null
    private val boundaries = ArrayList<Item>()
    private var lastKind: Kind? = null
    private val ledger = HashMap<String, Status>()
    private var tailUntil: Long? = null
    private var sealFrom: Long? = null
    private var sealUntil: Long? = null

    /** 지금 말하는 중이거나 말할 것이 남아 있는가 — **보류분을 포함한다**(종전 `isSpeaking` 은 보류분을 뺐다). */
    val busy: Boolean get() = current != null || counts.isNotEmpty() || held != null || boundaries.isNotEmpty()
    /** 엔진에 있는 문장. */
    val playing: Item? get() = current
    val pendingCounts: List<Item> get() = counts.toList()
    val heldCoach: Item? get() = held

    fun tailActive(now: Long): Boolean = tailUntil?.let { now < it && busy } == true
    fun tailRemainingMs(now: Long): Long = tailUntil?.let { (it - now).coerceAtLeast(0L) }?.takeIf { busy } ?: 0L
    /** 꼬리 상한을 넘겼는데 아직 남아 있다 — 호출자가 엔진을 멈춘다. */
    fun tailExpired(now: Long): Boolean = tailUntil?.let { now >= it && busy } == true

    private fun sealed(now: Long): Boolean {
        val from = sealFrom ?: return false
        val until = sealUntil ?: Long.MAX_VALUE
        return now >= from && now < until
    }

    /**
     * 요청 — 돌려주는 값은 지금 엔진에 제출할 것(없으면 null: 대기·보류·버림). 봉인 중의 BOUNDARY 가 아닌 요청은 버린다(DROPPED).
     */
    fun request(item: Item, now: Long): Submit? {
        if (item.kind != Kind.BOUNDARY && sealed(now)) { item.heardKeys.forEach { ledger[it] = Status.DROPPED }; return null }
        when (item.kind) {
            Kind.BOUNDARY -> {
                // 다음 세트의 안내: 봉인·장부를 새로 연다
                sealFrom = null; sealUntil = null; ledger.clear()
                if (tailActive(now)) {
                    // 꼬리 밖 항목만 비우고 꼬리 뒤에 — 꼬리 = 재생 중인 말 + 가장 새 카운트 + 보류 교정
                    boundaries.clear(); boundaries += item
                    return if (current == null) submitNext(now) else null
                }
                counts.clear(); held?.heardKeys?.forEach { ledger[it] = Status.DROPPED }; held = null; boundaries.clear()
                current?.heardKeys?.forEach { if (ledger[it] == Status.STARTED) ledger[it] = Status.DONE }
                return submit(item, flush = true, now = now)
            }
            Kind.COUNT -> {
                if (item.key != null) counts.removeAll { it.key == item.key }
                counts += item
            }
            Kind.COACH -> {
                held?.heardKeys?.forEach { if (ledger[it] == Status.REQUESTED) ledger[it] = Status.DROPPED }
                held = item
                item.heardKeys.forEach { ledger[it] = Status.REQUESTED }
            }
        }
        return if (current == null) submitNext(now) else null
    }

    /** 엔진이 재생을 시작했다. */
    fun onStart(now: Long) {
        currentStartedAt = now
        current?.heardKeys?.forEach { ledger[it] = Status.STARTED }
    }

    /** 엔진이 끝냈다(완료·중단·오류) — 다음 제출을 돌려준다. */
    fun onDone(now: Long): Submit? {
        current?.heardKeys?.forEach { ledger[it] = if (ledger[it] == Status.STARTED) Status.DONE else ledger[it] ?: Status.DONE }
        current = null; currentStartedAt = null
        if (tailUntil != null && !busy) { tailUntil = null; sealUntil = null; sealFrom = null }
        return submitNext(now)
    }

    /** 감시 타이머 — 제출 뒤 예상 길이 1.5배 + 2 s 안에 종결이 없으면 끝난 것으로 보고 다음을 제출한다. */
    fun watchdog(now: Long): Submit? {
        val c = current ?: return null
        val from = currentStartedAt ?: currentSubmittedAt
        return if (now - from > PrepSpeech.watchdogMs(c.text)) onDone(now) else null
    }

    private fun submitNext(now: Long): Submit? {
        if (current != null) return null
        val tail = tailActive(now)
        if (!tail) {
            // 묵은 중간 숫자 정리(가장 새 것은 남긴다)
            while (counts.size > 1 && now - counts.first().atMs > STALE_COUNT_MS) counts.removeAt(0)
            held?.let { h -> if (now - h.atMs > COACH_TTL_MS) { h.heardKeys.forEach { ledger[it] = Status.DROPPED }; held = null } }
        }
        val next: Item? = when {
            lastKind == Kind.COACH && counts.isNotEmpty() -> counts.removeAt(0)
            held != null -> held.also { held = null }
            counts.isNotEmpty() -> counts.removeAt(0)
            boundaries.isNotEmpty() -> boundaries.removeAt(0)
            else -> null
        }
        if (next == null) { if (tailUntil != null) { tailUntil = null; sealUntil = null; sealFrom = null }; return null }
        return submit(next, flush = false, now = now)
    }

    private fun submit(item: Item, flush: Boolean, now: Long): Submit {
        if (flush) current?.heardKeys?.forEach { if (ledger[it] == Status.STARTED) ledger[it] = Status.DONE }
        current = item; currentStartedAt = null; currentSubmittedAt = now; lastKind = item.kind
        return Submit(item, flush)
    }

    /** 목표 도달(t0) — 세트 끝 정리와 봉인. [dropCounts] 는 시간 목표(카운트가 남아 있을 이유가 없다). */
    fun beginSetEnd(t0: Long, dropCounts: Boolean) {
        if (dropCounts) counts.clear() else while (counts.size > 1) counts.removeAt(0)
        counts.removeAll { it.setScoped }
        held?.let { if (it.setScoped) { it.heardKeys.forEach { k -> ledger[k] = Status.DROPPED }; held = null } }
        sealFrom = t0 + SEAL_AFTER_MS; sealUntil = t0 + TAIL_MAX_MS
    }

    /** 화면을 넘기며 — 남은 것을 꼬리로 보호한다(수명 무시, 상한 t0+10 s). */
    fun handOff(t0: Long) {
        if (!busy) { sealFrom = null; sealUntil = null; return }
        tailUntil = t0 + TAIL_MAX_MS
        if (sealFrom == null) { sealFrom = t0 + SEAL_AFTER_MS; sealUntil = tailUntil }
    }

    /** 사용자 조작용 전부 정지 — 장부도 비운다. */
    fun stop() {
        current = null; currentStartedAt = null; counts.clear(); held = null; boundaries.clear(); lastKind = null
        ledger.clear(); tailUntil = null; sealFrom = null; sealUntil = null
    }

    /** 재생 중인 말만 남기고 비운다(준비 화면 첫 진입 — 직전 세트 꼬리는 지키고 묵은 대기는 버린다). */
    fun stopKeepingCurrent() { counts.clear(); held = null; boundaries.clear() }

    /**
     * 이 지적의 교정 문장을 내도 되는가 — 재생을 시작했거나 끝났으면 true. 아직 시작도 못 했으면(REQUESTED) 그 줄을 대기·보류에서 빼고 false(사용자가 이미 고쳤다 — 지적도 교정도 거둔다).
     * 버려졌거나 모르는 키는 false.
     */
    fun recoverable(key: String): Boolean = when (ledger[key]) {
        Status.STARTED, Status.DONE -> true
        Status.REQUESTED -> { held?.let { if (key in it.heardKeys) held = null }; ledger[key] = Status.DROPPED; false }
        else -> false
    }

    fun status(key: String): Status? = ledger[key]

    companion object {
        const val STALE_COUNT_MS = 3_000L
        const val COACH_TTL_MS = 4_000L
        const val SEAL_AFTER_MS = 300L
        const val TAIL_MAX_MS = 10_000L
    }
}

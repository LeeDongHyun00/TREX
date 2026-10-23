package com.example.trex_kotlin.posture

/**
 * 준비 화면 안내 음성의 **문장 계획**(spec §101, 10-08 보고 '설명이 끝나야 카운트다운') — 순수 Kotlin(안드로이드·재생기 무관), Composable 의 결정 논리를 JVM 테스트로 뺐다.
 *
 * 왜: 종전 준비 안내는 한 덩어리(13.6~26.5 s, 10세트 중 8세트가 12 s 초과)였고, 그동안 카운트다운이 보류돼(`holdStart`) 이미 서 있는 사용자도 12 s 마감에야 '3' 을 들었다.
 * 마감 `stop()` 이 문장 중간을 잘라 세는 조건·약속 문장("몸이 화면에 잡히면 3초 뒤 시작해요")이 들리지 않았다. 이미 준비된 사용자가 시작(최선 5.4 s) 전에 들을 수 있는 말은
 * 약 24음절뿐이다([PrepSpeech.predictMs] = 410 + 194×음절, 실측 완료 발화 86개 회귀, |잔차| p95 0.84 s).
 *
 * 그래서 문장을 셋으로 나눠 우선순위로 낸다:
 *  - [PrepKind.ESS] 세는 핵심 조건 한 문장(≤ 24음절) — 맨 앞, 늘.
 *  - [PrepKind.PLACE] 자리·방향 — 아직 자리·방향이 안 맞을 때만.
 *  - [PrepKind.DET] 세부 — 가장 이른 시작 전에 끝날 수 있을 때만.
 * 카운트다운은 말과 분리된다(진입 조건은 범위·안정·방향 근거뿐). 설명 중 카운트는 숫자 대신 **톤**([CountdownVoice]) — 숫자 음성은 같은 TTS 를 자른다.
 */
enum class PrepKind { ESS, PLACE, DET }

data class PrepLine(val kind: PrepKind, val text: String)

object PrepSpeech {
    private const val BASE_MS = 410L
    private const val PER_SYLLABLE_MS = 194L
    private val DIGIT = arrayOf("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")

    /** 한국어 음절 수 — 한글 음절 + 숫자(한국어 읽기: 80 → 팔십 2, 45 → 사십오 3, 100 → 백 1). 문장 부호·공백·라틴 문자는 세지 않는다. */
    fun syllables(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch.isDigit()) {
                var j = i
                while (j < text.length && text[j].isDigit()) j++
                n += numberSyllables(text.substring(i, j))
                i = j
                continue
            }
            if (ch in '가'..'힣') n++
            i++
        }
        return n
    }

    private fun numberSyllables(digits: String): Int {
        val v = digits.toIntOrNull() ?: return digits.length
        if (v == 0) return 1
        var n = 0
        val h = v / 100; val t = (v % 100) / 10; val o = v % 10
        if (h > 0) n += (if (h == 1) 0 else 1) + 1   // 백·이백…
        if (t > 0) n += (if (t == 1) 0 else 1) + 1   // 십·이십…
        if (o > 0) n += 1
        return if (v >= 1000) digits.length else n
    }

    /** 예상 발화 길이(ms). */
    fun predictMs(text: String): Long = BASE_MS + PER_SYLLABLE_MS * syllables(text)

    /** 감시 시한 — 예측의 1.5배 + 2 s 안에 종결 콜백이 없으면 끝난 것으로 본다(TTS 콜백 누락 대비, 옛 12 s 마감의 대체). */
    fun watchdogMs(text: String): Long = predictMs(text) * 3 / 2 + 2_000L
}

object PreparationVoicePlan {
    /**
     * 다음에 말할 문장의 인덱스([from] 이상에서 첫 후보) — 없으면 null.
     * @param settledRecommended 자리·권장 방향이 이미 맞는가(PLACE 를 건너뛸 조건). @param counting 카운트다운 중인가. @param started 운동이 시작됐는가.
     * @param earliestStartMs 가장 이른 운동 시작 시각 — DET 는 예측 끝이 이 앞일 때만.
     */
    fun nextLine(lines: List<PrepLine>, from: Int, nowMs: Long, settledRecommended: Boolean, counting: Boolean, started: Boolean, earliestStartMs: Long): Int? {
        if (started) return null
        for (i in from until lines.size) {
            val l = lines[i]
            val say = when (l.kind) {
                PrepKind.ESS -> true
                PrepKind.PLACE -> !settledRecommended && !counting
                PrepKind.DET -> !counting && nowMs + PrepSpeech.predictMs(l.text) <= earliestStartMs
            }
            if (say) return i
        }
        return null
    }
}

object CountdownVoice {
    enum class Mode { TONE, SPEAK, NONE }

    /** 카운트다운 진입 때 정한다 — 설명이 나오는 중이면 그 카운트는 끝까지 톤(한 카운트 안에서 톤과 숫자를 섞지 않는다), 아니면 숫자. TTS 가 없으면 톤, 음소거면 없음. */
    fun mode(introPlayingAtEntry: Boolean, ttsReady: Boolean, muted: Boolean): Mode = when {
        muted -> Mode.NONE
        introPlayingAtEntry || !ttsReady -> Mode.TONE
        else -> Mode.SPEAK
    }
}

package com.example.trex_kotlin.posture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Android 세션 스피커 — 공유 [SpeechCoach] 정책 위에 TextToSpeech 엔진(§108c 전 `SpeechCoach(context)` 와 같은 동작). */
fun SpeechCoach(context: Context): SpeechCoach = SpeechCoach(AndroidSpeechEngine(context))

/**
 * Android TTS 엔진 — 한국어 음성, 초기화 비동기. 발화 동안만 DUCK 포커스(음악을 끄지 않고 낮춘다), 개발 기록은
 * `<externalFilesDir>/posture_logs/feedback-yyyyMMdd.jsonl`(기기 시간대 날짜)에 기록 전용 스레드로 붙인다.
 */
private class AndroidSpeechEngine(context: Context) : SpeechEngine {
    private val appContext = context.applicationContext
    private val audioManager = runCatching {
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }.getOrNull()

    private var tts: TextToSpeech? = null
    private val lock = Any()
    private val traceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var focusRequest: AudioFocusRequest? = null

    override fun trace(atMs: Long, kind: String, text: String, utteranceId: String?, muted: Boolean, ready: Boolean) {
        if (!com.example.trex_kotlin.PLATFORM_DEV_TOOLS) return   // 배포 빌드는 개발 기록을 남기지 않는다(스펙 §116)
        val record = feedbackTraceLine(atMs, kind, text, utteranceId, muted, ready)
        runCatching { traceExecutor.execute {
            runCatching {
                val dir = java.io.File(appContext.getExternalFilesDir(null),"posture_logs").apply { mkdirs() }
                val date = java.text.SimpleDateFormat("yyyyMMdd",Locale.US).format(java.util.Date(atMs))
                java.io.File(dir,"feedback-$date.jsonl").appendText(record+"\n")
            }
        } }
    }

    override fun start(events: SpeechEngine.Events) {
        val progress = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { events.onStart(utteranceId) }
            override fun onDone(utteranceId: String?) { events.onFinished(utteranceId, "tts_done", "") }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { events.onFinished(utteranceId, "tts_stop", "interrupted=$interrupted") }
            @Deprecated("API 21 이전 시그니처 — 추상 메서드라 구현은 필요하다", ReplaceWith("onError(utteranceId, errorCode)"))
            override fun onError(utteranceId: String?) { events.onFinished(utteranceId, "tts_error", "") }
            override fun onError(utteranceId: String?, errorCode: Int) { events.onFinished(utteranceId, "tts_error", "code=$errorCode") }
        }
        try {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val r = runCatching { tts?.setLanguage(Locale.KOREAN) }.getOrNull()
                    val ready = r != null && r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    runCatching { tts?.setSpeechRate(1.05f) }
                    runCatching { tts?.setOnUtteranceProgressListener(progress) }
                    if (ready) events.onInit(true, null, null)
                    else events.onInit(false, "한국어 음성 데이터 없음 (기기 TTS 설정에서 한국어 설치)", "음성 안내를 쓸 수 없어요 — 기기 설정에서 한국어 음성을 설치하세요")
                } else {
                    events.onInit(false, "TTS 초기화 실패", "음성 안내를 쓸 수 없어요 — 기기 TTS 엔진을 확인하세요")
                }
            }
        } catch (t: Throwable) {
            events.onInit(false, "TTS 사용 불가: ${t.message}", "음성 안내를 쓸 수 없어요 — 기기 TTS 엔진을 확인하세요")
        }
    }

    override fun speak(text: String, flush: Boolean, id: String): Int? =
        tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)

    override fun stop() { tts?.stop() }

    override fun shutdown() {
        traceExecutor.shutdown()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        abandonFocus()
        tts = null
    }

    override fun nextUtteranceId(): String = "coach-${System.nanoTime()}"

    /** 발화 동안만 DUCK 포커스 — 음악을 끄지 않고 낮춘다. 실패해도 발화는 그대로 진행한다. */
    override fun requestFocus() {
        val am = audioManager ?: return
        val req = synchronized(lock) {
            if (focusRequest != null) return
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build()
                .also { focusRequest = it }
        }
        runCatching { am.requestAudioFocus(req) }
    }

    override fun abandonFocus() {
        val am = audioManager ?: return
        val req = synchronized(lock) { focusRequest.also { focusRequest = null } } ?: return
        runCatching { am.abandonAudioFocusRequest(req) }
    }
}

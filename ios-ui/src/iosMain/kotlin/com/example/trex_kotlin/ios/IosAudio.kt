@file:OptIn(ExperimentalForeignApi::class)

package com.example.trex_kotlin.ios

import com.example.trex_kotlin.RepTone
import com.example.trex_kotlin.VoiceAdvisor
import com.example.trex_kotlin.VoiceStatus
import com.example.trex_kotlin.dayStamp
import com.example.trex_kotlin.nowMillis
import com.example.trex_kotlin.posture.SerialWriter
import com.example.trex_kotlin.posture.SpeechEngine
import com.example.trex_kotlin.posture.feedbackTraceLine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityDefault
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityEnhanced
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityPremium
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.setActive
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.PI
import kotlin.math.sin

/*
 * iPhone 소리(§108c) — 세션 스피커의 엔진(AVSpeechSynthesizer)과 짧은 확인음(AVAudioEngine 합성음).
 * 정책(대기열·들림 장부·꼬리)은 공유 SpeechCoach 가 정하고, 여기서는 말하기·사건·오디오 세션만 맡는다.
 * 오디오 세션은 재생(playback) 범주라 무음 스위치와 무관하게 들린다 — Android 의 STREAM_MUSIC(미디어 음량)과 같은 자리.
 * 말하는 동안만 다른 소리(음악)를 낮추고, 말이 끝나면 세션을 놓아 음악을 되돌린다(Android DUCK 포커스와 같은 뜻).
 */

internal fun onMain(block: () -> Unit) {
    if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
}

/** 하나뿐인 앱 오디오 세션 — 말(낮춤)·확인음(섞기)이 같이 쓴다. 메인 스레드에서만. */
internal object IosAudioSession {
    private var ducking = false
    private var active = false
    /** 말이 끝나 세션을 놓으려 했는데 확인음이 울리던 중이라 미뤘다 — 마지막 확인음이 끝나면 놓는다([tonesFinished]). */
    private var releasePending = false

    fun speaking() {
        releasePending = false
        val session = AVAudioSession.sharedInstance()
        // 확인음 때문에 섞기로만 켜져 있었으면 한 번 놓았다가 낮춤으로 다시 켠다 — 켜진 채 범주만 바꾸면 낮춤이 걸리지 않을 수 있다
        if (active && !ducking && !IosTones.playing) {
            session.setActive(false, withOptions = 0u, error = null)
            active = false
        }
        if (!ducking || !active) {
            session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeSpokenAudio,
                options = AVAudioSessionCategoryOptionDuckOthers or AVAudioSessionCategoryOptionMixWithOthers, error = null)
            ducking = true
        }
        session.setActive(true, error = null)
        active = true
    }

    fun tone() {
        if (active) return   // 말하는 중이면 그 세션 그대로(이미 낮춤)
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeDefault,
            options = AVAudioSessionCategoryOptionMixWithOthers, error = null)
        ducking = false
        session.setActive(true, error = null)
        active = true
    }

    /** 말이 끝났다 — 세션을 놓아 다른 앱 소리를 되돌린다. 확인음이 울리는 중이면 마지막 확인음이 끝날 때까지 미룬다. */
    fun release() {
        if (!active) return
        if (IosTones.playing) {
            releasePending = true
            return
        }
        AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
        active = false
        ducking = false
        releasePending = false
    }

    /**
     * 마지막 확인음이 끝났다 — 말이 놓으려다 미룬 세션, 또는 확인음만 켰던 세션(섞기)을 놓는다. 말하는 중(낮춤, 미룬 것 없음)이면 그대로 둔다.
     * 예전에는 여기서 놓지 않아, 말 끝과 확인음이 겹치면 낮춤 세션이 켜진 채 남아 다른 앱 소리가 계속 작았다.
     */
    fun tonesFinished() {
        if (releasePending || !ducking) release()
    }
}

/** 이 기기의 한국어 음성들(설치된 것만). */
internal fun koreanVoices(): List<AVSpeechSynthesisVoice> =
    AVSpeechSynthesisVoice.speechVoices().filterIsInstance<AVSpeechSynthesisVoice>().filter { it.language == "ko-KR" }

/**
 * 가장 자연스러운 한국어 음성 — 품질이 높은 것부터(프리미엄 > 향상됨 > 기본), 같은 품질이면 시스템의 한국어 기본 음성.
 * 예전에는 시스템 기본(대개 기본 품질 '유나' 압축 음성)을 그대로 써서 Android 구글 음성보다 기계음이 강했다(10-23 사용자 보고 "TTS 가 어색하다").
 * 향상·프리미엄 음성은 앱이 받을 수 없고 사용자가 설정 › 손쉬운 사용 › 콘텐츠 말하기 › 음성 › 한국어에서 내려받는다 — 받아 두면 다음 세션부터 저절로 쓴다.
 */
/** 음성 품질 이름 — 발화 기록·개발용 확인. */
internal fun voiceQualityLabel(voice: AVSpeechSynthesisVoice): String = when (voice.quality) {
    AVSpeechSynthesisVoiceQualityPremium -> "premium"
    AVSpeechSynthesisVoiceQualityEnhanced -> "enhanced"
    else -> "default"
}

internal fun bestKoreanVoice(): AVSpeechSynthesisVoice? {
    val systemDefault = AVSpeechSynthesisVoice.voiceWithLanguage("ko-KR")
    return koreanVoices().maxWithOrNull(compareBy<AVSpeechSynthesisVoice>({ it.quality }, { it.identifier == systemDefault?.identifier })) ?: systemDefault
}

/**
 * Android `setSpeechRate(1.05f)` 자리 — iPhone 보통 빠르기 값(0.5)에 같은 배율. iPhone 빠르기 값은 체감 속도와 정비례하지 않아 '정확히 5 %' 는 아니고
 * 보통보다 조금 빠른 쪽이라는 뜻만 같다(예전 0.48 은 보통보다 느려 처졌다).
 */
internal val SPEECH_RATE: Float = AVSpeechUtteranceDefaultSpeechRate * 1.05f

/** 설치된 음성을 다시 보는 간격(s) — 세션 중에 받은 향상·프리미엄 음성을 앱을 다시 켜지 않고 쓴다(엔진은 앱 수명 하나다). */
private const val VOICE_RECHECK_S = 30.0

/**
 * 세션 스피커의 iPhone 엔진 — 가장 자연스러운 한국어 음성([bestKoreanVoice]), Android 와 같은 빠르기([SPEECH_RATE]). 신시사이저 호출과 사건은 메인 스레드다.
 * 끊긴 발화는 didCancel 이 오고(공유 정책은 Android onStop 과 같게 '끊김' 으로 받는다).
 */
class IosSpeechEngine : SpeechEngine {
    private val synthesizer = AVSpeechSynthesizer()
    private var voice: AVSpeechSynthesisVoice? = bestKoreanVoice()
    private var voiceCheckedAt = NSProcessInfo.processInfo.systemUptime
    /** 개발용 확인(--food-probe) — 소리 없이(볼륨 0) 사건만 보고, 발화 기록 파일에 쓰지 않는다. */
    internal var silent = false
    /** 낸 문장 → 발화 id(사건이 이 값으로 돌아간다). 메인 스레드에서만. */
    private val ids = ArrayList<Pair<AVSpeechUtterance, String>>()
    private var events: SpeechEngine.Events? = null
    private var counter = 0L
    private val traceWriter = SerialWriter()

    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didStartSpeechUtterance: AVSpeechUtterance) {
            onMain { events?.onStart(idOf(didStartSpeechUtterance, remove = false)) }
        }
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            onMain { events?.onFinished(idOf(didFinishSpeechUtterance, remove = true), "tts_done", "") }
        }
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
            onMain { events?.onFinished(idOf(didCancelSpeechUtterance, remove = true), "tts_stop", "interrupted=true") }
        }
    }

    /**
     * 사건의 발화 → 낸 id. 같은 객체(isEqual)로 먼저, 없으면 같은 글의 가장 오래된 것으로 찾는다 — Kotlin 참조 비교(===)로만 찾던 때는 iPhone 에서
     * 사건마다 못 찾아(10-23 실기기 기록: tts_start·tts_done 에 id 가 없음) 공유 대기열이 '아직 말하는 중' 으로 남았고, 세트 내내 교정 문장이
     * 전부 보류됐다(speech_held — 끊고 들어오는 카운트·경계 안내만 들렸다).
     */
    private fun idOf(u: AVSpeechUtterance, remove: Boolean): String? {
        var i = ids.indexOfFirst { it.first == u }
        if (i < 0) i = ids.indexOfFirst { it.first.speechString == u.speechString }
        if (i < 0) return null
        return if (remove) ids.removeAt(i).second else ids[i].second
    }

    /** 지금 쓸 음성 — [VOICE_RECHECK_S] 마다 설치된 음성을 다시 본다. 바뀌면 발화 기록에 남긴다. 메인 스레드에서. */
    private fun currentVoice(): AVSpeechSynthesisVoice? {
        val now = NSProcessInfo.processInfo.systemUptime
        if (now - voiceCheckedAt >= VOICE_RECHECK_S) {
            voiceCheckedAt = now
            val best = bestKoreanVoice()
            if (best != null && best.identifier != voice?.identifier) {
                voice = best
                traceVoice()
            }
        }
        return voice
    }

    private fun traceVoice() = voice?.let { trace(nowMillis(), "tts_voice", "${it.name}/${voiceQualityLabel(it)}", null, muted = false, ready = true) }

    override fun start(events: SpeechEngine.Events) {
        this.events = events
        synthesizer.delegate = delegate
        traceVoice()
        if (voice != null) events.onInit(true, null, null)
        else events.onInit(false, "한국어 음성 없음 (설정 › 손쉬운 사용 › 콘텐츠 말하기에서 한국어 음성 설치)",
            "음성 안내를 쓸 수 없어요 — 설정에서 한국어 음성을 설치하세요")
    }

    override fun speak(text: String, flush: Boolean, id: String): Int? {
        onMain {
            IosAudioSession.speaking()
            if (flush && synthesizer.speaking) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
            val utterance = AVSpeechUtterance(string = text)
            utterance.voice = currentVoice()
            utterance.rate = SPEECH_RATE
            if (silent) utterance.volume = 0f
            ids += utterance to id
            synthesizer.speakUtterance(utterance)
        }
        return SpeechEngine.SUCCESS
    }

    override fun stop() = onMain { synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate) }

    override fun shutdown() = stop()

    override fun requestFocus() = onMain { IosAudioSession.speaking() }

    override fun abandonFocus() = onMain { if (!synthesizer.speaking) IosAudioSession.release() }

    override fun nextUtteranceId(): String = "coach-${(platform.Foundation.NSProcessInfo.processInfo.systemUptime * 1e9).toLong()}-${counter++}"

    override fun trace(atMs: Long, kind: String, text: String, utteranceId: String?, muted: Boolean, ready: Boolean) {
        if (silent || !com.example.trex_kotlin.PLATFORM_DEV_TOOLS) return   // 배포 빌드는 개발 기록을 남기지 않는다(스펙 §116)
        val record = feedbackTraceLine(atMs, kind, text, utteranceId, muted, ready)
        traceWriter.execute {
            runCatching { appendTextFile(documentsPath() + "/posture_logs/feedback-${dayStamp(atMs)}.jsonl", record + "\n") }
        }
    }
}

/**
 * 짧은 확인음 — Android ToneGenerator 의 TONE_PROP_* 모양을 합성한다(삑 1200 Hz, 낮은 틱 300·400·500 Hz 화음, 이중 틱 400·1200 Hz 40 ms).
 * 요청 길이에서 자른다(Android startTone(종류, ms) 와 같다). 엔진은 울릴 때만 켜고 다 울리면 끈다 — 그래야 말이 끝난 뒤 세션을 놓을 수 있다.
 */
internal object IosTones {
    private const val RATE = 44_100.0
    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    private val format = AVAudioFormat(standardFormatWithSampleRate = RATE, channels = 1u)
    private var scheduled = 0
    val playing: Boolean get() = scheduled > 0

    init {
        engine.attachNode(player)
        engine.connect(player, to = engine.mainMixerNode, format = format)
    }

    /** 한 구간 = (길이 ms, 주파수들). 주파수가 비면 쉼. */
    private fun segments(kind: Int): List<Pair<Int, List<Double>>> = when (kind) {
        RepTone.TONE_PROP_NACK -> listOf(400 to listOf(300.0, 400.0, 500.0))
        RepTone.TONE_PROP_BEEP2 -> listOf(40 to listOf(400.0, 1200.0), 200 to emptyList(), 40 to listOf(400.0, 1200.0))
        RepTone.TONE_PROP_ACK -> listOf(100 to listOf(1200.0), 100 to emptyList(), 100 to listOf(1200.0))
        else -> listOf(100 to listOf(1200.0))
    }

    fun play(kind: Int, durationMs: Int, volume: Float) = onMain {
        val plan = segments(kind)
        val totalMs = minOf(durationMs, plan.sumOf { it.first }).coerceAtLeast(1)
        val frames = (RATE * totalMs / 1000.0).toInt().coerceAtLeast(1)
        val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = frames.toUInt()) ?: return@onMain
        buffer.frameLength = frames.toUInt()
        val data = buffer.floatChannelData?.get(0) ?: return@onMain
        val fade = (RATE * 0.004).toInt()   // 4 ms 머리·꼬리 — 딸깍 소리 없이
        var segStart = 0
        var seg = 0
        for (i in 0 until frames) {
            while (seg < plan.size - 1 && i >= segStart + (RATE * plan[seg].first / 1000.0).toInt()) { segStart += (RATE * plan[seg].first / 1000.0).toInt(); seg++ }
            val freqs = plan[seg].second
            val segFrames = (RATE * plan[seg].first / 1000.0).toInt()
            val k = i - segStart
            val envelope = minOf(1.0, k / fade.toDouble(), (segFrames - k) / fade.toDouble(), (frames - i) / fade.toDouble()).coerceAtLeast(0.0)
            val t = i / RATE
            val s = if (freqs.isEmpty()) 0.0 else freqs.sumOf { sin(2 * PI * it * t) } / freqs.size
            data[i] = (s * envelope * 0.6 * volume).toFloat()
        }
        IosAudioSession.tone()
        if (!engine.running) { engine.prepare(); engine.startAndReturnError(null) }
        scheduled++
        player.scheduleBuffer(buffer) {
            onMain {
                scheduled--
                if (scheduled == 0) {
                    player.stop()
                    engine.stop()
                    IosAudioSession.tonesFinished()
                }
            }
        }
        if (!player.playing) player.play()
    }
}

/** RepTone 의 iPhone 판 — [volume] 0~100(Android ToneGenerator 볼륨과 같은 자리). */
internal class IosRepTone(private val volume: Int) : RepTone {
    override fun startTone(toneType: Int, durationMs: Int): Boolean {
        IosTones.play(toneType, durationMs, volume / 100f)
        return true
    }
    override fun release() {}
}

/** 음성 품질의 화면 이름. */
internal fun voiceQualityKorean(voice: AVSpeechSynthesisVoice): String = when (voice.quality) {
    AVSpeechSynthesisVoiceQualityPremium -> "프리미엄"
    AVSpeechSynthesisVoiceQualityEnhanced -> "향상된 음질"
    else -> "기본 음질"
}

/**
 * 설정 화면의 음성 안내 목소리(§108d 후속) — 세션 스피커와 같은 고르기([bestKoreanVoice])·빠르기([SPEECH_RATE])로 미리 들려준다.
 * 말이 끝나면 오디오 세션을 놓아 다른 앱 소리를 되돌린다. 메인 스레드에서.
 */
internal class IosVoiceAdvisor : VoiceAdvisor {
    private val synthesizer = AVSpeechSynthesizer()
    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) = onMain { IosAudioSession.release() }
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) = onMain { IosAudioSession.release() }
    }

    init { synthesizer.delegate = delegate }

    override fun status(): VoiceStatus? = bestKoreanVoice()?.let { VoiceStatus("${it.name} · ${voiceQualityKorean(it)}", natural = it.quality != AVSpeechSynthesisVoiceQualityDefault) }

    override fun preview() = onMain {
        IosAudioSession.speaking()
        if (synthesizer.speaking) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val utterance = AVSpeechUtterance(string = "좋아요. 무릎이 발끝 방향을 따라가게 천천히 앉아 보세요.")
        utterance.voice = bestKoreanVoice()
        utterance.rate = SPEECH_RATE
        synthesizer.speakUtterance(utterance)
    }
}

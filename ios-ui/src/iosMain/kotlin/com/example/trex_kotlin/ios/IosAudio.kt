@file:OptIn(ExperimentalForeignApi::class)

package com.example.trex_kotlin.ios

import com.example.trex_kotlin.RepTone
import com.example.trex_kotlin.dayStamp
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
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.setActive
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

    fun speaking() {
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

    /** 말이 끝났다 — 확인음이 울리는 중이 아니면 세션을 놓아 다른 앱 소리를 되돌린다. */
    fun release() {
        if (!active || IosTones.playing) return
        AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
        active = false
        ducking = false
    }
}

/**
 * 세션 스피커의 iPhone 엔진 — 한국어 음성(ko-KR, 기존 iPhone 설정과 같은 빠르기 0.48). 신시사이저 호출과 사건은 메인 스레드다.
 * 끊긴 발화는 didCancel 이 오고(공유 정책은 Android onStop 과 같게 '끊김' 으로 받는다).
 */
class IosSpeechEngine : SpeechEngine {
    private val synthesizer = AVSpeechSynthesizer()
    private val voice: AVSpeechSynthesisVoice? = AVSpeechSynthesisVoice.voiceWithLanguage("ko-KR")
    /** 낸 문장 → 발화 id(사건이 이 값으로 돌아간다). 메인 스레드에서만. */
    private val ids = ArrayList<Pair<AVSpeechUtterance, String>>()
    private var events: SpeechEngine.Events? = null
    private var counter = 0L
    private val traceWriter = SerialWriter()

    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didStartSpeechUtterance: AVSpeechUtterance) {
            events?.onStart(idOf(didStartSpeechUtterance, remove = false))
        }
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            events?.onFinished(idOf(didFinishSpeechUtterance, remove = true), "tts_done", "")
        }
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
            events?.onFinished(idOf(didCancelSpeechUtterance, remove = true), "tts_stop", "interrupted=true")
        }
    }

    private fun idOf(u: AVSpeechUtterance, remove: Boolean): String? {
        val i = ids.indexOfFirst { it.first === u }
        if (i < 0) return null
        return if (remove) ids.removeAt(i).second else ids[i].second
    }

    override fun start(events: SpeechEngine.Events) {
        this.events = events
        synthesizer.delegate = delegate
        if (voice != null) events.onInit(true, null, null)
        else events.onInit(false, "한국어 음성 없음 (설정 › 손쉬운 사용 › 읽기 및 말하기에서 한국어 음성 설치)",
            "음성 안내를 쓸 수 없어요 — 설정에서 한국어 음성을 설치하세요")
    }

    override fun speak(text: String, flush: Boolean, id: String): Int? {
        onMain {
            IosAudioSession.speaking()
            if (flush && synthesizer.speaking) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
            val utterance = AVSpeechUtterance(string = text)
            utterance.voice = voice
            utterance.rate = 0.48f
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
                if (scheduled == 0) { player.stop(); engine.stop() }
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

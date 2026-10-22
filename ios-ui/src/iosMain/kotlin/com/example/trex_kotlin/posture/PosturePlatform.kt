@file:OptIn(ExperimentalForeignApi::class)

package com.example.trex_kotlin.posture

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.setActive

/**
 * 세션 스피커의 iPhone 판(§108 Phase 2) — Android SpeechCoach 와 같은 이름·같은 공개 API 중 공유 화면이 쓰는 것만 둔다.
 * 지금 말하는 것은 쉬는 시간·완료 화면 문장뿐이라 AVSpeechSynthesizer 를 그대로 쓴다(ko-KR·0.48·다른 소리 낮춤 — 기존 Swift
 * SpeechService 와 같은 설정). 발화 대기열(SpeechQueue — 세트 끝 꼬리·들림 장부)은 자세 화면(Phase 3)과 함께 Android 와 같게 옮긴다.
 * 그때까지 [beginSetEnd]·[handOff] 는 지금 말을 끊지 않는 것만 같다.
 */
class SpeechCoach {
    var muted: Boolean = false
    private val synthesizer = AVSpeechSynthesizer()

    fun speak(text: String, flush: Boolean = true, key: String? = null) {
        if (muted) return
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeSpokenAudio,
            options = AVAudioSessionCategoryOptionDuckOthers or AVAudioSessionCategoryOptionMixWithOthers, error = null)
        session.setActive(true, error = null)
        if (flush) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val utterance = AVSpeechUtterance(string = text)
        utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage("ko-KR")
        utterance.rate = 0.48f
        synthesizer.speakUtterance(utterance)
    }

    val isSpeaking: Boolean get() = synthesizer.speaking

    fun beginSetEnd(t0: Long, dropCounts: Boolean) {}

    fun handOff() {}

    fun stop() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    fun shutdown() = stop()
}

/** 개발용 자세 실험실은 Android 연구 도구다(도달하지 않는 경로). */
@Composable
fun PostureLabScreen(onClose: () -> Unit) {}

/** 개발용 기준선 수집 안내는 Android 연구 도구다(도달하지 않는 경로). */
@Composable
fun BaselineGuideScreen(onClose: () -> Unit) {}

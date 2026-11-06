import AVFoundation
import Combine

/// TTS 엔진(AVSpeechSynthesizer)만 든다. **무엇을 언제 말할지는 공통 엔진의 대기열(`IosSpeech`, spec §101·§107f)이 정한다** — 여기서 문장을 보류하거나 고르지 않는다.
/// `play` 는 대기열이 고른 제출 하나를 그대로 내고, 재생 시작·종결을 `onStart`·`onFinished` 로 돌려준다(엔진이 다음 제출을 고른다).
/// 세션 밖(휴식·결과 화면)의 짧은 안내는 `say` 로 바로 낸다.
@MainActor final class SpeechService: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    @Published private(set) var speaking = false
    var muted = false
    var onStart: (() -> Void)?
    var onFinished: (() -> Void)?
    private let synthesizer = AVSpeechSynthesizer()
    private var current: AVSpeechUtterance?
    private var engineDriven = false
    override init() { super.init(); synthesizer.delegate = self }
    /// 한국어 음성이 있는가 — 없으면 엔진이 숫자·문장 대신 톤을 낸다(Android `SpeechCoach.ready` 와 같은 뜻).
    nonisolated static var koreanVoiceAvailable: Bool { AVSpeechSynthesisVoice(language: "ko-KR") != nil }
    /// 엔진 대기열의 제출 하나. `flush` 면 재생 중인 말을 끊는다(끊긴 발화의 종결 콜백은 엔진 사건으로 보지 않는다).
    func play(_ submit: SpeechSubmit) {
        guard !muted, !submit.text.isEmpty else { onFinished?(); return }
        if submit.flush, synthesizer.isSpeaking { current = nil; synthesizer.stopSpeaking(at: .immediate) }
        do { try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers, .mixWithOthers]); try AVAudioSession.sharedInstance().setActive(true) }
        catch { onFinished?(); return }
        let utterance = AVSpeechUtterance(string: submit.text); utterance.voice = AVSpeechSynthesisVoice(language: "ko-KR"); utterance.rate = 0.48
        current = utterance; engineDriven = true; speaking = true; synthesizer.speak(utterance)
    }
    /// 세션 밖의 짧은 안내 — 대기열을 거치지 않는다.
    func say(_ text: String) {
        guard !muted, !text.isEmpty else { return }
        do { try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers, .mixWithOthers]); try AVAudioSession.sharedInstance().setActive(true) }
        catch { return }
        let utterance = AVSpeechUtterance(string: text); utterance.voice = AVSpeechSynthesisVoice(language: "ko-KR"); utterance.rate = 0.48
        current = utterance; engineDriven = false; speaking = true; synthesizer.speak(utterance)
    }
    /// 사용자 조작용 전부 정지 — 엔진 대기열도 호출자가 함께 비운다(`stopSpeech`).
    func stop() {
        current = nil; engineDriven = false; synthesizer.stopSpeaking(at: .immediate); speaking = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self, self.current === utterance, self.engineDriven else { return }
            self.onStart?()
        }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) { finished(utterance) }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) { finished(utterance) }
    nonisolated private func finished(_ utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self, self.current === utterance else { return }   // 끊긴(대체된) 발화의 뒤늦은 콜백
            self.current = nil; self.speaking = false
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            if self.engineDriven { self.engineDriven = false; self.onFinished?() }
        }
    }
}

/// 짧은 톤(Android `ToneGenerator` 의 ACK·NACK·BEEP·BEEP2 자리) — 세지 않은 회의 낮은 틱, 쪽을 모르는 걸음, 음성 없을 때의 카운트. 사인파 70~100 ms.
final class ToneService {
    private let engine = AVAudioEngine()
    private var player = AVAudioPlayerNode()
    private var format: AVAudioFormat?
    private var ready = false
    private func prepare() -> Bool {
        if ready { return true }
        let fmt = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)
        guard let fmt else { return false }
        engine.attach(player); engine.connect(player, to: engine.mainMixerNode, format: fmt)
        do { try engine.start() } catch { return false }
        format = fmt; ready = true; return true
    }
    /// `nack` 낮은 틱(세지 않은 회), `ack` 짧은 확인, `beep` 카운트, `beep2` 이중 톤(남은 5 s).
    func play(_ kind: String) {
        guard prepare(), let format else { return }
        let (hz, ms): (Double, Double) = { switch kind { case "nack": return (330, 90); case "ack": return (880, 60); case "beep2": return (1320, 120); default: return (1047, 90) } }()
        let frames = AVAudioFrameCount(format.sampleRate * ms / 1000)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames), let data = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = frames
        for i in 0..<Int(frames) {
            let t = Double(i) / format.sampleRate
            let envelope = min(1, Double(Int(frames) - i) / 400.0)
            let second = kind == "beep2" && (t * 1000).truncatingRemainder(dividingBy: 60) > 30 ? 0.0 : 1.0
            data[i] = Float(sin(2 * .pi * hz * t) * 0.35 * envelope * second)
        }
        player.scheduleBuffer(buffer, completionHandler: nil)
        if !player.isPlaying { player.play() }
    }
}

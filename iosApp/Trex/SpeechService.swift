import AVFoundation
import Combine

/// 최신 코칭 한 문장만 보류한다. 오래된 교정 지시가 뒤늦게 발화되지 않게 한다.
@MainActor final class SpeechService: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    @Published private(set) var speaking = false
    var muted = false
    var onFinished: (() -> Void)?
    private let synthesizer = AVSpeechSynthesizer()
    private var held: (String, Date)?
    override init() { super.init(); synthesizer.delegate = self }
    func say(_ text: String, latest: Bool = true) {
        guard !muted, !text.isEmpty else { return }
        if synthesizer.isSpeaking && latest { held = (text, Date()); return }
        do { try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers, .mixWithOthers]); try AVAudioSession.sharedInstance().setActive(true) }
        catch { return }
        let utterance = AVSpeechUtterance(string: text); utterance.voice = AVSpeechSynthesisVoice(language: "ko-KR"); utterance.rate = 0.48
        speaking = true; synthesizer.speak(utterance)
    }
    func stop() {
        held = nil; synthesizer.stopSpeaking(at: .immediate); speaking = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        onFinished?()
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            if let held = self.held { self.held = nil; if Date().timeIntervalSince(held.1) <= 4 { self.say(held.0); return } }
            self.speaking = false; try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation); self.onFinished?()
        }
    }
}

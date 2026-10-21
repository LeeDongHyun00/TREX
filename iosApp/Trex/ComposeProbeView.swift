import SwiftUI
import TrexCore

/// Android 정본 Compose 화면을 그대로 띄운다(§108 Phase 0 스파이크).
/// capture 는 실행 인자 --compose-probe 용 — 라이트·다크 화면을 Documents 에 PNG 로 남긴다.
struct ComposeProbeView: UIViewControllerRepresentable {
    var capture = false
    func makeUIViewController(context: Context) -> UIViewController {
        capture ? ComposeHostKt.ProbeCaptureViewController() : ComposeHostKt.MainViewController()
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

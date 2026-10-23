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

/// 앱 화면(§108 Phase 2) — Android 와 같은 Compose TrexApp.
struct ComposeAppView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { ComposeHostKt.TrexAppViewController() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

/// 실행 인자 --screen-tour(§108 Phase 2): 공유 화면을 하나씩 그려 Documents/screen-tour/ 에 PNG 로 남긴다(tour 이름공간).
struct ScreenTourView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { ScreenTourKt.ScreenTourViewController() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

/// 실행 인자 --app-probe(§108 Phase 2): 공유 앱 상태가 iPhone 플랫폼 구현 위에서 도는지 확인한다(probe 이름공간만 쓴다).
struct AppProbeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { AppProbeKt.AppProbeViewController() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

/// 실행 인자 --posture-probe(§108c): 공유 자세 화면이 iPhone 카메라·MediaPipe 로 도는지 확인한다(Documents/posture-probe/).
struct PostureProbeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { PostureProbeKt.PostureProbeViewController() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

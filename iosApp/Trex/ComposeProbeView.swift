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

/// 실행 인자 --food-probe(§108d): 공유 사진 식단이 iPhone TFLite·사진 다리로 도는지 확인한다(Documents/food-probe/).
struct FoodProbeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { FoodProbeKt.FoodProbeViewController(image: Self.plate()) }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}

    /// 합성 접시(1600×1200 PNG → 사진 경로와 같은 축소 디코딩) — 나무색 바탕에 흰 접시와 밥·김치·나물 색 덩어리.
    /// 왼쪽 위 빨강·오른쪽 아래 파랑 표지로 채널 순서와 위아래를 본다. 음식 사진이 아니라 인식 이름은 의미가 없다.
    static func plate() -> IosFoodImage? {
        let size = CGSize(width: 1600, height: 1200)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        format.preferredRange = .standard
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            let cg = context.cgContext
            UIColor(red: 0.55, green: 0.38, blue: 0.22, alpha: 1).setFill(); cg.fill(CGRect(origin: .zero, size: size))
            UIColor(white: 0.97, alpha: 1).setFill(); cg.fillEllipse(in: CGRect(x: 300, y: 150, width: 1000, height: 900))
            UIColor(red: 0.98, green: 0.97, blue: 0.92, alpha: 1).setFill(); cg.fillEllipse(in: CGRect(x: 450, y: 330, width: 360, height: 300))
            UIColor(red: 0.80, green: 0.20, blue: 0.12, alpha: 1).setFill(); cg.fillEllipse(in: CGRect(x: 860, y: 380, width: 300, height: 230))
            UIColor(red: 0.36, green: 0.55, blue: 0.20, alpha: 1).setFill(); cg.fillEllipse(in: CGRect(x: 640, y: 700, width: 330, height: 210))
            UIColor.red.setFill(); cg.fill(CGRect(x: 0, y: 0, width: 60, height: 60))
            UIColor.blue.setFill(); cg.fill(CGRect(x: size.width - 60, y: size.height - 60, width: 60, height: 60))
        }
        return image.pngData().flatMap { decodeFoodImage($0) }
    }
}

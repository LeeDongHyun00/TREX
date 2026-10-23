import SwiftUI
import UIKit

/// 왼쪽 가장자리에서 오른쪽으로 쓸면 뒤로(iOS 표준 pop 제스처) — SwiftUI `NavigationStack` 은 기본으로 켜지만 뒤로 버튼을 숨기거나 toolbar 를 바꾼 화면에서 꺼지는 일이 있어
/// 모든 내비게이션 컨트롤러에서 명시적으로 켠다. 루트 화면(쌓인 화면이 하나)에서는 시작하지 않는다.
extension UINavigationController: UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }
    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        gestureRecognizer === interactivePopGestureRecognizer && viewControllers.count > 1
    }
}

extension View {
    /// 팝업(시트) 공통 모양 — 위 손잡이를 보이고, 아래로 끌어 내리면 닫힌다(기본 동작, 끄지 않는다). 온보딩처럼 닫히면 안 되는 시트만 따로 `interactiveDismissDisabled` 를 건다.
    func trexSheet() -> some View {
        presentationDragIndicator(.visible)
    }
}

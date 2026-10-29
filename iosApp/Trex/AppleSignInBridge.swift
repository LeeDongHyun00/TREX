import AuthenticationServices
import CryptoKit
import Security
import TrexCore
import UIKit

/// iPhone Apple 로그인 다리 — 공유 Kotlin 앱 상태가 `IosAppleSignInBridge` 로 부른다
/// (설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md).
///
/// `ASAuthorizationAppleIDProvider` 로 이름·이메일 범위를 요청하고 identity token 과 authorization code 를 받는다.
/// nonce: 무작위 원문을 만들어 요청에는 SHA-256(소문자 16진)을 넣고, 원문은 서버에 보낸다 — 서버는 토큰의 nonce 가
/// 원문의 SHA-256 인지 본다(`AppleJwksTokenVerifier.sha256Hex` 와 같은 표기). 앱 타깃에 Sign in with Apple
/// 엔타이틀먼트(`Trex.entitlements`)가 있어야 하고, 동현님 개발자 계정에서 이 앱의 Sign in with Apple 기능을 켜야 실기기에서 된다.
final class AppleSignInBridge: NSObject, IosAppleSignInBridge, ASAuthorizationControllerDelegate,
    ASAuthorizationControllerPresentationContextProviding {
    private var done: ((String?, String?, String?, String?) -> Void)?
    private var rawNonce: String?
    private var controller: ASAuthorizationController?

    func signIn(done: @escaping (String?, String?, String?, String?) -> Void) {
        guard let raw = Self.randomNonce(byteCount: 32) else { done(nil, nil, nil, "로그인을 준비하지 못했어요"); return }
        self.done = done
        self.rawNonce = raw
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName, .email]
        request.nonce = Self.sha256Hex(raw)
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        self.controller = controller
        controller.performRequests()
    }

    func makeButton(onTap: @escaping () -> Void) -> UIView {
        let button = TapAppleIDButton(authorizationButtonType: .continue, authorizationButtonStyle: .black)
        button.cornerRadius = 12
        button.onTap = onTap
        return button
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        defer { finish() }
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let tokenData = credential.identityToken, let token = String(data: tokenData, encoding: .utf8) else {
            done?(nil, nil, nil, "Apple 로그인 정보를 받지 못했어요"); return
        }
        let code = credential.authorizationCode.flatMap { String(data: $0, encoding: .utf8) }
        done?(token, code, rawNonce, nil)
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        defer { finish() }
        if let error = error as? ASAuthorizationError, error.code == .canceled {
            done?(nil, nil, nil, IosAppleSignInRegistry.shared.CANCELLED); return
        }
        done?(nil, nil, nil, error.localizedDescription)
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        if let window = scenes.flatMap(\.windows).first(where: { $0.isKeyWindow }) ?? scenes.first?.windows.first { return window }
        if let scene = scenes.first { return ASPresentationAnchor(windowScene: scene) }
        return ASPresentationAnchor()
    }

    private func finish() {
        done = nil
        rawNonce = nil
        controller = nil
    }

    private static func randomNonce(byteCount: Int) -> String? {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        guard SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes) == errSecSuccess else { return nil }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func sha256Hex(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

/// 누른 동작을 버튼이 직접 든다 — 화면이 버튼을 버리면 동작도 같이 사라진다.
private final class TapAppleIDButton: ASAuthorizationAppleIDButton {
    var onTap: (() -> Void)? {
        didSet { addTarget(self, action: #selector(tapped), for: .touchUpInside) }
    }

    @objc private func tapped() { onTap?() }
}

import AuthenticationServices
import CryptoKit
import Security
import TrexCore
import UIKit

/// iPhone 구글 로그인 다리 — 공유 Kotlin 앱 상태가 `IosGoogleSignInBridge` 로 부른다(Android Credential Manager 자리).
///
/// GoogleSignIn SDK 를 쓰지 않고 iOS 기본 `ASWebAuthenticationSession` 으로 구글 OAuth(인가 코드 + PKCE)를 직접 한다.
/// SDK 가 끌어오는 GTMSessionFetcher 가 MediaPipe 정적 라이브러리 안의 같은 코드와 링크 때 중복 기호 196개로 부딪혔다
/// (2026-10-28 CI). 흐름은 SDK 안에서 하는 것과 같다: 로그인 창 → 리디렉션으로 코드 → 토큰 주소에서 코드를 ID 토큰으로.
/// iPhone 클라이언트 ID 는 Info.plist `GIDClientID`. 받은 ID 토큰의 대상(aud)은 iPhone 클라이언트 ID 라 서버
/// `GOOGLE_CLIENT_IDS` 에 그 ID 가 있어야 한다(2026-10-28 반영). [serverClientId] 는 쓰지 않는다.
final class GoogleSignInBridge: NSObject, IosGoogleSignInBridge, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func signIn(serverClientId: String, done: @escaping (String?, String?) -> Void) {
        guard let clientID = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String, !clientID.isEmpty else {
            done(nil, "iPhone 구글 로그인 설정(GIDClientID)이 없어요"); return
        }
        // 리디렉션 스킴 = 클라이언트 ID 를 뒤집은 것(구글 iOS 클라이언트 규칙)
        let scheme = clientID.components(separatedBy: ".").reversed().joined(separator: ".")
        let redirectURI = scheme + ":/oauthredirect"
        let verifier = Self.randomURLSafe(byteCount: 32)
        let challenge = Self.base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
        let state = Self.randomURLSafe(byteCount: 16)
        var components = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: "openid email profile"),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "prompt", value: "select_account"),
        ]
        guard let url = components.url else { done(nil, "로그인 주소를 만들지 못했어요"); return }
        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: scheme) { [weak self] callback, error in
            self?.session = nil
            if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                done(nil, IosGoogleSignInRegistry.shared.CANCELLED); return
            }
            if let error {
                NSLog("[GoogleSignIn] 로그인 창 실패 %@", error.localizedDescription)
                done(nil, "구글 로그인에 실패했어요 (\(error.localizedDescription))"); return
            }
            let items = callback.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems } ?? []
            func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
            if value("error") == "access_denied" { done(nil, IosGoogleSignInRegistry.shared.CANCELLED); return }
            guard value("state") == state, let code = value("code") else {
                done(nil, "구글 로그인 응답을 확인하지 못했어요"); return
            }
            Self.exchange(code: code, clientID: clientID, redirectURI: redirectURI, verifier: verifier, done: done)
        }
        session.presentationContextProvider = self
        self.session = session
        if !session.start() {
            self.session = nil
            done(nil, "로그인 창을 열지 못했어요")
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        if let window = scenes.flatMap(\.windows).first(where: { $0.isKeyWindow }) ?? scenes.first?.windows.first { return window }
        if let scene = scenes.first { return ASPresentationAnchor(windowScene: scene) }
        return ASPresentationAnchor()
    }

    /// 인가 코드 → ID 토큰. iPhone 클라이언트는 클라이언트 비밀값 없이 PKCE 검증값으로 바꾼다. 결과는 메인에서 [done].
    private static func exchange(code: String, clientID: String, redirectURI: String, verifier: String,
                                 done: @escaping (String?, String?) -> Void) {
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let form = ["code": code, "client_id": clientID, "redirect_uri": redirectURI,
                    "grant_type": "authorization_code", "code_verifier": verifier]
        request.httpBody = form.map { "\($0.key)=\(formEncode($0.value))" }.joined(separator: "&").data(using: .utf8)
        URLSession.shared.dataTask(with: request) { data, _, error in
            let json = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            let idToken = json?["id_token"] as? String
            let failure: String? = if let error {
                "구글 로그인에 실패했어요 (\(error.localizedDescription))"
            } else if idToken == nil {
                "구글이 로그인 토큰을 주지 않았어요 (\(json?["error"] as? String ?? "응답 없음"))"
            } else {
                nil
            }
            if let failure { NSLog("[GoogleSignIn] 토큰 교환 실패 %@", failure) }
            DispatchQueue.main.async { done(failure == nil ? idToken : nil, failure) }
        }.resume()
    }

    private static func randomURLSafe(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        _ = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
        return base64URL(Data(bytes))
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func formEncode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

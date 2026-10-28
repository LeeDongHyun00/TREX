import GoogleSignIn
import TrexCore
import UIKit

/// iPhone 구글 로그인 다리 — 공유 Kotlin 앱 상태가 `IosGoogleSignInBridge` 로 부른다(Android Credential Manager 자리).
/// iPhone 클라이언트 ID 는 Info.plist `GIDClientID`, 서버가 확인하는 웹 클라이언트 ID 는 Kotlin 이 넘긴다(Info.plist `GIDServerClientID` 와 같은 값).
/// 로그인 창에서 돌아오는 주소(Info.plist URL 스킴 = 클라이언트 ID 를 뒤집은 것)는 TrexApp 의 onOpenURL 이 GIDSignIn 에 넘긴다.
final class GoogleSignInBridge: NSObject, IosGoogleSignInBridge {
    func signIn(serverClientId: String, done: @escaping (String?, String?) -> Void) {
        guard let clientID = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String, !clientID.isEmpty else {
            done(nil, "iPhone 구글 로그인 설정(GIDClientID)이 없어요"); return
        }
        guard let top = Self.topViewController() else { done(nil, "로그인 창을 띄울 화면을 찾지 못했어요"); return }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID, serverClientID: serverClientId)
        GIDSignIn.sharedInstance.signIn(withPresenting: top) { result, error in
            if let error = error as NSError? {
                if error.domain == kGIDSignInErrorDomain && error.code == GIDSignInError.Code.canceled.rawValue {
                    done(nil, IosGoogleSignInRegistry.shared.CANCELLED)
                } else {
                    NSLog("[GoogleSignIn] 실패 %@", error)
                    done(nil, "구글 로그인에 실패했어요 (\(error.localizedDescription))")
                }
                return
            }
            guard let idToken = result?.user.idToken?.tokenString else { done(nil, "구글이 ID 토큰을 주지 않았어요"); return }
            done(idToken, nil)
        }
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

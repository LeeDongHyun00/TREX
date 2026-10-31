import Foundation
import SwiftUI
import TrexCore

extension Notification.Name {
    static let trexLocalStorageFailed = Notification.Name("trex.local.storage.failed")
}

/// 공유 UI는 암호화 키를 받지 않는다. 평문은 메모리에서만 전달하며 실패는 앱 진입 차단으로 올린다.
final class LocalSecurityBridge: NSObject, IosSecureStorageBridge {
    private let store: LocalEncryptedStore
    init(store: LocalEncryptedStore = .shared) { self.store = store }
    func execute(operation: String, path: String, value: String?) -> IosStorageResult {
        do {
            let output: String?
            switch operation {
            case "read": output = try store.read(URL(fileURLWithPath: path)).map { try store.utf8($0) }
            case "write":
                guard let value else { throw LocalEncryptionError.invalidData }
                try store.write(Data(value.utf8), to: URL(fileURLWithPath: path)); output = nil
            case "append":
                guard let value else { throw LocalEncryptionError.invalidData }
                try store.append(Data(value.utf8), to: URL(fileURLWithPath: path)); output = nil
            case "list":
                let paths = try store.logicalFiles(in: URL(fileURLWithPath: path))
                output = try store.utf8(JSONSerialization.data(withJSONObject: paths))
            case "preferences-read": output = try store.readPreferences(name: path)
            case "preferences-write":
                guard let value else { throw LocalEncryptionError.invalidData }
                try store.writePreferences(name: path, json: value); output = nil
            default: throw LocalEncryptionError.invalidData
            }
            return IosStorageResult(value: output, error: nil)
        } catch {
            // 앱 상태가 오류를 잡더라도 다른 저장/동기화 경로가 빈 값으로 덮어쓰지 못하게 차단한다.
            store.block()
            DispatchQueue.main.async { NotificationCenter.default.post(name: .trexLocalStorageFailed, object: nil) }
            return IosStorageResult(value: nil, error: "암호화 데이터를 읽거나 저장하지 못했습니다. 원본을 초기화하지 않았습니다.")
        }
    }
}

/// 이관에 성공하기 전에는 Compose 컨트롤러·AppViewModel·동기화를 생성하지 않는다.
struct LocalSecurityGate<Content: View>: View {
    @ViewBuilder var content: () -> Content
    @State private var ready = false
    @State private var message: String?
    @State private var working = false
    @State private var runtimeFailure = false

    var body: some View {
        Group {
            if ready { content() }
            else {
                VStack(spacing: 20) {
                    if working { ProgressView(); Text("저장 데이터를 준비하고 있습니다.") }
                    else if let message {
                        Text("저장 데이터를 확인하지 못했습니다.").font(.headline)
                        Text(message).multilineTextAlignment(.center)
                        Text("데이터를 초기화하지 않았습니다. 앱을 삭제하지 마세요.").font(.footnote)
                        if runtimeFailure { Text("앱을 완전히 종료한 뒤 다시 실행해 주세요.") }
                        else { Button("다시 시도") { prepare() }.buttonStyle(.borderedProminent) }
                    }
                }.padding(28)
            }
        }
        .onAppear { if !ready && !working && message == nil { prepare() } }
        .onReceive(NotificationCenter.default.publisher(for: .trexLocalStorageFailed)) { _ in
            runtimeFailure = true; ready = false; working = false
            message = "암호화 키 또는 저장 파일을 사용할 수 없습니다."
        }
    }

    private func prepare() {
        working = true; message = nil
        DispatchQueue.global(qos: .userInitiated).async {
            let result = Result { try LocalEncryptedStore.shared.prepare() }
            DispatchQueue.main.async {
                working = false
                switch result {
                case .success: ready = true
                case .failure: message = "휴대폰 잠금을 해제하고 저장 공간을 확인한 뒤 다시 시도해 주세요. 문제가 계속되면 담당자에게 문의해 주세요."
                }
            }
        }
    }
}

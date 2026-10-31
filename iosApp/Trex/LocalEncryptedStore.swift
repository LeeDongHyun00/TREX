import Foundation
import CoreFoundation
import CryptoKit
import Security
import Darwin

/// §125: CryptoKit의 AES-256-GCM. 키는 동기화하지 않는 기기 귀속 Keychain 항목으로 보관한다.
/// Secure Enclave의 비반출 AES 키라고 주장하지 않는다. 사용할 때 키 바이트는 앱 메모리에 올라온다.
protocol LocalEncryptionKeySource {
    func load(allowCreation: Bool) throws -> SymmetricKey
}

enum LocalEncryptionError: Error, LocalizedError {
    case keyUnavailable, invalidData, migrationMismatch, invalidPath, notPrepared, ioFailure
    var errorDescription: String? {
        switch self {
        case .keyUnavailable: return "기기의 암호화 키를 사용할 수 없습니다."
        case .invalidData: return "저장 데이터의 인증 또는 형식을 확인하지 못했습니다."
        case .migrationMismatch: return "기존 데이터와 암호화 사본이 일치하지 않습니다."
        case .invalidPath: return "허용되지 않은 저장 경로입니다."
        case .notPrepared: return "암호화 저장소가 준비되지 않았습니다."
        case .ioFailure: return "기기에 데이터를 안전하게 저장하지 못했습니다."
        }
    }
}

final class KeychainEncryptionKey: LocalEncryptionKeySource {
    private let service: String
    init(service: String = (Bundle.main.bundleIdentifier ?? "com.leedonghyun.trex.ios") + ".local.aes256.v1") {
        self.service = service
    }
    func load(allowCreation: Bool) throws -> SymmetricKey {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: "data-key",
            kSecAttrSynchronizable as String: false]
        var lookup = query
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        if status == errSecSuccess {
            guard let data = result as? Data, data.count == 32 else { throw LocalEncryptionError.keyUnavailable }
            return SymmetricKey(data: data)
        }
        // 잠금·권한 오류를 '키 없음'으로 취급하여 새 키를 만들지 않는다.
        guard status == errSecItemNotFound, allowCreation else { throw LocalEncryptionError.keyUnavailable }
        let key = SymmetricKey(size: .bits256)
        var insert = query
        insert[kSecValueData as String] = key.withUnsafeBytes { Data($0) }
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(insert as CFDictionary, nil)
        if added == errSecDuplicateItem { return try load(allowCreation: false) }
        guard added == errSecSuccess else { throw LocalEncryptionError.keyUnavailable }
        // 저장 성공 뒤 다시 읽을 수 있어야 파일을 쓰기 시작한다.
        return try load(allowCreation: false)
    }
}

/// 평문 파일 이름은 논리 주소로만 사용한다. 실제 저장 파일은 같은 자리에 붙은 `.aes`다.
/// 인증 실패 시 평문 폴백·빈 값·자동 초기화를 하지 않는다. 모든 IO는 같은 잠금에서 직렬화한다.
final class LocalEncryptedStore {
    static let shared = LocalEncryptedStore(container: URL(fileURLWithPath: NSHomeDirectory()))
    private static let magic = Data("TREXAES1".utf8)
    private static let marker = Data("TREX iOS local storage v1".utf8)
    let container: URL
    let securityDirectory: URL
    private let keys: LocalEncryptionKeySource
    private let fm = FileManager.default
    private let lock = NSRecursiveLock()
    private var key: SymmetricKey?
    private var prepared = false
    private var blocked = false

    init(container: URL, keys: LocalEncryptionKeySource = KeychainEncryptionKey()) {
        self.container = container.standardizedFileURL.resolvingSymlinksInPath()
        self.securityDirectory = self.container.appendingPathComponent("Library/Application Support/TREXSecure")
        self.keys = keys
    }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock(); defer { lock.unlock() }; return try body()
    }

    func block() { locked { blocked = true } }

    private func requireReady() throws {
        guard prepared, !blocked, key != nil else { throw LocalEncryptionError.notPrepared }
    }

    /// Compose/AppViewModel과 서버 동기화가 만들어지기 전에 호출한다.
    func prepare() throws {
        try locked {
            guard !blocked else { throw LocalEncryptionError.notPrepared }
            prepared = false
            let roots = [securityDirectory, container.appendingPathComponent("Library/Application Support/TREX"),
                         container.appendingPathComponent("Documents/posture_logs")]
            let encryptedExists = try roots.contains { root in try regularFiles(root).contains { $0.pathExtension == "aes" } }
            key = try keys.load(allowCreation: !encryptedExists)
            try protectDirectory(securityDirectory)
            let sentinel = securityDirectory.appendingPathComponent("key-check.aes")
            if fm.fileExists(atPath: sentinel.path) {
                guard try decryptFile(sentinel, context: "key-check") == Self.marker else { throw LocalEncryptionError.invalidData }
            } else {
                try commit(Self.marker, to: sentinel, context: "key-check")
            }
            // 기존 앱이 실제로 사용한 trex.* 스위트를 전부 발견한다(토큰·동기화 버전 포함).
            let preferences = container.appendingPathComponent("Library/Preferences")
            for url in try regularFiles(preferences) where url.pathExtension == "plist" && url.lastPathComponent.hasPrefix("trex.") {
                _ = try preferencesLocked(suite: url.deletingPathExtension().lastPathComponent)
            }
            let prefDir = securityDirectory.appendingPathComponent("preferences")
            for url in try regularFiles(prefDir) where url.pathExtension == "aes" {
                _ = try preferencesLocked(suite: url.deletingPathExtension().lastPathComponent)
            }
            // SwiftUI 시절 data.json(기준선 포함)도 버리지 않고 암호화한다.
            for root in roots.dropFirst() {
                try protectDirectory(root)
                let files = try regularFiles(root)
                let logical = Set(files.compactMap { url -> String? in
                    let source = url.pathExtension == "aes" ? url.deletingPathExtension() : url
                    return ["json", "jsonl", "csv", "tsv"].contains(source.pathExtension) ? source.path : nil
                })
                for path in logical.sorted() { _ = try readLocked(URL(fileURLWithPath: path)) }
            }
            prepared = true
        }
    }

    func read(_ source: URL) throws -> Data? {
        try locked { try requireReady(); return try readLocked(source) }
    }

    func write(_ data: Data, to source: URL) throws {
        try locked {
            try requireReady()
            _ = try readLocked(source) // 손상되었거나 이관 중인 원본을 먼저 검증한다.
            try commit(data, to: encryptedURL(source), context: try fileContext(source))
        }
    }

    func append(_ data: Data, to source: URL) throws {
        try locked {
            try requireReady()
            var previous = try readLocked(source) ?? Data()
            previous.append(data)
            try commit(previous, to: encryptedURL(source), context: try fileContext(source))
        }
    }

    /// 개발용 파일 열거도 논리 이름을 반환한다. 암호문을 JSONL로 오인하여 분석하지 않는다.
    func logicalFiles(in directory: URL) throws -> [String] {
        try locked {
            try requireReady(); _ = try fileContext(directory.appendingPathComponent("placeholder"))
            guard fm.fileExists(atPath: directory.path) else { return [] }
            return try Set(fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey])
                .filter { try $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true }
                .filter { !$0.lastPathComponent.contains(".tmp-") }
                .map { $0.pathExtension == "aes" ? $0.deletingPathExtension().path : $0.path }).sorted()
        }
    }

    func readPreferences(name: String) throws -> String {
        try locked {
            try requireReady()
            return try utf8(preferencesLocked(suite: "trex." + name))
        }
    }

    func writePreferences(name: String, json: String) throws {
        try locked {
            try requireReady()
            let suite = "trex." + name
            _ = try preferencesLocked(suite: suite)
            let data = Data(json.utf8)
            _ = try validatePreferences(data)
            try commit(data, to: preferenceURL(suite), context: "preferences/" + suite)
        }
    }

    private func preferenceURL(_ suite: String) throws -> URL {
        guard suite.hasPrefix("trex."), suite.count > 5,
              suite.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "._-".contains($0)) }) else {
            throw LocalEncryptionError.invalidPath
        }
        return securityDirectory.appendingPathComponent("preferences/" + suite + ".aes")
    }

    private func preferencesLocked(suite: String) throws -> Data {
        let destination = try preferenceURL(suite)
        guard let defaults = UserDefaults(suiteName: suite) else { throw LocalEncryptionError.invalidData }
        // dictionaryRepresentation에는 등록/전역 기본값도 들어가므로 영구 도메인만 이관한다.
        let legacy = defaults.persistentDomain(forName: suite) ?? [:]
        let original = try taggedPreferences(legacy)
        let data: Data
        if fm.fileExists(atPath: destination.path) {
            data = try decryptFile(destination, context: "preferences/" + suite)
            let decoded = try validatePreferences(data)
            if !legacy.isEmpty && !NSDictionary(dictionary: decoded).isEqual(to: original) {
                throw LocalEncryptionError.migrationMismatch
            }
        } else {
            data = try JSONSerialization.data(withJSONObject: original, options: [.sortedKeys])
            try commit(data, to: destination, context: "preferences/" + suite)
        }
        // 암호문을 디스크에서 읽어 인증·값 대조까지 마친 뒤에만 평문 스위트를 비운다.
        let legacyFile = container.appendingPathComponent("Library/Preferences/" + suite + ".plist")
        if !legacy.isEmpty || fm.fileExists(atPath: legacyFile.path) {
            let verified = try validatePreferences(decryptFile(destination, context: "preferences/" + suite))
            if !legacy.isEmpty && !NSDictionary(dictionary: verified).isEqual(to: original) {
                throw LocalEncryptionError.migrationMismatch
            }
            defaults.removePersistentDomain(forName: suite)
            guard defaults.synchronize(), (defaults.persistentDomain(forName: suite) ?? [:]).isEmpty else {
                throw LocalEncryptionError.ioFailure
            }
            // synchronize 실패 뒤 재시도에서는 메모리만 비어 있을 수 있다. 디스크 평문도 확인한다.
            if fm.fileExists(atPath: legacyFile.path) {
                let disk = try PropertyListSerialization.propertyList(from: Data(contentsOf: legacyFile), options: [], format: nil)
                guard let remaining = disk as? [String: Any], remaining.isEmpty else { throw LocalEncryptionError.ioFailure }
            }
        }
        return data
    }

    /// 정수는 문자열로 전달하여 Swift/JSON/Kotlin을 지날 때 Int64 정밀도를 잃지 않는다.
    private func taggedPreferences(_ values: [String: Any]) throws -> [String: Any] {
        var result: [String: Any] = [:]
        for (name, value) in values {
            if let string = value as? String { result[name] = ["t": "s", "v": string] }
            else if let number = value as? NSNumber {
                if CFGetTypeID(number) == CFBooleanGetTypeID() { result[name] = ["t": "b", "v": number.boolValue] }
                else {
                    guard let integer = Int64(number.stringValue) else { throw LocalEncryptionError.invalidData }
                    result[name] = ["t": "l", "v": String(integer)]
                }
            } else if let strings = value as? [String] { result[name] = ["t": "ss", "v": Array(Set(strings)).sorted()] }
            else { throw LocalEncryptionError.invalidData }
        }
        return result
    }

    private func validatePreferences(_ data: Data) throws -> [String: Any] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw LocalEncryptionError.invalidData }
        for (_, value) in root {
            guard let entry = value as? [String: Any], entry.count == 2, let type = entry["t"] as? String else {
                throw LocalEncryptionError.invalidData
            }
            let valid: Bool
            switch type {
            case "s": valid = entry["v"] is String
            case "b": valid = (entry["v"] as? NSNumber).map { CFGetTypeID($0) == CFBooleanGetTypeID() } ?? false
            case "l": valid = (entry["v"] as? String).flatMap { Int64($0) } != nil
            case "ss": valid = entry["v"] is [String]
            default: valid = false
            }
            guard valid else { throw LocalEncryptionError.invalidData }
        }
        return root
    }

    private func encryptedURL(_ source: URL) -> URL { source.appendingPathExtension("aes") }

    private func fileContext(_ source: URL) throws -> String {
        let resolved = source.standardizedFileURL.resolvingSymlinksInPath().path
        let prefix = container.path + "/"
        guard resolved.hasPrefix(prefix), !resolved.hasSuffix(".aes") else { throw LocalEncryptionError.invalidPath }
        let relative = String(resolved.dropFirst(prefix.count))
        guard relative.hasPrefix("Library/Application Support/TREX/") || relative.hasPrefix("Documents/") else {
            throw LocalEncryptionError.invalidPath
        }
        // 컨테이너 UUID는 업데이트/복원 때 달라질 수 있어 AAD에 넣지 않는다.
        return "file/" + relative
    }

    private func readLocked(_ source: URL) throws -> Data? {
        let context = try fileContext(source)
        let destination = encryptedURL(source)
        if fm.fileExists(atPath: destination.path) {
            let data = try decryptFile(destination, context: context)
            if fm.fileExists(atPath: source.path) {
                guard try Data(contentsOf: source) == data else { throw LocalEncryptionError.migrationMismatch }
                try fm.removeItem(at: source)
                try syncDirectory(source.deletingLastPathComponent())
            }
            return data
        }
        guard fm.fileExists(atPath: source.path) else { return nil }
        let data = try Data(contentsOf: source)
        try commit(data, to: destination, context: context)
        guard try decryptFile(destination, context: context) == data else { throw LocalEncryptionError.migrationMismatch }
        try fm.removeItem(at: source)
        try syncDirectory(source.deletingLastPathComponent())
        return data
    }

    private func aad(_ context: String) -> Data { Data(("trex.ios.aes256.v1\u{0}" + context).utf8) }

    private func decryptFile(_ url: URL, context: String) throws -> Data {
        guard let key else { throw LocalEncryptionError.keyUnavailable }
        let data = try Data(contentsOf: url)
        guard data.starts(with: Self.magic), data.count >= Self.magic.count + 28 else { throw LocalEncryptionError.invalidData }
        let sealed = try AES.GCM.SealedBox(combined: data.dropFirst(Self.magic.count))
        return try AES.GCM.open(sealed, using: key, authenticating: aad(context))
    }

    private func commit(_ data: Data, to destination: URL, context: String) throws {
        guard let key else { throw LocalEncryptionError.keyUnavailable }
        let parent = destination.deletingLastPathComponent()
        try protectDirectory(parent)
        // CryptoKit가 매번 새 96비트 nonce를 생성하며, combined에 nonce·암호문·128비트 태그가 들어간다.
        guard let combined = try AES.GCM.seal(data, using: key, authenticating: aad(context)).combined else {
            throw LocalEncryptionError.invalidData
        }
        let temp = parent.appendingPathComponent(destination.lastPathComponent + ".tmp-" + UUID().uuidString)
        defer { try? fm.removeItem(at: temp) }
        try (Self.magic + combined).write(to: temp, options: [.withoutOverwriting, .completeFileProtectionUntilFirstUserAuthentication])
        let handle = try FileHandle(forWritingTo: temp)
        do { try handle.synchronize(); try handle.close() }
        catch { try? handle.close(); throw error }
        guard try decryptFile(temp, context: context) == data else { throw LocalEncryptionError.migrationMismatch }
        // 같은 디렉터리 rename은 기존 파일을 원자적으로 교체한다. 목적지를 먼저 삭제하지 않는다.
        guard rename(temp.path, destination.path) == 0 else { throw LocalEncryptionError.ioFailure }
        try syncDirectory(parent)
        var protected = destination
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try protected.setResourceValues(values)
    }

    private func protectDirectory(_ directory: URL) throws {
        try fm.createDirectory(at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var protected = directory
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try protected.setResourceValues(values)
    }

    private func syncDirectory(_ directory: URL) throws {
        let fd = open(directory.path, O_RDONLY)
        guard fd >= 0 else { throw LocalEncryptionError.ioFailure }
        defer { close(fd) }
        guard fsync(fd) == 0 else { throw LocalEncryptionError.ioFailure }
    }

    private func regularFiles(_ directory: URL) throws -> [URL] {
        guard fm.fileExists(atPath: directory.path) else { return [] }
        var failure: Error?
        guard let iterator = fm.enumerator(at: directory, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey],
            errorHandler: { _, error in failure = error; return false }) else { throw LocalEncryptionError.ioFailure }
        var files: [URL] = []
        for case let url as URL in iterator {
            let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true else { throw LocalEncryptionError.invalidPath }
            if values.isRegularFile == true { files.append(url) }
        }
        if let failure { throw failure }
        return files
    }

    func utf8(_ data: Data) throws -> String {
        guard let text = String(data: data, encoding: .utf8) else { throw LocalEncryptionError.invalidData }
        return text
    }
}

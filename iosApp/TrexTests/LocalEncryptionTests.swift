import XCTest
import CryptoKit
import Security
@testable import Trex

/// Apple CryptoKit·Keychain·파일 보호를 실제 iOS 시뮬레이터/기기에서 확인하는 시험이다.
/// Windows의 문법 검사 결과를 이 시험의 통과로 기록하지 않는다.
final class LocalEncryptionTests: XCTestCase {
    private final class TestKeys: LocalEncryptionKeySource {
        var key: SymmetricKey? = SymmetricKey(size: .bits256)
        var creationRequests: [Bool] = []
        func load(allowCreation: Bool) throws -> SymmetricKey {
            creationRequests.append(allowCreation)
            if let key { return key }
            guard allowCreation else { throw LocalEncryptionError.keyUnavailable }
            let generated = SymmetricKey(size: .bits256); key = generated; return generated
        }
    }
    private var root: URL!
    private var keys: TestKeys!
    private var store: LocalEncryptedStore!
    private var suites: [String] = []

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("trex-security-test-" + UUID().uuidString)
        keys = TestKeys(); store = LocalEncryptedStore(container: root, keys: keys)
        try store.prepare()
    }
    override func tearDownWithError() throws {
        for suite in suites { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        try FileManager.default.removeItem(at: root)
    }
    private func source(_ name: String = "muscle_load.json") -> URL {
        root.appendingPathComponent("Library/Application Support/TREX/" + name)
    }
    private func writeLegacy(_ data: Data, to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url)
    }
    private func preferenceName() -> String {
        let name = "test." + UUID().uuidString
        suites.append("trex." + name); return name
    }

    func testFreshInstallRoundTripAndRandomNonce() throws {
        let url = source(), value = Data("운동 12회 · 식단 0.5인분".utf8)
        try store.write(value, to: url)
        let first = try Data(contentsOf: url.appendingPathExtension("aes"))
        XCTAssertNil(first.range(of: value))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        try store.write(value, to: url)
        XCTAssertNotEqual(first, try Data(contentsOf: url.appendingPathExtension("aes")))
        let reopened = LocalEncryptedStore(container: root, keys: keys); try reopened.prepare()
        XCTAssertEqual(try reopened.read(url), value)
    }

    func testLegacyFilesAreComparedAndMigratedBeforeEntry() throws {
        let log = root.appendingPathComponent("Documents/posture_logs/labels/set_labels.jsonl")
        let files = [source(), source("data.json"), source("food-memory.tsv"), source("posture_baseline.tsv"), log]
        let value = Data("기존 기록\n1234\n".utf8)
        for file in files { try writeLegacy(value, to: file) }
        let reopened = LocalEncryptedStore(container: root, keys: keys); try reopened.prepare()
        for file in files {
            XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
            XCTAssertEqual(try reopened.read(file), value)
        }
    }

    func testPreferenceMigrationPreservesTypesAndInt64() throws {
        let name = preferenceName(), suite = "trex." + name
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.set("토큰-테스트", forKey: "token")
        defaults.set(false, forKey: "flag")
        defaults.set(NSNumber(value: Int64.max), forKey: "max")
        defaults.set(NSNumber(value: Int64.min), forKey: "min")
        defaults.set(["덤벨", "바벨"], forKey: "equipment")
        XCTAssertTrue(defaults.synchronize())
        let json = try store.readPreferences(name: name)
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: [String: Any]])
        XCTAssertEqual(root["token"]?["v"] as? String, "토큰-테스트")
        XCTAssertEqual(root["flag"]?["v"] as? Bool, false)
        XCTAssertEqual(root["max"]?["v"] as? String, String(Int64.max))
        XCTAssertEqual(root["min"]?["v"] as? String, String(Int64.min))
        XCTAssertEqual(Set(root["equipment"]?["v"] as? [String] ?? []), ["덤벨", "바벨"])
        XCTAssertTrue((defaults.persistentDomain(forName: suite) ?? [:]).isEmpty)
        let reopened = LocalEncryptedStore(container: self.root, keys: keys); try reopened.prepare()
        XCTAssertEqual(try reopened.readPreferences(name: name), json)
    }

    func testInterruptedMigrationWithMatchingCopiesResumes() throws {
        let value = Data("원본".utf8), url = source()
        try store.write(value, to: url); try writeLegacy(value, to: url)
        XCTAssertEqual(try store.read(url), value)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    func testMigrationMismatchKeepsBothCopies() throws {
        let url = source(), original = Data("다른 원본".utf8)
        try store.write(Data("먼저 만든 사본".utf8), to: url)
        let encrypted = try Data(contentsOf: url.appendingPathExtension("aes"))
        try writeLegacy(original, to: url)
        XCTAssertThrowsError(try store.read(url))
        XCTAssertEqual(try Data(contentsOf: url), original)
        XCTAssertEqual(try Data(contentsOf: url.appendingPathExtension("aes")), encrypted)
    }

    func testTamperingCannotBeReadOrOverwritten() throws {
        let url = source(), encrypted = url.appendingPathExtension("aes")
        try store.write(Data("기존 기록".utf8), to: url)
        var damaged = try Data(contentsOf: encrypted); damaged[damaged.count - 1] ^= 1
        try damaged.write(to: encrypted)
        XCTAssertThrowsError(try store.read(url))
        XCTAssertThrowsError(try store.write(Data("빈 기록".utf8), to: url))
        XCTAssertEqual(try Data(contentsOf: encrypted), damaged)
    }

    func testTruncatedCiphertextAndSwappedPathsAreRejected() throws {
        let url = source(), other = source("data.json")
        try store.write(Data(repeating: 65, count: 128), to: url)
        let cipher = try Data(contentsOf: url.appendingPathExtension("aes"))
        try cipher.write(to: other.appendingPathExtension("aes"))
        XCTAssertThrowsError(try store.read(other))
        try cipher.dropLast().write(to: url.appendingPathExtension("aes"))
        XCTAssertThrowsError(try store.read(url))
    }

    func testLostKeyDoesNotGenerateReplacement() throws {
        let url = source(); try store.write(Data("보존".utf8), to: url)
        let original = try Data(contentsOf: url.appendingPathExtension("aes"))
        keys.key = nil
        let reopened = LocalEncryptedStore(container: root, keys: keys)
        XCTAssertThrowsError(try reopened.prepare())
        XCTAssertEqual(keys.creationRequests.last, false)
        XCTAssertNil(keys.key)
        XCTAssertEqual(try Data(contentsOf: url.appendingPathExtension("aes")), original)
    }

    func testWrongKeyStopsStartup() throws {
        let reopened = LocalEncryptedStore(container: root, keys: TestKeys())
        XCTAssertThrowsError(try reopened.prepare())
        XCTAssertThrowsError(try reopened.write(Data(), to: source()))
    }

    func testConcurrentAppendKeepsEveryRecord() throws {
        let url = root.appendingPathComponent("Documents/posture_logs/sets-test.jsonl")
        let lock = NSLock(); var failures: [Error] = []
        DispatchQueue.concurrentPerform(iterations: 40) { i in
            do { try store.append(Data("\(i)\n".utf8), to: url) }
            catch { lock.lock(); failures.append(error); lock.unlock() }
        }
        XCTAssertTrue(failures.isEmpty)
        let text = try store.utf8(XCTUnwrap(store.read(url)))
        XCTAssertEqual(Set(text.split(separator: "\n").compactMap { Int($0) }), Set(0..<40))
    }

    func testMissingEmptyAndBackupExclusion() throws {
        let url = source()
        XCTAssertNil(try store.read(url))
        try store.write(Data(), to: url)
        XCTAssertEqual(try store.read(url), Data())
        let attributes = try url.appendingPathExtension("aes").resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(attributes.isExcludedFromBackup, true)
        let files = try store.logicalFiles(in: url.deletingLastPathComponent())
        XCTAssertTrue(files.contains(url.path)); XCTAssertFalse(files.contains(url.appendingPathExtension("aes").path))
    }

    func testSandboxUUIDChangeDoesNotChangeAAD() throws {
        let url = source(); try store.write(Data("기록".utf8), to: url)
        let moved = root.deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
        try FileManager.default.copyItem(at: root, to: moved)
        defer { try? FileManager.default.removeItem(at: moved) }
        let reopened = LocalEncryptedStore(container: moved, keys: keys); try reopened.prepare()
        XCTAssertEqual(try reopened.read(moved.appendingPathComponent("Library/Application Support/TREX/muscle_load.json")), Data("기록".utf8))
    }

    func testOutsideSandboxIsRejected() throws {
        XCTAssertThrowsError(try store.write(Data(), to: URL(fileURLWithPath: "/tmp/trex-outside.json")))
    }

    func testInvalidPreferenceTypeCannotReplaceStoredRecord() throws {
        let name = preferenceName()
        let valid = "{\"record\":{\"t\":\"s\",\"v\":\"원본\"}}"
        try store.writePreferences(name: name, json: valid)
        XCTAssertThrowsError(try store.writePreferences(name: name, json: "{\"record\":{\"t\":\"unknown\",\"v\":0}}"))
        XCTAssertEqual(try store.readPreferences(name: name), valid)
    }

    func testMigrationWriteFailureLeavesPlaintextSourceIntact() throws {
        let url = source(), original = Data("기존 기록 보존".utf8)
        try writeLegacy(original, to: url)
        // 목적지에 디렉터리가 있으면 암호화 파일을 만들 수 없다.
        try FileManager.default.createDirectory(at: url.appendingPathExtension("aes"), withIntermediateDirectories: true)
        XCTAssertThrowsError(try store.read(url))
        XCTAssertEqual(try Data(contentsOf: url), original)
    }

    func testKeychainUses256BitDeviceOnlyKey() throws {
        let service = "trex.test." + UUID().uuidString
        let keychain = KeychainEncryptionKey(service: service)
        defer { SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary) }
        let first = try keychain.load(allowCreation: true)
        XCTAssertEqual(first.bitCount, 256)
        let second = try keychain.load(allowCreation: false)
        XCTAssertEqual(first.withUnsafeBytes { Data($0) }, second.withUnsafeBytes { Data($0) })
        var item: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecReturnAttributes as String: true] as CFDictionary, &item)
        XCTAssertEqual(status, errSecSuccess)
        let attributes = try XCTUnwrap(item as? [String: Any])
        XCTAssertEqual(attributes[kSecAttrAccessible as String] as? String, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
        XCTAssertEqual(attributes[kSecAttrSynchronizable as String] as? Bool, false)
    }
}

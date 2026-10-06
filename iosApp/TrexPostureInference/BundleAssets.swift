import CryptoKit
import Foundation

enum BundleAssets {
    static let names = ["pose_landmarker_full.task", "rules_mp_v0.json", "rules_floor_v0.json", "normal_pose_reference.tsv"]
    private struct Manifest: Decodable {
        struct Entry: Decodable { let path: String; let sha256: String }
        let files: [Entry]
    }

    static func verify() throws -> [String: String] {
        guard let manifestURL = Bundle.main.url(forResource: "ASSET_BASELINE", withExtension: "json") else {
            throw DiagnosticError("번들 자산 기준표 없음")
        }
        let manifest = try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: manifestURL))
        var verified: [String: String] = [:]
        for name in names {
            guard let entry = manifest.files.first(where: { $0.path == "app/src/main/assets/posture/" + name }),
                  let url = Bundle.main.url(forResource: name, withExtension: nil) else {
                throw DiagnosticError("번들 자산 없음: \(name)")
            }
            let digest = SHA256.hash(data: try Data(contentsOf: url)).map { String(format: "%02x", $0) }.joined()
            guard digest == entry.sha256 else { throw DiagnosticError("번들 SHA-256 불일치: \(name)") }
            verified[name] = digest
        }
        return verified
    }
}

struct DiagnosticError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

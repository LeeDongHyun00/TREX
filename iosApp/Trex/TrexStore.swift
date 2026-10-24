import Foundation
import SwiftUI
import TrexCore

@MainActor final class TrexStore: ObservableObject {
    @Published var data = PersistedData()
    @Published var error: String?
    @Published var exercises: [Exercise] = []
    @Published var guides: [Guide] = []
    @Published var foods: [Food] = []
    private let file: URL
    init() {
        file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TREX/data.json")
        do {
            exercises = try Resources.decode("catalog.json", as: [Exercise].self)
            guides = try Resources.decode("guides.json", as: [Guide].self)
            foods = try Resources.decode("nutrition.json", as: [Food].self)
            if FileManager.default.fileExists(atPath: file.path) {
                data = try JSONDecoder().decode(PersistedData.self, from: Data(contentsOf: file))
            } else { data.plan = exercises.filter { ["기본 스쿼트", "런지", "덤벨 컬"].contains($0.name) }.map(PlanItem.init) }
        } catch { self.error = "데이터를 불러오지 못했습니다. \(error.localizedDescription)" }
    }
    func save() {
        data.profile.height = data.profile.height.isFinite ? min(250,max(80,data.profile.height)) : 170
        data.profile.weight = data.profile.weight.isFinite ? min(350,max(20,data.profile.weight)) : 65
        data.nutritionGoal = data.nutritionGoal?.normalized
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(data).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch { self.error = "저장하지 못했습니다. \(error.localizedDescription)" }
    }
    func add(_ exercise: Exercise) { data.plan.append(PlanItem(exercise)); save() }
    func record(_ record: SetRecord, frames: String?) {
        data.records.append(record); save()
        if let frames, !frames.isEmpty {
            do {
                let dir = file.deletingLastPathComponent().appendingPathComponent("posture_logs")
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                try Data(frames.utf8).write(to: dir.appendingPathComponent(record.id.uuidString + ".jsonl"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            } catch { self.error = "운동 기록은 저장했지만 자세 로그 저장에 실패했습니다. \(error.localizedDescription)" }
        }
    }
    func acceptBaseline(_ report: SetReport) {
        guard !report.baselineValues.isEmpty else { error = "측정 프레임이 부족해 기준선을 수집하지 못했습니다."; return }
        var sets = data.baselineSets[report.name, default: []]; sets.append(report.baselineValues)
        data.baselineSets[report.name] = sets
        do {
            let result = try IosBaseline().build(input: Resources.json(sets), requiredSets: Int32(report.baselineSets))
            data.baselines[report.name] = try Resources.parse(result, as: [String: Float].self)
        } catch { self.error = "기준선을 계산하지 못했습니다. \(error.localizedDescription)" }
        save()
    }
    func exportData() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("TREX-backup.json")
        try JSONEncoder().encode(data).write(to: url, options: .atomic); return url
    }
    func restore(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let restored = try JSONDecoder().decode(PersistedData.self, from: Data(contentsOf: url))
            guard restored.schemaVersion == 1 else { throw DiagnosticError("지원하지 않는 백업 버전입니다.") }
            data = restored; save()
        } catch { self.error = "백업을 복원하지 못했습니다. \(error.localizedDescription)" }
    }
    var todayRecords: [SetRecord] { data.records.filter { Calendar.current.isDateInToday($0.date) } }
    var todayMeals: [MealEntry] { data.meals.filter { Calendar.current.isDateInToday($0.date) } }
    var nutritionGoal: NutritionGoal { data.nutritionGoal?.normalized ?? data.profile.recommendedNutrition }
    func loadSnapshot(records: [SetRecord]? = nil) -> [String: Any] {
        do {
            let rows: [[String: Any]] = (records ?? data.records).map { r in
                var row: [String: Any] = ["id": r.id.uuidString, "name": r.name, "endedAt": Int64(r.date.timeIntervalSince1970*1000), "reps": r.report?.physicalReps ?? r.repetitions, "duration": r.duration, "weight": r.weight, "height": r.height]
                if let n = r.actualReps { row["actualReps"] = n }
                if let n = r.report?.left { row["left"] = n }; if let n = r.report?.right { row["right"] = n }
                return row
            }
            let result = try IosLoad().snapshot(input: Resources.json(rows), nowMs: Int64(Date().timeIntervalSince1970*1000), availableEquipment: Resources.json(Array(data.profile.equipment ?? [])))
            return (try JSONSerialization.jsonObject(with: Data(result.utf8))) as? [String: Any] ?? [:]
        } catch { return [:] }
    }
}

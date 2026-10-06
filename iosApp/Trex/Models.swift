import Foundation

struct Exercise: Codable, Identifiable, Hashable {
    var id: String { name }
    let name: String
    let category: String
    let repetitions: Int
    let sets: Int
    let hold: Bool
}
struct PlanItem: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var repetitions: Int
    var sets: Int
    var hold: Bool
    var posture = true
    var secondsPerRep = 4
    var restSeconds = 45
    init(_ exercise: Exercise) {
        name = exercise.name; repetitions = exercise.repetitions; sets = exercise.sets; hold = exercise.hold
    }
}
struct Guide: Codable, Identifiable {
    var id: String { name }
    let name: String; let asset: String; let comment: String
    let setup: [String]; let steps: [String]; let breathing: String; let cautions: [String]
    let sourceName: String; let sourceUrl: String
}
struct Food: Codable, Identifiable {
    var id: String { name }
    var name: String; var kcal: Int; var carbs: Double; var protein: Double; var fat: Double
}
struct MealEntry: Codable, Identifiable {
    var id = UUID(); var date = Date(); var meal = "점심"
    var food: Food; var servings = 1.0
}
struct Profile: Codable {
    var name = "트렉스"; var gender = "none"; var age = 30
    var height = 170.0; var weight = 65.0; var activity = 1.35
    var goal = "건강 유지"; var days: Set<Int> = [1, 3, 5]; var place = "집"
    var onboarded = false
    var equipment: Set<String>? = ["DUMBBELL"]
    var kcal: Int {
        let w = weight.isFinite ? min(350, max(20, weight)) : 65
        let h = height.isFinite ? min(250, max(80, height)) : 170
        let a = activity.isFinite ? min(2.5, max(1, activity)) : 1.35
        return Int((10 * w + 6.25 * h - 5 * Double(min(120, max(10, age))) + (gender == "female" ? -161 : 5)) * a / 10) * 10
    }
    var recommendedNutrition: NutritionGoal {
        let w = weight.isFinite ? min(350, max(20, weight)) : 65
        let protein = (w * 1.8).rounded()
        let fat = (Double(kcal) * 0.25 / 9).rounded()
        return NutritionGoal(kcal: kcal, carbs: max(0, ((Double(kcal)-protein*4-fat*9)/4).rounded()), protein: protein, fat: fat).normalized
    }
}
struct NutritionGoal: Codable {
    var kcal: Int; var carbs: Double; var protein: Double; var fat: Double
    var normalized: NutritionGoal {
        NutritionGoal(kcal: min(5000,max(800,kcal)), carbs: carbs.isFinite ? min(800,max(0,carbs)) : 0,
            protein: protein.isFinite ? min(400,max(0,protein)) : 0, fat: fat.isFinite ? min(250,max(0,fat)) : 0)
    }
}
struct Scope: Codable {
    var watched: [String] = []; var provisional: [String] = []; var blind: [String] = []; var startLine: String?
}
struct RuleState: Codable { let label: String; let state: String }
struct EngineStatus: Decodable {
    var stage = "preparing"; var name = ""; var reps = 0; var cycles = 0; var target = 10
    var done = false; var seconds = 0; var message = "카메라를 준비합니다."
    var sideLine: String?; var cue: String?; var highlight: [Int] = []; var scope = Scope()
    var comparison: [String] = []; var ship: [RuleState] = []; var beta: [RuleState] = []
    var breathing: String?; var normal: [String] = []
}
struct ResultItem: Codable, Identifiable {
    var id: String; let condition: String; let state: String; let verdict: String; let beta: Bool
    let observation: String; let fix: String; let reason: String?
}
struct SetReport: Codable {
    let name: String; let exercise: String; let mode: String; var reps: Int; let cycles: Int
    let summary: String; let voice: String; let shipOk: Int; let shipJudged: Int; let betaJudged: Int
    let score: Int?; let scope: Scope; let repLine: String?; let measurements: [String]
    let baselineValues: [String: Float]; let baselineSets: Int; let items: [ResultItem]; let frames: Int; let duration: Int
    var left: Int?; var right: Int?
    var physicalReps: Int?; var halfPending: Bool?
}
struct SetRecord: Codable, Identifiable {
    var id = UUID(); var date = Date(); var name: String; var setNumber: Int; var repetitions: Int
    var duration: Int; var report: SetReport?; var actualReps: Int?; var formLabel: String?
    var weight: Double; var height: Double
}
struct PersistedData: Codable {
    var schemaVersion = 1; var profile = Profile(); var plan: [PlanItem] = []; var meals: [MealEntry] = []
    var records: [SetRecord] = []; var mode = "coach"; var muted = false; var dark = true
    var baselines: [String: [String: Float]] = [:]
    var baselineSets: [String: [[String: Float]]] = [:]
    var nutritionGoal: NutritionGoal?
}
enum Resources {
    static func text(_ name: String) throws -> String {
        guard let url = Bundle.main.url(forResource: name, withExtension: nil) else { throw DiagnosticError("자산 없음: \(name)") }
        return try String(contentsOf: url, encoding: .utf8)
    }
    static func decode<T: Decodable>(_ name: String, as type: T.Type) throws -> T {
        try JSONDecoder().decode(type, from: Data(text(name).utf8))
    }
    static func json(_ object: Any) throws -> String { String(decoding: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), as: UTF8.self) }
    static func parse<T: Decodable>(_ text: String, as type: T.Type) throws -> T { try JSONDecoder().decode(type, from: Data(text.utf8)) }
    static func nowMs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }
}

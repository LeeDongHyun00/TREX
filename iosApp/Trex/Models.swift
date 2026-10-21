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
/// 엔진 대기열이 고른 발화 하나(spec §107f) — Swift 는 한 번에 하나만 TTS 에 낸다. `flush` 면 재생 중인 말을 끊는다.
struct SpeechSubmit: Codable, Equatable { let text: String; let flush: Bool }
/// 화면에 켜진 교정 단서 하나(Android `MotionCue`) — `kind` 는 `MotionKind` 이름, 관절 번호는 MediaPipe 33.
struct MotionCue: Decodable, Equatable {
    let kind: String; let anchors: [Int]; let targets: [Int]; let gravity: Bool; let chain: Bool; let soft: Bool; let origin: String; let startedAt: Int64
}
/// 플랭크 HUD(spec §99) — 큰 숫자 = 카메라가 확인한 시간, 멈춘 동안 회색. `camera` 가 false 면 시계 폴백.
struct HoldHud: Decodable, Equatable { let heldSec: Int; let wallSec: Int; let stopped: Bool; let status: String?; let camera: Bool; let heldMs: Int64 }
struct CoverageLine: Decodable, Equatable { let message: String; let fix: String }
struct ScoreLine: Decodable, Equatable { let ok: Int; let judged: Int }
struct EngineStatus: Decodable {
    var stage = "preparing"; var name = ""; var reps = 0; var cycles = 0; var target = 10
    var done = false; var seconds = 0; var message = "카메라를 준비합니다."
    var sideLine: String?; var cue: String?; var highlight: [Int] = []; var scope = Scope()
    var comparison: [String] = []; var ship: [RuleState] = []; var beta: [RuleState] = []
    var breathing: String?; var normal: [String] = []
    var provisionalNote: String?; var guideNote: String?; var note: String?; var floorMeasurement: String?
    var coverage: CoverageLine?; var provisional: [Int] = []
    /// 관절 게이트의 목표 좌표(66) — 마지막으로 믿은 자리가 없는 관절은 null(엔진은 NaN 을 null 로 낸다).
    var shown: [Bool] = []; var target2d: [Float?] = []
    var motion: MotionCue?; var hold: HoldHud?; var score: ScoreLine?
    var speech: [SpeechSubmit] = []; var tones: [String] = []; var fast = false
}
struct EngineConfig: Decodable {
    var instruction = "몸 전체가 보이게 자리 잡아 주세요."; var conditions: String?; var scope = Scope(); var floor = false; var hold = false; var startHint: String?
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
    var heldSec: Int?; var holdSource: String?
    /// 마감 때 엔진 대기열이 낸 제출(결과 음성 — 꼬리 뒤). 기록에는 저장하지 않는다.
    var speech: [SpeechSubmit]? = nil
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

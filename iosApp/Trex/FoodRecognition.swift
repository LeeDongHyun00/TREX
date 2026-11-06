import UIKit
import TrexFoodRuntime
import TrexCore
import Combine

/// 사진의 자리 하나(Android `FoodRegion` 의 Swift 거울) — 판정(이름·기억 이름·빈 자리)은 공통 엔진 `IosFood` 가 돌려준 값을 그대로 든다.
struct FoodSpot: Identifiable {
    let id: Int; let photoIndex: Int; let box: CGRect
    let modelName: String?; let rememberedName: String?; let looksEmpty: Bool; let confidence: Float; let rememberedScore: Float?
    let top: [(String, Float)]; let remembered: [(String, Float)]
}
/// 결과 한 줄(Android `RecognizedItem`) — `source` 가 모델 확신·기억·직접 고름을 가른다. 수량은 정수 qty(직접 기록과 같은 단위).
struct RecognizedItem: Identifiable, Equatable {
    enum Source: Equatable { case detected(Float, Int), remembered(Float, Int), picked }
    let id = UUID(); var food: Food; var qty = 1; var source: Source; var spotIds: Set<Int> = []
    var name: String { food.name }
    static func == (a: RecognizedItem, b: RecognizedItem) -> Bool { a.id == b.id && a.qty == b.qty && a.food.name == b.food.name }
}
enum FoodDetection {
    case success(items: [RecognizedItem], candidates: [(String, Float)], spots: [FoodSpot])
    case modelMissing, failed(String), nothing
}

/// 음식 사진은 기기 안에서만 추론한다. 모델 로딩·전처리·추론만 여기서 하고, 이름 문턱·후보·기억 정책은 공통 엔진(`IosFood`·`IosFoodMemory`)이 정한다(§107f 기준 1).
final class FoodRecognizer: ObservableObject {
    @Published private(set) var busy = false
    @Published private(set) var progress: Float = 0
    private let worker = DispatchQueue(label: "trex.food.inference", qos: .userInitiated)
    private var classifier: FoodModel?
    private var regionModel: FoodModel?
    private var regionMissing = false
    private var embedding: FoodModel?
    private var labels: [String] = []
    private var memory: IosFoodMemory?
    private let policy = IosFood()
    private let memoryURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TREX/food-memory.tsv")
    private let legacyMemoryURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TREX/food-memory.json")
    private struct LegacyItem: Codable { var name: String; var vector: [Float]; var date: Date; var correctedFrom: String? }

    /// 시트를 열 때 모델을 미리 올린다(Android `warmUp`).
    func warmUp() { worker.async { [self] in _ = try? ensureModels() } }

    func detect(_ images: [UIImage], foods: [Food], completion: @escaping (FoodDetection) -> Void) {
        guard !busy else { return }
        busy = true; progress = 0
        worker.async { [self] in
            let result: FoodDetection
            do {
                try ensureModels()
                result = try run(images.prefix(5).map { $0.normalized(maxDimension: 1280) }, foods: foods)
            } catch let error as DiagnosticError where error.message.hasPrefix("음식 모델 없음") {
                result = .modelMissing
            } catch { result = .failed(error.localizedDescription) }
            DispatchQueue.main.async { [weak self] in self?.busy = false; self?.progress = 1; completion(result) }
        }
    }

    private func ensureModels() throws {
        if classifier == nil { classifier = try model("yolov8n_food") }
        if regionModel == nil && !regionMissing { regionModel = try? model("food_region"); regionMissing = regionModel == nil }
        if labels.isEmpty { labels = try Resources.text("food_labels.txt").components(separatedBy: .newlines).filter { !$0.isEmpty } }
        if memory == nil { memory = loadMemory() }
    }

    private func loadMemory() -> IosFoodMemory {
        if let text = try? String(contentsOf: memoryURL, encoding: .utf8) { return IosFoodMemory(text: text) }
        // §107 의 JSON 기억(이름·벡터·날짜·고친 이름)을 Android 텍스트 형식으로 한 번 옮긴다
        let m = IosFoodMemory(text: "")
        if let data = try? Data(contentsOf: legacyMemoryURL), let items = try? JSONDecoder().decode([LegacyItem].self, from: data) {
            for item in items { _ = try? m.plus(name: item.name, vectorJson: Resources.json(item.vector), savedAtMs: Int64(item.date.timeIntervalSince1970 * 1000), correctedFrom: item.correctedFrom) }
            saveMemory(m); try? FileManager.default.removeItem(at: legacyMemoryURL)
        }
        return m
    }
    private func saveMemory(_ m: IosFoodMemory) {
        do {
            try FileManager.default.createDirectory(at: memoryURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data(m.encode().utf8).write(to: memoryURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch { }
    }

    /// Android `FoodDetector.detect` 와 같은 순서: 자리(2단계) → 자리별 상위 3 이름 → 기억 비교 → 이름 없는 사진의 전체 1회 폴백 → 공통 정책으로 목록. 자리를 못 찾으면 전체 1회만.
    private func run(_ images: [UIImage], foods: [Food]) throws -> FoodDetection {
        guard let classifier, let memory else { throw DiagnosticError("음식 모델이 준비되지 않았습니다.") }
        var spots: [[String: Any]] = []
        var raw: [(photo: Int, box: CGRect, score: Float, top: [(String, Float)], remembered: [(String, Float)])] = []
        if let regionModel {
            for (photoIndex, image) in images.enumerated() {
                let boxes = (try? regions(regionModel, image)) ?? []
                for (box, score) in boxes {
                    let top = Array(try scores(classifier, image, box: box).prefix(Int(IosFood.companion.TOP_PER_REGION)))
                    let modelName = top.first.flatMap { $0.1 >= IosFood.companion.NAME_THRESHOLD ? $0.0 : nil }
                    let looksEmpty = (top.first?.1 ?? 0) < IosFood.companion.LOOKS_EMPTY_BELOW
                    var remembered: [(String, Float)] = []
                    // 이름 없고 비어 보이지 않는 자리는 모든 기억과, 모델 이름이 붙은 자리는 그 이름을 고친 기억하고만 견준다(공통 FoodMemory.match)
                    if !memory.isEmpty, (modelName == nil && !looksEmpty) || (modelName != nil && memory.hasCorrectionOf(modelName: modelName!)) {
                        if let v = try? embed(image, box: box), !v.isEmpty { remembered = pairs(try memory.match(vectorJson: Resources.json(v), correctedFrom: modelName)) }
                    }
                    raw.append((photoIndex, box, score, top, remembered))
                }
                DispatchQueue.main.async { [weak self] in self?.progress = Float(photoIndex + 1) / Float(images.count + 1) }
            }
            spots = raw.enumerated().map { i, r in ["id": i, "photoIndex": r.photo, "box": ["left": r.box.minX, "top": r.box.minY, "right": r.box.maxX, "bottom": r.box.maxY],
                "regionScore": r.score, "top": r.top.map { [$0.0, $0.1] as [Any] }, "remembered": r.remembered.map { [$0.0, $0.1] as [Any] }] }
        }
        let listed: [String: Any]
        if !spots.isEmpty {
            let spotsJson = try Resources.json(spots)
            let missing = (try JSONSerialization.jsonObject(with: Data(try policy.photosWithoutNames(regionsJson: spotsJson, photoCount: Int32(images.count)).utf8)) as? [Int]) ?? []
            var fallback: [[String: Any]] = []
            for index in missing { for (name, score) in try scores(classifier, images[index], box: nil) { fallback.append(["name": name, "confidence": score, "photoIndex": index]) } }
            listed = try JSONSerialization.jsonObject(with: Data(try policy.resultLists(regionsJson: spotsJson, fallbackJson: Resources.json(fallback)).utf8)) as? [String: Any] ?? [:]
        } else {
            var all: [[String: Any]] = []
            for (index, image) in images.enumerated() { for (name, score) in try scores(classifier, image, box: nil) { all.append(["name": name, "confidence": score, "photoIndex": index]) } }
            listed = try JSONSerialization.jsonObject(with: Data(try policy.wholeLists(scoresJson: Resources.json(all)).utf8)) as? [String: Any] ?? [:]
        }
        func rows(_ key: String) -> [(String, Float, Int)] { ((listed[key] as? [[String: Any]]) ?? []).map { ($0["name"] as? String ?? "", Float($0["confidence"] as? Double ?? 0), $0["photoIndex"] as? Int ?? 0) } }
        let spotRows = (listed["regions"] as? [[String: Any]]) ?? []
        let spotModels: [FoodSpot] = spotRows.enumerated().map { i, s in
            let r = raw[i]
            return FoodSpot(id: s["id"] as? Int ?? i, photoIndex: r.photo, box: r.box, modelName: s["name"] as? String, rememberedName: s["rememberedName"] as? String,
                looksEmpty: s["looksEmpty"] as? Bool ?? false, confidence: Float(s["confidence"] as? Double ?? 0), rememberedScore: (s["rememberedScore"] as? Double).map(Float.init), top: r.top, remembered: r.remembered)
        }
        func lookup(_ name: String) -> Food? { foods.first { $0.name == name } }
        var items: [RecognizedItem] = []
        for (name, confidence, photo) in rows("foods") {
            guard let food = lookup(name) else { continue }   // 영양값이 없는 이름은 기록할 수 없다 — 후보로만
            let ids = Set(spotModels.filter { $0.modelName == name && $0.rememberedName == nil }.map(\.id))
            items.append(RecognizedItem(food: food, source: .detected(confidence, photo), spotIds: ids))
        }
        for spot in spotModels {
            guard let name = spot.rememberedName, let food = lookup(name) else { continue }
            items.append(RecognizedItem(food: food, source: .remembered(spot.rememberedScore ?? 0, spot.photoIndex), spotIds: [spot.id]))
        }
        items = merged(items)
        let candidates = rows("candidates").map { ($0.0, $0.1) }
        if items.isEmpty && candidates.isEmpty && spotModels.allSatisfy(\.looksEmpty) { return .nothing }
        return .success(items: items, candidates: candidates, spots: spotModels)
    }

    /// 같은 이름은 한 줄로 합친다(Android `mergedByName`) — 두 줄이 생기면 한 줄을 지워도 남는다.
    func merged(_ items: [RecognizedItem]) -> [RecognizedItem] {
        var out: [RecognizedItem] = []
        for item in items {
            if let i = out.firstIndex(where: { $0.name == item.name }) { out[i].qty += item.qty; out[i].spotIds.formUnion(item.spotIds) } else { out.append(item) }
        }
        return out
    }

    /// 기록 때 — 사용자가 이름을 붙이거나 바꾼 자리를 기억한다(Android `rememberUserNamed`): 모델 이름과 다르고, 기억 이름 그대로 둔 줄은 빼고, 비어 보이는 자리는 빼고.
    func remember(items: [RecognizedItem], spots: [FoodSpot], images: [UIImage]) {
        worker.async { [self] in
            guard let memory else { return }
            var changed = false
            for spot in spots {
                guard let row = items.first(where: { $0.spotIds.contains(spot.id) }) else { continue }
                if row.name == spot.modelName { continue }
                if row.name == spot.rememberedName, case .picked = row.source {} else if row.name == spot.rememberedName { continue }
                if spot.looksEmpty { continue }
                guard images.indices.contains(spot.photoIndex) else { continue }
                if embedding == nil { embedding = try? model("food_embed") }
                guard let v = try? embed(images[spot.photoIndex].normalized(maxDimension: 1280), box: spot.box), !v.isEmpty,
                      let json = try? Resources.json(v) else { continue }
                if (try? memory.plus(name: row.name, vectorJson: json, savedAtMs: Int64(Date().timeIntervalSince1970 * 1000), correctedFrom: spot.modelName)) == true { changed = true }
            }
            if changed { saveMemory(memory) }
        }
    }

    private func pairs(_ json: String) -> [(String, Float)] {
        ((try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [[Any]] ?? []).compactMap { p in (p.count == 2 ? (p[0] as? String).map { ($0, Float((p[1] as? Double) ?? 0)) } : nil) }
    }
    private func model(_ name: String) throws -> FoodModel {
        guard let url = Bundle.main.url(forResource: name, withExtension: "tflite") else { throw DiagnosticError("음식 모델 없음: \(name)") }
        let interpreter = try FoodModel(modelPath: url.path, threadCount: 4); try interpreter.allocateTensors(); return interpreter
    }
    /// 라벨별 최고 점수(Android `classScores`) — 전체 사진은 letterbox, 자리는 30 % 면적 크롭.
    private func scores(_ classifier: FoodModel, _ image: UIImage, box: CGRect?) throws -> [(String, Float)] {
        let output = try infer(classifier, image: image, box: box)
        let shape = try classifier.output(at: 0).shape.dimensions
        guard shape.count == 3 else { throw DiagnosticError("음식 모델 출력 형태가 다릅니다.") }
        let expected = labels.count + 4
        let first = shape[1] == expected
        guard first || shape[2] == expected else { throw DiagnosticError("음식 라벨과 출력 크기가 다릅니다.") }
        let boxes = first ? shape[2] : shape[1]
        return labels.indices.filter { !labels[$0].hasPrefix("#") }.map { c in
            (labels[c], (0..<boxes).reduce(Float(0)) { max($0, output[first ? (c+4)*boxes+$1 : $1*expected+c+4]) })
        }.sorted { $0.1 > $1.1 }
    }
    /// 자리 모델 → 공통 `FoodRegions.decode/tidy`(NMS·면적·포함 규칙) → 정규화 상자와 자리 점수.
    private func regions(_ regionModel: FoodModel, _ image: UIImage) throws -> [(CGRect, Float)] {
        let output = try infer(regionModel, image: image, box: nil)
        let shape = try regionModel.output(at: 0).shape.dimensions
        guard shape.count == 3 else { throw DiagnosticError("음식 위치 모델 출력 오류") }
        let first = shape[1] < shape[2]; let channels = first ? shape[1] : shape[2]; let anchors = first ? shape[2] : shape[1]
        let channelFirst = first ? output : (0..<channels).flatMap { c in (0..<anchors).map { output[$0*channels+c] } }
        let inputShape = try regionModel.input(at: 0).shape.dimensions
        let size = inputShape[1] == 3 ? inputShape[2] : inputShape[1]
        let payload: [String: Any] = ["output": channelFirst, "channels": channels, "anchors": anchors, "width": Int(image.size.width), "height": Int(image.size.height), "size": size]
        let result = try IosFoodMath().regions(input: Resources.json(payload))
        let rows = try JSONSerialization.jsonObject(with: Data(result.utf8)) as? [[String: Double]] ?? []
        return rows.map { row in (CGRect(x: row["left"]! / Double(image.size.width), y: row["top"]! / Double(image.size.height), width: (row["right"]!-row["left"]!) / Double(image.size.width), height: (row["bottom"]!-row["top"]!) / Double(image.size.height)), Float(row["score"] ?? 0)) }
    }
    private func embed(_ image: UIImage, box: CGRect) throws -> [Float] {
        if embedding == nil { embedding = try? model("food_embed") }
        guard let embedding else { return [] }
        return try infer(embedding, image: image, box: box, embed: true)
    }
    private func infer(_ model: FoodModel, image: UIImage, box: CGRect?, embed: Bool = false) throws -> [Float] {
        let shape = try model.input(at: 0).shape.dimensions
        guard shape.count == 4 else { throw DiagnosticError("음식 모델 입력 형태가 다릅니다.") }
        let first = shape[1] == 3 && shape[3] != 3; let width = first ? shape[3] : shape[2]; let height = first ? shape[2] : shape[1]
        guard try model.input(at: 0).dataType == .float32 else { throw DiagnosticError("음식 모델 입력 자료형이 다릅니다.") }
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let canvas = UIGraphicsImageRenderer(size: CGSize(width: CGFloat(width), height: CGFloat(height)), format: format).image { context in
            UIColor(white: 114.0/255, alpha: 1).setFill(); context.fill(CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height)))
            var source = CGRect(origin: .zero, size: image.size)
            if let box {
                let padding: CGFloat = embed ? 0.1 : 0
                source = CGRect(x: max(0, (box.minX-box.width*padding)*image.size.width), y: max(0, (box.minY-box.height*padding)*image.size.height),
                    width: box.width*(1+2*padding)*image.size.width, height: box.height*(1+2*padding)*image.size.height).intersection(source)
            }
            let scale: CGFloat = embed ? CGFloat(width)/min(source.width, source.height) :
                (box == nil ? min(CGFloat(width)/source.width, CGFloat(height)/source.height) : CGFloat(min(width,height))*sqrt(0.30)/max(source.width,source.height))
            let dw = source.width*scale; let dh = source.height*scale
            context.cgContext.saveGState(); context.cgContext.clip(to: CGRect(x: (CGFloat(width)-dw)/2, y: (CGFloat(height)-dh)/2, width: dw, height: dh))
            image.draw(in: CGRect(x: (CGFloat(width)-dw)/2-source.minX*scale, y: (CGFloat(height)-dh)/2-source.minY*scale, width: image.size.width*scale, height: image.size.height*scale)); context.cgContext.restoreGState()
        }
        guard let cg = canvas.cgImage else { throw DiagnosticError("음식 사진 변환 실패") }
        var bytes = [UInt8](repeating: 0, count: width*height*4)
        let ok = bytes.withUnsafeMutableBytes { pointer -> Bool in
            guard let context = CGContext(data: pointer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width*4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
            context.draw(cg, in: CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height))); return true
        }
        guard ok else { throw DiagnosticError("음식 픽셀 변환 실패") }
        let means: [Float] = [0.485,0.456,0.406]; let stds: [Float] = [0.229,0.224,0.225]
        var floats = [Float](repeating: 0, count: width*height*3)
        for i in 0..<width*height { for c in 0..<3 {
            let value = Float(bytes[i*4+c])/255
            floats[first ? c*width*height+i : i*3+c] = embed ? (value-means[c])/stds[c] : value
        } }
        let data = floats.withUnsafeBufferPointer { Data(buffer: $0) }
        try model.copy(data, toInputAt: 0); try model.invoke()
        let tensor = try model.output(at: 0)
        guard tensor.dataType == .float32, tensor.data.count % MemoryLayout<Float>.size == 0 else { throw DiagnosticError("음식 모델 출력 자료형이 다릅니다.") }
        var result = [Float](repeating: 0, count: tensor.data.count/MemoryLayout<Float>.size)
        _ = result.withUnsafeMutableBytes { tensor.data.copyBytes(to: $0) }; return result
    }
}
extension UIImage {
    func normalized(maxDimension: CGFloat) -> UIImage {
        let ratio = min(1, maxDimension/max(size.width,size.height)); let format = UIGraphicsImageRendererFormat(); format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: floor(size.width*ratio), height: floor(size.height*ratio)), format: format).image { _ in draw(in: CGRect(x: 0, y: 0, width: floor(size.width*ratio), height: floor(size.height*ratio))) }
    }
}

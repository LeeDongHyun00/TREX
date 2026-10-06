import UIKit
import TrexFoodRuntime
import TrexCore
import Combine

struct FoodSuggestion: Identifiable {
    let id = UUID(); var name: String; let score: Float; let region: CGRect?
    var photoIndex = 0
    var remembered = false; var candidates: [String] = []; var vector: [Float] = []; var modelName: String?
}
private struct FoodMemoryItem: Codable { var name: String; var vector: [Float]; var date: Date; var correctedFrom: String? }

/// 음식 사진은 기기 안에서만 추론한다. 확신이 낮은 음식은 선택할 후보로만 남긴다.
final class FoodRecognizer: ObservableObject {
    @Published private(set) var suggestions: [FoodSuggestion] = []
    @Published private(set) var busy = false
    @Published private(set) var error: String?
    private let worker = DispatchQueue(label: "trex.food.inference", qos: .userInitiated)
    private var classifier: FoodModel?
    private var regionModel: FoodModel?
    private var embedding: FoodModel?
    private var labels: [String] = []
    private var memory: [FoodMemoryItem] = []
    private let memoryURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TREX/food-memory.json")
    func detect(_ images: [UIImage]) {
        guard !busy else { return }
        busy = true; error = nil; suggestions = []
        worker.async { [self] in
            do {
                if classifier == nil { classifier = try model("yolov8n_food") }
                if regionModel == nil { regionModel = try? model("food_region") }
                if labels.isEmpty { labels = try Resources.text("food_labels.txt").components(separatedBy: .newlines).filter { !$0.isEmpty } }
                if let data = try? Data(contentsOf: memoryURL) { memory = (try? JSONDecoder().decode([FoodMemoryItem].self, from: data)) ?? [] }
                if !memory.isEmpty && embedding == nil { embedding = try? model("food_embed") }
                var all: [FoodSuggestion] = []
                for (photoIndex, image) in images.prefix(5).enumerated() {
                    let normalized = image.normalized(maxDimension: 1280)
                    let boxes = (try? regions(normalized)) ?? []
                    var found: [FoodSuggestion] = []
                    for box in boxes {
                        let ranking = try scores(normalized, box: box).prefix(3)
                        let top = ranking.first
                        guard (top?.1 ?? 0) >= 0.02 else { continue }
                        let modelName = (top?.1 ?? 0) >= 0.4 ? top?.0 : nil
                        let vector = !memory.isEmpty ? (try? embed(normalized, box: box)) ?? [] : []
                        let matches = match(vector, correctedFrom: modelName)
                        let remembered = matches.first.flatMap { $0.1 >= 0.77 && $0.0 != modelName ? $0.0 : nil }
                        let name = remembered ?? modelName ?? "확인 필요"
                        found.append(FoodSuggestion(name: name, score: top?.1 ?? 0, region: box, photoIndex: photoIndex, remembered: remembered != nil,
                            candidates: Array(Set(matches.filter { $0.1 >= 0.45 }.map { $0.0 } + ranking.map { $0.0 })).sorted(), vector: vector, modelName: modelName))
                    }
                    if found.allSatisfy({ $0.name == "확인 필요" }) {
                        let ranking = try scores(normalized, box: nil).filter { $0.1 >= 0.1 }.prefix(8)
                        for (name, score) in ranking { found.append(FoodSuggestion(name: score >= 0.4 ? name : "확인 필요", score: score, region: nil, photoIndex: photoIndex, candidates: [name], modelName: score >= 0.4 ? name : nil)) }
                    }
                    for suggestion in found {
                        if suggestion.name != "확인 필요", let index = all.firstIndex(where: { $0.name == suggestion.name }) {
                            if suggestion.score > all[index].score { all[index] = suggestion }
                        } else { all.append(suggestion) }
                    }
                }
                DispatchQueue.main.async { [weak self] in self?.suggestions = all; self?.busy = false }
            } catch { DispatchQueue.main.async { [weak self] in self?.error = "사진 분석에 실패했습니다. 직접 음식 이름을 선택해 주세요. \(error.localizedDescription)"; self?.busy = false } }
        }
    }
    func remember(name: String, suggestion: FoodSuggestion, image: UIImage?) {
        worker.async { [self] in
            var vector = suggestion.vector
            if vector.isEmpty, let image, let box = suggestion.region {
                if embedding == nil { embedding = try? model("food_embed") }
                vector = (try? embed(image.normalized(maxDimension: 1280), box: box)) ?? []
            }
            guard vector.count == 384, vector.allSatisfy(\.isFinite) else { return }
            memory.append(FoodMemoryItem(name: name, vector: vector, date: Date(), correctedFrom: suggestion.modelName))
            let same = memory.enumerated().filter { $0.element.name == name }.map(\.offset)
            if same.count > 5 { let remove = Set(same.prefix(same.count - 5)); memory = memory.enumerated().filter { !remove.contains($0.offset) }.map(\.element) }
            memory = Array(memory.suffix(300))
            do {
                try FileManager.default.createDirectory(at: memoryURL.deletingLastPathComponent(), withIntermediateDirectories: true)
                try JSONEncoder().encode(memory).write(to: memoryURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            } catch { DispatchQueue.main.async { [weak self] in self?.error = "음식은 선택했지만 기억 저장에 실패했습니다." } }
        }
    }
    private func model(_ name: String) throws -> FoodModel {
        guard let url = Bundle.main.url(forResource: name, withExtension: "tflite") else { throw DiagnosticError("음식 모델 없음: \(name)") }
        let interpreter = try FoodModel(modelPath: url.path, threadCount: 4); try interpreter.allocateTensors(); return interpreter
    }
    private func scores(_ image: UIImage, box: CGRect?) throws -> [(String, Float)] {
        guard let classifier else { throw DiagnosticError("음식 모델이 준비되지 않았습니다.") }
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
    private func regions(_ image: UIImage) throws -> [CGRect] {
        guard let regionModel else { return [] }
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
        return rows.map { row in CGRect(x: row["left"]! / Double(image.size.width), y: row["top"]! / Double(image.size.height), width: (row["right"]!-row["left"]!) / Double(image.size.width), height: (row["bottom"]!-row["top"]!) / Double(image.size.height)) }
    }
    private func embed(_ image: UIImage, box: CGRect) throws -> [Float] {
        guard let embedding else { return [] }; return try infer(embedding, image: image, box: box, embed: true)
    }
    private func match(_ vector: [Float], correctedFrom: String?) -> [(String, Float)] {
        guard !vector.isEmpty else { return [] }
        var best: [String: Float] = [:]
        for item in memory where item.vector.count == vector.count && (correctedFrom == nil || item.correctedFrom == correctedFrom) {
            let dot = zip(item.vector, vector).reduce(Float(0)) { $0+$1.0*$1.1 }
            let norm = sqrt(item.vector.reduce(Float(0)) { $0+$1*$1 } * vector.reduce(Float(0)) { $0+$1*$1 }) + 1e-9
            best[item.name] = max(best[item.name] ?? -.infinity, dot/norm)
        }
        return best.sorted { $0.value > $1.value }.prefix(3).map { ($0.key, $0.value) }
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

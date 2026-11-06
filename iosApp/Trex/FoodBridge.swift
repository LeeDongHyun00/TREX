import AVFoundation
import ImageIO
import PhotosUI
import TrexCore
import TrexFoodRuntime
import UIKit
import UniformTypeIdentifiers

/// 사진 식단의 iPhone 다리(spec §108d). 시트·판정 흐름·입력 배치(letterbox·30% 자리·특징값 자리)는 공유 Kotlin
/// (`PhotoFoodSheet`·`FoodPipeline`·`PhotoFoodIos.kt`)이고, 이 파일은 사진(보관함·카메라 → 방향 반영, 최대 변 1280),
/// 입력 캔버스 그리기, TFLite(TrexFoodRuntime) 실행만 한다.

// MARK: - 사진

/// 방향(EXIF)을 반영해 최대 변 [maxDimension] 이하로 디코딩한다 — ImageIO 축소 디코딩이라 원본 크기 비트맵을 만들지 않는다
/// (Android `decodeScaledBitmap`·촬영 축소와 같은 자리). 실패하면 nil.
func decodeFoodImage(_ data: Data, maxDimension: Int = 1280) -> FoodImage? {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
          let width = properties[kCGImagePropertyPixelWidth] as? Int,
          let height = properties[kCGImagePropertyPixelHeight] as? Int, width > 0, height > 0 else { return nil }
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceShouldCacheImmediately: true,
        // 작은 사진은 키우지 않는다
        kCGImageSourceThumbnailMaxPixelSize: min(maxDimension, max(width, height)),
    ]
    return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary).map(FoodImage.init)
}

/// 사진 한 장 — 방향은 이미 위쪽, 크기 = 픽셀.
final class FoodImage: NSObject, IosFoodImage {
    let image: CGImage
    init(_ image: CGImage) { self.image = image }

    var width: Int32 { Int32(image.width) }
    var height: Int32 { Int32(image.height) }

    /// 화면 그림용 RGBA 8888(미리 곱한 알파) — Kotlin 이 Skia 그림으로 만든다.
    func rgba() -> Data {
        let w = image.width, h = image.height
        var data = Data(count: w * h * 4)
        data.withUnsafeMutableBytes { raw in
            guard let context = FoodPixels.context(width: w, height: h, data: raw.baseAddress) else { return }
            context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        return data
    }

    /// 비율을 유지해 최대 변을 맞춘다(Android `scaledToMax` 와 같은 크기 식). 이미 작으면 그대로.
    func scaled(maxDimension: Int32) -> IosFoodImage {
        let largest = max(image.width, image.height)
        guard largest > Int(maxDimension) else { return self }
        let scale = Float(maxDimension) / Float(largest)
        let w = max(1, Int(Float(image.width) * scale)), h = max(1, Int(Float(image.height) * scale))
        guard let context = FoodPixels.context(width: w, height: h) else { return self }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        return context.makeImage().map(FoodImage.init) ?? self
    }

    func cropped(left: Int32, top: Int32, width: Int32, height: Int32) -> IosFoodImage {
        image.cropping(to: CGRect(x: Int(left), y: Int(top), width: Int(width), height: Int(height))).map(FoodImage.init) ?? self
    }
}

enum FoodPixels {
    static let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()

    /// sRGB RGBA 8888(미리 곱한 알파) — 메모리 순서 R, G, B, A(Skia RGBA_8888 과 같다). 첫 줄이 그림의 위.
    static func context(width: Int, height: Int, data: UnsafeMutableRawPointer? = nil) -> CGContext? {
        CGContext(data: data, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: colorSpace,
                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue)
    }
}

private struct FoodBridgeError: Error { let message: String; init(_ message: String) { self.message = message } }

// MARK: - 모델

/// TFLite 셋(342종 `yolov8n_food`·위치 `food_region`·특징값 `food_embed`). Kotlin 은 판정 잠금 안의 분석 스레드에서 부르고,
/// TrexFoodRuntime 은 음식 추론 직렬 큐에서만 부른다는 약속대로 여기서 큐로 옮겨 기다린다.
final class FoodModelBridge: NSObject, IosFoodModelBridge {
    private let queue = DispatchQueue(label: "trex.food.inference", qos: .userInitiated)
    private var models: [String: Loaded] = [:]
    private var failures: [String: String] = [:]

    /// 모델 하나와 모양, 크기별로 한 벌만 두고 돌려 쓰는 입력 캔버스·버퍼(Android scratch 와 같은 이유 — 2단계는 자리마다 입력을 만든다).
    private final class Loaded {
        let model: FoodModel
        let input: [Int]
        let output: [Int]
        var canvas: [UInt8] = []
        var floats: [Float] = []
        init(model: FoodModel, input: [Int], output: [Int]) { self.model = model; self.input = input; self.output = output }
    }

    func prepare(name: String) -> String? {
        queue.sync {
            if models[name] != nil { return nil }
            if let failure = failures[name] { return failure }
            do {
                models[name] = try load(name)
                return nil
            } catch {
                let reason = (error as? FoodBridgeError)?.message ?? error.localizedDescription
                failures[name] = reason
                NSLog("[FoodBridge] %@ 를 불러오지 못함: %@", name, reason)
                return reason
            }
        }
    }

    private func load(_ name: String) throws -> Loaded {
        guard let path = Bundle.main.path(forResource: name, ofType: "tflite") else { throw FoodBridgeError(PhotoFoodIosKt.MODEL_MISSING) }
        let model = try FoodModel(modelPath: path, threadCount: 4)
        try model.allocateTensors()
        let input = try model.input(at: 0), output = try model.output(at: 0)
        let inputShape = input.shape.dimensions, outputShape = output.shape.dimensions
        NSLog("[FoodBridge] %@ 입력 %@ 출력 %@", name, inputShape.description, outputShape.description)
        // 전처리가 float32 입력을 전제한다(Android 와 같은 확인)
        guard input.dataType == .float32, inputShape.count == 4 else { throw FoodBridgeError("\(name) 입력이 맞지 않는다: \(inputShape)") }
        if name == "food_region", outputShape.count != 3 { throw FoodBridgeError("위치 모델 출력이 맞지 않는다: \(outputShape)") }
        if name == "food_embed", inputShape != [1, 224, 224, 3] { throw FoodBridgeError("특징값 모델 입력이 맞지 않는다: \(inputShape)") }
        return Loaded(model: model, input: inputShape, output: outputShape)
    }

    func inputShape(name: String) -> KotlinIntArray { queue.sync { Self.intArray(models[name]?.input ?? []) } }

    func outputShape(name: String) -> KotlinIntArray { queue.sync { Self.intArray(models[name]?.output ?? []) } }

    private static func intArray(_ values: [Int]) -> KotlinIntArray {
        let out = KotlinIntArray(size: Int32(values.count))
        for (i, v) in values.enumerated() { out.set(index: Int32(i), value: Int32(v)) }
        return out
    }

    func run(name: String, image: IosFoodImage, srcLeft: Float, srcTop: Float, srcRight: Float, srcBottom: Float,
             dstLeft: Float, dstTop: Float, dstRight: Float, dstBottom: Float, imageNet: Bool, reduceClasses: Int32) -> Data? {
        guard let photo = image as? FoodImage else { return nil }
        let source = CGRect(x: CGFloat(srcLeft), y: CGFloat(srcTop), width: CGFloat(srcRight - srcLeft), height: CGFloat(srcBottom - srcTop))
        return queue.sync {
            guard let loaded = models[name] else { return nil }
            do {
                return try infer(loaded, photo.image, source: source, dst: (dstLeft, dstTop, dstRight, dstBottom), imageNet: imageNet, reduce: Int(reduceClasses))
            } catch {
                NSLog("[FoodBridge] %@ 실행 실패: %@", name, (error as? FoodBridgeError)?.message ?? error.localizedDescription)
                return nil
            }
        }
    }

    private func infer(_ loaded: Loaded, _ image: CGImage, source: CGRect, dst: (Float, Float, Float, Float), imageNet: Bool, reduce: Int) throws -> Data {
        // 입력은 NHWC [1, H, W, 3](Ultralytics 기본) 또는 NCHW [1, 3, H, W](ONNX 경유 변환본) 둘 다 온다(Android `inputGeometry`)
        let shape = loaded.input
        let channelsFirst = shape[1] == 3 && shape[3] != 3
        let width = channelsFirst ? shape[3] : shape[2], height = channelsFirst ? shape[2] : shape[1]
        let count = width * height
        if loaded.canvas.count != count * 4 { loaded.canvas = [UInt8](repeating: 0, count: count * 4) }
        if loaded.floats.count != count * 3 { loaded.floats = [Float](repeating: 0, count: count * 3) }

        // 회색(114) 캔버스에 사진의 source 영역을 dst 자리로(보간) — CG 좌표는 아래가 원점이라 위 기준 dst 를 뒤집어 놓는다
        let full = CGRect(x: 0, y: 0, width: image.width, height: image.height)
        guard let part = source == full ? image : image.cropping(to: source) else { throw FoodBridgeError("사진 자리 자르기 실패") }
        let drawn = loaded.canvas.withUnsafeMutableBytes { raw -> Bool in
            guard let context = FoodPixels.context(width: width, height: height, data: raw.baseAddress) else { return false }
            context.setFillColor(red: 114 / 255, green: 114 / 255, blue: 114 / 255, alpha: 1)
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
            context.interpolationQuality = .high
            let (left, top, right, bottom) = dst
            context.draw(part, in: CGRect(x: CGFloat(left), y: CGFloat(Float(height) - bottom), width: CGFloat(right - left), height: CGFloat(bottom - top)))
            return true
        }
        guard drawn else { throw FoodBridgeError("입력 캔버스를 만들지 못함") }

        // 0~1 float(Android `toInputBuffer`) — NCHW 는 R 평면 → G → B, NHWC 는 픽셀마다 R, G, B.
        // 특징값 모델은 ImageNet 정규화를 쓴다(342종·위치 모델은 0~1 그대로). 채널 우선 입력에는 쓰지 않는다.
        let mean: [Float] = [0.485, 0.456, 0.406], deviation: [Float] = [0.229, 0.224, 0.225]
        loaded.canvas.withUnsafeBufferPointer { px in
            loaded.floats.withUnsafeMutableBufferPointer { out in
                if channelsFirst {
                    for i in 0..<count {
                        out[i] = Float(px[i * 4]) / 255
                        out[count + i] = Float(px[i * 4 + 1]) / 255
                        out[2 * count + i] = Float(px[i * 4 + 2]) / 255
                    }
                } else {
                    for i in 0..<count {
                        for c in 0..<3 {
                            let value = Float(px[i * 4 + c]) / 255
                            out[3 * i + c] = imageNet ? (value - mean[c]) / deviation[c] : value
                        }
                    }
                }
            }
        }
        try loaded.model.copy(loaded.floats.withUnsafeBufferPointer { Data(buffer: $0) }, toInputAt: 0)
        try loaded.model.invoke()
        let tensor = try loaded.model.output(at: 0)
        guard tensor.dataType == .float32 else { throw FoodBridgeError("출력 자료형이 float32 가 아니다") }
        guard reduce > 0 else { return tensor.data }

        // 342종 모델의 박스는 쓰지 않는다 — 클래스별 최고 점수만(Android `classScores`). 출력 [1, 4+C, B] 기본, 반대 배치도 받는다
        let dims = tensor.shape.dimensions
        let expected = reduce + 4
        guard dims.count == 3, dims[1] == expected || dims[2] == expected else {
            throw FoodBridgeError("출력 형태 \(dims) 가 라벨 수 \(reduce) 와 맞지 않는다")
        }
        let channelFirst = dims[1] == expected
        let boxes = channelFirst ? dims[2] : dims[1]
        var best = [Float](repeating: 0, count: reduce)
        tensor.data.withUnsafeBytes { raw in
            let f = raw.bindMemory(to: Float.self)
            for c in 0..<reduce {
                var top: Float = 0
                for b in 0..<boxes {
                    let score = channelFirst ? f[(4 + c) * boxes + b] : f[b * expected + 4 + c]
                    if score > top { top = score }
                }
                best[c] = top
            }
        }
        return best.withUnsafeBufferPointer { Data(buffer: $0) }
    }
}

// MARK: - 보관함·카메라

/// 사진 보관함(PHPicker — 권한 없이 고른 사진만 받는다)과 시트 안 후면 카메라(AVFoundation, Android CameraX 자리).
final class FoodPhotoBridge: NSObject, IosFoodPhotoSource, PHPickerViewControllerDelegate {
    private var pickDone: (([IosFoodImageLoader]) -> Void)?
    private let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "trex.food.camera", qos: .userInitiated)
    /// 진행 중인 촬영의 대리자 — 사진 출력은 대리자를 붙들지 않는다(촬영 큐에서만 만진다)
    private var captures: [Int64: PhotoCapture] = [:]
    /// 미리보기 레이어는 하나를 세션에 한 번만 붙이고, Compose 에는 호출마다 새 컨테이너를 준다(자세 화면 `PoseCameraBridge.makePreviewView` 와 같은 이유 — 레이어 붙이고 떼기는 세션 재구성).
    private lazy var preview: CameraPreviewView = {
        let view = CameraPreviewView()
        view.backgroundColor = .black
        view.previewLayer.videoGravity = .resizeAspectFill   // Android FILL_CENTER
        view.previewLayer.session = session
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        return view
    }()

    func pickPhotos(maxItems: Int32, done: @escaping ([IosFoodImageLoader]) -> Void) {
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = Int(maxItems)
        configuration.selection = .ordered
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = self
        guard let top = Self.topViewController() else { done([]); return }
        pickDone = done
        top.present(picker, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        let done = pickDone
        pickDone = nil
        done?(results.map { PickedFoodPhoto(provider: $0.itemProvider) })
    }

    func makeCameraView() -> UIView {
        let container = UIView()
        container.backgroundColor = .black
        // 시트의 미리보기 칸은 22 dp 둥근 모서리다(PhotoFoodSheet `CameraStep`) — UIKit 층은 Compose clip 과 따로라 여기서도 깎는다
        container.layer.cornerRadius = 22
        container.layer.masksToBounds = true
        preview.frame = container.bounds
        container.addSubview(preview)
        return container
    }

    func releaseCameraView(view: UIView) {
        if preview.superview === view { preview.removeFromSuperview() }
    }

    func startCamera() -> String? {
        var failure: String?
        queue.sync {
            do { try configure() } catch { failure = (error as? FoodBridgeError)?.message ?? error.localizedDescription }
        }
        if let failure { return failure }
        queue.async { [session] in if !session.isRunning { session.startRunning() } }
        if let connection = preview.previewLayer.connection, connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
        return nil
    }

    func stopCamera() {
        queue.async { [session] in if session.isRunning { session.stopRunning() } }
    }

    /// 한 장 — 지연 최소(Android CAPTURE_MODE_MINIMIZE_LATENCY), 세로. 결과는 축소 디코딩해 메인에 넘긴다.
    func capture(done: @escaping (IosFoodImage?) -> Void) {
        queue.async { [self] in
            guard session.isRunning else { DispatchQueue.main.async { done(nil) }; return }
            if let connection = output.connection(with: .video), connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
            let settings = AVCapturePhotoSettings()
            settings.photoQualityPrioritization = .speed
            let id = settings.uniqueID
            let delegate = PhotoCapture { [weak self] image in
                self?.queue.async { self?.captures[id] = nil }
                DispatchQueue.main.async { done(image) }
            }
            captures[id] = delegate
            output.capturePhoto(with: settings, delegate: delegate)
        }
    }

    private func configure() throws {
        if !session.inputs.isEmpty && session.outputs.contains(output) { return }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        if session.canSetSessionPreset(.photo) { session.sessionPreset = .photo }
        session.inputs.forEach { session.removeInput($0) }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            throw FoodBridgeError("이 기기에서 후면 카메라를 찾지 못했어요")
        }
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else { throw FoodBridgeError("카메라 입력을 연결하지 못했어요") }
        session.addInput(input)
        if !session.outputs.contains(output) {
            guard session.canAddOutput(output) else { throw FoodBridgeError("사진 출력을 연결하지 못했어요") }
            session.addOutput(output)
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

/// 보관함에서 고른 사진 한 장 — Kotlin 이 분석 스레드에서 [loadImage] 를 부르면 원본 자료를 받아 축소 디코딩한다(블로킹).
final class PickedFoodPhoto: NSObject, IosFoodImageLoader {
    private let provider: NSItemProvider
    init(provider: NSItemProvider) { self.provider = provider }

    func loadImage() -> IosFoodImage? {
        let type = UTType.image.identifier
        guard provider.hasItemConformingToTypeIdentifier(type) else { return nil }
        let done = DispatchSemaphore(value: 0)
        var bytes: Data?
        provider.loadDataRepresentation(forTypeIdentifier: type) { data, error in
            if let error { NSLog("[FoodBridge] 사진을 읽지 못함: %@", error.localizedDescription) }
            bytes = data
            done.signal()
        }
        guard done.wait(timeout: .now() + 60) == .success, let bytes else { return nil }
        return decodeFoodImage(bytes)
    }
}

private final class PhotoCapture: NSObject, AVCapturePhotoCaptureDelegate {
    private let finish: (IosFoodImage?) -> Void
    private var image: IosFoodImage?
    init(finish: @escaping (IosFoodImage?) -> Void) { self.finish = finish }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error { NSLog("[FoodBridge] 촬영 실패: %@", error.localizedDescription); return }
        // 축소 디코딩(최대 변 1280)과 방향 반영을 한 번에 — 원본 크기 사본을 만들지 않는다(Android 는 축소 뒤 회전)
        image = photo.fileDataRepresentation().flatMap { decodeFoodImage($0) }
    }

    /// 마지막 콜백 — 사진이 없으면(실패) nil 을 넘긴다.
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        finish(image)
    }
}

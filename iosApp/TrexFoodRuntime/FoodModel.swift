import Foundation
@_implementationOnly import TensorFlowLite

public enum FoodTensorDataType { case float32, unsupported }
public struct FoodTensorShape { public let dimensions: [Int] }
public struct FoodTensor {
    public let shape: FoodTensorShape
    public let dataType: FoodTensorDataType
    public let data: Data
}

/// 음식 런타임을 별도 동적 프레임워크에 묶어 MediaPipe 내장 TFLite와 각자의 ABI를 유지한다.
/// 공개 경계에는 TensorFlowLite 타입을 노출하지 않는다. 호출은 음식 추론 직렬 큐에서만 한다.
public final class FoodModel {
    private let interpreter: Interpreter
    public init(modelPath: String, threadCount: Int = 4) throws {
        var options = Interpreter.Options(); options.threadCount = threadCount
        interpreter = try Interpreter(modelPath: modelPath, options: options)
    }
    public func allocateTensors() throws { try interpreter.allocateTensors() }
    public func invoke() throws { try interpreter.invoke() }
    public func copy(_ data: Data, toInputAt index: Int) throws { _ = try interpreter.copy(data, toInputAt: index) }
    public func input(at index: Int) throws -> FoodTensor { snapshot(try interpreter.input(at: index)) }
    public func output(at index: Int) throws -> FoodTensor { snapshot(try interpreter.output(at: index)) }
    private func snapshot(_ tensor: Tensor) -> FoodTensor {
        FoodTensor(shape: FoodTensorShape(dimensions: tensor.shape.dimensions),
                   dataType: tensor.dataType == .float32 ? .float32 : .unsupported, data: tensor.data)
    }
}

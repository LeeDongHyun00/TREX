// 실행: swiftc iosApp/TrexPostureDiagnostics/CaptureGate.swift iosApp/Tests/main.swift -o <출력 파일>
// 카메라 하드웨어 대신 순수 세대/시각 계약을 검사한다. 합성 값이며 실제 프레임이 아니다.
var gate = CaptureGate()
precondition(!gate.accept(epoch: 0, sampleTimeMs: 0))
let first = gate.begin()
precondition(gate.accept(epoch: first, sampleTimeMs: 0), "0ms는 유효해야 합니다")
precondition(!gate.accept(epoch: first, sampleTimeMs: 299))
precondition(gate.accept(epoch: first, sampleTimeMs: 300))
precondition(!gate.accept(epoch: first, sampleTimeMs: 300), "중복 시각 차단")
precondition(!gate.accept(epoch: first, sampleTimeMs: 200), "역행 시각 차단")
gate.end()
precondition(!gate.accept(epoch: first, sampleTimeMs: 900), "정지 뒤 콜백 폐기")
let second = gate.begin()
precondition(!gate.accept(epoch: first, sampleTimeMs: 1_000), "재시작 뒤 이전 세대 폐기")
precondition(!gate.accept(epoch: second, sampleTimeMs: -1))
precondition(gate.accept(epoch: second, sampleTimeMs: 1_000))
precondition(gate.frameId == 3)
for _ in 0..<100 {
    let old = gate.epoch
    gate.end()
    let current = gate.begin()
    precondition(!gate.isCurrent(old))
    precondition(gate.accept(epoch: current, sampleTimeMs: 0))
}
print("CaptureGate: 0ms/300ms/중복·역행/정지·늦은 세대/100회 재시작 검사 통과")

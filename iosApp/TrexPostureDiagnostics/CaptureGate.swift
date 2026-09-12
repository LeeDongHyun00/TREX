/// 촬영 세대와 단조 시각만 관리한다. 운동 세트/판정 상태 머신이 아니다.
struct CaptureGate {
    private(set) var epoch = 0
    private(set) var running = false
    private(set) var frameId = 0
    private var lastSampleTimeMs: Int64?
    let intervalMs: Int64 = 300

    mutating func begin() -> Int {
        epoch += 1
        running = true
        lastSampleTimeMs = nil
        return epoch
    }

    mutating func end() {
        epoch += 1
        running = false
        lastSampleTimeMs = nil
    }

    func isCurrent(_ candidate: Int) -> Bool {
        running && candidate == epoch
    }

    mutating func accept(epoch candidate: Int, sampleTimeMs: Int64) -> Bool {
        guard isCurrent(candidate), sampleTimeMs >= 0 else { return false }
        if let previous = lastSampleTimeMs {
            guard sampleTimeMs >= previous, sampleTimeMs - previous >= intervalMs else { return false }
        }
        lastSampleTimeMs = sampleTimeMs
        frameId += 1
        return true
    }
}

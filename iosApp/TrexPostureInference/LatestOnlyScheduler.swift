/// 호출자는 하나의 잠금으로 보호한다. 실행 1건과 대기 최신 1건만 소유한다.
struct LatestOnlyScheduler<Value> {
    struct Job {
        let id: Int
        let generation: Int
        let value: Value
    }
    private(set) var generation = 0
    private(set) var active = false
    private(set) var inFlight: Job?
    private(set) var pending: Job?
    private(set) var replaced = 0
    private(set) var discarded = 0
    private(set) var peakPending = 0
    private var sequence = 0

    mutating func reset(active: Bool) {
        generation += 1
        self.active = active
        if pending != nil { replaced += 1 }
        pending = nil
        // 실행 중인 버퍼는 완료 때까지 소유한다. 취소했다고 동시에 다음 추론을 시작하지 않는다.
    }

    mutating func submit(_ value: Value) -> Job? {
        guard active else { return nil }
        sequence += 1
        let job = Job(id: sequence, generation: generation, value: value)
        if inFlight == nil {
            inFlight = job
            return job
        }
        if pending != nil { replaced += 1 }
        pending = job
        peakPending = 1
        return nil
    }

    mutating func complete(_ job: Job) -> (accepted: Bool, next: Job?) {
        guard inFlight?.id == job.id else { return (false, nil) }
        let accepted = active && job.generation == generation
        if !accepted { discarded += 1 }
        inFlight = nil
        let next = pending
        pending = nil
        if active, let next, next.generation == generation {
            inFlight = next
            return (accepted, next)
        }
        return (accepted, nil)
    }
}

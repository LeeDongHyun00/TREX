// 합성 프레임 번호/고정 좌표 검사다. 모델 추론이나 실제 관절 정확도를 검증하지 않는다.
var scheduler = LatestOnlyScheduler<Int>()
scheduler.reset(active: true)
let first = scheduler.submit(1)!
precondition(scheduler.submit(2) == nil)
precondition(scheduler.submit(3) == nil)
precondition(scheduler.pending?.value == 3 && scheduler.peakPending == 1)
let completion = scheduler.complete(first)
precondition(completion.accepted && completion.next?.value == 3)
scheduler.reset(active: false)
precondition(scheduler.submit(4) == nil)
scheduler.reset(active: true)
precondition(scheduler.submit(5) == nil, "기존 추론이 끝나기 전 동시 실행 금지")
let old = scheduler.complete(completion.next!)
precondition(!old.accepted && old.next?.value == 5 && scheduler.discarded == 1)
precondition(scheduler.complete(old.next!).accepted)
precondition(scheduler.inFlight == nil && scheduler.pending == nil)
precondition(!scheduler.complete(first).accepted, "중복 완료 무시")
for i in 0..<100 {
    let job = scheduler.submit(i)!
    for value in 0..<100 { precondition(scheduler.submit(value) == nil) }
    scheduler.reset(active: true)
    precondition(!scheduler.complete(job).accepted)
    precondition(scheduler.pending == nil && scheduler.peakPending == 1)
}
func near(_ a: Double, _ b: Double) { precondition(abs(a-b) < 0.00001) }
let p = OverlayProjection.point(x: 0, y: 0, imageWidth: 100, imageHeight: 200,
                                viewWidth: 300, viewHeight: 300, mirrored: false)!
near(p.x, 75); near(p.y, 0)
let m = OverlayProjection.point(x: 0, y: 0, imageWidth: 100, imageHeight: 200,
                                viewWidth: 300, viewHeight: 300, mirrored: true)!
near(m.x, 225); near(m.y, 0)
let landscape = OverlayProjection.point(x: 1, y: 1, imageWidth: 200, imageHeight: 100,
                                        viewWidth: 300, viewHeight: 300, mirrored: false)!
near(landscape.x, 300); near(landscape.y, 225)
precondition(OverlayProjection.point(x: .nan, y: 0, imageWidth: 1, imageHeight: 1,
                                    viewWidth: 1, viewHeight: 1, mirrored: false) == nil)
print("M2 합성 검사: 실행1/대기1·최신 교체·늦은/중복 완료·100회 재설정·비율/미러 좌표 통과")

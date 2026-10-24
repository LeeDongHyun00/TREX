import SwiftUI

struct SessionView: View {
    @ObservedObject var controller: SessionController
    @EnvironmentObject var store: TrexStore
    @Environment(\.dismiss) var dismiss
    @Environment(\.scenePhase) var scenePhase
    @State private var closing = false
    @State private var closeAfterSaving = false
    @State private var actual = 0
    @State private var form = "좋았음"
    @State private var guide: Guide?
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    Text("\(controller.exerciseIndex+1) / \(controller.plan.count) 운동 · \(controller.setNumber) / \(controller.item.sets) 세트").foregroundStyle(.secondary)
                    if controller.phase == "complete" {
                        Image(systemName: "checkmark.circle.fill").font(.system(size: 72)).foregroundStyle(.tint)
                        Text("오늘도 해냈어룡").font(.largeTitle.bold())
                        Text("운동 기록을 저장했습니다.")
                        MuscleMap(snapshot: store.loadSnapshot(records: store.data.records.filter { controller.recordIDs.contains($0.id) }), session: true).frame(height: 360)
                        Button("완료") { dismiss() }.buttonStyle(.borderedProminent)
                    } else if controller.phase == "result" {
                        result
                    } else if controller.phase == "rest" {
                        Text("휴식").font(.title)
                        Text("\(controller.seconds)").font(.system(size: 84, weight: .bold, design: .rounded)).monospacedDigit()
                        Text("잠깐 숨을 고르고 다음 세트를 준비하세요.")
                        Button("휴식 건너뛰기") { controller.advance() }.buttonStyle(.borderedProminent)
                    } else {
                        live
                    }
                }.padding()
            }.navigationTitle(controller.item.name).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) { Button("닫기") { closing = true } }
                    ToolbarItem(placement: .navigationBarTrailing) { Button { controller.setMuted(!controller.muted) } label: { Image(systemName: controller.muted ? "speaker.slash" : "speaker.wave.2") } }
                }
                .confirmationDialog("운동을 종료할까요?", isPresented: $closing, titleVisibility: .visible) {
                    Button("현재 세트 기록 후 종료") {
                        if controller.phase == "active" || controller.phase == "preparing" { closeAfterSaving = true; controller.finishSet() }
                        else { dismiss() }
                    }
                    Button("완료한 세트만 남기고 종료", role: .destructive) { dismiss() }
                    Button("계속하기", role: .cancel) {}
                } message: { Text("완료한 세트는 이미 저장되어 있습니다.") }
                .sheet(item: $guide) { guide in NavigationStack { GuideView(guide: guide).toolbar { Button("닫기") { self.guide = nil } } }.trexSheet() }
        }
        // 세트가 도는 동안(준비·운동·저장)은 끌어 내려도 닫히지 않는다 — 진행 중 세트를 잃지 않게 '닫기' 의 확인 대화로만. 결과·휴식·완료 화면은 끌어 내려 닫을 수 있다(완료한 세트는 이미 저장됐다)
        .interactiveDismissDisabled(controller.phase == "active" || controller.phase == "preparing" || controller.phase == "saving")
        .onAppear { controller.start() }
        .onDisappear { controller.stop() }
        .onChange(of: scenePhase) { controller.sceneActive($0 == .active) }
        .onChange(of: controller.phase) { if closeAfterSaving && $0 == "result" { dismiss() } }
    }
    @ViewBuilder var live: some View {
        if let pipe = controller.pipeline {
            SkeletonView(output: pipe.output, highlight: Set(pipe.status.highlight), provisional: Set(pipe.status.provisional),
                         shown: pipe.status.shown, target: pipe.status.target2d, motion: pipe.status.motion).frame(height: 360).clipShape(RoundedRectangle(cornerRadius: 24))
            Text(controller.capture?.snapshot.status.label ?? "카메라 준비 중").font(.caption).foregroundStyle(.secondary)
            if let error = pipe.output.error {
                Text(error).foregroundStyle(.red)
                Button("타이머로 진행") { controller.useTimer() }.buttonStyle(.bordered)
            }
            if let camera = controller.capture?.snapshot.status, [.denied, .restricted, .unavailable, .error].contains(camera) {
                Button("타이머로 진행") { controller.useTimer() }.buttonStyle(.bordered)
                if camera == .denied {
                    Button("카메라 권한 설정") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                }
            }
            if controller.phase == "preparing" {
                Text(pipe.status.message).font(.headline)
                if pipe.status.seconds > 0 { Text("\(pipe.status.seconds)").font(.system(size: 64, weight: .bold)).monospacedDigit() }
                Text(pipe.instruction).font(.callout)
                // 세는 조건 카드(원칙 #5 의 화면판) — 시험 대사 대신 무엇을 세는지 밝힌다
                if let conditions = pipe.config.conditions { Text(conditions).font(.caption).foregroundStyle(.secondary) }
                Button("5초 뒤 직접 시작") { pipe.manualStart() }.buttonStyle(.bordered)
            } else if let hold = pipe.status.hold {
                // 플랭크 HUD(spec §99) — 큰 숫자 = 카메라가 확인한 시간 / 목표, 멈춘 동안 회색, 아래 상태 줄과 작게 경과
                Text("\(hold.camera ? hold.heldSec : hold.wallSec) / \(controller.item.repetitions)초").font(.system(size: 38, weight: .bold, design: .rounded))
                    .foregroundStyle(hold.stopped && hold.camera ? .secondary : .primary)
                if let status = hold.status { Text(status).font(.callout).foregroundStyle(hold.stopped ? .orange : .secondary) }
                Text("경과 \(hold.wallSec)초 · \(hold.camera ? "카메라 확인 시간" : "시계")").font(.caption).foregroundStyle(.secondary)
                if let note = pipe.status.guideNote { Text(note).font(.headline).foregroundStyle(.tint) }
                if let measurement = pipe.status.floorMeasurement { Text(measurement).font(.caption) }
                if let provisional = pipe.status.provisionalNote { Text("참고 · " + provisional).font(.caption).foregroundStyle(.orange) }
            } else {
                Text(pipe.status.sideLine ?? "\(pipe.status.reps) / \(controller.item.repetitions)회").font(.system(size: 38, weight: .bold, design: .rounded))
                Text("자동 횟수 · 참고").font(.caption).foregroundStyle(.secondary)
                if let note = pipe.status.note { Text(note).font(.caption).foregroundStyle(.orange) }
                if let breathing = pipe.status.breathing { Text(breathing).font(.caption).foregroundStyle(.secondary) }
                Text(pipe.status.message)
                if let cue = pipe.status.cue { Text(cue).font(.headline).foregroundStyle(.tint) }
                if let guide = pipe.status.guideNote, guide != pipe.status.cue { Text(guide).font(.callout).foregroundStyle(.tint) }
                if let coverage = pipe.status.coverage { Text(coverage.message + " · " + coverage.fix).font(.callout).foregroundStyle(.orange) }
                // beta 는 화면 '참고' 로만 — 침묵이 "이상 없음" 으로 읽히면 안 된다(원칙 #2)
                if let provisional = pipe.status.provisionalNote { Text("참고 · " + provisional).font(.caption).foregroundStyle(.orange) }
                if let measurement = pipe.status.floorMeasurement { Text(measurement).font(.caption) }
                if let score = pipe.status.score, store.data.mode == "coach" { Text("확인한 항목 \(score.ok) / \(score.judged)").font(.caption).foregroundStyle(.secondary) }
                if pipe.status.fast { Text("동작이 빨라 일부 회를 놓칠 수 있어요").font(.caption2).foregroundStyle(.secondary) }
                Text("\(controller.seconds)초 경과").monospacedDigit()
            }
            scope(pipe.status.scope)
            DisclosureGroup("자세 평가와 측정") {
                ForEach(Array(pipe.status.ship.enumerated()), id: \.offset) { _, rule in HStack { Text(rule.label); Spacer(); Text(rule.state) } }
                ForEach(Array(pipe.status.beta.enumerated()), id: \.offset) { _, rule in HStack { Text("참고 · \(rule.label)"); Spacer(); Text(rule.state) }.foregroundStyle(.secondary) }
                ForEach(pipe.status.comparison, id: \.self) { Text($0).font(.caption) }
                ForEach(pipe.status.normal, id: \.self) { Text("참고 · " + $0).font(.caption) }
            }
            Button("전면 / 후면 전환") { controller.switchCamera() }.buttonStyle(.bordered)
        } else {
            Image(systemName: "figure.strengthtraining.traditional").font(.system(size: 70)).foregroundStyle(.tint).padding()
            Text(controller.phase == "preparing" ? "준비 \(controller.seconds)초" : "\(controller.seconds)초").font(.system(size: 64, weight: .bold)).monospacedDigit()
            Text("\(controller.item.repetitions)\(controller.item.hold ? "초" : "회") 목표").font(.title2)
            Text("타이머 진행 · 자세 판정 없음").foregroundStyle(.secondary)
        }
        if let error = controller.error {
            Text(error).foregroundStyle(.red)
            if controller.phase == "saveFailed" { Button("기록 다시 저장") { controller.retrySaving() } }
            else { Button("타이머로 진행") { controller.useTimer() } }
        }
        HStack {
            Button(controller.paused ? "이어 하기" : "일시 정지") { controller.togglePause() }.buttonStyle(.bordered)
            Button("세트 마치기") { controller.finishSet() }.buttonStyle(.borderedProminent).disabled(controller.phase == "saving")
        }
        if let found = store.guides.first(where: { $0.name == controller.item.name }) {
            Button("가이드북 보기") { if !controller.paused { controller.togglePause() }; guide = found }
        }
    }
    var result: some View {
        VStack(spacing: 18) {
            Text("세트 완료").font(.largeTitle.bold())
            if let report = controller.report {
                ReportView(report: report)
                if !report.baselineValues.isEmpty {
                    DisclosureGroup("개발용 기준선 수집") {
                        Text("촬영 메타데이터가 없어 실시간 보정에 자동 적용하지 않습니다.").font(.caption)
                        Button(controller.baselineCollected ? "이 세트 수집 완료" : "바르게 수행한 세트로 수집") { controller.collectBaseline() }.buttonStyle(.bordered).disabled(controller.baselineCollected)
                        Text("수집 \(store.data.baselineSets[report.name]?.count ?? 0) / \(report.baselineSets)세트").font(.caption)
                    }
                }
            } else { Text("타이머 수행을 기록했습니다. 자세 판정은 없습니다.") }
            TrexCard {
                Stepper("실제 수행 \(actual)회", value: $actual, in: 0...999)
                Picker("내 자세", selection: $form) { ForEach(["좋았음", "의도적 변형", "무너짐"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented)
                Button("내 평가 저장") { controller.label(actual: actual, form: form) }
            }
            Button("다음") { controller.next() }.buttonStyle(.borderedProminent).controlSize(.large)
        }.onAppear { actual = controller.report?.reps ?? 0 }
    }
    func scope(_ scope: Scope) -> some View {
        TrexCard {
            Text("평가 범위").font(.headline)
            if !scope.watched.isEmpty { Text("확인: " + scope.watched.joined(separator: " · ")) }
            if !scope.provisional.isEmpty { Text("검증 중: " + scope.provisional.joined(separator: " · ")).foregroundStyle(.secondary) }
            if !scope.blind.isEmpty { Text("판정하지 못함: " + scope.blind.joined(separator: " · ")).foregroundStyle(.secondary) }
        }
    }
}
struct ReportView: View {
    let report: SetReport
    var body: some View {
        TrexCard {
            Text(report.summary).font(.title3.bold())
            if report.mode == "coach" && report.shipJudged > 0 { Text("확인한 항목 \(report.shipOk) / \(report.shipJudged)") }
            if report.shipJudged == 0 { Text("검증된 자세 항목의 판정이 없습니다.").foregroundStyle(.secondary) }
            if report.betaJudged > 0 { Text("검증 중인 측정 \(report.betaJudged)건 · 참고").font(.caption) }
            if let line = report.repLine { Text(line) }
            ForEach(report.measurements, id: \.self) { Text($0).font(.caption) }
            if !report.scope.blind.isEmpty { Text("못 보는 항목: " + report.scope.blind.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary) }
            DisclosureGroup("전체 평가 보기") {
                ForEach(report.items) { item in
                    VStack(alignment: .leading, spacing: 5) {
                        Text((item.beta ? "참고 · " : "") + item.condition).font(.headline)
                        Text(item.state)
                        if item.verdict != "ABSTAIN" { Text(item.observation).font(.caption) }
                        if !item.fix.isEmpty { Text(item.fix).font(.caption).foregroundStyle(.tint) }
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 5)
                }
            }
        }
    }
}
/// 영상 위 뼈대. 관절을 그릴지와 좌표는 엔진의 관절 게이트(`JointGate`, §101c — 믿을 수 없는 관절은 그리지 않고 마지막으로 믿은 자리에 둔다)가 정한다.
/// 붉은색 = ship 위반(말하는 판정), 노란색 = beta '참고'(말하지 않는 판정 — 같은 붉은색이면 확신처럼 보인다). 화살표는 COACH 에서 말한 교정 문장 하나의 그림(§101).
struct SkeletonView: View {
    let output: PoseOutput
    var highlight = Set<Int>()
    var provisional = Set<Int>()
    var shown: [Bool] = []
    var target: [Float?] = []
    var motion: MotionCue?
    private let edges = [[11,12],[11,13],[13,15],[12,14],[14,16],[11,23],[12,24],[23,24],[23,25],[25,27],[24,26],[26,28],[27,31],[28,32],[0,7],[0,8]]
    var body: some View {
        ZStack {
            Color.black
            if let image = output.image {
                Image(decorative: image, scale: 1).resizable().aspectRatio(contentMode: .fit).scaleEffect(x: output.mirrored ? -1 : 1, y: 1)
                TimelineView(.animation(minimumInterval: 0.1)) { timeline in
                    Canvas { context, size in
                        func project(_ x: Double, _ y: Double) -> CGPoint? {
                            guard let p = OverlayProjection.point(x: x, y: y, imageWidth: Double(output.width), imageHeight: Double(output.height), viewWidth: size.width, viewHeight: size.height, mirrored: output.mirrored) else { return nil }
                            return CGPoint(x: p.x, y: p.y)
                        }
                        func visible(_ index: Int) -> Bool {
                            if shown.count == 33 { return shown[index] }
                            guard output.normalized.indices.contains(index) else { return false }
                            return min(output.normalized[index].visibility ?? 1, output.normalized[index].presence ?? 1) >= 0.5
                        }
                        func position(_ index: Int) -> CGPoint? {
                            if target.count == 66, let x = target[index*2], let y = target[index*2+1], x.isFinite, y.isFinite { return project(Double(x), Double(y)) }
                            guard output.normalized.indices.contains(index) else { return nil }
                            return project(Double(output.normalized[index].x), Double(output.normalized[index].y))
                        }
                        func color(_ i: Int) -> Color { highlight.contains(i) ? .red : provisional.contains(i) ? .yellow : .green }
                        for edge in edges {
                            guard let a = position(edge[0]), let b = position(edge[1]) else { continue }
                            var path = Path(); path.move(to: a); path.addLine(to: b)
                            let reliable = visible(edge[0]) && visible(edge[1])
                            let c: Color = edge.contains(where: highlight.contains) ? .red : edge.contains(where: provisional.contains) ? .yellow : .green
                            context.stroke(path, with: .color(reliable ? c : c.opacity(0.35)), style: StrokeStyle(lineWidth: 3, dash: reliable ? [] : [6, 6]))
                        }
                        for i in 0..<33 where visible(i) {
                            guard let p = position(i) else { continue }
                            context.fill(Path(ellipseIn: CGRect(x: p.x-4, y: p.y-4, width: 8, height: 8)), with: .color(highlight.contains(i) ? .red : provisional.contains(i) ? .yellow : .white))
                        }
                        if let m = motion { drawMotion(m, in: &context, size: size, now: timeline.date, position: position) }
                    }
                }
            } else { Text(output.state == .error ? "카메라를 확인해 주세요." : "카메라 준비 중").foregroundStyle(.white) }
        }
    }
    /// 화살표 — 말한 교정의 방향. 위아래는 화면 기준(세로 고정 iPhone 은 중력 위 ≈ 화면 위), 안쪽·바깥쪽은 몸 중심선 기준, 목표가 있으면 출발 관절에서 목표 관절로. MARK 는 고리.
    /// 바닥(soft)은 참고색. 처음 1.8 s 맥동(Android `MotionCue.PULSE_MS`).
    private func drawMotion(_ m: MotionCue, in context: inout GraphicsContext, size: CGSize, now: Date, position: (Int) -> CGPoint?) {
        let anchors = m.anchors.compactMap(position)
        guard !anchors.isEmpty else { return }
        let targets = m.targets.compactMap(position)
        let elapsed = max(0, Resources.nowMs() - m.startedAt)
        let pulse = elapsed < 1800 ? 0.75 + 0.25 * sin(Double(elapsed) / 600 * 2 * .pi) : 1
        let color: Color = m.soft ? .yellow : .red
        let len = 36.0 * pulse
        let midX = (output.normalized.indices.contains(23) && output.normalized.indices.contains(24)) ? position(23).map { ($0.x + (position(24)?.x ?? $0.x)) / 2 } : nil
        for a in anchors {
            var dir: CGVector? = nil
            switch m.kind {
            case "UP": dir = CGVector(dx: 0, dy: -1)
            case "DOWN": dir = CGVector(dx: 0, dy: 1)
            case "TOWARD_MIDLINE": if let midX { dir = CGVector(dx: midX > a.x ? 1 : -1, dy: 0) }
            case "AWAY_MIDLINE": if let midX { dir = CGVector(dx: midX > a.x ? -1 : 1, dy: 0) }
            case "TOWARD_TORSO": if let midX { dir = CGVector(dx: midX > a.x ? 1 : -1, dy: 0) }
            case "TOWARD_TARGET":
                if let t = targets.min(by: { hypot($0.x - a.x, $0.y - a.y) < hypot($1.x - a.x, $1.y - a.y) }) {
                    let d = hypot(t.x - a.x, t.y - a.y); if d > 1 { dir = CGVector(dx: (t.x - a.x) / d, dy: (t.y - a.y) / d) }
                }
            default: dir = nil
            }
            if let d = dir {
                let end = CGPoint(x: a.x + d.dx * len, y: a.y + d.dy * len)
                var shaft = Path(); shaft.move(to: a); shaft.addLine(to: end)
                context.stroke(shaft, with: .color(color), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                let back = CGPoint(x: end.x - d.dx * 10, y: end.y - d.dy * 10)
                var head = Path(); head.move(to: end)
                head.addLine(to: CGPoint(x: back.x - d.dy * 7, y: back.y + d.dx * 7)); head.addLine(to: CGPoint(x: back.x + d.dy * 7, y: back.y - d.dx * 7)); head.closeSubpath()
                context.fill(head, with: .color(color))
            } else {
                // MARK·회전(ROTATE_IN/OUT)·어휘 없음: 자리 고리 — 어디를 보라는지만
                let r = 14.0 * pulse
                context.stroke(Path(ellipseIn: CGRect(x: a.x - r, y: a.y - r, width: 2 * r, height: 2 * r)), with: .color(color), lineWidth: 3)
            }
        }
    }
}

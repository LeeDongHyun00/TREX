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
                    ToolbarItem(placement: .navigationBarTrailing) { Button { controller.muted.toggle(); controller.speech.muted = controller.muted; if controller.muted { controller.speech.stop(); controller.pipeline?.holdInstruction(false) } } label: { Image(systemName: controller.muted ? "speaker.slash" : "speaker.wave.2") } }
                }
                .confirmationDialog("운동을 종료할까요?", isPresented: $closing, titleVisibility: .visible) {
                    Button("현재 세트 기록 후 종료") {
                        if controller.phase == "active" || controller.phase == "preparing" { closeAfterSaving = true; controller.finishSet() }
                        else { dismiss() }
                    }
                    Button("완료한 세트만 남기고 종료", role: .destructive) { dismiss() }
                    Button("계속하기", role: .cancel) {}
                } message: { Text("완료한 세트는 이미 저장되어 있습니다.") }
                .sheet(item: $guide) { guide in NavigationStack { GuideView(guide: guide).toolbar { Button("닫기") { self.guide = nil } } } }
        }
        .onAppear { controller.start() }
        .onDisappear { controller.stop() }
        .onChange(of: scenePhase) { controller.sceneActive($0 == .active) }
        .onChange(of: controller.phase) { if closeAfterSaving && $0 == "result" { dismiss() } }
    }
    @ViewBuilder var live: some View {
        if let pipe = controller.pipeline {
            SkeletonView(output: pipe.output, highlight: Set(pipe.status.highlight)).frame(height: 360).clipShape(RoundedRectangle(cornerRadius: 24))
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
                Button("5초 뒤 직접 시작") { pipe.manualStart() }.buttonStyle(.bordered)
            } else {
                Text(pipe.status.sideLine ?? "\(pipe.status.reps) / \(controller.item.repetitions)회").font(.system(size: 38, weight: .bold, design: .rounded))
                Text("자동 횟수 · 참고").font(.caption).foregroundStyle(.secondary)
                if let breathing = pipe.status.breathing { Text(breathing).font(.caption).foregroundStyle(.secondary) }
                Text(pipe.status.message)
                if let cue = pipe.status.cue { Text(cue).font(.headline).foregroundStyle(.tint) }
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
struct SkeletonView: View {
    let output: PoseOutput
    var highlight = Set<Int>()
    private let edges = [[11,12],[11,13],[13,15],[12,14],[14,16],[11,23],[12,24],[23,24],[23,25],[25,27],[24,26],[26,28],[27,31],[28,32],[0,7],[0,8]]
    var body: some View {
        ZStack {
            Color.black
            if let image = output.image {
                Image(decorative: image, scale: 1).resizable().aspectRatio(contentMode: .fit).scaleEffect(x: output.mirrored ? -1 : 1, y: 1)
                Canvas { context, size in
                    func position(_ index: Int) -> CGPoint? {
                        guard output.normalized.indices.contains(index) else { return nil }
                        let point = output.normalized[index]
                        guard min(point.visibility ?? 1, point.presence ?? 1) >= 0.5,
                              let p = OverlayProjection.point(x: Double(point.x), y: Double(point.y), imageWidth: Double(output.width), imageHeight: Double(output.height), viewWidth: size.width, viewHeight: size.height, mirrored: output.mirrored) else { return nil }
                        return CGPoint(x: p.x, y: p.y)
                    }
                    for edge in edges {
                        guard let a = position(edge[0]), let b = position(edge[1]) else { continue }
                        var path = Path(); path.move(to: a); path.addLine(to: b)
                        context.stroke(path, with: .color(edge.contains(where: highlight.contains) ? .red : .green), lineWidth: 3)
                    }
                    for i in output.normalized.indices {
                        guard let p = position(i) else { continue }
                        context.fill(Path(ellipseIn: CGRect(x: p.x-4, y: p.y-4, width: 8, height: 8)), with: .color(highlight.contains(i) ? .red : .white))
                    }
                }
            } else { Text(output.state == .error ? "카메라를 확인해 주세요." : "카메라 준비 중").foregroundStyle(.white) }
        }
    }
}

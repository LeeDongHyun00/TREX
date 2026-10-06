import SwiftUI

struct InferenceView: View {
    @StateObject private var inference: InferenceCoordinator
    @StateObject private var capture: CaptureController
    @Environment(\.scenePhase) private var scenePhase
    @State private var delayed = false

    init() {
        let inference = InferenceCoordinator()
        _inference = StateObject(wrappedValue: inference)
        _capture = StateObject(wrappedValue: CaptureController(onFrame: inference.submit, onActivity: inference.setCaptureActivity))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("평가 엔진 연결 전").font(.headline).accessibilityIdentifier("m2.engine")
                    Text(inference.output.state.label).accessibilityIdentifier("m2.state")
                        .accessibilityValue(inference.output.state.rawValue)
                    Text(capture.snapshot.status.label).font(.caption).accessibilityIdentifier("m2.camera")
                    HStack {
                        Button("시작") { capture.start() }.accessibilityIdentifier("m2.start")
                        Spacer()
                        Button("정지") { capture.stopByUser() }.accessibilityIdentifier("m2.stop")
                        Spacer()
                        Button("전후면") { capture.switchCamera() }.accessibilityIdentifier("m2.switch")
                    }.buttonStyle(.bordered)
                    Text(capture.snapshot.front ? "전면" : "후면").accessibilityIdentifier("m2.position")
                    if let error = inference.output.error { Text(error).font(.caption).foregroundStyle(.red) }
                }

                Section("관절 오버레이 · 실제로 분석한 프레임") {
                    PoseOverlay(output: inference.output).frame(height: 300).clipped()
                    Text(inference.output.source).accessibilityIdentifier("m2.source")
                    HStack {
                        Text("normalized / world")
                        Spacer()
                        Text("\(inference.output.normalized.count) / \(inference.output.world.count)")
                            .accessibilityIdentifier("m2.counts")
                    }
                    Text("분석 이미지는 이미 회전됐습니다. 전면 미러는 이미지·오버레이 표시에만 적용합니다.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("실행 정보") {
                    Text(inference.output.assets).accessibilityIdentifier("m2.assets")
                    Text("\(PoseRunner.sdkVersion) · CPU · VIDEO · Full · 1명 · 신뢰도 0.5")
                        .font(.caption)
                    value("프레임 ID", "\(inference.output.frameId)", id: "m2.frame")
                    value("버퍼 너비 × 높이", "\(inference.output.width) × \(inference.output.height)", id: "m2.size")
                    value("captureEpoch", "\(inference.output.captureEpoch)", id: "m2.epoch")
                    value("승인 시각 · 단조 ms", inference.output.sampleTimeMs.map(String.init) ?? "없음", id: "m2.time")
                    value("완료 시각 · 단조 ms", inference.output.inferenceEndMs.map(String.init) ?? "없음")
                    value("카메라 PTS · 별도 기준 ms", inference.output.sourceCaptureTimeMs.map { String(format: "%.1f", $0) } ?? "없음")
                    Text("phaseEpoch=0 · setToken 미연결 · 진단 세션만 존재")
                        .font(.caption).foregroundStyle(.secondary)
                    Text("world 좌표는 원본 미터입니다. 엔진의 cm/축 변환과 중력 upSource는 미연결입니다.")
                        .font(.caption).foregroundStyle(.secondary)
                    Text("confidence 필드 존재: visibility \(inference.output.normalized.filter { $0.visibility != nil }.count), presence \(inference.output.normalized.filter { $0.presence != nil }.count)")
                        .font(.caption)
                }

                Section("개발 검사 · 합성 입력은 별도 표시") {
                    Toggle("추론 지연 1초 주입", isOn: $delayed).accessibilityIdentifier("m2.delay")
                        .onChange(of: delayed) { inference.setDelay($0) }
                    value("실행 / 대기", "\(inference.output.inFlight) / \(inference.output.pending)", id: "m2.queue")
                    value("대기 최대", "\(inference.output.peakPending)", id: "m2.peak")
                    value("대기 교체/폐기", "\(inference.output.replaced)")
                    value("늦은 결과 폐기", "\(inference.output.discarded)", id: "m2.discarded")
                    Button("합성 검정 프레임 검사") { inference.runProbe(invalidModel: false) }
                        .accessibilityIdentifier("m2.blank")
                        .disabled(capture.snapshot.status == .running || inference.output.inFlight > 0)
                    Button("합성 모델 오류 검사") { inference.runProbe(invalidModel: true) }
                        .accessibilityIdentifier("m2.errorProbe")
                        .disabled(capture.snapshot.status == .running || inference.output.inFlight > 0)
                    Text("검출은 자세 정상 판정이 아닙니다. 각도·횟수·점수·교정은 제공하지 않으며 영상·관절을 파일에 저장하지 않습니다.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("M2 관절 진단")
            .navigationBarTitleDisplayMode(.inline)
            .background(InterfaceOrientationReader(onChange: capture.setOrientation))
            .onAppear { capture.setVisible(true); capture.setActive(scenePhase == .active) }
            .onDisappear { capture.setVisible(false) }
            .onChange(of: scenePhase) { capture.setActive($0 == .active) }
        }
    }

    private func value(_ title: String, _ text: String, id: String = "") -> some View {
        VStack(alignment: .leading) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(text).monospacedDigit().accessibilityIdentifier(id)
        }
    }
}

private struct PoseOverlay: View {
    let output: PoseOutput
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color.black
                if let image = output.image {
                    Image(decorative: image, scale: 1).resizable().aspectRatio(contentMode: .fit)
                        .scaleEffect(x: output.mirrored ? -1 : 1, y: 1)
                    Canvas { context, size in
                        for point in output.normalized {
                            guard let position = OverlayProjection.point(x: Double(point.x), y: Double(point.y),
                                imageWidth: Double(output.width), imageHeight: Double(output.height),
                                viewWidth: size.width, viewHeight: size.height, mirrored: output.mirrored) else { continue }
                            let rect = CGRect(x: position.x - 3, y: position.y - 3, width: 6, height: 6)
                            context.fill(Path(ellipseIn: rect), with: .color(.yellow))
                            context.draw(Text("\(point.id)").font(.system(size: 8)).foregroundColor(.yellow),
                                         at: CGPoint(x: position.x + 6, y: position.y))
                        }
                    }
                } else {
                    Text("분석 이미지 없음").foregroundStyle(.white)
                }
            }.frame(width: geometry.size.width, height: geometry.size.height)
        }
        .accessibilityLabel("분석 프레임의 관절 표시")
    }
}

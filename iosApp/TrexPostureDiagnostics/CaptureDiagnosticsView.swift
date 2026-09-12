import AVFoundation
import SwiftUI

struct CaptureDiagnosticsView: View {
    @StateObject private var controller = CaptureController()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        List {
            Section {
                Text("평가 엔진 연결 전")
                    .font(.headline)
                    .accessibilityIdentifier("capture.engineStatus")
                Text(controller.snapshot.status.label)
                    .accessibilityIdentifier("capture.status")
                    .accessibilityValue(controller.snapshot.status.rawValue)
                HStack {
                    Button("시작") { controller.start() }
                        .accessibilityIdentifier("capture.start")
                    Spacer()
                    Button("정지") { controller.stopByUser() }
                        .accessibilityIdentifier("capture.stop")
                    Spacer()
                    Button("전후면 전환") { controller.switchCamera() }
                        .accessibilityIdentifier("capture.switch")
                }
                .buttonStyle(.bordered)
                if controller.snapshot.status == .denied {
                    Button("카메라 권한 설정 열기") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                }
                if let detail = controller.snapshot.errorDetail {
                    Text(detail).font(.footnote).foregroundStyle(.secondary)
                }
            }

            Section("프리뷰 · 영상은 저장하지 않습니다") {
                CameraPreview(controller: controller, front: controller.snapshot.front)
                    .frame(height: 220)
                    .clipped()
                    .opacity(controller.snapshot.status == .running ? 1 : 0)
                    .overlay {
                        if controller.snapshot.status != .running {
                            Text("카메라 프리뷰 정지")
                                .foregroundStyle(.secondary)
                        }
                    }
                    .overlay(alignment: .bottomLeading) {
                        Text(controller.snapshot.front ? "전면 · 프리뷰만 미러" : "후면 · 미러 없음")
                            .font(.caption).padding(6).background(.ultraThinMaterial)
                    }
                    .accessibilityLabel("카메라 프리뷰")
                Text(controller.snapshot.front ? "전면" : "후면")
                    .accessibilityIdentifier("capture.position")
                Text(controller.snapshot.orientationLabel)
                    .accessibilityIdentifier("capture.orientation")
            }

            Section("프레임 진단 · 300ms 간격") {
                detail("촬영 세대", value: "\(controller.snapshot.epoch)", id: "capture.epoch")
                detail("캡처 포맷 · 회전 전", value: controller.snapshot.sourceSize)
                let frame = controller.snapshot.frame
                detail("출력 버퍼 · 회전 적용 / 미러 없음",
                       value: frame.map { "\($0.width) × \($0.height)" } ?? "프레임 없음", id: "capture.dimensions")
                detail("승인한 프레임 ID", value: frame.map { "\($0.frameId)" } ?? "없음", id: "capture.frameId")
                detail("샘플 승인 시각 · DispatchTime 단조 ms",
                       value: frame.map { "\($0.sampleTimeMs)" } ?? "없음")
                detail("카메라 PTS · 별도 시간 기준 ms",
                       value: frame?.sourceCaptureTimeMs.map { String(format: "%.1f", $0) } ?? "없음")
            }

            Section("중력 진단 · 엔진 축 변환 미검증") {
                Text(controller.snapshot.motionStatus).accessibilityIdentifier("capture.motionStatus")
                if let gravity = controller.snapshot.gravity {
                    detail("출처", value: "Core Motion · raw device gravity (g)")
                    detail("x / y / z", value: String(format: "%.3f / %.3f / %.3f", gravity.x, gravity.y, gravity.z))
                    detail("센서 시각 · 부팅 후 경과 ms", value: String(format: "%.1f", gravity.sensorTimeMs))
                } else {
                    Text("중력값 없음 · 0이나 SCREEN_UP으로 대신 표시하지 않습니다")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Text("upSource / upInEngineWorld: 미연결. 위 값은 디바이스 축의 진단값이며 검증된 엔진 입력이 아닙니다.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("M1 카메라·센서")
        .navigationBarTitleDisplayMode(.inline)
        .background(InterfaceOrientationReader(onChange: controller.setOrientation))
        .onAppear {
            controller.setVisible(true)
            controller.setActive(scenePhase == .active)
        }
        .onDisappear { controller.setVisible(false) }
        .onChange(of: scenePhase) { controller.setActive($0 == .active) }
    }

    private func detail(_ title: String, value: String, id: String = "") -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).monospacedDigit().accessibilityIdentifier(id)
        }
    }
}

/// 프리뷰 셀이 스크롤 밖에 있어도 현재 화면 방향을 추적한다.
private struct InterfaceOrientationReader: UIViewRepresentable {
    let onChange: (AVCaptureVideoOrientation) -> Void
    func makeUIView(context: Context) -> OrientationSurface {
        let view = OrientationSurface()
        view.onChange = onChange
        return view
    }
    func updateUIView(_ uiView: OrientationSurface, context: Context) { uiView.setNeedsLayout() }
}

private final class OrientationSurface: UIView {
    var onChange: ((AVCaptureVideoOrientation) -> Void)?
    private var previous: AVCaptureVideoOrientation?
    override func layoutSubviews() {
        super.layoutSubviews()
        guard let direction = window?.windowScene?.interfaceOrientation,
              let value = captureOrientation(direction), value != previous else { return }
        previous = value
        onChange?(value)
    }
}

private func captureOrientation(_ interface: UIInterfaceOrientation) -> AVCaptureVideoOrientation? {
    switch interface {
    case .portrait: return .portrait
    case .portraitUpsideDown: return .portraitUpsideDown
    case .landscapeLeft: return .landscapeLeft
    case .landscapeRight: return .landscapeRight
    default: return nil
    }
}

private struct CameraPreview: UIViewRepresentable {
    let controller: CaptureController
    let front: Bool

    func makeUIView(context: Context) -> PreviewSurface {
        let view = PreviewSurface()
        view.previewLayer.session = controller.session
        view.previewLayer.videoGravity = .resizeAspect
        view.onOrientation = controller.setOrientation
        return view
    }

    func updateUIView(_ uiView: PreviewSurface, context: Context) {
        uiView.front = front
        uiView.setNeedsLayout()
    }
}

private final class PreviewSurface: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    var front = false
    var onOrientation: ((AVCaptureVideoOrientation) -> Void)?
    private var previousOrientation: AVCaptureVideoOrientation?

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let interface = window?.windowScene?.interfaceOrientation,
              let orientation = captureOrientation(interface) else { return }
        if let connection = previewLayer.connection {
            if connection.isVideoOrientationSupported { connection.videoOrientation = orientation }
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = front
            }
        }
        if previousOrientation != orientation {
            previousOrientation = orientation
            onOrientation?(orientation)
        }
    }
}

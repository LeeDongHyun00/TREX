import SwiftUI
import OSLog

/// M0의 실행 진단과 M1 진입점을 제공한다. 판정 엔진은 연결하지 않는다.
struct DiagnosticsView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var responseChecks = 0

    private let logger = Logger(
        subsystem: "com.leedonghyun.trex.posturediagnostics",
        category: "M0"
    )

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        Label("M0 · 앱 실행 진단", systemImage: "wrench.and.screwdriver")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.secondary)

                        Text("평가 엔진 연결 전")
                            .font(.title2.bold())
                            .accessibilityIdentifier("diagnostics.engineStatus")

                        Text("지금은 앱 화면과 조작 응답을 확인합니다. 자세 판정과 운동 기록은 제공하지 않습니다.")
                            .font(.body)

                        Label("카메라·센서는 별도 진단 화면에서 확인합니다", systemImage: "camera")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 8)
                }

                Section {
                    NavigationLink("카메라·센서 진단") { CaptureDiagnosticsView() }
                        .accessibilityIdentifier("diagnostics.openCapture")
                }

                Section("화면 조작 확인") {
                    Button {
                        responseChecks += 1
                        logger.notice("M0_DIAGNOSTICS ui_response=\(responseChecks)")
                    } label: {
                        Label("화면 응답 확인", systemImage: "hand.tap")
                    }
                    .accessibilityIdentifier("diagnostics.checkUI")

                    Text(responseChecks == 0
                         ? "아직 확인하지 않았습니다"
                         : "응답 확인 \(responseChecks)회")
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("diagnostics.responseStatus")
                }

                Section("실행 환경") {
                    detail("실행 대상", value: runtimeTarget)
                    detail("운영체제", value: "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)")
                    detail("앱 버전", value: appVersion)
                    detail("개발 최소 OS", value: "iOS 16.0")
                }

                Section {
                    Text("화면 실행 성공은 카메라 촬영·모델 추론·자세 평가의 검증 결과가 아닙니다.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("TREX 자세 진단")
            .onAppear {
                logger.notice("M0_DIAGNOSTICS screen=visible engine=not_connected")
            }
            .onChange(of: scenePhase) { phase in
                switch phase {
                case .active:
                    logger.notice("M0_DIAGNOSTICS scene=active")
                case .inactive:
                    logger.notice("M0_DIAGNOSTICS scene=inactive")
                case .background:
                    logger.notice("M0_DIAGNOSTICS scene=background")
                @unknown default:
                    logger.notice("M0_DIAGNOSTICS scene=unknown")
                }
            }
        }
    }

    private func detail(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value)
        }
    }

    private var runtimeTarget: String {
        #if targetEnvironment(simulator)
        return "iOS 시뮬레이터"
        #else
        return "실제 iOS 기기"
        #endif
    }

    private var appVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "미확인"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "미확인"
        return "\(version) (\(build))"
    }
}

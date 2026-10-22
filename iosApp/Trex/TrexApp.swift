import SwiftUI

@main struct TrexApp: App {
    var body: some Scene {
        WindowGroup {
            if ProcessInfo.processInfo.arguments.contains("--compose-probe") {
                ComposeProbeView(capture: true).ignoresSafeArea()
            } else if ProcessInfo.processInfo.arguments.contains("--app-probe") {
                AppProbeView().ignoresSafeArea()
            } else if ProcessInfo.processInfo.arguments.contains("--screen-tour") {
                ScreenTourView().ignoresSafeArea()
            } else {
                // §108 Phase 2: 앱 화면은 Android 정본 Compose(TrexApp)다. 아래 예전 SwiftUI 화면(RootView 등)은
                // 카메라 자세·사진 식단 경로를 옮기는 동안(Phase 3·4)만 남긴다 — 지금은 쓰지 않는다.
                ComposeAppView().ignoresSafeArea()
            }
        }
    }
}
struct RootView: View {
    @EnvironmentObject var store: TrexStore
    var body: some View {
        TabView {
            NavigationStack { HomeView() }.tabItem { Label("홈", systemImage: "house.fill") }
            NavigationStack { WorkoutView() }.tabItem { Label("운동", systemImage: "figure.strengthtraining.traditional") }
            NavigationStack { DietView() }.tabItem { Label("식단", systemImage: "fork.knife") }
            NavigationStack { ProfileView() }.tabItem { Label("내 정보", systemImage: "person.fill") }
            ComposeProbeView().ignoresSafeArea(edges: .top).tabItem { Label("Compose", systemImage: "square.on.square") }
        }
        .sheet(isPresented: Binding(get: { !store.data.profile.onboarded }, set: { _ in })) { OnboardingView().interactiveDismissDisabled() }
        .alert("확인이 필요합니다", isPresented: Binding(get: { store.error != nil }, set: { if !$0 { store.error = nil } })) { Button("확인") { store.error = nil } } message: { Text(store.error ?? "") }
    }
}
struct TrexCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View { VStack(alignment: .leading, spacing: 12) { content }.frame(maxWidth: .infinity, alignment: .leading).padding(20).background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 22)) }
}
struct OnboardingView: View {
    @EnvironmentObject var store: TrexStore
    var body: some View {
        NavigationStack {
            ProfileForm()
                .navigationTitle("TREX 시작하기")
                .safeAreaInset(edge: .bottom) {
                    Button("운동 시작하기") { store.data.profile.onboarded = true; store.save() }
                        .buttonStyle(.borderedProminent).controlSize(.large).padding()
                }
        }
    }
}

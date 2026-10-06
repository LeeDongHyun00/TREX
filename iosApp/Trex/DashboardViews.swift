import SwiftUI
import WebKit
import UniformTypeIdentifiers

struct HomeView: View {
    @EnvironmentObject var store: TrexStore
    @State private var session: SessionController?
    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                TrexCard {
                    Text("\(store.data.profile.name)님, 오늘도 움직여요").font(.title2.bold())
                    Text("\(store.data.profile.goal) · \(store.data.plan.count)개 운동")
                    Button("오늘 운동 시작") { session = SessionController(plan: store.data.plan, store: store) }.buttonStyle(.borderedProminent).disabled(store.data.plan.isEmpty)
                }
                TrexCard {
                    Text("오늘의 기록").font(.headline)
                    HStack { metric("운동", "\(store.todayRecords.count)세트"); Spacer(); metric("운동 시간", "\(store.todayRecords.reduce(0) { $0+$1.duration } / 60)분") }
                    HStack { metric("섭취", "\(Int(store.todayMeals.reduce(0) { $0 + Double($1.food.kcal)*$1.servings })) kcal"); Spacer(); metric("목표", "\(store.nutritionGoal.kcal) kcal") }
                }
                NavigationLink { MuscleLoadView() } label: { TrexCard { HStack { Image(systemName: "figure.stand"); Text("근육 피로도").font(.headline); Spacer(); Image(systemName: "chevron.right") }; Text("운동 기록을 바탕으로 부위별 부하를 확인해요.").font(.caption).foregroundStyle(.secondary) } }
                NavigationLink("운동 기록 보기") { HistoryView() }
                TrexCard { Text("가이드북").font(.headline); ForEach(store.guides) { guide in NavigationLink(guide.name) { GuideView(guide: guide) } } }
            }.padding()
        }.navigationTitle("TREX")
            .fullScreenCover(item: $session) { SessionView(controller: $0) }
    }
    func metric(_ label: String, _ value: String) -> some View { VStack(alignment: .leading, spacing: 5) { Text(label).font(.caption).foregroundStyle(.secondary); Text(value).font(.headline) } }
}
struct HistoryView: View {
    @EnvironmentObject var store: TrexStore
    var body: some View {
        List {
            if store.data.records.isEmpty { Text("아직 운동 기록이 없습니다.") }
            ForEach(store.data.records.reversed()) { record in
                NavigationLink {
                    ScrollView {
                        VStack(spacing: 18) {
                            Text("\(record.name) · \(record.setNumber)세트").font(.title2.bold())
                            Text("\(record.actualReps ?? record.repetitions)회 · \(record.duration)초")
                            if let label = record.formLabel { Text("내 평가: \(label)") }
                            if let report = record.report { ReportView(report: report) } else { Text("자세 판정 없이 기록한 운동입니다.") }
                        }.padding()
                    }.navigationTitle("세트 기록")
                } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(record.name).font(.headline)
                        Text("\(record.actualReps ?? record.repetitions)회 · \(record.duration)초 · \(record.date.formatted(date: .abbreviated, time: .shortened))").font(.caption)
                        Text(record.report?.summary ?? "자세 판정 없음").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }.onDelete { offsets in
                let ids = offsets.map { Array(store.data.records.reversed())[$0].id }
                store.data.records.removeAll { ids.contains($0.id) }; store.save()
            }
        }.navigationTitle("운동 기록")
    }
}
struct MuscleLoadView: View {
    @EnvironmentObject var store: TrexStore
    var body: some View {
        let snapshot = store.loadSnapshot()
        ScrollView {
            VStack(spacing: 16) {
                MuscleMap(snapshot: snapshot).frame(height: 440).clipShape(RoundedRectangle(cornerRadius: 24))
                Text("기록 기반 부하 지수이며 실제 근육의 피로를 측정한 값은 아닙니다.").font(.caption).foregroundStyle(.secondary)
                ForEach(Array((snapshot["muscles"] as? [[String: Any]] ?? []).enumerated()), id: \.offset) { _, muscle in
                    TrexCard { HStack { Text(muscle["name"] as? String ?? ""); Spacer(); Text("왼 \(Int(muscle["left"] as? Double ?? 0)) · 오 \(Int(muscle["right"] as? Double ?? 0))") } }
                }
                TrexCard {
                    Text("기록 부하와 덜 겹치는 운동").font(.headline)
                    ForEach(snapshot["recommendations"] as? [String] ?? [], id: \.self) { name in Button("\(name) 추가") { if let ex = store.exercises.first(where: { $0.name == name }) { store.add(ex) } } }
                }
            }.padding()
        }.navigationTitle("근육 피로도")
    }
}
struct MuscleMap: UIViewRepresentable {
    var snapshot: [String: Any]
    var session = false
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView(); view.navigationDelegate = context.coordinator; view.scrollView.isScrollEnabled = false
        if let file = Bundle.main.url(forResource: "muscle_load/index", withExtension: "html") {
            if session, var parts = URLComponents(url: file, resolvingAgainstBaseURL: false) { parts.query = "mode=session"; if let url = parts.url { view.loadFileURL(url, allowingReadAccessTo: file.deletingLastPathComponent()) } }
            else { view.loadFileURL(file, allowingReadAccessTo: file.deletingLastPathComponent()) }
        }
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) { context.coordinator.parent = self; context.coordinator.update(view) }
    class Coordinator: NSObject, WKNavigationDelegate {
        var parent: MuscleMap; var ready = false
        init(_ parent: MuscleMap) { self.parent = parent }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { ready = true; update(webView) }
        func update(_ view: WKWebView) {
            guard ready else { return }
            let values = parent.snapshot["values"] as? [String: Double] ?? [:]
            if parent.session {
                let keys = values.filter { $0.value > 0 }.map(\.key)
                if let json = try? Resources.json(keys) { view.evaluateJavaScript("window.setUsedMuscles(\(json))") }
            } else if let json = try? Resources.json(values) { view.evaluateJavaScript("window.setFatigue(\(json),'')") }
        }
    }
}
struct ProfileView: View {
    @EnvironmentObject var store: TrexStore
    @State private var backup: URL?
    @State private var importing = false
    var body: some View {
        VStack {
            ProfileForm()
            HStack {
                Button("백업 만들기") { do { backup = try store.exportData() } catch { store.error = error.localizedDescription } }
                if let backup { ShareLink(item: backup) { Label("백업 공유", systemImage: "square.and.arrow.up") } }
                Button("복원") { importing = true }
            }.font(.caption).padding()
        }.navigationTitle("내 정보")
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in if case .success(let url) = result { store.restore(url) } }
            .onDisappear { store.save() }
    }
}
struct ProfileForm: View {
    @EnvironmentObject var store: TrexStore
    var body: some View {
        Form {
            Section("내 프로필") {
                TextField("이름", text: $store.data.profile.name)
                Picker("목표", selection: $store.data.profile.goal) { ForEach(["건강 유지", "근력 향상", "체중 관리"], id: \.self) { Text($0).tag($0) } }
                Picker("성별", selection: $store.data.profile.gender) { Text("선택 안 함").tag("none"); Text("남성").tag("male"); Text("여성").tag("female") }
                Stepper("나이 \(store.data.profile.age)세", value: $store.data.profile.age, in: 13...100)
                HStack { Text("키 (cm)"); TextField("키", value: $store.data.profile.height, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing) }
                HStack { Text("체중 (kg)"); TextField("체중", value: $store.data.profile.weight, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing) }
                Picker("활동량", selection: $store.data.profile.activity) { Text("낮음").tag(1.2); Text("가벼움").tag(1.35); Text("보통").tag(1.55); Text("높음").tag(1.725) }
                Picker("장소", selection: $store.data.profile.place) { Text("집").tag("집"); Text("헬스장").tag("헬스장") }
                ForEach(0..<7) { day in
                    Toggle(["일", "월", "화", "수", "목", "금", "토"][day] + "요일 운동", isOn: Binding(get: { store.data.profile.days.contains(day) }, set: { if $0 { store.data.profile.days.insert(day) } else { store.data.profile.days.remove(day) } }))
                }
            }
            Section("앱 설정") {
                Toggle("음성 끄기", isOn: $store.data.muted)
                Toggle("다크 모드", isOn: $store.data.dark)
                Text("운동·식단·개인 기준선은 이 iPhone에 저장합니다. 카메라 영상은 저장하거나 전송하지 않습니다.").font(.caption)
            }
            Section("사용 가능한 장비") {
                ForEach(["DUMBBELL", "BARBELL", "LAT_MACHINE", "DIP_BAR", "PULLUP_BAR", "BENCH"], id: \.self) { key in
                    Toggle(["DUMBBELL":"덤벨", "BARBELL":"바벨", "LAT_MACHINE":"랫풀 다운 머신", "DIP_BAR":"딥스 바", "PULLUP_BAR":"철봉", "BENCH":"벤치"][key] ?? key,
                        isOn: Binding(get: { store.data.profile.equipment?.contains(key) ?? false }, set: { selected in
                            var equipment = store.data.profile.equipment ?? []; if selected { equipment.insert(key) } else { equipment.remove(key) }; store.data.profile.equipment = equipment
                        }))
                }
            }
            Section("영양 목표") { NavigationLink("열량과 탄단지 목표 설정") { NutritionGoalView() } }
        }
    }
}
struct NutritionGoalView: View {
    @EnvironmentObject var store: TrexStore
    @State private var goal = NutritionGoal(kcal: 2000, carbs: 250, protein: 120, fat: 60)
    @State private var custom = false
    var body: some View {
        Form {
            Toggle("직접 목표 설정", isOn: $custom)
            if custom {
                Stepper("열량 \(goal.kcal) kcal", value: $goal.kcal, in: 800...5000, step: 10)
                Stepper("탄수화물 \(Int(goal.carbs))g", value: $goal.carbs, in: 0...800, step: 5)
                Stepper("단백질 \(Int(goal.protein))g", value: $goal.protein, in: 0...400, step: 5)
                Stepper("지방 \(Int(goal.fat))g", value: $goal.fat, in: 0...250, step: 5)
            } else {
                Text("\(store.data.profile.recommendedNutrition.kcal) kcal")
                Text("입력한 프로필과 활동량으로 권장 목표를 계산합니다.")
            }
        }.navigationTitle("영양 목표")
            .onAppear { goal = store.nutritionGoal; custom = store.data.nutritionGoal != nil }
            .onDisappear { store.data.nutritionGoal = custom ? goal.normalized : nil; store.save() }
    }
}

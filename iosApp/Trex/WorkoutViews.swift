import SwiftUI
import WebKit

struct WorkoutView: View {
    @EnvironmentObject var store: TrexStore
    @State private var adding = false
    @State private var session: SessionController?
    var body: some View {
        List {
            Section {
                Picker("평가 모드", selection: $store.data.mode) { Text("코칭").tag("coach"); Text("기록").tag("track") }.pickerStyle(.segmented)
                Text(store.data.mode == "track" ? "초반의 내 움직임과 이후 변화를 기록합니다." : "평가 범위 안의 검증된 규칙으로 안내합니다.").font(.caption).foregroundStyle(.secondary)
            }
            Section("운동 스케줄") {
                ForEach($store.data.plan) { $item in
                    NavigationLink { PlanEditor(item: $item) } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(item.name).font(.headline)
                            Text("\(item.repetitions)\(item.hold ? "초" : "회") × \(item.sets)세트 · \(item.posture ? "카메라 평가" : "타이머")").font(.caption).foregroundStyle(.secondary)
                        }.padding(.vertical, 6)
                    }
                }
                .onDelete { store.data.plan.remove(atOffsets: $0); store.save() }
                .onMove { store.data.plan.move(fromOffsets: $0, toOffset: $1); store.save() }
                Button { adding = true } label: { Label("운동 추가", systemImage: "plus") }
            }
            Section {
                Button("전체 운동 시작") { session = SessionController(plan: store.data.plan, store: store) }.disabled(store.data.plan.isEmpty)
                NavigationLink("운동 기록") { HistoryView() }
            }
        }
        .navigationTitle("오늘의 운동")
        .toolbar { EditButton() }
        .sheet(isPresented: $adding) { CatalogView() }
        .fullScreenCover(item: $session) { session in SessionView(controller: session) }
        .onChange(of: store.data.mode) { _ in store.save() }
    }
}
struct CatalogView: View {
    @EnvironmentObject var store: TrexStore
    @Environment(\.dismiss) var dismiss
    @State private var query = ""
    var body: some View {
        NavigationStack {
            List {
                ForEach(["하체", "상체", "코어", "복근"], id: \.self) { category in
                    Section(category) {
                        ForEach(store.exercises.filter { $0.category == category && (query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)) }) { ex in
                            Button { store.add(ex); dismiss() } label: { HStack { Text(ex.name); Spacer(); Image(systemName: "plus.circle") } }
                        }
                    }
                }
            }.searchable(text: $query, prompt: "운동 검색").navigationTitle("운동 추가")
                .toolbar { Button("닫기") { dismiss() } }
        }
    }
}
struct PlanEditor: View {
    @EnvironmentObject var store: TrexStore
    @Binding var item: PlanItem
    @State private var session: SessionController?
    var body: some View {
        Form {
            Section("목표") {
                Stepper("\(item.repetitions)\(item.hold ? "초" : "회")", value: $item.repetitions, in: 1...999)
                Stepper("\(item.sets)세트", value: $item.sets, in: 1...20)
                Stepper("세트 사이 휴식 \(item.restSeconds)초", value: $item.restSeconds, in: 0...300, step: 5)
                Stepper("반복 시간 \(item.secondsPerRep)초", value: $item.secondsPerRep, in: 1...20)
                Toggle("카메라 자세 평가", isOn: $item.posture)
            }
            if let guide = store.guides.first(where: { $0.name == item.name }) {
                Section { NavigationLink("가이드북") { GuideView(guide: guide) } }
            }
            Section {
                Button("이 운동 시작") { store.save(); session = SessionController(plan: [item], store: store) }
                if store.data.baselines[item.name] != nil {
                    Text("개발용 기준선 저장됨 · 자동 보정에 적용하지 않음")
                    Button("기준선 다시 수집") { store.data.baselines.removeValue(forKey: item.name); store.data.baselineSets.removeValue(forKey: item.name); store.save() }
                } else { Text("세트 완료 화면에서 개발용 기준선을 수집할 수 있습니다.").font(.caption) }
            }
        }.navigationTitle(item.name).onDisappear { store.save() }
            .fullScreenCover(item: $session) { SessionView(controller: $0) }
    }
}
struct GuideView: View {
    let guide: Guide
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                GuideAnimation(asset: guide.asset).frame(height: 280).clipShape(RoundedRectangle(cornerRadius: 24))
                Text(guide.comment)
                section("시작 자세", guide.setup)
                section("동작 순서", guide.steps)
                TrexCard { Text("호흡").font(.headline); Text(guide.breathing) }
                section("주의사항", guide.cautions)
                Text("교육용 안내입니다. 카메라 촬영 방향과 평가 범위는 세트 시작 안내를 확인해 주세요.").font(.caption).foregroundStyle(.secondary)
                if let url = URL(string: guide.sourceUrl) { Link(guide.sourceName, destination: url).font(.caption) }
            }.padding()
        }.navigationTitle(guide.name)
    }
    func section(_ title: String, _ lines: [String]) -> some View {
        TrexCard { Text(title).font(.headline); ForEach(Array(lines.enumerated()), id: \.offset) { i, line in Text("\(i+1). \(line)") } }
    }
}
struct GuideAnimation: UIViewRepresentable {
    let asset: String
    func makeUIView(context: Context) -> WKWebView {
        let web = WKWebView(); web.isOpaque = false; web.backgroundColor = .clear; web.scrollView.isScrollEnabled = false
        if let url = Bundle.main.url(forResource: asset, withExtension: nil) {
            web.loadHTMLString("<meta name='viewport' content='width=device-width'><style>body{margin:0;background:#141816}img{width:100%;height:100vh;object-fit:contain}</style><img src='\(url.lastPathComponent)'>", baseURL: url.deletingLastPathComponent())
        }
        return web
    }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

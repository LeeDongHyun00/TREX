import SwiftUI
import PhotosUI

struct DietView: View {
    @EnvironmentObject var store: TrexStore
    @State private var adding = false
    @State private var photos = false
    @State private var date = Date()
    var body: some View {
        List {
            Section {
                DatePicker("날짜", selection: $date, displayedComponents: .date)
                let meals = store.data.meals.filter { Calendar.current.isDate($0.date, inSameDayAs: date) }
                Text("\(Int(meals.reduce(0) { $0 + Double($1.food.kcal)*$1.servings })) / \(store.nutritionGoal.kcal) kcal").font(.title2.bold())
                let carbs = meals.reduce(0.0) { total, entry in total + entry.food.carbs * entry.servings }
                let protein = meals.reduce(0.0) { total, entry in total + entry.food.protein * entry.servings }
                let fat = meals.reduce(0.0) { total, entry in total + entry.food.fat * entry.servings }
                Text("탄수화물 \(Int(carbs))/\(Int(store.nutritionGoal.carbs))g · 단백질 \(Int(protein))/\(Int(store.nutritionGoal.protein))g · 지방 \(Int(fat))/\(Int(store.nutritionGoal.fat))g").font(.caption)
                HStack { Button("음식 추가") { adding = true }; Spacer(); Button("사진으로 추가") { photos = true } }
            }
            ForEach(["아침", "점심", "간식", "저녁"], id: \.self) { meal in
                Section(meal) {
                    ForEach(store.data.meals.filter { $0.meal == meal && Calendar.current.isDate($0.date, inSameDayAs: date) }) { entry in
                        HStack { VStack(alignment: .leading) { Text(entry.food.name); Text(String(format: "%.1f인분", entry.servings)).font(.caption) }; Spacer(); Text("\(Int(Double(entry.food.kcal)*entry.servings)) kcal") }
                    }.onDelete { offsets in
                        let entries = store.data.meals.filter { $0.meal == meal && Calendar.current.isDate($0.date, inSameDayAs: date) }
                        let ids = offsets.map { entries[$0].id }; store.data.meals.removeAll { ids.contains($0.id) }; store.save()
                    }
                }
            }
        }.navigationTitle("식단 기록")
            .sheet(isPresented: $adding) { FoodEntryView(date: date).trexSheet() }
            .sheet(isPresented: $photos) { PhotoFoodView(date: date).trexSheet() }
    }
}
struct FoodEntryView: View {
    @EnvironmentObject var store: TrexStore
    @Environment(\.dismiss) var dismiss
    let date: Date
    var preferred: [String] = []
    var onSelect: ((Food) -> Void)?
    @State private var search = ""
    @State private var meal = "점심"
    @State private var servings = 1.0
    @State private var custom = false
    @State private var customKcal = 0
    @State private var carbs = 0.0; @State private var protein = 0.0; @State private var fat = 0.0
    var body: some View {
        NavigationStack {
            List {
                if onSelect == nil {
                    Section("분량") {
                        Picker("끼니", selection: $meal) { ForEach(["아침", "점심", "간식", "저녁"], id: \.self) { Text($0).tag($0) } }
                        Stepper(String(format: "%.1f인분", servings), value: $servings, in: 0.1...10, step: 0.1)
                    }
                }
                if !preferred.isEmpty {
                    Section("사진에서 나온 후보") {
                        ForEach(preferred, id: \.self) { name in Button(name) { if let food = store.foods.first(where: { $0.name == name }) { select(food) } else { search = name; custom = true } } }
                    }
                }
                Section("음식 선택") {
                    ForEach(store.foods.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }) { food in
                        Button { select(food) } label: { HStack { Text(food.name); Spacer(); Text("\(food.kcal) kcal").foregroundStyle(.secondary) } }
                    }
                }
                Section {
                    Toggle("직접 영양 정보 입력", isOn: $custom)
                    if custom {
                        TextField("음식 이름", text: $search)
                        HStack { Text("열량 kcal"); TextField("열량", value: $customKcal, format: .number).keyboardType(.numberPad) }
                        HStack { Text("탄수화물 g"); TextField("탄수화물", value: $carbs, format: .number).keyboardType(.decimalPad) }
                        HStack { Text("단백질 g"); TextField("단백질", value: $protein, format: .number).keyboardType(.decimalPad) }
                        HStack { Text("지방 g"); TextField("지방", value: $fat, format: .number).keyboardType(.decimalPad) }
                        Button("직접 입력한 음식 선택") { select(Food(name: search, kcal: max(0,customKcal), carbs: max(0,carbs), protein: max(0,protein), fat: max(0,fat))) }.disabled(search.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            }.searchable(text: $search, prompt: "음식 이름").navigationTitle("음식 추가").toolbar { Button("닫기") { dismiss() } }
        }
    }
    private func select(_ food: Food) {
        if let onSelect { onSelect(food) }
        else { store.data.meals.append(MealEntry(date: date, meal: meal, food: food, servings: servings)); store.save() }
        dismiss()
    }
}
/// 사진 식단(Android `PhotoFoodSheet` 의 흐름): 고르기(촬영·갤러리) → 분석(자동, 기기 안) → 결과(끼니·줄마다 수량·"확신 NN%"/기억/직접 고름·바꾸기·빠진 음식 추가) → 한 번에 기록.
/// 실패는 원인별(모델 없음·분석 오류·검출 0건)로 보이고 결과를 지어내지 않는다.
struct PhotoFoodView: View {
    enum Step { case pick, analyzing, result, failed(String, String) }
    @EnvironmentObject var store: TrexStore
    @Environment(\.dismiss) var dismiss
    let date: Date
    @StateObject private var recognizer = FoodRecognizer()
    @State private var step: Step = .pick
    @State private var items: [PhotosPickerItem] = []
    @State private var images: [UIImage] = []
    @State private var camera = false
    @State private var rows: [RecognizedItem] = []
    @State private var candidates: [(String, Float)] = []
    @State private var spots: [FoodSpot] = []
    @State private var picking: PickTarget?
    @State private var meal = PhotoFoodView.currentMeal()
    @State private var saved = false
    struct PickTarget: Identifiable { let id = UUID(); let replacing: RecognizedItem? }
    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case .pick: pick
                case .analyzing: analyzing
                case .result: result
                case .failed(let title, let message): failed(title, message)
                }
            }
            .navigationTitle("사진 식단").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .navigationBarLeading) { Button("닫기") { dismiss() } } }
            .sheet(item: $picking) { target in
                FoodEntryView(date: date, preferred: target.replacing.map { [$0.name] + candidateNames } ?? candidateNames, onSelect: { food in
                    if let replacing = target.replacing, let i = rows.firstIndex(where: { $0.id == replacing.id }) {
                        rows[i] = RecognizedItem(food: food, qty: replacing.qty, source: .picked, spotIds: replacing.spotIds)
                    } else { rows.append(RecognizedItem(food: food, source: .picked)) }
                    rows = recognizer.merged(rows)
                }).trexSheet()
            }
            .sheet(isPresented: $camera) { FoodCamera { image in camera = false; analyze([image]) } }
            .onChange(of: items) { selected in
                Task {
                    var loaded: [UIImage] = []
                    for item in selected { if let bytes = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: bytes) { loaded.append(image) } }
                    if !loaded.isEmpty { analyze(loaded) }
                }
            }
            .onAppear { recognizer.warmUp() }
        }
    }
    private var candidateNames: [String] { candidates.map(\.0) }
    private func analyze(_ loaded: [UIImage]) {
        images = Array(loaded.prefix(5)); step = .analyzing; saved = false
        recognizer.detect(images, foods: store.foods) { detection in
            switch detection {
            case .success(let found, let cands, let foundSpots): rows = found; candidates = cands; spots = foundSpots; step = .result
            case .modelMissing: step = .failed("음식 모델이 없어요", "앱에 음식 인식 모델이 들어 있지 않아 사진을 분석할 수 없어요. 직접 입력으로 기록해 주세요.")
            case .failed(let why): step = .failed("분석에 실패했어요", "사진을 분석하는 중 오류가 났어요. 다른 사진으로 다시 해 보거나 직접 입력해 주세요. \(why)")
            case .nothing: step = .failed("음식을 찾지 못했어요", "사진에서 음식으로 보이는 자리를 찾지 못했어요. 음식이 잘 보이게 다시 찍거나 직접 입력해 주세요.")
            }
        }
    }
    // MARK: 단계
    private var pick: some View {
        List {
            Section {
                if UIImagePickerController.isSourceTypeAvailable(.camera) { Button { camera = true } label: { Label("음식 촬영", systemImage: "camera") } }
                PhotosPicker(selection: $items, maxSelectionCount: 5, matching: .images) { Label("갤러리에서 고르기 · 최대 5장", systemImage: "photo.on.rectangle") }
                Button { picking = PickTarget(replacing: nil) } label: { Label("직접 입력", systemImage: "keyboard") }
            } footer: { Text("사진은 기기 안에서만 분석하고 밖으로 보내거나 저장하지 않아요. 고르면 바로 분석해요.") }
        }
    }
    private var analyzing: some View {
        VStack(spacing: 16) {
            if let first = images.first { Image(uiImage: first).resizable().scaledToFit().frame(maxHeight: 260).clipShape(RoundedRectangle(cornerRadius: 16)) }
            ProgressView(value: Double(recognizer.progress)) { Text(images.count > 1 ? "사진 \(images.count)장 분석 중" : "음식 분석 중") }
            Text("기기 안에서 분석 중이라 네트워크가 필요 없어요.").font(.caption).foregroundStyle(.secondary)
        }.padding()
    }
    private func failed(_ title: String, _ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle").font(.system(size: 44)).foregroundStyle(.orange)
            Text(title).font(.title3.bold()); Text(message).multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button("다시 고르기") { step = .pick; items = []; images = [] }.buttonStyle(.borderedProminent)
            Button("직접 입력") { picking = PickTarget(replacing: nil) }
        }.padding()
    }
    private var result: some View {
        List {
            Section {
                ScrollView(.horizontal) { HStack { ForEach(images.indices, id: \.self) { i in PhotoWithSpots(image: images[i], spots: spots.filter { $0.photoIndex == i }, names: spotNames).frame(height: 180) } } }
                Picker("끼니", selection: $meal) { ForEach(["아침", "점심", "간식", "저녁"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented)
            }
            Section("인식한 음식 · 줄을 누르면 다른 음식으로 바꿀 수 있어요") {
                if rows.isEmpty { Text("확신 40 % 이상으로 잡힌 음식이 없어요. 아래에서 후보를 고르거나 직접 추가해 주세요.").font(.caption).foregroundStyle(.secondary) }
                ForEach(rows) { row in
                    HStack {
                        Button { picking = PickTarget(replacing: row) } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(row.name).font(.body.weight(.semibold))
                                Text(label(row)).font(.caption).foregroundStyle(.secondary)
                                if row.food.approximate == true { Text("영양값은 추정값이에요").font(.caption2).foregroundStyle(.orange) }
                            }
                        }.buttonStyle(.plain)
                        Spacer()
                        Stepper("\(row.qty)", value: Binding(get: { row.qty }, set: { q in
                            guard let i = rows.firstIndex(where: { $0.id == row.id }) else { return }
                            if q <= 0 { rows.remove(at: i) } else { rows[i].qty = q }
                        }), in: 0...20).labelsHidden()
                        Text("\(row.qty)").monospacedDigit().frame(minWidth: 24)
                    }
                }
                Button { picking = PickTarget(replacing: nil) } label: {
                    Label(candidates.isEmpty ? "빠진 음식 추가" : "빠진 음식 추가 · 후보 \(candidates.count)개", systemImage: "plus.circle")
                }
            }
            let unnamed = spots.filter { s in s.modelName == nil && s.rememberedName == nil && !s.looksEmpty && !rows.contains { r in r.spotIds.contains(s.id) } }.count
            if unnamed > 0 { Text("이름이 없는 자리 \(unnamed)곳 · 음식이면 '빠진 음식 추가' 로 알려 주세요. 음식이 아니면 그냥 두면 기록되지 않아요.").font(.caption).foregroundStyle(.secondary) }
            Section {
                Text("확신 %는 보정되지 않은 모델 점수예요. 이름과 수량을 확인한 뒤 기록해 주세요. 1인분 기준 영양값이고 일부 일반 식품은 추정값이에요.").font(.caption).foregroundStyle(.secondary)
                Button(saved ? "기록했어요" : "\(rows.count)개 음식 기록") { save() }.buttonStyle(.borderedProminent).disabled(rows.isEmpty || saved)
                Button("다른 사진으로") { step = .pick; items = []; images = []; rows = []; candidates = []; spots = [] }
            }
        }
    }
    private var spotNames: [Int: String] {
        var out: [Int: String] = [:]
        for row in rows { for id in row.spotIds { out[id] = row.name } }
        return out
    }
    private func label(_ row: RecognizedItem) -> String {
        switch row.source {
        case .detected(let c, let photo): return "확신 \(Int(c * 100))%" + (images.count > 1 ? " · 사진 \(photo + 1)" : "")
        case .remembered(let s, let photo): return "내가 기억시킨 음식 · 유사 \(Int(s * 100))%" + (images.count > 1 ? " · 사진 \(photo + 1)" : "")
        case .picked: return "직접 고름"
        }
    }
    private func save() {
        guard !saved else { return }
        for row in rows { store.data.meals.append(MealEntry(date: date, meal: meal, food: row.food, servings: Double(row.qty))) }
        store.save(); saved = true
        recognizer.remember(items: rows, spots: spots, images: images)
        dismiss()
    }
    static func currentMeal() -> String {
        let hour = Calendar.current.component(.hour, from: Date())
        return hour < 10 ? "아침" : hour < 15 ? "점심" : hour < 18 ? "간식" : "저녁"
    }
}
/// 사진 위 자리 이름표 — 이름 붙은 자리는 이름, 없는 자리는 "?", 빈 그릇으로 보이는 자리는 그리지 않는다.
struct PhotoWithSpots: View {
    let image: UIImage; let spots: [FoodSpot]; let names: [Int: String]
    var body: some View {
        GeometryReader { geo in
            let scale = min(geo.size.width / image.size.width, geo.size.height / image.size.height)
            let w = image.size.width * scale, h = image.size.height * scale
            let ox = (geo.size.width - w) / 2, oy = (geo.size.height - h) / 2
            ZStack(alignment: .topLeading) {
                Image(uiImage: image).resizable().scaledToFit().frame(width: w, height: h).offset(x: ox, y: oy)
                ForEach(spots.filter { !$0.looksEmpty }) { spot in
                    let name = names[spot.id] ?? (spot.rememberedName ?? spot.modelName) ?? "?"
                    Text(name).font(.caption2.bold()).padding(.horizontal, 6).padding(.vertical, 3)
                        .background(name == "?" ? Color.orange : Color.black.opacity(0.7)).foregroundStyle(.white).clipShape(Capsule())
                        .offset(x: ox + spot.box.minX * w, y: oy + spot.box.minY * h)
                }
            }
        }.aspectRatio(image.size.width / image.size.height, contentMode: .fit)
    }
}
struct FoodCamera: UIViewControllerRepresentable {
    var onPhoto: (UIImage) -> Void
    @Environment(\.dismiss) var dismiss
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> UIImagePickerController { let camera = UIImagePickerController(); camera.sourceType = .camera; camera.delegate = context.coordinator; return camera }
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        var parent: FoodCamera; init(_ parent: FoodCamera) { self.parent = parent }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) { if let image = info[.originalImage] as? UIImage { parent.onPhoto(image) } }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}

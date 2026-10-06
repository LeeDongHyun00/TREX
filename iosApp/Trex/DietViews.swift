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
            .sheet(isPresented: $adding) { FoodEntryView(date: date) }
            .sheet(isPresented: $photos) { PhotoFoodView(date: date) }
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
struct PhotoFoodView: View {
    @EnvironmentObject var store: TrexStore
    @Environment(\.dismiss) var dismiss
    let date: Date
    @StateObject private var recognizer = FoodRecognizer()
    @State private var items: [PhotosPickerItem] = []
    @State private var images: [UIImage] = []
    @State private var camera = false
    @State private var selection: FoodSuggestion?
    @State private var saved = Set<UUID>()
    @State private var meal = "점심"
    @State private var servings = 1.0
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("끼니", selection: $meal) { ForEach(["아침", "점심", "간식", "저녁"], id: \.self) { Text($0).tag($0) } }
                    Stepper(String(format: "%.1f인분", servings), value: $servings, in: 0.1...10, step: 0.1)
                    PhotosPicker(selection: $items, maxSelectionCount: 5, matching: .images) { Label("사진 선택 · 최대 5장", systemImage: "photo") }
                    if UIImagePickerController.isSourceTypeAvailable(.camera) { Button("음식 촬영") { camera = true } }
                    if !images.isEmpty { ScrollView(.horizontal) { HStack { ForEach(images.indices, id: \.self) { index in Image(uiImage: images[index]).resizable().scaledToFit().frame(height: 160) } } } }
                    Button("사진 분석") { recognizer.detect(images); saved.removeAll() }.disabled(images.isEmpty || recognizer.busy)
                    Text("사진은 기기 밖으로 전송하지 않습니다. 음식 이름과 분량을 확인한 뒤 기록해 주세요.").font(.caption).foregroundStyle(.secondary)
                    if recognizer.busy { ProgressView("음식 분석 중") }
                    if let error = recognizer.error { Text(error).foregroundStyle(.red) }
                }
                Section("분석 결과 · 눌러서 확인") {
                    ForEach(recognizer.suggestions) { suggestion in
                        Button { selection = suggestion } label: {
                            HStack { VStack(alignment: .leading) { Text(suggestion.name); if suggestion.remembered { Text("기억한 음식").font(.caption) } else if suggestion.name == "확인 필요" { Text(suggestion.candidates.joined(separator: " · ")).font(.caption) } }; Spacer(); Image(systemName: saved.contains(suggestion.id) ? "checkmark.circle.fill" : "chevron.right") }
                        }
                    }
                }
                Button("빠진 음식 직접 추가") { selection = FoodSuggestion(name: "확인 필요", score: 0, region: nil) }
            }.navigationTitle("사진 식단").toolbar { Button("완료") { dismiss() } }
                .sheet(item: $selection) { suggestion in
                    FoodEntryView(date: date, preferred: suggestion.name == "확인 필요" ? suggestion.candidates : [suggestion.name] + suggestion.candidates, onSelect: { food in
                        guard !saved.contains(suggestion.id) else { return }
                        store.data.meals.append(MealEntry(date: date, meal: meal, food: food, servings: servings)); store.save(); saved.insert(suggestion.id)
                        recognizer.remember(name: food.name, suggestion: suggestion, image: images.indices.contains(suggestion.photoIndex) ? images[suggestion.photoIndex] : nil)
                    })
                }
                .sheet(isPresented: $camera) { FoodCamera { image in images = [image]; camera = false } }
                .onChange(of: items) { selected in
                    Task {
                        var loaded: [UIImage] = []
                        for item in selected { if let bytes = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: bytes) { loaded.append(image) } }
                        images = loaded
                    }
                }
        }
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

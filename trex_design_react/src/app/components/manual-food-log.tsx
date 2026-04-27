import { useMemo, useState } from "react";
import { X, Search, Plus, Trash2, Check } from "lucide-react";

const slots = [
  { id: "breakfast", label: "아침" },
  { id: "lunch", label: "점심" },
  { id: "snack", label: "간식" },
  { id: "dinner", label: "저녁" },
];

type Nutrition = { kcal: number; carb: number; protein: number; fat: number };
type FoodEntry = { name: string } & Nutrition;

const foodDb: Record<string, Nutrition> = {
  닭가슴살: { kcal: 165, carb: 0, protein: 31, fat: 3.6 },
  현미밥: { kcal: 220, carb: 46, protein: 5, fat: 1.7 },
  바나나: { kcal: 89, carb: 23, protein: 1.1, fat: 0.3 },
  오트밀: { kcal: 150, carb: 27, protein: 5, fat: 3 },
  그릭요거트: { kcal: 100, carb: 4, protein: 17, fat: 0 },
  고구마: { kcal: 130, carb: 30, protein: 2, fat: 0.1 },
  샐러드: { kcal: 120, carb: 8, protein: 4, fat: 7 },
  사과: { kcal: 95, carb: 25, protein: 0.5, fat: 0.3 },
  아몬드: { kcal: 160, carb: 6, protein: 6, fat: 14 },
};

export function ManualFoodLog({
  onClose,
  mode = "add",
  initialSlot,
  initialFoods = [],
  onDelete,
}: {
  onClose: () => void;
  mode?: "add" | "edit";
  initialSlot?: string;
  initialFoods?: FoodEntry[];
  onDelete?: () => void;
}) {
  const [slot, setSlot] = useState(initialSlot ?? "breakfast");
  const [foods, setFoods] = useState<FoodEntry[]>(initialFoods);
  const [adding, setAdding] = useState(mode === "add" && initialFoods.length === 0);

  const total = foods.reduce(
    (a, f) => ({
      kcal: a.kcal + f.kcal,
      carb: a.carb + f.carb,
      protein: a.protein + f.protein,
      fat: a.fat + f.fat,
    }),
    { kcal: 0, carb: 0, protein: 0, fat: 0 },
  );

  return (
    <div className="absolute inset-0 z-30 bg-black/40 backdrop-blur-sm flex items-end">
      <div className="w-full bg-[#1F2618] text-white rounded-t-[28px] p-5 max-h-[88%] overflow-y-auto">
        <div>
          <p className="text-[11px] text-[#C7E26B]">끼니 식단 기록</p>
          <h2 className="mt-0.5">
            {slots.find((s) => s.id === slot)?.label} 기록
          </h2>
        </div>

        {mode === "add" ? (
          <div className="mt-4">
            <p className="text-[11px] text-white/70">끼니 선택</p>
            <div className="mt-2 grid grid-cols-4 gap-2">
              {slots.map((s) => {
                const active = slot === s.id;
                return (
                  <button
                    key={s.id}
                    onClick={() => setSlot(s.id)}
                    className={`h-10 rounded-xl text-[12px] ${
                      active
                        ? "bg-[#C7E26B] text-[#1F2618]"
                        : "bg-white/[0.08] text-white/85"
                    }`}
                  >
                    {s.label}
                  </button>
                );
              })}
            </div>
          </div>
        ) : (
          <div className="mt-4 grid grid-cols-4 gap-2">
            {slots.map((s) => (
              <div
                key={s.id}
                className={`h-10 rounded-xl text-[12px] flex items-center justify-center ${
                  slot === s.id
                    ? "bg-[#C7E26B] text-[#1F2618]"
                    : "bg-white/[0.04] text-white/30"
                }`}
              >
                {s.label}
              </div>
            ))}
          </div>
        )}

        {foods.length > 0 && (
          <div className="mt-5">
            <div className="flex items-center justify-between">
              <p className="text-[11px] text-white/70">기록된 음식 {foods.length}개</p>
              <p className="text-[11px] text-[#C7E26B]">{total.kcal} kcal</p>
            </div>
            <div className="mt-2 space-y-2">
              {foods.map((f, i) => (
                <div
                  key={i}
                  className="rounded-2xl bg-white/[0.06] border border-white/10 p-3 flex items-center gap-3"
                >
                  <div className="flex-1 min-w-0">
                    <p className="text-[13px] text-white">{f.name}</p>
                    <p className="text-[11px] text-white/65 mt-0.5">
                      {f.kcal} kcal · 탄수 {f.carb}g · 단백질 {f.protein}g · 지방 {f.fat}g
                    </p>
                  </div>
                  <button
                    onClick={() => setFoods(foods.filter((_, idx) => idx !== i))}
                    className="w-7 h-7 rounded-full bg-[#FF6B6B]/15 text-[#FF8A8A] flex items-center justify-center shrink-0"
                  >
                    <X size={14} />
                  </button>
                </div>
              ))}
            </div>

            <div className="mt-3 grid grid-cols-3 gap-2">
              <Stat label="탄수" v={`${total.carb.toFixed(0)}g`} />
              <Stat label="단백질" v={`${total.protein.toFixed(0)}g`} />
              <Stat label="지방" v={`${total.fat.toFixed(0)}g`} />
            </div>
          </div>
        )}

        {adding ? (
          <FoodAdder
            onAdd={(f) => {
              setFoods([...foods, f]);
              setAdding(false);
            }}
            onCancel={() => setAdding(false)}
            canCancel={foods.length > 0}
          />
        ) : (
          <button
            onClick={() => setAdding(true)}
            className="mt-4 w-full h-12 rounded-2xl bg-white/[0.06] border border-white/15 border-dashed text-white/85 flex items-center justify-center gap-2"
          >
            <Plus size={15} /> 음식 추가
          </button>
        )}

        {mode === "edit" && (
          <button
            onClick={() => {
              onDelete?.();
              onClose();
            }}
            className="mt-3 w-full h-12 rounded-2xl bg-[#FF6B6B]/12 border border-[#FF6B6B]/25 text-[#FF8A8A] flex items-center justify-center gap-2"
          >
            <Trash2 size={15} /> 삭제하기
          </button>
        )}

        <div className="mt-4 flex gap-2.5">
          <button
            onClick={onClose}
            disabled={foods.length === 0}
            className={`flex-1 rounded-2xl flex items-center justify-center gap-2 transition-colors ${
              foods.length === 0
                ? "bg-white/10 text-white/40"
                : "bg-[#C7E26B] text-[#1F2618]"
            }`}
            style={{ height: 52 }}
          >
            {mode === "edit" ? (
              <>
                <Check size={16} /> 수정 완료
              </>
            ) : (
              <>
                <Plus size={16} /> 기록 추가
              </>
            )}
          </button>
          <button
            onClick={onClose}
            className="w-[52px] h-[52px] rounded-2xl bg-[#FF6B6B]/12 border border-[#FF6B6B]/25 text-[#FF8A8A] flex items-center justify-center shrink-0"
          >
            <X size={18} />
          </button>
        </div>
      </div>
    </div>
  );
}

function Stat({ label, v }: { label: string; v: string }) {
  return (
    <div className="rounded-xl bg-white/[0.05] border border-white/10 p-2 text-center">
      <p className="text-[10px] text-white/65">{label}</p>
      <p className="text-[13px] mt-0.5">{v}</p>
    </div>
  );
}

function FoodAdder({
  onAdd,
  onCancel,
  canCancel,
}: {
  onAdd: (f: FoodEntry) => void;
  onCancel: () => void;
  canCancel: boolean;
}) {
  const [name, setName] = useState("");
  const [manual, setManual] = useState(false);
  const [kcal, setKcal] = useState("");
  const [carb, setCarb] = useState("");
  const [protein, setProtein] = useState("");
  const [fat, setFat] = useState("");

  const matches = useMemo(() => {
    if (!name) return [];
    return Object.keys(foodDb).filter((k) => k.includes(name));
  }, [name]);
  const auto = name && foodDb[name] ? foodDb[name] : null;

  const submit = () => {
    if (auto) {
      onAdd({ name, ...auto });
    } else if (name && kcal) {
      onAdd({
        name,
        kcal: Number(kcal) || 0,
        carb: Number(carb) || 0,
        protein: Number(protein) || 0,
        fat: Number(fat) || 0,
      });
    }
  };

  const ready = !!auto || (manual && name && kcal);

  return (
    <div className="mt-4 rounded-2xl bg-white/[0.04] border border-white/10 p-3">
      <p className="text-[11px] text-white/70">음식 검색</p>
      <div className="mt-2 relative">
        <Search size={14} className="absolute left-4 top-1/2 -translate-y-1/2 text-white/50" />
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="예: 닭가슴살, 바나나"
          className="w-full h-11 rounded-xl bg-white/[0.08] border border-white/15 pl-10 pr-4 text-[14px] text-white placeholder:text-white/45 outline-none focus:border-[#C7E26B]"
        />
      </div>

      {name && !auto && matches.length > 0 && !manual && (
        <div className="mt-2 space-y-1">
          {matches.map((m) => (
            <button
              key={m}
              onClick={() => setName(m)}
              className="w-full text-left px-3 py-2 rounded-xl bg-white/[0.06] text-[13px]"
            >
              {m} · {foodDb[m].kcal} kcal
            </button>
          ))}
        </div>
      )}

      {auto && (
        <div className="mt-3 rounded-xl bg-[#C7E26B] text-[#1F2618] p-3">
          <p className="text-[11px] opacity-70">자동 추천 영양 정보</p>
          <p className="mt-0.5 text-[16px]">{auto.kcal} kcal</p>
          <p className="text-[11px] opacity-70 mt-0.5">
            탄수 {auto.carb}g · 단백질 {auto.protein}g · 지방 {auto.fat}g
          </p>
        </div>
      )}

      {name && !auto && !manual && matches.length === 0 && (
        <button
          onClick={() => setManual(true)}
          className="mt-2 text-[12px] text-white/80 underline underline-offset-4"
        >
          찾는 음식이 없나요? 직접 입력하기
        </button>
      )}

      {manual && (
        <div className="mt-3 grid grid-cols-2 gap-2">
          {[
            { v: kcal, set: setKcal, l: "칼로리(kcal)" },
            { v: carb, set: setCarb, l: "탄수(g)" },
            { v: protein, set: setProtein, l: "단백질(g)" },
            { v: fat, set: setFat, l: "지방(g)" },
          ].map((f) => (
            <label key={f.l} className="block">
              <span className="text-[11px] text-white/70">{f.l}</span>
              <input
                inputMode="decimal"
                value={f.v}
                onChange={(e) => f.set(e.target.value.replace(/[^0-9.]/g, ""))}
                className="mt-1 w-full h-10 rounded-lg bg-white/[0.08] border border-white/15 px-3 text-[14px] outline-none focus:border-[#C7E26B]"
              />
            </label>
          ))}
        </div>
      )}

      <div className="mt-3 flex gap-2">
        <button
          onClick={submit}
          disabled={!ready}
          className={`flex-1 h-11 rounded-xl flex items-center justify-center gap-2 ${
            ready ? "bg-[#C7E26B] text-[#1F2618]" : "bg-white/10 text-white/40"
          }`}
        >
          <Plus size={15} /> 추가
        </button>
        {canCancel && (
          <button
            onClick={onCancel}
            className="px-4 h-11 rounded-xl bg-white/[0.08] border border-white/15 text-white/85 text-[12px]"
          >
            취소
          </button>
        )}
      </div>
    </div>
  );
}

import { useState } from "react";
import { Plus, Apple, Coffee, UtensilsCrossed, Moon, Pencil } from "lucide-react";
import { ManualFoodLog } from "./manual-food-log";

const seedFoods: Record<string, { name: string; kcal: number; carb: number; protein: number; fat: number }[]> = {
  breakfast: [
    { name: "오트밀", kcal: 150, carb: 27, protein: 5, fat: 3 },
    { name: "바나나", kcal: 89, carb: 23, protein: 1.1, fat: 0.3 },
    { name: "아몬드", kcal: 160, carb: 6, protein: 6, fat: 14 },
  ],
  lunch: [
    { name: "닭가슴살", kcal: 165, carb: 0, protein: 31, fat: 3.6 },
    { name: "현미밥", kcal: 220, carb: 46, protein: 5, fat: 1.7 },
    { name: "샐러드", kcal: 120, carb: 8, protein: 4, fat: 7 },
  ],
  snack: [
    { name: "사과", kcal: 95, carb: 25, protein: 0.5, fat: 0.3 },
    { name: "그릭요거트", kcal: 100, carb: 4, protein: 17, fat: 0 },
  ],
  dinner: [],
};

const mealMeta = [
  { id: "breakfast", label: "아침", icon: Coffee },
  { id: "lunch", label: "점심", icon: UtensilsCrossed },
  { id: "snack", label: "간식", icon: Apple },
  { id: "dinner", label: "저녁", icon: Moon },
];

export function DietScreen() {
  const [foodsBySlot, setFoodsBySlot] = useState(seedFoods);
  const [editing, setEditing] = useState<{ slot: string; mode: "add" | "edit" } | null>(null);

  const meals = mealMeta.map((m) => {
    const list = foodsBySlot[m.id] ?? [];
    return { ...m, kcal: list.reduce((s, f) => s + f.kcal, 0), items: list };
  });

  const total = meals.reduce((s, m) => s + m.kcal, 0);
  const goal = 1800;
  const pct = Math.min(100, (total / goal) * 100);

  const close = () => setEditing(null);
  const remove = (slot: string) =>
    setFoodsBySlot((p) => ({ ...p, [slot]: [] }));

  return (
    <div className="w-full h-full bg-[#1F2618] pt-12 overflow-y-auto pb-28 text-white">
      <div className="px-5">
        <p className="text-[12px] text-white/60">오늘의 식단</p>
        <h2 className="text-white">바로 보고, 바로 채우고</h2>
      </div>

      <div className="px-5 mt-4">
        <div className="rounded-[24px] bg-[#C7E26B] text-[#1F2618] p-5">
          <div className="flex items-center justify-between">
            <p className="text-[12px] opacity-70">섭취 / 목표</p>
            <span className="text-[11px] px-2 py-0.5 rounded-full bg-[#1F2618] text-[#C7E26B]">
              {Math.round(pct)}%
            </span>
          </div>
          <p className="mt-2">
            <span className="text-[28px]">{total}</span>
            <span className="opacity-70"> / {goal} kcal</span>
          </p>
          <div className="mt-3 h-2 rounded-full bg-[#1F2618]/20 overflow-hidden">
            <div className="h-full bg-[#1F2618] rounded-full" style={{ width: `${pct}%` }} />
          </div>
        </div>
      </div>

      <div className="px-5 mt-3">
        <button
          onClick={() => setEditing({ slot: "breakfast", mode: "add" })}
          className="w-full rounded-2xl bg-white/[0.06] border border-white/10 p-3.5 flex items-center justify-between hover:bg-white/[0.1] transition-colors"
        >
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-[#C7E26B] text-[#1F2618] flex items-center justify-center">
              <Pencil size={15} />
            </div>
            <div className="text-left">
              <p className="text-[13px] text-white">끼니 식단 기록</p>
              <p className="text-[11px] text-white/65 mt-0.5">음식만 입력하면 영양정보 자동 추천</p>
            </div>
          </div>
          <Plus size={16} className="text-[#C7E26B]" />
        </button>
      </div>

      <div className="px-5 mt-3 space-y-3">
        {meals.map((m) => {
          const Icon = m.icon;
          const empty = m.kcal === 0;
          return (
            <div
              key={m.id}
              className={`rounded-[20px] p-4 ${
                empty ? "bg-white/5 border border-white/10" : "bg-white text-[#1F2618]"
              }`}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div
                    className={`w-10 h-10 rounded-full flex items-center justify-center ${
                      empty ? "bg-white/10" : "bg-[#C7E26B]"
                    }`}
                  >
                    <Icon size={18} className={empty ? "text-white" : "text-[#1F2618]"} />
                  </div>
                  <div>
                    <p className="text-[14px]">{m.label}</p>
                    <p
                      className={`text-[11px] mt-0.5 ${
                        empty ? "text-white/60" : "text-[#5E6754]"
                      }`}
                    >
                      {empty ? "기록 전" : `${m.kcal} kcal`}
                    </p>
                  </div>
                </div>
                <button
                  onClick={() =>
                    setEditing({ slot: m.id, mode: empty ? "add" : "edit" })
                  }
                  className={`w-8 h-8 rounded-full flex items-center justify-center ${
                    empty ? "bg-[#C7E26B] text-[#1F2618]" : "bg-[#1F2618] text-[#C7E26B]"
                  }`}
                  title={empty ? "기록 추가" : "기록 수정"}
                >
                  {empty ? <Plus size={16} /> : <Pencil size={14} />}
                </button>
              </div>
              {m.items.length > 0 && (
                <div className="mt-3 flex flex-wrap gap-1.5">
                  {m.items.map((it) => (
                    <span
                      key={it.name}
                      className="text-[11px] px-2.5 py-1 rounded-full bg-[#F5F7F1] text-[#5F7D39]"
                    >
                      {it.name}
                    </span>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>

      <div className="px-5 mt-5">
        <p className="text-white/80">추천 식단</p>
        <div className="mt-3 rounded-[20px] bg-[#C7E26B] text-[#1F2618] p-4">
          <p className="text-[11px] opacity-70">초보자용</p>
          <p className="text-[14px] mt-0.5">고단백 저녁 한끼</p>
          <p className="text-[11px] opacity-70 mt-1">
            연어 스테이크 · 퀴노아 · 브로콜리 · 약 520 kcal
          </p>
        </div>
      </div>

      {editing && (
        <ManualFoodLog
          onClose={close}
          mode={editing.mode}
          initialSlot={editing.slot}
          initialFoods={foodsBySlot[editing.slot] ?? []}
          onDelete={() => remove(editing.slot)}
        />
      )}
    </div>
  );
}

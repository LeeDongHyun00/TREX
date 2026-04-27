import { useState } from "react";
import { Pencil, Repeat, Eye, CloudRain, Plus } from "lucide-react";
import { todayPlan, Workout } from "../data/workouts";
import { AltSuggestModal } from "./alt-suggest-modal";

const RAIN_MM = 6.4;
const RAIN_THRESHOLD = 3;

export function WorkoutList() {
  const [plan, setPlan] = useState<Workout[]>(todayPlan);
  const [editTarget, setEditTarget] = useState<Workout | null>(null);
  const heavyRain = RAIN_MM >= RAIN_THRESHOLD;

  const togglePosture = (id: string) =>
    setPlan((p) => p.map((w) => (w.id === id ? { ...w, posture: !w.posture } : w)));

  return (
    <div className="w-full h-full bg-[#1F2618] pt-12 overflow-y-auto pb-28 text-white">
      <div className="px-5 flex items-center justify-between">
        <div>
          <p className="text-[12px] text-white/60">오늘의 운동</p>
          <h2>총 {plan.length}개 · 약 35분</h2>
        </div>
        <button className="w-9 h-9 rounded-full bg-white/10 flex items-center justify-center">
          <Plus size={16} />
        </button>
      </div>

      <div className="px-5 mt-4">
        <div
          className={`rounded-2xl p-3.5 flex items-center gap-3 ${
            heavyRain
              ? "bg-[#C7E26B] text-[#1F2618]"
              : "bg-white/5 text-white border border-white/10"
          }`}
        >
          <div
            className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 ${
              heavyRain ? "bg-[#1F2618] text-[#C7E26B]" : "bg-white/10 text-[#C7E26B]"
            }`}
          >
            <CloudRain size={18} />
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-[11px] opacity-70">현재 예상 강수량</p>
            <p className="text-[15px] mt-0.5">{RAIN_MM.toFixed(1)} mm/h</p>
          </div>
          {heavyRain && (
            <span className="text-[11px] px-2 py-1 rounded-full bg-[#1F2618] text-[#C7E26B] shrink-0">
              실내 권장
            </span>
          )}
        </div>
      </div>

      <div className="px-5 mt-4 space-y-2.5">
        {plan.map((w, i) => (
          <div
            key={w.id}
            className="rounded-[20px] bg-white text-[#1F2618] p-3 flex items-stretch gap-3"
          >
            <div className="flex-1 min-w-0 flex flex-col justify-center">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-[10px] px-1.5 py-0.5 rounded-md bg-[#1F2618] text-[#C7E26B]">
                  {String(i + 1).padStart(2, "0")}
                </span>
                <span className="text-[10px] px-1.5 py-0.5 rounded-md bg-[#F5F7F1] text-[#5F7D39]">
                  {w.category}
                </span>
                {w.posture && (
                  <span className="text-[10px] px-1.5 py-0.5 rounded-md bg-[#C7E26B] text-[#1F2618] flex items-center gap-1">
                    <Eye size={10} /> 자세교정
                  </span>
                )}
              </div>
              <p className="text-[14px] mt-1.5">{w.name}</p>
              <p className="text-[11px] text-[#5E6754] mt-0.5">
                {w.reps} · {w.duration}
              </p>
            </div>

            <div className="flex flex-col gap-1.5 shrink-0">
              <button
                title="수정"
                onClick={() => setEditTarget(w)}
                className="w-8 h-8 rounded-xl bg-[#F5F7F1] text-[#5F7D39] flex items-center justify-center"
              >
                <Pencil size={13} />
              </button>
              <button
                title="교체"
                onClick={() => setEditTarget(w)}
                className="w-8 h-8 rounded-xl bg-[#F5F7F1] text-[#5F7D39] flex items-center justify-center"
              >
                <Repeat size={13} />
              </button>
              <button
                title="자세 교정"
                onClick={() => togglePosture(w.id)}
                className={`w-8 h-8 rounded-xl flex items-center justify-center ${
                  w.posture
                    ? "bg-[#C7E26B] text-[#1F2618]"
                    : "bg-[#F5F7F1] text-[#5E6754]"
                }`}
              >
                <Eye size={13} />
              </button>
            </div>
          </div>
        ))}
      </div>

      {editTarget && (
        <AltSuggestModal workout={editTarget} onClose={() => setEditTarget(null)} />
      )}
    </div>
  );
}

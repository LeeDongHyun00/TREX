import { useEffect, useState } from "react";
import { Pause, X, Check, Activity } from "lucide-react";
import { Workout } from "../data/workouts";

export function TimerSession({
  workout,
  index,
  total,
  onNext,
  onExit,
}: {
  workout: Workout;
  index: number;
  total: number;
  onNext: () => void;
  onExit: () => void;
}) {
  const [t, setT] = useState(60);
  useEffect(() => setT(60), [workout.id]);
  useEffect(() => {
    const id = setInterval(() => setT((v) => (v > 0 ? v - 1 : 0)), 1000);
    return () => clearInterval(id);
  }, [workout.id]);

  const mm = String(Math.floor(t / 60)).padStart(2, "0");
  const ss = String(t % 60).padStart(2, "0");
  const pct = ((60 - t) / 60) * 100;

  return (
    <div className="w-full h-full bg-[#1F2618] text-white flex flex-col pt-12 px-5">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-[11px] opacity-70">
            진행중 · {index + 1}/{total}
          </p>
          <p>{workout.name}</p>
        </div>
        <button
          onClick={onExit}
          className="w-9 h-9 rounded-full bg-[#FF6B6B]/15 border border-[#FF6B6B]/30 text-[#FF8A8A] flex items-center justify-center"
        >
          <X size={16} />
        </button>
      </div>

      <div className="flex-1 flex flex-col items-center justify-center">
        <div className="relative w-56 h-56">
          <svg className="w-full h-full -rotate-90">
            <circle cx="112" cy="112" r="100" stroke="rgba(255,255,255,0.1)" strokeWidth="10" fill="none" />
            <circle
              cx="112"
              cy="112"
              r="100"
              stroke="#C7E26B"
              strokeWidth="10"
              fill="none"
              strokeLinecap="round"
              strokeDasharray={2 * Math.PI * 100}
              strokeDashoffset={2 * Math.PI * 100 * (1 - pct / 100)}
              className="transition-all duration-700"
            />
          </svg>
          <div className="absolute inset-0 flex flex-col items-center justify-center">
            <p className="text-[12px] text-white/60">남은 시간</p>
            <p className="text-[44px] tracking-tight mt-1">
              {mm}:{ss}
            </p>
            <p className="text-[11px] text-[#C7E26B] mt-1">{workout.reps}</p>
          </div>
        </div>

        <div className="mt-6 px-3 py-2 rounded-full bg-white/5 border border-white/10 flex items-center gap-2">
          <Activity size={14} className="text-[#C7E26B]" />
          <p className="text-[11px] text-white/70">{workout.category} · 자세 교정 미사용</p>
        </div>
      </div>

      <div className="mb-6 flex items-center justify-center gap-5">
        <button className="w-12 h-12 rounded-full bg-white/10 flex items-center justify-center">
          <Pause size={20} />
        </button>
        <button
          onClick={onNext}
          className="px-6 h-14 rounded-full bg-[#C7E26B] text-[#1F2618] flex items-center gap-2"
        >
          <Check size={18} /> 다음 운동
        </button>
      </div>
    </div>
  );
}

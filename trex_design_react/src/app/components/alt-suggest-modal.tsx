import { X, Repeat, Activity } from "lucide-react";
import { Workout } from "../data/workouts";

const fallbacks: Record<string, { name: string; reps: string }[]> = {
  하체: [
    { name: "글루트 브릿지", reps: "12회 × 3세트" },
    { name: "카프 레이즈", reps: "15회 × 3세트" },
  ],
  코어: [
    { name: "버드독", reps: "10회 × 3세트" },
    { name: "사이드 플랭크", reps: "30초 × 3세트" },
  ],
  상체: [
    { name: "니 푸쉬업", reps: "10회 × 3세트" },
    { name: "밴드 로우", reps: "12회 × 3세트" },
  ],
  회복: [
    { name: "캣카우 스트레칭", reps: "전신 5분" },
    { name: "차일드 포즈", reps: "전신 4분" },
  ],
};

export function AltSuggestModal({
  workout,
  onClose,
}: {
  workout: Workout;
  onClose: () => void;
}) {
  const alts = [
    ...(workout.alt ? [workout.alt] : []),
    ...(fallbacks[workout.category] ?? []),
  ];

  return (
    <div className="absolute inset-0 z-30 bg-black/40 backdrop-blur-sm flex items-end">
      <div className="w-full bg-[#1F2618] text-white rounded-t-[28px] p-5">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-[11px] text-[#C7E26B]">추천 대체 운동</p>
            <h2 className="mt-0.5">{workout.name}</h2>
          </div>
          <button
            onClick={onClose}
            className="w-9 h-9 rounded-full bg-[#FF6B6B]/15 border border-[#FF6B6B]/30 text-[#FF8A8A] flex items-center justify-center"
          >
            <X size={16} />
          </button>
        </div>

        <p className="text-[12px] text-white/50 mt-3">
          {workout.category} · {workout.reps} 와 비슷한 강도예요
        </p>

        <div className="mt-3 space-y-2">
          {alts.map((a, i) => (
            <button
              key={a.name}
              onClick={onClose}
              className="w-full p-3 rounded-2xl bg-white/5 border border-white/10 flex items-center gap-3 hover:bg-white/10 transition-colors text-left"
            >
              <div className="w-10 h-10 rounded-xl bg-[#C7E26B] text-[#1F2618] flex items-center justify-center shrink-0">
                <Activity size={16} />
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-[13px]">{a.name}</p>
                <p className="text-[11px] text-white/50 mt-0.5">{a.reps}</p>
              </div>
              {i === 0 && (
                <span className="text-[10px] px-2 py-1 rounded-full bg-[#C7E26B] text-[#1F2618] shrink-0">
                  가장 추천
                </span>
              )}
              <Repeat size={14} className="text-[#C7E26B] shrink-0" />
            </button>
          ))}
        </div>

        <button
          onClick={onClose}
          className="mt-4 w-full h-12 rounded-2xl bg-white/10 text-white/70"
        >
          그대로 유지
        </button>
      </div>
    </div>
  );
}

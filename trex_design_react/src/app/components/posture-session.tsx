import { useEffect, useState } from "react";
import { Pause, RotateCcw, Camera, Check, X } from "lucide-react";
import { ImageWithFallback } from "./figma/ImageWithFallback";
import { Workout } from "../data/workouts";

export function PostureSession({
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
  const [t, setT] = useState(90);
  useEffect(() => {
    const id = setInterval(() => setT((v) => (v > 0 ? v - 1 : 0)), 1000);
    return () => clearInterval(id);
  }, [workout.id]);
  useEffect(() => setT(90), [workout.id]);

  const mm = String(Math.floor(t / 60)).padStart(2, "0");
  const ss = String(t % 60).padStart(2, "0");

  return (
    <div className="w-full h-full flex flex-col bg-[#1F2618] pt-12 text-white">
      <div className="px-5 flex items-center justify-between">
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

      <div className="mx-5 mt-4 rounded-[24px] overflow-hidden relative h-[280px]">
        <ImageWithFallback
          src="https://images.unsplash.com/photo-1574680096145-d05b474e2155?w=600"
          alt="운동 중"
          className="w-full h-full object-cover"
        />
        <div className="absolute inset-0 bg-gradient-to-b from-transparent via-transparent to-black/60" />
        <div className="absolute top-3 left-3 px-2.5 py-1 rounded-full bg-black/40 backdrop-blur text-[11px] flex items-center gap-1.5">
          <Camera size={12} /> 자세 인식 중
        </div>
        <div className="absolute top-3 right-3 px-2.5 py-1 rounded-full bg-[#C7E26B] text-[#1F2618] text-[11px] flex items-center gap-1">
          <Check size={12} /> 정확도 94%
        </div>
        <div className="absolute bottom-3 left-3 right-3 rounded-2xl bg-white/95 text-[#1F2618] p-3">
          <p className="text-[11px] text-[#759848]">바로 고치고</p>
          <p className="text-[13px] mt-0.5">무릎 높이를 조금 더 올려보세요</p>
        </div>
      </div>

      <div className="px-5 mt-5 flex items-end justify-between">
        <div>
          <p className="text-[11px] opacity-60">남은 시간</p>
          <p className="text-[40px] tracking-tight">
            {mm}:{ss}
          </p>
        </div>
        <div className="text-right">
          <p className="text-[11px] opacity-60">반복</p>
          <p className="text-[24px]">{workout.reps}</p>
        </div>
      </div>

      <div className="px-5 mt-3">
        <div className="h-2 rounded-full bg-white/10 overflow-hidden">
          <div className="h-full bg-[#C7E26B]" style={{ width: `${100 - (t / 90) * 100}%` }} />
        </div>
      </div>

      <div className="px-5 mt-auto mb-6 flex items-center justify-center gap-5">
        <button className="w-12 h-12 rounded-full bg-white/10 flex items-center justify-center">
          <RotateCcw size={20} />
        </button>
        <button className="w-16 h-16 rounded-full bg-[#C7E26B] text-[#1F2618] flex items-center justify-center shadow-lg">
          <Pause size={26} fill="#1F2618" />
        </button>
        <button
          onClick={onNext}
          className="w-12 h-12 rounded-full bg-white/10 flex items-center justify-center"
        >
          <Check size={20} />
        </button>
      </div>
    </div>
  );
}

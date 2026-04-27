import { Pause, RotateCcw, Camera, Check } from "lucide-react";
import { ImageWithFallback } from "./figma/ImageWithFallback";

export function WorkoutScreen() {
  return (
    <div className="w-full h-full flex flex-col bg-[#1F2618] pt-12 text-white">
      <div className="px-5 flex items-center justify-between">
        <div>
          <p className="text-[11px] opacity-70">진행중 · 2/5 세트</p>
          <p>기본 스쿼트</p>
        </div>
        <div className="px-3 py-1.5 rounded-full bg-[#759848] text-[12px] flex items-center gap-1.5">
          <span className="w-1.5 h-1.5 rounded-full bg-white animate-pulse" /> LIVE
        </div>
      </div>

      <div className="mx-5 mt-4 rounded-[24px] overflow-hidden relative h-[300px]">
        <ImageWithFallback
          src="https://images.unsplash.com/photo-1574680096145-d05b474e2155?w=600"
          alt="운동 중"
          className="w-full h-full object-cover"
        />
        <div className="absolute inset-0 bg-gradient-to-b from-transparent via-transparent to-black/60" />

        <div className="absolute top-3 left-3 px-2.5 py-1 rounded-full bg-black/40 backdrop-blur text-[11px] flex items-center gap-1.5">
          <Camera size={12} /> 자세 인식 중
        </div>

        <div className="absolute top-3 right-3 px-2.5 py-1 rounded-full bg-[#759848] text-[11px] flex items-center gap-1">
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
          <p className="text-[40px] tracking-tight">01:30</p>
        </div>
        <div className="text-right">
          <p className="text-[11px] opacity-60">반복 횟수</p>
          <p className="text-[28px]">
            08<span className="opacity-50 text-[18px]">/12</span>
          </p>
        </div>
      </div>

      <div className="px-5 mt-3">
        <div className="h-2 rounded-full bg-white/10 overflow-hidden">
          <div className="h-full w-[66%] bg-[#759848]" />
        </div>
      </div>

      <div className="px-5 mt-auto mb-8 flex items-center justify-center gap-5">
        <button className="w-12 h-12 rounded-full bg-white/10 flex items-center justify-center">
          <RotateCcw size={20} />
        </button>
        <button className="w-16 h-16 rounded-full bg-[#759848] flex items-center justify-center shadow-lg">
          <Pause size={26} fill="white" />
        </button>
        <button className="w-12 h-12 rounded-full bg-white/10 flex items-center justify-center">
          <Camera size={20} />
        </button>
      </div>
    </div>
  );
}

import { useState } from "react";
import { ArrowLeft, ArrowRight, Check, Dumbbell, Flame, Activity, Shield, Eye } from "lucide-react";

const goals = [
  { id: "lower", label: "건강한 하체 만들어룡!", desc: "스쿼트 · 런지 중심", icon: Dumbbell },
  { id: "diet", label: "다이어트를 목표로 해룡!", desc: "유산소 + 식단 관리", icon: Flame },
  { id: "simple", label: "간단하게 운동만 하고 싶어룡!", desc: "하루 10분 루틴", icon: Activity },
  { id: "core", label: "탄탄한 코어 잡고싶어룡!", desc: "플랭크 · 복근 루틴", icon: Shield },
  { id: "posture", label: "자세부터 바로잡고 싶어룡!", desc: "거북목 · 골반 교정", icon: Eye },
];

const weeks = [2, 4, 6, 8, 12];

export function Onboarding({ onDone }: { onDone: () => void }) {
  const [step, setStep] = useState(0);
  const [goal, setGoal] = useState<string | null>(null);
  const [week, setWeek] = useState<number | null>(null);
  const [height, setHeight] = useState("");
  const [weight, setWeight] = useState("");

  const canNext =
    (step === 0 && goal) ||
    (step === 1 && week !== null) ||
    (step === 2 && height && weight);

  const next = () => {
    if (step < 2) setStep(step + 1);
    else onDone();
  };

  const prev = () => setStep(Math.max(0, step - 1));

  return (
    <div className="w-full h-full bg-[#1F2618] text-white flex flex-col pt-12 px-6 pb-6">
      <div className="flex items-center justify-center">
        <div className="flex gap-1.5">
          {[0, 1, 2].map((i) => (
            <span
              key={i}
              className={`h-1 rounded-full transition-all ${
                i === step ? "w-8 bg-[#C7E26B]" : "w-4 bg-white/15"
              }`}
            />
          ))}
        </div>
      </div>

      {step === 0 && (
        <div className="mt-8 flex-1 flex flex-col">
          <p className="text-[12px] text-[#C7E26B]">Step 1</p>
          <h2 className="mt-1">어떤 목표로 시작할까룡?</h2>
          <p className="text-[12px] text-white/50 mt-1.5">하나만 골라주세요</p>

          <div className="mt-5 space-y-2.5 overflow-y-auto pr-1">
            {goals.map((g) => {
              const Icon = g.icon;
              const active = goal === g.id;
              return (
                <button
                  key={g.id}
                  onClick={() => setGoal(g.id)}
                  className={`w-full flex items-center gap-3 p-3.5 rounded-2xl border text-left transition-all ${
                    active
                      ? "bg-[#C7E26B] border-[#C7E26B] text-[#1F2618]"
                      : "bg-white/5 border-white/10 text-white"
                  }`}
                >
                  <div
                    className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 ${
                      active ? "bg-[#1F2618] text-[#C7E26B]" : "bg-white/10 text-[#C7E26B]"
                    }`}
                  >
                    <Icon size={18} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-[13px]">{g.label}</p>
                    <p className={`text-[11px] mt-0.5 ${active ? "opacity-70" : "text-white/50"}`}>
                      {g.desc}
                    </p>
                  </div>
                  {active && (
                    <div className="w-6 h-6 rounded-full bg-[#1F2618] text-[#C7E26B] flex items-center justify-center shrink-0">
                      <Check size={14} />
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {step === 1 && (
        <div className="mt-8 flex-1 flex flex-col">
          <p className="text-[12px] text-[#C7E26B]">Step 2</p>
          <h2 className="mt-1">몇 주 동안 함께할까룡?</h2>
          <p className="text-[12px] text-white/50 mt-1.5">목표 기간을 선택해주세요</p>

          <div className="mt-6 grid grid-cols-3 gap-2.5">
            {weeks.map((w) => {
              const active = week === w;
              return (
                <button
                  key={w}
                  onClick={() => setWeek(w)}
                  className={`aspect-square rounded-2xl border flex flex-col items-center justify-center transition-all ${
                    active
                      ? "bg-[#C7E26B] border-[#C7E26B] text-[#1F2618]"
                      : "bg-white/5 border-white/10 text-white"
                  }`}
                >
                  <p className="text-[28px] leading-none">{w}</p>
                  <p className={`text-[11px] mt-1 ${active ? "opacity-70" : "text-white/50"}`}>
                    주
                  </p>
                </button>
              );
            })}
          </div>

          {week !== null && (
            <div className="mt-6 rounded-2xl bg-white/5 border border-white/10 p-4">
              <p className="text-[11px] text-white/50">예상 완료일</p>
              <p className="mt-1 text-[14px]">
                약 {week * 7}일 후 · 주 4회 권장
              </p>
            </div>
          )}
        </div>
      )}

      {step === 2 && (
        <div className="mt-8 flex-1 flex flex-col">
          <p className="text-[12px] text-[#C7E26B]">Step 3</p>
          <h2 className="mt-1">키와 몸무게를 알려주세룡</h2>
          <p className="text-[12px] text-white/50 mt-1.5">맞춤 강도 계산에 사용돼요</p>

          <div className="mt-6 space-y-3">
            <label className="block">
              <span className="text-[11px] text-white/50">키 (cm)</span>
              <div className="mt-1.5 relative">
                <input
                  inputMode="numeric"
                  value={height}
                  onChange={(e) => setHeight(e.target.value.replace(/\D/g, ""))}
                  placeholder="170"
                  className="w-full h-14 rounded-2xl bg-white/5 border border-white/10 px-4 pr-12 text-[18px] text-white placeholder:text-white/30 outline-none focus:border-[#C7E26B]"
                />
                <span className="absolute right-4 top-1/2 -translate-y-1/2 text-[12px] text-white/40">
                  cm
                </span>
              </div>
            </label>
            <label className="block">
              <span className="text-[11px] text-white/50">몸무게 (kg)</span>
              <div className="mt-1.5 relative">
                <input
                  inputMode="numeric"
                  value={weight}
                  onChange={(e) => setWeight(e.target.value.replace(/\D/g, ""))}
                  placeholder="65"
                  className="w-full h-14 rounded-2xl bg-white/5 border border-white/10 px-4 pr-12 text-[18px] text-white placeholder:text-white/30 outline-none focus:border-[#C7E26B]"
                />
                <span className="absolute right-4 top-1/2 -translate-y-1/2 text-[12px] text-white/40">
                  kg
                </span>
              </div>
            </label>
          </div>
        </div>
      )}

      <div className="mt-4 flex gap-2.5">
        <button
          onClick={prev}
          disabled={step === 0}
          className={`flex-1 rounded-2xl flex items-center justify-center gap-2 transition-all ${
            step === 0
              ? "bg-white/5 text-white/30"
              : "bg-white/[0.08] border border-white/15 text-white"
          }`}
          style={{ height: 52 }}
        >
          <ArrowLeft size={16} /> 이전
        </button>
        <button
          onClick={next}
          disabled={!canNext}
          className={`flex-1 rounded-2xl flex items-center justify-center gap-2 transition-all ${
            canNext ? "bg-[#C7E26B] text-[#1F2618]" : "bg-white/10 text-white/40"
          }`}
          style={{ height: 52 }}
        >
          {step < 2 ? "다음" : "시작"}
          <ArrowRight size={16} />
        </button>
      </div>
    </div>
  );
}

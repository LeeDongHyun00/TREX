import { TrendingUp, Award, Target } from "lucide-react";

const weeks = [
  { d: "월", v: 60 },
  { d: "화", v: 80 },
  { d: "수", v: 45 },
  { d: "목", v: 92 },
  { d: "금", v: 70 },
  { d: "토", v: 0 },
  { d: "일", v: 0 },
];

export function ProgressScreen() {
  return (
    <div className="w-full h-full flex flex-col bg-[#F5F7F1] pt-12 overflow-y-auto">
      <div className="px-5">
        <p className="text-[12px] text-[#5E6754]">나의 기록</p>
        <h2 className="text-[#1F2618]">조금씩 좋아지고 있어요</h2>
      </div>

      <div className="px-5 mt-4">
        <div className="rounded-[24px] bg-white p-5 shadow-sm">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-[12px] text-[#5E6754]">이번 주 자세 정확도</p>
              <p className="text-[#1F2618] mt-0.5">평균 92%</p>
            </div>
            <span className="px-2.5 py-1 rounded-full bg-[#A8C47C]/30 text-[11px] text-[#5F7D39] flex items-center gap-1">
              <TrendingUp size={12} /> +6%
            </span>
          </div>

          <div className="mt-5 flex items-end justify-between h-32 gap-2">
            {weeks.map((w, i) => (
              <div key={w.d} className="flex-1 flex flex-col items-center gap-2">
                <div className="w-full flex items-end h-full">
                  <div
                    className={`w-full rounded-t-lg ${
                      i === 3 ? "bg-[#759848]" : "bg-[#A8C47C]/50"
                    }`}
                    style={{ height: `${w.v}%` }}
                  />
                </div>
                <span className="text-[10px] text-[#5E6754]">{w.d}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="px-5 mt-4 grid grid-cols-2 gap-3">
        <div className="rounded-[20px] bg-white p-4 shadow-sm">
          <Award size={20} className="text-[#759848]" />
          <p className="mt-2 text-[#1F2618]">12회</p>
          <p className="text-[11px] text-[#5E6754]">완료 운동</p>
        </div>
        <div className="rounded-[20px] bg-white p-4 shadow-sm">
          <Target size={20} className="text-[#759848]" />
          <p className="mt-2 text-[#1F2618]">5일 연속</p>
          <p className="text-[11px] text-[#5E6754]">최장 기록</p>
        </div>
      </div>

      <div className="px-5 mt-5">
        <p className="text-[#1F2618]">최근 활동</p>
        <div className="mt-3 space-y-2">
          {[
            { name: "기본 스쿼트", time: "오늘 · 09:20", acc: 94 },
            { name: "플랭크 자세 교정", time: "어제 · 21:05", acc: 88 },
            { name: "런지 기초", time: "4월 23일", acc: 91 },
          ].map((r) => (
            <div
              key={r.name}
              className="rounded-[16px] bg-white p-3 flex items-center justify-between shadow-sm"
            >
              <div>
                <p className="text-[#1F2618] text-[14px]">{r.name}</p>
                <p className="text-[11px] text-[#5E6754]">{r.time}</p>
              </div>
              <div className="text-right">
                <p className="text-[#759848] text-[14px]">{r.acc}%</p>
                <p className="text-[10px] text-[#5E6754]">정확도</p>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="h-6" />
    </div>
  );
}

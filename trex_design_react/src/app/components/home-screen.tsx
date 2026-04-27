import {
  Activity,
  Coffee,
  UtensilsCrossed,
  Apple,
  Moon,
  Clock,
  Flame,
} from "lucide-react";

const days = ["월", "화", "수", "목", "금", "토", "일"];
const dates = [20, 21, 22, 23, 24, 25, 26];
const completed = [true, true, false, true, true, false, false];

const todayWorkouts = [
  { name: "기본 스쿼트", set: "12회 × 3세트", time: "08:00", duration: "8분", done: true },
  { name: "플랭크 자세 교정", set: "60초 × 3세트", time: "08:15", duration: "5분", done: false },
  { name: "런지 기초", set: "10회 × 3세트", time: "19:00", duration: "10분", done: false },
  { name: "스트레칭 마무리", set: "전신", time: "19:15", duration: "6분", done: false },
];

function currentMeal(hour: number) {
  if (hour < 10) return { id: "breakfast", label: "아침 식사", icon: Coffee, hint: "08:00 ~ 10:00" };
  if (hour < 14) return { id: "lunch", label: "점심 식사", icon: UtensilsCrossed, hint: "12:00 ~ 14:00" };
  if (hour < 18) return { id: "snack", label: "오후 간식", icon: Apple, hint: "15:00 ~ 17:00" };
  return { id: "dinner", label: "저녁 식사", icon: Moon, hint: "18:00 ~ 20:00" };
}

export function HomeScreen() {
  const meal = currentMeal(new Date().getHours());
  const MealIcon = meal.icon;
  const doneCount = todayWorkouts.filter((w) => w.done).length;

  return (
    <div className="w-full h-full flex flex-col bg-[#1F2618] pt-12 overflow-y-auto pb-28 text-white">
      <div className="px-5">
        <p className="text-[12px] text-white/60">Your Activity</p>
        <h2 className="text-white">2026년 4월</h2>
      </div>

      <div className="px-5 mt-4">
        <div className="rounded-[24px] bg-[#C7E26B] p-4 text-[#1F2618]">
          <div className="flex items-center justify-between">
            <p className="text-[12px] opacity-70">이번 주 출석</p>
            <span className="text-[11px] px-2 py-0.5 rounded-full bg-[#1F2618] text-[#C7E26B]">
              4/7일
            </span>
          </div>
          <div className="flex justify-between mt-3">
            {days.map((d, i) => (
              <div key={d} className="flex flex-col items-center gap-1.5">
                <span className="text-[10px] opacity-60">{d}</span>
                <div
                  className={`w-8 h-8 rounded-full flex items-center justify-center text-[12px] ${
                    completed[i]
                      ? "bg-[#1F2618] text-[#C7E26B]"
                      : i === 5
                      ? "bg-white text-[#1F2618]"
                      : "bg-[#1F2618]/10 text-[#1F2618]/70"
                  }`}
                >
                  {dates[i]}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="px-5 mt-4">
        <div className="rounded-[24px] bg-white text-[#1F2618] p-4">
          <p className="text-[12px] text-[#5E6754]">오늘 운동 리스트</p>
          <p className="mt-0.5">
            <span>{doneCount}</span>
            <span className="text-[#5E6754]"> / {todayWorkouts.length} 완료</span>
          </p>

          <div className="mt-3 h-1.5 rounded-full bg-[#F5F7F1] overflow-hidden">
            <div
              className="h-full bg-[#759848]"
              style={{ width: `${(doneCount / todayWorkouts.length) * 100}%` }}
            />
          </div>

          <div className="mt-4 space-y-2">
            {todayWorkouts.map((w) => (
              <div
                key={w.name}
                className="flex items-center gap-3 p-2.5 rounded-2xl bg-[#F5F7F1]"
              >
                <div
                  className={`w-9 h-9 rounded-xl flex items-center justify-center shrink-0 ${
                    w.done ? "bg-[#759848] text-white" : "bg-white text-[#5F7D39]"
                  }`}
                >
                  <Activity size={16} />
                </div>
                <div className="flex-1 min-w-0">
                  <p className={`text-[13px] ${w.done ? "line-through text-[#5E6754]" : ""}`}>
                    {w.name}
                  </p>
                  <p className="text-[11px] text-[#5E6754] mt-0.5">{w.set}</p>
                </div>
                <div className="text-right shrink-0">
                  <p className="text-[12px] text-[#1F2618] flex items-center gap-1 justify-end">
                    <Clock size={11} /> {w.time}
                  </p>
                  <p className="text-[10px] text-[#5E6754] mt-0.5">{w.duration}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="px-5 mt-3">
        <div className="rounded-[20px] bg-[#C7E26B] text-[#1F2618] p-4 flex items-center gap-2.5">
          <div className="w-10 h-10 rounded-full bg-[#1F2618] text-[#C7E26B] flex items-center justify-center shrink-0">
            <MealIcon size={18} />
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-[11px] opacity-70">{meal.hint}</p>
            <p className="text-[14px]">{meal.label} 시간이에요</p>
          </div>
        </div>
      </div>

      <div className="px-5 mt-3 grid grid-cols-2 gap-3">
        <div className="rounded-[20px] bg-white text-[#1F2618] p-4">
          <div className="flex items-center justify-between">
            <p className="text-[11px] text-[#5E6754]">오늘 소모</p>
            <Flame size={16} className="text-[#D78B28]" />
          </div>
          <p className="mt-3">
            <span className="text-[24px]">248</span>
            <span className="text-[11px] text-[#5E6754]"> kcal</span>
          </p>
        </div>
        <div className="rounded-[20px] bg-white/5 border border-white/10 p-4">
          <p className="text-[11px] text-white/60">연속 출석</p>
          <p className="mt-3">
            <span className="text-[24px] text-white">5</span>
            <span className="text-[11px] text-white/60"> 일</span>
          </p>
        </div>
      </div>
    </div>
  );
}

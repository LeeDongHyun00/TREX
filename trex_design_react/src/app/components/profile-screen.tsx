import {
  ChevronRight,
  UserCog,
  Bell,
  Shield,
  HelpCircle,
  FileText,
  Globe,
  LogOut,
  User,
} from "lucide-react";

const groups: { title?: string; rows: { id: string; label: string; icon: typeof Bell; sub?: string; danger?: boolean }[] }[] = [
  {
    rows: [
      { id: "edit", label: "프로필 편집", icon: User },
      { id: "account", label: "계정 관리", icon: UserCog, sub: "이메일 · 비밀번호 · 연결된 계정" },
    ],
  },
  {
    rows: [
      { id: "notif", label: "알림", icon: Bell, sub: "운동 · 식단 리마인더" },
      { id: "lang", label: "언어", icon: Globe, sub: "한국어" },
      { id: "privacy", label: "개인정보 보호", icon: Shield },
    ],
  },
  {
    rows: [
      { id: "help", label: "도움말", icon: HelpCircle },
      { id: "terms", label: "약관 및 정책", icon: FileText },
    ],
  },
];

export function ProfileScreen() {
  return (
    <div className="w-full h-full bg-[#1F2618] pt-12 overflow-y-auto pb-28 text-white">
      <div className="px-5">
        <p className="text-[12px] text-white/50">내 정보</p>
        <h2 className="mt-0.5">설정</h2>
      </div>

      <div className="px-5 mt-4">
        <div className="rounded-[20px] bg-white/[0.06] border border-white/10 p-4 flex items-center gap-3">
          <div className="w-14 h-14 rounded-full bg-[#C7E26B] text-[#1F2618] flex items-center justify-center shrink-0">
            <User size={26} />
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-[15px] truncate">지민</p>
            <p className="text-[11px] text-white/50 mt-0.5 truncate">jimin@trex.app</p>
          </div>
          <button className="w-8 h-8 rounded-full bg-white/10 flex items-center justify-center text-white/60">
            <ChevronRight size={16} />
          </button>
        </div>
      </div>

      {groups.map((g, gi) => (
        <div key={gi} className="px-5 mt-5">
          <div className="rounded-[18px] bg-white/[0.04] border border-white/10 overflow-hidden">
            {g.rows.map((r, i) => {
              const Icon = r.icon;
              return (
                <button
                  key={r.id}
                  className={`w-full flex items-center gap-3 px-4 py-3.5 text-left hover:bg-white/[0.04] transition-colors ${
                    i !== g.rows.length - 1 ? "border-b border-white/[0.06]" : ""
                  }`}
                >
                  <div className="w-8 h-8 rounded-lg bg-white/10 flex items-center justify-center shrink-0">
                    <Icon size={15} className="text-[#C7E26B]" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-[14px] text-white">{r.label}</p>
                    {r.sub && (
                      <p className="text-[11px] text-white/45 mt-0.5 truncate">{r.sub}</p>
                    )}
                  </div>
                  <ChevronRight size={15} className="text-white/30 shrink-0" />
                </button>
              );
            })}
          </div>
        </div>
      ))}

      <div className="px-5 mt-5">
        <button className="w-full rounded-[18px] bg-[#C65454]/10 border border-[#C65454]/30 py-3.5 flex items-center justify-center gap-2 text-[#FF8585]">
          <LogOut size={16} />
          <span className="text-[14px]">로그아웃</span>
        </button>
        <p className="text-[11px] text-white/30 text-center mt-3">TREX v1.0.0</p>
      </div>
    </div>
  );
}

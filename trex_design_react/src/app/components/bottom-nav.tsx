import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Home, Dumbbell, Salad, User, Play, Camera, X } from "lucide-react";

export type Tab = "home" | "workout" | "diet" | "profile";

const tabs: { id: Tab; label: string; icon: typeof Home }[] = [
  { id: "home", label: "홈", icon: Home },
  { id: "workout", label: "운동", icon: Dumbbell },
  { id: "diet", label: "식단", icon: Salad },
  { id: "profile", label: "내 정보", icon: User },
];

export function BottomNav({
  tab,
  setTab,
  onStartWorkout,
  onOpenPhotoLog,
}: {
  tab: Tab;
  setTab: (t: Tab) => void;
  onStartWorkout: () => void;
  onOpenPhotoLog: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const activeIdx = tabs.findIndex((t) => t.id === tab);
  const ActiveIcon = tabs[activeIdx].icon;
  const isMorphTab = tab === "workout" || tab === "diet";

  useEffect(() => {
    if (!isMorphTab) {
      setExpanded(false);
      return;
    }
    const id = setTimeout(() => setExpanded(true), 240);
    return () => clearTimeout(id);
  }, [tab, isMorphTab]);

  const handleTab = (t: Tab) => {
    if (expanded) return;
    setTab(t);
  };

  const handleClose = () => {
    setExpanded(false);
    setTimeout(() => setTab("home"), 260);
  };

  const SPRING = { type: "spring" as const, stiffness: 380, damping: 34 };

  return (
    <div
      className="absolute bottom-3 left-3 right-3 rounded-full bg-[#2B3424]/70 backdrop-blur-xl h-[68px] overflow-hidden"
      style={{ boxShadow: "0 18px 40px -12px rgba(0,0,0,0.55), 0 4px 12px rgba(0,0,0,0.25)" }}
    >
      <div className="absolute inset-0 flex items-center">
        {tabs.map((t, i) => {
          const Icon = t.icon;
          const isActive = i === activeIdx;
          return (
            <button
              key={t.id}
              onClick={() => handleTab(t.id)}
              className="flex-1 h-full flex flex-col items-center justify-center gap-1"
            >
              <motion.div
                animate={{ opacity: isActive ? 0 : expanded ? 0 : 0.7 }}
                transition={{ duration: 0.18 }}
                className="w-7 h-7 rounded-full flex items-center justify-center text-white"
              >
                <Icon size={16} />
              </motion.div>
              <motion.span
                animate={{ opacity: expanded ? 0 : isActive ? 1 : 0.6 }}
                transition={{ duration: 0.18 }}
                className={`text-[10px] leading-none ${
                  isActive ? "text-[#C7E26B]" : "text-white/60"
                }`}
              >
                {t.label}
              </motion.span>
            </button>
          );
        })}
      </div>

      <motion.div
        className="absolute bg-[#C7E26B] flex items-center justify-center overflow-hidden pointer-events-none"
        initial={false}
        animate={{
          left: expanded ? 8 : `calc(${(2 * activeIdx + 1) * 12.5}% - 14px)`,
          top: expanded ? "50%" : 8,
          width: expanded ? "calc(100% - 64px)" : 28,
          height: expanded ? 44 : 28,
          y: expanded ? "-50%" : 0,
          borderRadius: 999,
        }}
        transition={SPRING}
      >
        <AnimatePresence mode="wait" initial={false}>
          {expanded ? (
            <motion.button
              key="action"
              onClick={tab === "workout" ? onStartWorkout : onOpenPhotoLog}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1, transition: { delay: 0.18 } }}
              exit={{ opacity: 0, transition: { duration: 0.1 } }}
              className="w-full h-full flex items-center justify-center gap-2 text-[#1F2618] whitespace-nowrap text-[13px]"
            >
              {tab === "workout" ? (
                <>
                  <Play size={15} fill="#1F2618" /> 운동 시작
                </>
              ) : (
                <>
                  <Camera size={15} /> 사진 식단 기록
                </>
              )}
            </motion.button>
          ) : (
            <motion.div
              key="icon"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1, transition: { delay: 0.08 } }}
              exit={{ opacity: 0, transition: { duration: 0.08 } }}
              className="text-[#1F2618]"
            >
              <ActiveIcon size={14} />
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>

      <AnimatePresence>
        {expanded && (
          <motion.button
            key="close"
            onClick={handleClose}
            initial={{ opacity: 0, scale: 0.4 }}
            animate={{
              opacity: 1,
              scale: 1,
              transition: { delay: 0.32, type: "spring", stiffness: 500, damping: 24 },
            }}
            exit={{ opacity: 0, scale: 0.4, transition: { duration: 0.12 } }}
            className="absolute right-2 top-1/2 -translate-y-1/2 w-11 h-11 rounded-full bg-[#FF6B6B]/15 border border-[#FF6B6B]/30 text-[#FF8A8A] flex items-center justify-center"
          >
            <X size={18} />
          </motion.button>
        )}
      </AnimatePresence>
    </div>
  );
}

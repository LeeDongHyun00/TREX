import { useState } from "react";
import { PhoneFrame } from "./components/phone-frame";
import { HomeScreen } from "./components/home-screen";
import { WorkoutList } from "./components/workout-list";
import { DietScreen } from "./components/diet-screen";
import { LoginScreen } from "./components/login-screen";
import { Onboarding } from "./components/onboarding";
import { PostureSession } from "./components/posture-session";
import { TimerSession } from "./components/timer-session";
import { SessionComplete } from "./components/session-complete";
import { PhotoFoodFlow } from "./components/photo-food-flow";
import { BottomNav, Tab } from "./components/bottom-nav";
import { ProfileScreen } from "./components/profile-screen";
import { todayPlan } from "./data/workouts";

export default function App() {
  const [tab, setTab] = useState<Tab>("home");
  const [loggedIn, setLoggedIn] = useState(false);
  const [onboarded, setOnboarded] = useState(false);

  const [sessionIdx, setSessionIdx] = useState<number | null>(null);
  const [sessionDone, setSessionDone] = useState(false);
  const [photoLog, setPhotoLog] = useState(false);

  const startSession = () => {
    setSessionIdx(0);
    setSessionDone(false);
  };
  const advance = () => {
    if (sessionIdx === null) return;
    if (sessionIdx + 1 >= todayPlan.length) {
      setSessionIdx(null);
      setSessionDone(true);
    } else setSessionIdx(sessionIdx + 1);
  };
  const exitSession = () => {
    setSessionIdx(null);
    setSessionDone(false);
    setTab("home");
  };

  const inSession = sessionIdx !== null;

  return (
    <div className="size-full min-h-screen bg-[#E4F2B5] flex items-center justify-center p-6 relative overflow-hidden">
      <div className="absolute inset-0 pointer-events-none select-none flex flex-col justify-between py-8 text-[64px] tracking-[0.3em] text-white/40 leading-none overflow-hidden">
        <p className="whitespace-nowrap">TREX TREX TREX TREX</p>
        <p className="whitespace-nowrap text-right">TREX TREX TREX TREX</p>
        <p className="whitespace-nowrap">TREX TREX TREX TREX</p>
      </div>
      <div className="flex flex-col items-center gap-6 relative">
        <div className="text-center">
          <p className="text-[12px] tracking-[0.3em] text-[#1F2618]">TREX</p>
          <p className="text-[#1F2618] mt-1">바로 보고, 바로 고치고</p>
        </div>

        <PhoneFrame>
          {!loggedIn ? (
            <LoginScreen onLogin={() => setLoggedIn(true)} />
          ) : !onboarded ? (
            <Onboarding onDone={() => setOnboarded(true)} />
          ) : sessionDone ? (
            <SessionComplete
              onDone={() => {
                setSessionDone(false);
                setTab("home");
              }}
            />
          ) : inSession ? (
            todayPlan[sessionIdx!].posture ? (
              <PostureSession
                workout={todayPlan[sessionIdx!]}
                index={sessionIdx!}
                total={todayPlan.length}
                onNext={advance}
                onExit={exitSession}
              />
            ) : (
              <TimerSession
                workout={todayPlan[sessionIdx!]}
                index={sessionIdx!}
                total={todayPlan.length}
                onNext={advance}
                onExit={exitSession}
              />
            )
          ) : (
            <div className="w-full h-full relative">
              {tab === "home" && <HomeScreen />}
              {tab === "workout" && <WorkoutList />}
              {tab === "diet" && <DietScreen />}
              {tab === "profile" && <ProfileScreen />}

              {photoLog && <PhotoFoodFlow onClose={() => setPhotoLog(false)} />}

              <BottomNav
                tab={tab}
                setTab={setTab}
                onStartWorkout={startSession}
                onOpenPhotoLog={() => setPhotoLog(true)}
              />
            </div>
          )}
        </PhoneFrame>
      </div>
    </div>
  );
}

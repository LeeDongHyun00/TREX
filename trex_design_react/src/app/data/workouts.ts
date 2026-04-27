export type Workout = {
  id: string;
  name: string;
  reps: string;
  duration: string;
  posture: boolean;
  category: string;
  alt?: { name: string; reps: string };
};

export const todayPlan: Workout[] = [
  { id: "squat", name: "기본 스쿼트", reps: "12회 × 3세트", duration: "8분", posture: true, category: "하체",
    alt: { name: "의자 스쿼트", reps: "10회 × 3세트" } },
  { id: "plank", name: "플랭크", reps: "60초 × 3세트", duration: "5분", posture: false, category: "코어",
    alt: { name: "데드버그", reps: "12회 × 3세트" } },
  { id: "lunge", name: "런지", reps: "10회 × 3세트", duration: "10분", posture: true, category: "하체",
    alt: { name: "제자리 스텝업", reps: "12회 × 3세트" } },
  { id: "pushup", name: "푸쉬업 입문", reps: "8회 × 3세트", duration: "6분", posture: false, category: "상체",
    alt: { name: "벽 푸쉬업", reps: "12회 × 3세트" } },
  { id: "stretch", name: "마무리 스트레칭", reps: "전신 6분", duration: "6분", posture: false, category: "회복",
    alt: { name: "폼롤러 마무리", reps: "전신 5분" } },
];

import { Check } from "lucide-react";

export function SessionComplete({ onDone }: { onDone: () => void }) {
  return (
    <div className="w-full h-full bg-[#1F2618] text-white flex flex-col items-center justify-center px-8 text-center">
      <div className="w-20 h-20 rounded-full bg-[#C7E26B] text-[#1F2618] flex items-center justify-center">
        <Check size={36} />
      </div>
      <p className="mt-5 text-[12px] text-[#C7E26B] tracking-[0.3em]">DONE</p>
      <h2 className="mt-2">오늘도 정확하게 끝냈어룡</h2>
      <p className="text-[12px] text-white/60 mt-2">
        조금씩 좋아지고 있어요. 내일 같은 시간에 만나요.
      </p>
      <button
        onClick={onDone}
        className="mt-8 w-full h-12 rounded-2xl bg-[#C7E26B] text-[#1F2618]"
      >
        홈으로
      </button>
    </div>
  );
}

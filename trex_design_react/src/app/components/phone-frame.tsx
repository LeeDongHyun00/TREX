import { ReactNode } from "react";

export function PhoneFrame({ children }: { children: ReactNode }) {
  return (
    <div className="relative w-[340px] h-[700px] rounded-[44px] bg-[#1F2618] p-[6px] shadow-[0_30px_60px_-20px_rgba(31,38,24,0.45)]">
      <div className="absolute top-[14px] left-1/2 -translate-x-1/2 w-[110px] h-[26px] bg-[#1F2618] rounded-full z-20" />
      <div className="relative w-full h-full rounded-[38px] overflow-hidden bg-[#F5F7F1]">
        {children}
      </div>
    </div>
  );
}

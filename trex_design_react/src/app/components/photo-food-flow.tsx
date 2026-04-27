import { useEffect, useRef, useState } from "react";
import { X, ImageIcon, Camera, RefreshCcw, Check, Sparkles, ChevronRight } from "lucide-react";
import { ImageWithFallback } from "./figma/ImageWithFallback";

type Stage = "choose" | "capture" | "analyzing" | "result";

const sampleAnalysis = {
  meal: "점심",
  detected: [
    { name: "현미밥", kcal: 220, carb: 46, protein: 5, fat: 1.7, confidence: 0.96 },
    { name: "닭가슴살", kcal: 165, carb: 0, protein: 31, fat: 3.6, confidence: 0.93 },
    { name: "샐러드", kcal: 120, carb: 8, protein: 4, fat: 7, confidence: 0.88 },
  ],
};

export function PhotoFoodFlow({ onClose }: { onClose: () => void }) {
  const [stage, setStage] = useState<Stage>("choose");
  const [image, setImage] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (stage !== "analyzing") return;
    const id = setTimeout(() => setStage("result"), 1800);
    return () => clearTimeout(id);
  }, [stage]);

  const onPick = (file?: File) => {
    if (!file) return;
    const url = URL.createObjectURL(file);
    setImage(url);
    setStage("capture");
  };

  const total = sampleAnalysis.detected.reduce(
    (a, f) => ({
      kcal: a.kcal + f.kcal,
      carb: a.carb + f.carb,
      protein: a.protein + f.protein,
      fat: a.fat + f.fat,
    }),
    { kcal: 0, carb: 0, protein: 0, fat: 0 },
  );

  return (
    <div className="absolute inset-0 z-30 bg-black/45 backdrop-blur-sm flex items-end">
      <input
        ref={fileRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={(e) => onPick(e.target.files?.[0])}
      />
      <input
        ref={cameraRef}
        type="file"
        accept="image/*"
        capture="environment"
        className="hidden"
        onChange={(e) => onPick(e.target.files?.[0])}
      />

      <div className="w-full bg-[#1F2618] text-white rounded-t-[28px] max-h-[92%] overflow-y-auto">
        {stage === "choose" && (
          <div className="p-5">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-[11px] text-[#C7E26B]">사진 식단 기록</p>
                <h2 className="mt-0.5">사진으로 빠르게</h2>
              </div>
              <button
                onClick={onClose}
                className="w-9 h-9 rounded-full bg-[#FF6B6B]/15 border border-[#FF6B6B]/30 text-[#FF8A8A] flex items-center justify-center"
              >
                <X size={16} />
              </button>
            </div>

            <p className="text-[12px] text-white/65 mt-2">
              찍거나 가져온 음식 사진을 분석해서 영양 정보를 자동으로 채워드려요
            </p>

            <div className="mt-5 space-y-2.5">
              <button
                onClick={() => cameraRef.current?.click()}
                className="w-full p-4 rounded-2xl bg-[#C7E26B] text-[#1F2618] flex items-center gap-3"
              >
                <div className="w-11 h-11 rounded-xl bg-[#1F2618] text-[#C7E26B] flex items-center justify-center">
                  <Camera size={18} />
                </div>
                <div className="flex-1 text-left">
                  <p className="text-[14px]">사진 찍기</p>
                  <p className="text-[11px] opacity-70 mt-0.5">카메라로 바로 촬영</p>
                </div>
                <ChevronRight size={16} className="opacity-70" />
              </button>

              <button
                onClick={() => fileRef.current?.click()}
                className="w-full p-4 rounded-2xl bg-white/[0.06] border border-white/10 flex items-center gap-3"
              >
                <div className="w-11 h-11 rounded-xl bg-white/10 text-[#C7E26B] flex items-center justify-center">
                  <ImageIcon size={18} />
                </div>
                <div className="flex-1 text-left">
                  <p className="text-[14px] text-white">갤러리에서 선택</p>
                  <p className="text-[11px] text-white/65 mt-0.5">앨범에서 음식 사진 가져오기</p>
                </div>
                <ChevronRight size={16} className="text-white/40" />
              </button>
            </div>

            <div className="mt-5 rounded-2xl bg-white/[0.04] border border-white/10 p-3.5 flex items-start gap-2.5">
              <Sparkles size={14} className="text-[#C7E26B] mt-0.5 shrink-0" />
              <p className="text-[11px] text-white/70 leading-relaxed">
                여러 음식이 한 접시에 있어도 자동으로 분리해서 인식해요
              </p>
            </div>
          </div>
        )}

        {stage === "capture" && image && (
          <div className="p-5">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-[11px] text-[#C7E26B]">사진 확인</p>
                <h2 className="mt-0.5">이 사진으로 분석할까요?</h2>
              </div>
              <button
                onClick={onClose}
                className="w-9 h-9 rounded-full bg-[#FF6B6B]/15 border border-[#FF6B6B]/30 text-[#FF8A8A] flex items-center justify-center"
              >
                <X size={16} />
              </button>
            </div>

            <div className="mt-4 rounded-[24px] overflow-hidden bg-black aspect-square">
              <img src={image} alt="음식" className="w-full h-full object-cover" />
            </div>

            <div className="mt-4 flex gap-2.5">
              <button
                onClick={() => {
                  setImage(null);
                  setStage("choose");
                }}
                className="flex-1 h-12 rounded-2xl bg-white/[0.06] border border-white/15 text-white/85 flex items-center justify-center gap-2"
              >
                <RefreshCcw size={15} /> 다시 선택
              </button>
              <button
                onClick={() => setStage("analyzing")}
                className="flex-1 h-12 rounded-2xl bg-[#C7E26B] text-[#1F2618] flex items-center justify-center gap-2"
              >
                <Sparkles size={15} /> 분석 시작
              </button>
            </div>
          </div>
        )}

        {stage === "analyzing" && (
          <div className="p-5">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-[11px] text-[#C7E26B]">AI 분석 중</p>
                <h2 className="mt-0.5">잠시만 기다려주세요</h2>
              </div>
              <button
                onClick={onClose}
                className="w-9 h-9 rounded-full bg-[#FF6B6B]/15 border border-[#FF6B6B]/30 text-[#FF8A8A] flex items-center justify-center"
              >
                <X size={16} />
              </button>
            </div>

            <div className="mt-5 rounded-[24px] overflow-hidden relative aspect-square bg-black">
              {image && <img src={image} alt="" className="w-full h-full object-cover opacity-90" />}
              <div className="absolute inset-0 bg-gradient-to-t from-[#1F2618]/80 via-transparent to-transparent" />
              <div className="absolute inset-x-0 bottom-0 p-4">
                <div className="flex items-center gap-2">
                  <span className="w-2 h-2 rounded-full bg-[#C7E26B] animate-pulse" />
                  <p className="text-[12px] text-white/85">음식을 인식하고 있어요…</p>
                </div>
                <div className="mt-2 h-1.5 rounded-full bg-white/15 overflow-hidden">
                  <div className="h-full bg-[#C7E26B] rounded-full animate-[grow_1.6s_ease-out_forwards]" style={{ width: "85%" }} />
                </div>
              </div>
            </div>

            <div className="mt-4 grid grid-cols-3 gap-2">
              {["인식", "분류", "영양 계산"].map((s, i) => (
                <div key={s} className="rounded-xl bg-white/[0.05] border border-white/10 p-2 text-center">
                  <p className="text-[10px] text-white/65">{i + 1}단계</p>
                  <p className="text-[12px] mt-0.5">{s}</p>
                </div>
              ))}
            </div>
          </div>
        )}

        {stage === "result" && (
          <div className="p-5">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-[11px] text-[#C7E26B]">분석 완료</p>
                <h2 className="mt-0.5">{sampleAnalysis.detected.length}가지 음식을 찾았어요</h2>
              </div>
              <button
                onClick={onClose}
                className="w-9 h-9 rounded-full bg-[#FF6B6B]/15 border border-[#FF6B6B]/30 text-[#FF8A8A] flex items-center justify-center"
              >
                <X size={16} />
              </button>
            </div>

            <div className="mt-4 rounded-[24px] bg-[#C7E26B] text-[#1F2618] p-5">
              <div className="flex items-center justify-between">
                <p className="text-[11px] opacity-70">총 칼로리</p>
                <span className="text-[11px] px-2 py-0.5 rounded-full bg-[#1F2618] text-[#C7E26B]">
                  {sampleAnalysis.meal}
                </span>
              </div>
              <p className="mt-1">
                <span className="text-[28px]">{total.kcal}</span>
                <span className="opacity-70"> kcal</span>
              </p>
              <div className="mt-3 grid grid-cols-3 gap-2">
                {[
                  { l: "탄수", v: total.carb },
                  { l: "단백질", v: total.protein },
                  { l: "지방", v: total.fat },
                ].map((m) => (
                  <div key={m.l} className="rounded-xl bg-[#1F2618]/10 p-2 text-center">
                    <p className="text-[10px] opacity-70">{m.l}</p>
                    <p className="text-[13px] mt-0.5">{m.v.toFixed(0)}g</p>
                  </div>
                ))}
              </div>
            </div>

            {image && (
              <div className="mt-3 rounded-2xl overflow-hidden h-28">
                <img src={image} alt="" className="w-full h-full object-cover" />
              </div>
            )}

            <p className="mt-4 text-[11px] text-white/70">인식된 음식</p>
            <div className="mt-2 space-y-2">
              {sampleAnalysis.detected.map((f) => (
                <div
                  key={f.name}
                  className="rounded-2xl bg-white/[0.06] border border-white/10 p-3 flex items-center gap-3"
                >
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <p className="text-[13px] text-white">{f.name}</p>
                      <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-[#C7E26B]/15 text-[#C7E26B]">
                        {Math.round(f.confidence * 100)}%
                      </span>
                    </div>
                    <p className="text-[11px] text-white/65 mt-0.5">
                      {f.kcal} kcal · 탄수 {f.carb}g · 단백질 {f.protein}g · 지방 {f.fat}g
                    </p>
                  </div>
                  <div className="w-7 h-7 rounded-full bg-[#C7E26B] text-[#1F2618] flex items-center justify-center shrink-0">
                    <Check size={14} />
                  </div>
                </div>
              ))}
            </div>

            <div className="mt-5 flex gap-2.5">
              <button
                onClick={onClose}
                className="flex-1 h-[52px] rounded-2xl bg-[#C7E26B] text-[#1F2618] flex items-center justify-center gap-2"
              >
                <Check size={16} /> 식단에 저장
              </button>
              <button
                onClick={onClose}
                className="w-[52px] h-[52px] rounded-2xl bg-[#FF6B6B]/12 border border-[#FF6B6B]/25 text-[#FF8A8A] flex items-center justify-center shrink-0"
              >
                <X size={18} />
              </button>
            </div>
          </div>
        )}
      </div>

      <style>{`@keyframes grow { from { width: 0%; } to { width: 95%; } }`}</style>
    </div>
  );
}

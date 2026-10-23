# -*- coding: utf-8 -*-
"""09-23 폰 플랭크 1세트(좌표 없음)에 제안 시간 게이트를 피처만으로 근사 적용."""
import json
p = r"C:/Users/hp276/Desktop/trex/data/phone/20260925-main/sets-20260923.jsonl"
for line in open(p, encoding="utf-8"):
    s = json.loads(line)
    if s.get("exercise") != "플랭크": continue
    fr = s["frames"]; print("set", s.get("set_id"), "frames", len(fr), "dur", fr[-1]["t_ms"] / 1000)
    def run(lo, hi, label):
        t_obs = t_in = 0.0; prev = None; seg = []
        for f in fr:
            x = f.get("features", {}); t = f["t_ms"]
            dt = 0 if prev is None else min(t - prev, 750)
            prev = t
            knee = x.get("knee_ang_L"); hip = x.get("hip_dev_ankle_L"); el = x.get("elbow_ang_L")
            obs = knee is not None and hip is not None and float(knee) >= 145
            if obs:
                t_obs += dt
                if lo <= float(hip) <= hi: t_in += dt
            seg.append((round(t / 1000, 1), obs, None if hip is None else round(float(hip), 3), None if knee is None else round(float(knee)), None if el is None else round(float(el))))
        print(f" {label}: 관측(무릎≥145 & 골반값) {t_obs/1000:.1f}s, 띠 안 {t_in/1000:.1f}s")
        return seg
    run(-.06, .124, "현행 띠[-0.06,+0.124]")
    seg = run(-.08, .16, "제안 띠[-0.08,+0.16]")
    print(" ", [x for x in seg if x[1]][:60])

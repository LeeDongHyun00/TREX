# -*- coding: utf-8 -*-
"""M3: 09-23 폰 플랭크(하체가 보인 유일한 세트)에 제안 '플랭크 유지 시계' 상태기계를 돌린다.
좌표가 없어 수평·골반 접지는 못 보고, 보이는 쪽 측별 피처로 대리: 관측 = hip_dev_ankle_L·knee_ang_L 존재, 정체 = knee_ang_L ≥ 145.
hip_dev_ankle_L 은 plank_hip_offset 과 같은 식(devUp(hip; shoulder→ankle), PostureFloor.kt:68 · PlankAlignment.kt:34)."""
import json
import numpy as np
P = r"C:/Users/hp276/Desktop/trex/data/phone/20260925-main/sets-20260923.jsonl"
d = next(x for x in (json.loads(l) for l in open(P, encoding="utf-8")) if x.get("exercise") == "플랭크" and x["set_id"].endswith("164f08a4"))
fr = [(x["t_ms"], x["features"]) for x in d["frames"]]
ENTRY, EXIT, GAP = 1000, 1000, 750
st = "OUT"; run = None; bad = None; lastobs = None; hold = 0.0; tent = 0.0; ev = []; prev = None
abs_out = rel_out = 0.0; base = []; ref = None; rel_since = None; rel_events = []; abs_since=None; abs_events=[]
for t, f in fr:
    dt = 0 if prev is None else t - prev; prev = t
    obs = "hip_dev_ankle_L" in f and "knee_ang_L" in f
    if not obs:
        if st == "HOLD" and lastobs is not None and t - lastobs > GAP: st = "PAUSED"; ev.append(("일시정지·관측끊김", lastobs)); run = None
        elif st == "HOLD": hold += dt
        continue
    ok = f["knee_ang_L"] >= 145
    if st == "HOLD" and lastobs is not None and t - lastobs > GAP: st = "PAUSED"; ev.append(("일시정지·관측끊김", lastobs)); run = None
    lastobs = t
    if st in ("OUT", "PAUSED"):
        if ok:
            run = run if run is not None else t
            if t - run >= ENTRY: st = "HOLD"; hold += t - run; ev.append(("유지 시작", run)); bad = None
        else: run = None
        continue
    # HOLD
    if ok:
        hold += dt; tent = 0; bad = None
    else:
        bad = bad if bad is not None else t; tent += dt; hold += dt
        if t - bad >= EXIT: hold -= tent; tent = 0; st = "OUT"; ev.append(("이탈·무릎", bad)); run = None; continue
    h = f["hip_dev_ankle_L"]
    if not (-0.06 <= h <= 0.124):
        abs_since = abs_since if abs_since is not None else t
        abs_out += dt
        if t - abs_since >= 1000 and (not abs_events or abs_events[-1][1] != abs_since): abs_events.append(("절대띠 이탈", abs_since))
    else: abs_since = None
    if ref is None:
        base.append((t, h)); base = [b for b in base if t - b[0] <= 3000]
        if base[-1][0] - base[0][0] >= 2700 and len(base) >= 8 and max(b[1] for b in base) - min(b[1] for b in base) <= 0.06:
            ref = float(np.median([b[1] for b in base])); ev.append((f"본인 기준 {ref:+.3f}", t))
    else:
        if abs(h - ref) > 0.06:
            rel_since = rel_since if rel_since is not None else t
            rel_out += dt
            if t - rel_since >= 1000 and (not rel_events or rel_events[-1][1] != rel_since): rel_events.append((("솟음" if h > ref else "처짐"), rel_since))
        else: rel_since = None
print("벽시계", fr[-1][0] / 1000, "s | 제안 유지 시계", round(hold / 1000, 1), "s")
print("사건", [(k, round(t / 1000, 1)) for k, t in ev])
print("현행 절대 띠[-0.06,0.124] 밖 시간", round(abs_out / 1000, 1), "s, 1 s 지속 이벤트", [(k, round(t/1000,1)) for k,t in abs_events])
print("본인 기준(첫 안정 3 s) ±0.06 밖 시간", round(rel_out / 1000, 1), "s, 1 s 지속 이벤트", [(k, round(t/1000,1)) for k,t in rel_events])

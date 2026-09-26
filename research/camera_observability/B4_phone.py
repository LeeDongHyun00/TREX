#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""B4 — 폰 덤벨 컬 2세트(2026-09-25, 정면 C, 좌표 있음)의 팔별 사이클 2D 단서 · 정답 세트의 검출/오탐 · ROM 게이트.

입력  data/phone/20260926-curl/sets-20260925.jsonl — 20260925T153442-327b933d(13회, 정답: 1~4 정상 · 5~8 옆 벌림 · 9~11 반동 · 12~13 짧게)
                                                       20260925T153312-d2dc3f91(14회, 정답 없음)
출력  outputs/B4/phone_cycles.csv(팔별 사이클 값) · phone_start.csv

사이클 창 = 앱 `reps.arms[]` 의 [start_ms, t_ms](팔별). 수축 = 창 안 월드 팔꿈치각 최소 ± 1 프레임 중앙값.
시작 = 첫 사이클 start_ms 앞 3 s 의 서 있음(그 팔 ≥ 135°) 프레임 중앙값(2프레임 미만이면 1~2회 사이 상단 프레임을 보탬).
사이클 → 앱 회 번호: 앱 `reps.t_ms` 와 600 ms 안의 가장 가까운 것. 못 붙는 사이클은 회 0(기각·미배정).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import B4_lib as B  # noqa: E402

REPO = HERE.parents[1]
LOG = REPO / "data" / "phone" / "20260926-curl" / "sets-20260925.jsonl"
SETS = {"20260925T153442-327b933d": "truth13", "20260925T153312-d2dc3f91": "free14"}
TRUTH = {**{i: "normal" for i in (1, 2, 3, 4)}, **{i: "flare" for i in (5, 6, 7, 8)}, **{i: "swing" for i in (9, 10, 11)}, **{i: "short" for i in (12, 13)}}
IMG = np.array([480.0, 640.0])
MIN_VIS = 0.5


def ang3(a, b, c):
    u, w = a - b, c - b
    with np.errstate(all="ignore"):
        cs = (u * w).sum(-1) / np.linalg.norm(u, axis=-1) / np.linalg.norm(w, axis=-1)
    return np.degrees(np.arccos(np.clip(cs, -1, 1)))


def load():
    out = {}
    for line in LOG.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        d = json.loads(line)
        if d["set_id"] in SETS:
            out[SETS[d["set_id"]]] = d
    return out


def frames_arrays(d):
    fr = [f for f in d["frames"] if f.get("xy")]
    t = np.array([f["t_ms"] for f in fr], np.int64)
    P = np.array([np.array(f["xy"]).reshape(33, 2) for f in fr], float) * IMG
    vis = np.array([f["vis"] for f in fr], float)
    P[vis < MIN_VIS] = np.nan
    W = np.array([np.array(f["w"]).reshape(33, 3) for f in fr], float)
    W[vis < MIN_VIS] = np.nan
    elbow = {"L": ang3(W[:, 11], W[:, 13], W[:, 15]), "R": ang3(W[:, 12], W[:, 14], W[:, 16])}
    return t, P, elbow, fr


def main():
    B.OUT.mkdir(parents=True, exist_ok=True)
    rows, srows = [], []
    for label, d in load().items():
        t, P, elbow, fr = frames_arrays(d)
        F = B.feat2d(P)
        arms = sorted(d["reps"]["arms"], key=lambda a: (a["t_ms"], a["arm"]))
        app_t = list(d["reps"]["t_ms"])
        first_start = min(a["start_ms"] for a in arms)
        # 시작(개인 기준): [그 팔 첫 사이클 start − 3 s, 그 팔 2번째 사이클 start] 의 서 있음(≥ 140°) 프레임 중앙값 — MM-Fit 과 같은 정의
        det = np.ones(len(t), bool)
        frontal = np.ones(len(t), bool)
        start = {}
        for a in "LR":
            cyc = [c for c in arms if c["arm"] == a]
            t_lo = cyc[0]["start_ms"] - 3000
            t_hi = cyc[1]["start_ms"] if len(cyc) >= 2 else cyc[0]["t_ms"]
            msk = B.start_mask_window(t, elbow[a], det, frontal, t_lo, t_hi)
            start[a], n = B.start_values(F, B.KEYS, a, msk)
            pre, n_pre = B.start_values(F, B.KEYS, a, B.start_mask_window(t, elbow[a], det, frontal, t_lo, cyc[0]["start_ms"], angle=135))
            srows.append({"set": label, "arm": a, "n_start": n, "t_lo": t_lo, "t_hi": t_hi, "n_pre3s": n_pre, **{k: v for k, v in start[a].items()},
                          **{f"pre_{k}": v for k, v in pre.items()}})
        start_c, _ = B.start_values(F, B.KEYS_COMMON, "", B.start_mask_window(t, np.fmin(elbow["L"], elbow["R"]), det, frontal, first_start - 3000,
                                                                              sorted(c["start_ms"] for c in arms)[2] if len(arms) >= 3 else first_start))
        for c in arms:
            a = c["arm"]
            win = (t >= c["start_ms"]) & (t <= c["t_ms"])
            i_min = B.contraction_idx(elbow[a], win, need=1)
            # 앱 회 번호
            dt = [abs(c["t_ms"] - x) for x in app_t]
            rep_no = int(np.argmin(dt)) + 1 if dt and min(dt) <= 600 else 0
            row = {"set": label, "rep": rep_no, "truth": TRUTH.get(rep_no, "") if label == "truth13" else "", "arm": a, "t_ms": c["t_ms"], "start_ms": c["start_ms"],
                   "app_min": c["min"], "app_max": c["max"], "app_amp": c["amp"], "app_valid": c.get("valid"), "n_win": int(win.sum()),
                   "elbow_min_win": float(np.nanmin(np.where(win, elbow[a], np.nan))) if win.any() else np.nan, "t_min": int(t[i_min]) if i_min >= 0 else -1}
            row.update(B.rep_stats(F, B.KEYS, a, win, i_min, start[a]))
            row.update(B.rep_stats(F, B.KEYS_COMMON, "", win, i_min, start_c))
            rows.append(row)
        # 기각된 사이클도 기록
        for rj in d["reps"].get("rejected", []):
            rows.append({"set": label, "rep": 0, "truth": "rejected", "arm": rj.get("feature", "")[-1], "t_ms": rj["t_ms"], "start_ms": None, "app_min": rj["min"], "app_max": rj["max"],
                         "app_amp": rj["max"] - rj["min"], "app_valid": False, "note": f"{rj.get('feature')} swing {rj.get('swing'):.1f}"})
    # 팔별 → 반복 행(두 팔 값을 한 행에) 도 만든다: (set, rep) 로 묶음
    B.write_csv(B.OUT / "phone_cycles.csv", rows)
    B.write_csv(B.OUT / "phone_start.csv", srows)
    # 반복 단위 행
    by = {}
    for r in rows:
        if r["rep"] == 0 or r.get("start_ms") is None:
            continue
        key = (r["set"], r["rep"])
        by.setdefault(key, {"set": r["set"], "rep": r["rep"], "truth": r["truth"], "t_ms": r["t_ms"]})
        a = r["arm"]
        for k, v in r.items():
            if k.endswith(f"_{a}"):
                by[key][k] = v
            elif k not in ("set", "rep", "truth", "arm", "t_ms", "start_ms") and not k.endswith("_L") and not k.endswith("_R"):
                by[key][f"{k}_{a}"] = v
    reps = sorted(by.values(), key=lambda r: (r["set"], r["rep"]))
    B.write_csv(B.OUT / "phone_reps.csv", reps)
    # 게이트·띠 판정 (반복 단위 = 두 팔 중 하나라도)
    grows = []
    for r in reps:
        same = lambda a, base: [q for q in reps if q["set"] == r["set"] and np.isfinite(q.get(f"{base}_{a}", np.nan))][:2]  # noqa: E731
        g = {"set": r["set"], "rep": r["rep"], "truth": r["truth"]}
        for a in "LR":
            ref_w = np.nanmedian([q[f"app_amp_{a}"] for q in same(a, "app_amp")]) if same(a, "app_amp") else np.nan
            g[f"ratio_world_{a}"] = r.get(f"app_amp_{a}", np.nan) / ref_w if np.isfinite(ref_w) else np.nan
            amp2d = lambda q: q.get(f"wrist_h_max_{a}", np.nan) - q.get(f"wrist_h_min_{a}", np.nan)  # noqa: E731
            ref2 = np.nanmedian([amp2d(q) for q in same(a, "wrist_h_max")]) if same(a, "wrist_h_max") else np.nan
            g[f"wrist_amp_{a}"] = amp2d(r)
            g[f"ratio_wrist_{a}"] = amp2d(r) / ref2 if np.isfinite(ref2) else np.nan
            ref3 = np.nanmedian([q.get(f"wrist_h_d_{a}", np.nan) for q in same(a, "wrist_h_d")]) if same(a, "wrist_h_d") else np.nan
            g[f"ratio_wrist_rise_{a}"] = r.get(f"wrist_h_d_{a}", np.nan) / ref3 if np.isfinite(ref3) else np.nan
        lv = lambda k: np.nanmax([r.get(f"{k}_L", np.nan), r.get(f"{k}_R", np.nan)])  # noqa: E731
        mn = lambda k: np.nanmin([r.get(f"{k}_L", np.nan), r.get(f"{k}_R", np.nan)])  # noqa: E731
        g["lat_c"], g["lat_d"], g["lat_max"], g["lat_dmax"] = lv("lat_c"), lv("lat_d"), lv("lat_max"), lv("lat_dmax")
        g["rise_c"], g["rise_d"], g["rise_max"], g["rise_dmax"] = lv("rise_c"), lv("rise_d"), lv("rise_max"), lv("rise_dmax")
        g["wrist_h_max"], g["wrist_h_min"] = lv("wrist_h_max"), mn("wrist_h_min")
        g["flare(c: d≥0.15&≥0.28)"] = bool(g["lat_d"] >= 0.15 and g["lat_c"] >= 0.28)
        g["flare(max: d≥0.15&≥0.30)"] = bool(g["lat_dmax"] >= 0.15 and g["lat_max"] >= 0.30)
        g["flare(combo 0.15/0.30/0.25)"] = bool(g["lat_dmax"] >= 0.15 and g["lat_max"] >= 0.30 and g["lat_c"] >= 0.25)
        g["swing(c: d≥0.10)"] = bool(g["rise_d"] >= 0.10)
        g["swing(max: d≥0.15)"] = bool(g["rise_dmax"] >= 0.15)
        g["swing(max: d≥0.15&≥-0.30)"] = bool(g["rise_dmax"] >= 0.15 and g["rise_max"] >= -0.30)
        g["swing(abs: rise_max≥-0.25)"] = bool(g["rise_max"] >= -0.25)
        g["wrist_above(≥0.10)"] = bool(g["wrist_h_max"] >= 0.10)
        for th in (0.7, 0.8, 0.85, 0.9):
            g[f"rom_world<{th}"] = bool(np.nanmin([g["ratio_world_L"], g["ratio_world_R"]]) < th)
            g[f"rom_wrist<{th}"] = bool(np.nanmin([g["ratio_wrist_L"], g["ratio_wrist_R"]]) < th)
        g["bottom_fail(wrist_min>-0.80)"] = bool(g["wrist_h_min"] > -0.80)
        grows.append(g)
    B.write_csv(B.OUT / "phone_gates.csv", grows)
    print(f"[phone] 사이클 {len(rows)}, 반복 {len(reps)}")


if __name__ == "__main__":
    main()

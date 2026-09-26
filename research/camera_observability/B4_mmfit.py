#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""B4 — MM-Fit 덤벨 컬 59세트(300 ms 캡처, 정면 고정 카메라, 교대 컬)의 정면 2D 단서 정상 분포 · ROM 게이트 격자 · 2D 가동 범위 대리.

입력  data/mmfit_mp_captures/*.cap(59세트), data/mm-fit/mm-fit/wNN/*_pose_3d.npy(반복 창 기준 — B2 와 같은 분할), B2_lib
출력  outputs/B4/mmfit_reps.csv(팔별 반복 값) · mmfit_dist.csv · mmfit_bands.csv · mmfit_rom_grid.csv · mmfit_rom_fidelity.csv · mmfit_sets.csv
MM-Fit 파생물은 측정 전용 — 커밋·공개하지 않는다.

반복 창은 B2 와 같다(데이터셋 3D 팔꿈치각의 굴곡 최소 기준, 팔별). 수축 = 창 안 MediaPipe 월드 팔꿈치각 최소 ± 1 프레임 중앙값,
시작 = 첫 하강 3 s 전 창의 서 있음(그 팔 ≥ 140°) 프레임 중앙값(부족하면 6 s 까지, 그래도 없으면 1~2회 사이 상단).
"""
from __future__ import annotations

import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from scipy.stats import spearmanr

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import B2_lib as L  # noqa: E402
import B4_lib as B  # noqa: E402

OUT = B.OUT
MARGIN_MS = 500
FPS = 30.0
RATIOS = [0.6, 0.7, 0.75, 0.8, 0.85, 0.9]
TOP_T = [-0.40, -0.35, -0.30, -0.25, -0.20, -0.15, -0.10]        # 위 끝: 수축 손목 높이 ≥ T
BOT_T = [-0.95, -0.90, -0.85, -0.80, -0.75, -0.70]                # 아래 끝: 창 안 손목 최저 ≤ T


def segment(V, t, lo, hi, key, k=5):
    rL = L.find_arm_reps(V["elbow_L"], t, lo, hi, k=k)
    rR = L.find_arm_reps(V["elbow_R"], t, lo, hi, k=k)
    return L.pair_reps(rL, rR, t, V, key)


def start_mask(tc, elbow, det, r0, lo_ms, hi_ms, angle=B.REST_ANGLE):
    return (tc >= r0 - lo_ms) & (tc <= r0 - hi_ms) & det & (elbow >= angle)


def process():
    index = L.load_index()
    rep_rows, set_rows = [], []
    for s in index:
        w = s["workout"]
        cap = L.load_cap(L.CAP300_DIR / s["capture"])
        Vc, tc = L.arm_vars(cap), cap.t_ms
        f0, e3 = L.pose3d_elbows(w)
        t3 = np.round((f0 + np.arange(len(e3["elbow_L"]))) * 1000.0 / FPS).astype(np.int64)
        shift = s["shiftMs"]
        lo3, hi3 = s["startMs"] - MARGIN_MS, s["endMs"] + MARGIN_MS
        m = (tc >= s["startMs"] + shift - MARGIN_MS) & (tc <= s["endMs"] + shift + MARGIN_MS)
        fl = np.round(tc[m] * FPS / 1000.0).astype(int) - s["lagFrames"] - f0
        okf = (fl >= 0) & (fl < len(e3["elbow_L"]))
        rr = {}
        for a in L.SIDES:
            for b in L.SIDES:
                x, y = Vc[f"elbow_{a}"][m][okf], e3[f"elbow_{b}"][fl[okf]]
                g = np.isfinite(x) & np.isfinite(y)
                rr[a + b] = float(np.corrcoef(x[g], y[g])[0, 1]) if g.sum() >= 10 else np.nan
        swapped = bool(np.nanmean([rr["LR"], rr["RL"]]) > np.nanmean([rr["LL"], rr["RR"]]))
        e3u = {"elbow_L": e3["elbow_R"], "elbow_R": e3["elbow_L"]} if swapped else e3
        reps, mode = segment(e3u, t3, lo3, hi3, s["capture"])
        # 2D 피처
        ok = cap.det[:, None] & (cap.vis >= L.MIN_VIS)
        P = cap.xy.astype(float) * np.array([cap.wa, cap.ha])
        P[~ok] = np.nan
        F = B.feat2d(P)
        # 시작(개인 기준) 값 — 팔별: [그 팔 첫 하강 − 3 s, 그 팔 2번째 반복 하강 시작] 의 서 있음(≥ 140°) · 정면 프레임 중앙값
        #   = 앱이 '첫 상단 창'(§62a) 으로 잡을 수 있는 구간. 세트 전 3 s 만 쓰면 자리 잡는 동작(옆으로 선 프레임, 어깨 x 간격 ÷ 몸통 0.1~0.2)이 섞인다.
        frontal = F["sh_wx_torso"] >= 0.35
        r0 = min((r.t_d0 + shift for r in reps), default=int(s["startMs"] + shift))
        start, n_start, start_pre = {}, {}, {}
        for a in L.SIDES:
            mine = [r for r in reps if a in r.parts]
            t_lo = (mine[0].t_d0 + shift - 3300) if mine else r0 - 3300
            t_hi = (mine[1].t_d0 + shift) if len(mine) >= 2 else (mine[0].t_top_a + shift if mine else r0)
            msk = B.start_mask_window(tc, Vc[f"elbow_{a}"], cap.det, frontal, t_lo, t_hi)
            start[a], n_start[a] = B.start_values(F, B.KEYS, a, msk)
            pre, _ = B.start_values(F, B.KEYS, a, B.start_mask_window(tc, Vc[f"elbow_{a}"], cap.det, frontal, r0 - 3300, r0 - 300))
            start_pre[a] = pre
        start_common, _ = B.start_values(F, B.KEYS_COMMON, "", B.start_mask_window(tc, np.fmin(Vc["elbow_L"], Vc["elbow_R"]), cap.det, frontal, r0 - 3300,
                                                                                      (reps[1].t_d0 + shift) if len(reps) >= 2 else r0))
        set_rows.append({"set": s["capture"], "workout": w, "subject": s["subject"], "truth": s["truthReps"], "found": len(reps), "mode": mode,
                         "swapped": swapped, "n_start_L": n_start["L"], "n_start_R": n_start["R"],
                         **{f"start_{k}_{a}": start[a][k] for a in L.SIDES for k in ("lat", "rise", "wrist_h", "fa_ua", "ua_len")},
                         **{f"pre_{k}_{a}": start_pre[a][k] for a in L.SIDES for k in ("lat", "rise", "wrist_h")}})
        for i, r in enumerate(reps):
            row = {"set": s["capture"], "workout": w, "subject": s["subject"], "rep": i + 1, "arm": r.arm, "mode": mode, "t_min": r.t_min + shift,
                   "ref3d_min": r.ref_min, "ref3d_top_b": r.ref_top_b, "ref3d_amp_b": r.ref_top_b - r.ref_min,
                   "cycle_s": (r.t_top_a - r.t_top_b) / 1000.0}
            row.update(L.rep_values(Vc, tc, r, shift_ms=shift))          # min / ext_b / amp_b (월드각, 창 안 자기 표본) 등
            for a, p in r.parts.items():
                win = (tc >= t3[p.i_top_b] + shift) & (tc <= t3[p.i_top_a] + shift) & cap.det
                i_min = B.contraction_idx(Vc[f"elbow_{a}"], win)
                row.update(B.rep_stats(F, B.KEYS, a, win, i_min, start[a]))
                row.update(B.rep_stats(F, B.KEYS_COMMON, "", win, i_min, start_common))
                row[f"n_start_{a}"] = n_start[a]
            rep_rows.append(row)
    return rep_rows, set_rows


def rom_grid(rows):
    by_set = defaultdict(list)
    for r in rows:
        if np.isfinite(r.get("min", np.nan)):
            by_set[r["set"]].append(r)
    for k in by_set:
        by_set[k].sort(key=lambda r: r["t_min"])
    truth = {}
    n_reps = sum(len(v) for v in by_set.values())
    out = []

    def arm_val(r, base):
        return r.get(f"{base}_{r['arm']}", np.nan) if r["arm"] in ("L", "R") else np.nanmax([r.get(f"{base}_L", np.nan), r.get(f"{base}_R", np.nan)])

    def tally(name, passfn, kind, extra=None):
        kept, exact = 0, 0
        per = defaultdict(lambda: [0, 0])
        for sk, reps in by_set.items():
            k = sum(1 for r in reps if passfn(r, reps))
            kept += k
            if k == len(reps):
                exact += 1
            per[reps[0]["subject"]][0] += k
            per[reps[0]["subject"]][1] += len(reps)
        row = {"gate": name, "kind": kind, "recall": kept / max(n_reps, 1), "set_all_kept": exact / max(len(by_set), 1), "kept": kept, "n_reps": n_reps, "n_sets": len(by_set)}
        rec = {s: a / max(b, 1) for s, (a, b) in per.items()}
        row["subject_min"] = min(rec.values())
        row["subject_argmin"] = min(rec, key=rec.get)
        for s, v in sorted(rec.items()):
            row[f"recall_{s}"] = v
        if extra:
            row.update(extra)
        out.append(row)

    tally("none(분할만)", lambda r, reps: True, "none")
    for ratio in RATIOS:
        def pfa(r, reps, ratio=ratio):
            same = [q for q in reps if q["arm"] == r["arm"]][:2]
            ref = float(np.nanmedian([q["amp_b"] for q in same])) if same else np.nan
            return np.isfinite(ref) and r["amp_b"] >= ratio * ref
        tally(f"월드 진폭 ≥ {ratio}×같은 팔 첫2회", pfa, "rel_arm", {"ratio": ratio})
        def pf3(r, reps, ratio=ratio):
            ref = float(np.nanmedian([q["amp_b"] for q in reps[:3]]))
            return r["amp_b"] >= ratio * ref
        tally(f"월드 진폭 ≥ {ratio}×첫3회", pf3, "rel_amp3", {"ratio": ratio})
        # 2D: 손목 상승(수축 − 시작) 비율, 같은 팔 첫 2회 기준
        def pfw(r, reps, ratio=ratio):
            same = [q for q in reps if q["arm"] == r["arm"]][:2]
            ref = float(np.nanmedian([arm_val(q, "wrist_h_d") for q in same])) if same else np.nan
            v = arm_val(r, "wrist_h_d")
            return np.isfinite(ref) and np.isfinite(v) and v >= ratio * ref
        tally(f"2D 손목 상승 ≥ {ratio}×같은 팔 첫2회", pfw, "rel_wrist", {"ratio": ratio})
        # 2D: 손목 창 진폭(창 최대 − 창 최소) 비율
        def pfw2(r, reps, ratio=ratio):
            same = [q for q in reps if q["arm"] == r["arm"]][:2]
            amp = lambda q: arm_val(q, "wrist_h_max") - arm_val(q, "wrist_h_min")  # noqa: E731
            ref = float(np.nanmedian([amp(q) for q in same])) if same else np.nan
            v = amp(r)
            return np.isfinite(ref) and np.isfinite(v) and v >= ratio * ref
        tally(f"2D 손목 창 진폭 ≥ {ratio}×같은 팔 첫2회", pfw2, "rel_wrist_amp", {"ratio": ratio})
    for T in TOP_T:
        tally(f"위 끝: 수축 손목 높이 ≥ {T}", lambda r, reps, T=T: arm_val(r, "wrist_h_c") >= T, "top_abs", {"T": T})
    for T in BOT_T:
        tally(f"아래 끝: 창 안 손목 최저 ≤ {T}", lambda r, reps, T=T: arm_val(r, "wrist_h_min") <= T, "bot_abs", {"T": T})
    for Tt in (-0.30, -0.25, -0.20):
        for Tb in (-0.85, -0.80, -0.75):
            tally(f"위 ≥ {Tt} & 아래 ≤ {Tb}", lambda r, reps, Tt=Tt, Tb=Tb: arm_val(r, "wrist_h_c") >= Tt and arm_val(r, "wrist_h_min") <= Tb, "top_bot_abs", {"T": Tt, "Tb": Tb})
    return out


def fidelity(rows):
    out = []
    reps = [r for r in rows if np.isfinite(r.get("ref3d_min", np.nan)) and np.isfinite(r.get("min", np.nan))]
    subj = np.array([r["subject"] for r in reps])

    def arm_val(r, base):
        return r.get(f"{base}_{r['arm']}", np.nan) if r["arm"] in ("L", "R") else np.nan
    truths = {"ref3d_min(수축각)": np.array([r["ref3d_min"] for r in reps]), "ref3d_amp(진폭)": np.array([r["ref3d_amp_b"] for r in reps])}
    cands = {"MP 월드 수축 최소각": np.array([r["min"] for r in reps]), "MP 월드 진폭": np.array([r["amp_b"] for r in reps]),
             "2D 손목 높이 수축 wrist_h_c": np.array([arm_val(r, "wrist_h_c") for r in reps]),
             "2D 손목 상승 wrist_h_c−start": np.array([arm_val(r, "wrist_h_d") for r in reps]),
             "2D 손목 창 진폭 max−min": np.array([arm_val(r, "wrist_h_max") - arm_val(r, "wrist_h_min") for r in reps]),
             "2D 전완/상완 수축 fa_ua_c": np.array([arm_val(r, "fa_ua_c") for r in reps]),
             "2D 전완/상완 창 최소": np.array([arm_val(r, "fa_ua_min") for r in reps]),
             "2D 팔꿈치 높이 수축 rise_c": np.array([arm_val(r, "rise_c") for r in reps])}
    for tn, tv in truths.items():
        for cn, cv in cands.items():
            m = np.isfinite(tv) & np.isfinite(cv)
            if m.sum() < 30:
                continue
            rho = float(spearmanr(cv[m], tv[m])[0])
            import pandas as pd  # noqa: PLC0415
            cw = cv[m] - pd.Series(cv[m]).groupby(subj[m]).transform("mean").to_numpy()
            tw = tv[m] - pd.Series(tv[m]).groupby(subj[m]).transform("mean").to_numpy()
            rhow = float(spearmanr(cw, tw)[0]) if np.std(cw) > 0 else np.nan
            per = []
            for sname in sorted(set(subj[m])):
                mm = m & (subj == sname)
                if mm.sum() >= 15:
                    per.append(float(spearmanr(cv[mm], tv[mm])[0]))
            p = B.pct(cv[m])
            out.append({"truth": tn, "cand": cn, "n": int(m.sum()), "rho": rho, "rho_within": rhow, "subject_rho_med": float(np.median(per)) if per else np.nan,
                        "subject_rho_min": min(per) if per else np.nan, "p5": p[0], "p50": p[1], "p95": p[2]})
    return out


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rep_rows, set_rows = process()
    B.write_csv(OUT / "mmfit_reps.csv", rep_rows)
    B.write_csv(OUT / "mmfit_sets.csv", set_rows)
    print(f"[mmfit] 세트 {len(set_rows)}, 반복 {len(rep_rows)}, 분할 = 정답 {sum(1 for s in set_rows if s['found'] == s['truth'])}, "
          f"시작 프레임 수 중앙 L {np.median([s['n_start_L'] for s in set_rows]):.0f} · R {np.median([s['n_start_R'] for s in set_rows]):.0f}, "
          f"< 2 인 팔 {sum(1 for s in set_rows for a in 'LR' if s[f'n_start_{a}'] < 2)}")
    good = [r for r in rep_rows if np.isfinite(r.get("min", np.nan))]
    drows = B.dist_rows(good, ["lat_c", "lat_d", "lat_max", "lat_dmax", "lat_s", "rise_c", "rise_d", "rise_max", "rise_dmax", "rise_s", "wrist_h_c", "wrist_h_s", "wrist_h_max",
                               "wrist_h_min", "wrist_h_d", "fa_ua_c", "fa_ua_s", "fa_ua_min", "ua_len_c", "ua_len_s", "ua_out_c", "ua_out_d"], "subject", "mmfit_cap300")
    B.write_csv(OUT / "mmfit_dist.csv", drows)
    brows = B.band_rates(good, "subject")
    for r in brows:
        r["source"] = "mmfit_cap300"
    B.write_csv(OUT / "mmfit_bands.csv", brows)
    B.write_csv(OUT / "mmfit_rom_grid.csv", rom_grid(good))
    B.write_csv(OUT / "mmfit_rom_fidelity.csv", fidelity(good))
    print("[done]", OUT)


if __name__ == "__main__":
    main()

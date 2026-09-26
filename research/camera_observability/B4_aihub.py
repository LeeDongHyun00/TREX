#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""B4 — AIHub 덤벨 컬 정면(C) 뷰 MediaPipe 2D 단서의 정상 분포 · '팔꿈치 위치 고정' 누설(AUC) · 가동 범위 2D 대리.

입력(읽기 전용)
  aihub_fitness/outputs/{clips,conditions,kp3d,kp3d_frame_ok,kp2d}.parquet
  camera_observability/outputs/B1/cameras.parquet, outputs/B1/mp/lm_*_full.parquet (B1_mp_infer, IMAGE 모드, 1920×1080)
출력  outputs/B4/aihub_clips.csv(클립별 값) · aihub_dist.csv · aihub_bands.csv · aihub_auc.csv · aihub_rom.csv

반복 단위 = 클립(16프레임, 여러 반복의 성긴 표본). 수축 = GT 3D 팔꿈치각 평균이 가장 낮은 ≤ 3프레임의 중앙값,
이완(= '시작' 대용) = 가장 높은 ≤ 3프레임의 중앙값. 서 있음 구간이 클립에 없어 '시작' 은 이완 프레임으로 대신한다.
"""
from __future__ import annotations

import glob
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import spearmanr
from sklearn.metrics import roc_auc_score

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "aihub_fitness"))
from parse_labels import JOINTS_SHORT, KP3D_COLS  # noqa: E402
import B4_lib as B  # noqa: E402

SRC = HERE.parent / "aihub_fitness" / "outputs"
B1 = HERE / "outputs" / "B1"
OUT = B.OUT
EXERCISE = "덤벨 컬"
T = 16
MIN_VIS = 0.5
MIN_ROM = 60.0
GJ = {n: i for i, n in enumerate(JOINTS_SHORT)}
COND_ELBOW, COND_SHRUG, COND_SPINE, COND_TENSION = "팔꿈치 위치 고정", "수축 시 어깨 으쓱 없음", "척추의 중립", "이완 시 팔 긴장 유지"


def ang3(a, b, c):
    u, w = a - b, c - b
    with np.errstate(all="ignore"):
        cs = (u * w).sum(-1) / np.linalg.norm(u, axis=-1) / np.linalg.norm(w, axis=-1)
    return np.degrees(np.arccos(np.clip(cs, -1, 1)))


def load():
    clips = pd.read_parquet(SRC / "clips.parquet", columns=["clip_id", "exercise", "performer", "day"])
    sel = clips[clips.exercise == EXERCISE].sort_values("clip_id").reset_index(drop=True)
    ids = sel.clip_id.tolist()
    cix = {c: i for i, c in enumerate(ids)}
    N = len(ids)
    k3 = pd.read_parquet(SRC / "kp3d.parquet", filters=[("clip_id", "in", ids)])
    qc = pd.read_parquet(SRC / "kp3d_frame_ok.parquet", filters=[("clip_id", "in", ids)])
    k3 = k3.merge(qc[["clip_id", "frame_idx", "ok"]], on=["clip_id", "frame_idx"], how="left")
    A = np.full((N, T, 24, 3), np.nan)
    ci, fi = k3.clip_id.map(cix).to_numpy(), k3.frame_idx.to_numpy().astype(int)
    A[ci, fi] = k3[KP3D_COLS].to_numpy(float).reshape(-1, 24, 3)
    ok = np.zeros((N, T), bool)
    ok[ci, fi] = k3.ok.fillna(False).to_numpy()
    A[~ok] = np.nan
    k2 = pd.read_parquet(SRC / "kp2d.parquet", filters=[("clip_id", "in", ids)], columns=["clip_id", "frame_idx", "view_letter", "img_key"])
    k2 = k2[(k2.img_key != "") & (k2.view_letter == "C")]
    keymap = k2[["img_key", "clip_id", "frame_idx"]].copy()
    keymap["ci"] = keymap.clip_id.map(cix)
    conds = pd.read_parquet(SRC / "conditions.parquet")
    conds = conds[conds.clip_id.isin(ids)].pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first").reindex(ids)
    cam = pd.read_parquet(B1 / "cameras.parquet")
    cam = cam[(cam.view_letter == "C") & cam.clip_id.isin(ids)]
    okv = np.zeros(N, bool)
    for r in cam.itertuples():
        okv[cix[r.clip_id]] = bool(r.cam_ok) and (r.view_actual == "C")
    # MediaPipe C 뷰
    files = sorted(glob.glob(str(B1 / "mp" / "lm_*_full.parquet")))
    df = pd.concat([pd.read_parquet(f) for f in files], ignore_index=True).merge(keymap, on="img_key", how="inner")
    X = np.full((N, T, 33, 2), np.nan)
    DET = np.zeros((N, T), bool)
    ci, fi = df.ci.to_numpy(), df.frame_idx.to_numpy().astype(int)
    DET[ci, fi] = df.detected.to_numpy().astype(bool)
    x = df[[f"l{i}_x" for i in range(33)]].to_numpy(float)
    y = df[[f"l{i}_y" for i in range(33)]].to_numpy(float)
    v = np.minimum(df[[f"l{i}_v" for i in range(33)]].to_numpy(float), df[[f"l{i}_p" for i in range(33)]].to_numpy(float))
    P = np.stack([x, y], -1)
    P[v < MIN_VIS] = np.nan
    X[ci, fi] = P
    X[~DET] = np.nan
    return sel, ids, A, ok, okv, conds, X, DET


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    sel, ids, A, ok, okv, conds, X, DET = load()
    N = len(ids)
    sess = (sel.performer + "@" + sel.day).to_numpy()
    g = lambda n: A[:, :, GJ[n], :]  # noqa: E731
    eL = ang3(g("LShoulder"), g("LElbow"), g("LWrist"))
    eR = ang3(g("RShoulder"), g("RElbow"), g("RWrist"))
    em = (eL + eR) / 2
    rng = np.nanmax(em, 1) - np.nanmin(em, 1)
    valid = np.isfinite(rng) & (rng >= MIN_ROM) & (np.isfinite(em).sum(1) >= 8)
    F = B.feat2d(X)                                  # (N, T)
    use = ok & DET & okv[:, None]
    rows = []
    for i in range(N):
        if not valid[i] or not okv[i]:
            continue
        u = use[i] & np.isfinite(em[i])
        if u.sum() < 8:
            continue
        order = np.argsort(np.where(u, em[i], np.inf))
        n_u = int(u.sum())
        c_idx = order[:3]
        e_idx = order[n_u - 3:n_u]
        row = {"clip_id": ids[i], "session": sess[i], "performer": sel.performer[i], "n_use": n_u,
               "gt_elbow_c": float(np.median(em[i][c_idx])), "gt_elbow_e": float(np.median(em[i][e_idx])),
               "gt_elbow_c1": float(em[i][order[0]]), "gt_elbow_min": float(np.nanmin(em[i][u])), "gt_elbow_max": float(np.nanmax(em[i][u])),
               "gt_elbow_L_c": float(np.median(eL[i][c_idx])), "gt_elbow_R_c": float(np.median(eR[i][c_idx]))}
        for c in (COND_ELBOW, COND_SHRUG, COND_SPINE, COND_TENSION):
            row[c] = bool(conds[c].iloc[i])
        for b, s in [(b, s) for s in ("L", "R") for b in B.KEYS] + [(k, "") for k in B.KEYS_COMMON]:
            suf = f"_{s}" if s else ""
            x = F[f"{b}{suf}"][i]
            xc, xe = x[c_idx], x[e_idx]
            xu = np.where(u, x, np.nan)
            row[f"{b}_c{suf}"] = float(np.nanmedian(xc)) if np.isfinite(xc).any() else np.nan
            row[f"{b}_c1{suf}"] = float(x[order[0]])
            row[f"{b}_s{suf}"] = float(np.nanmedian(xe)) if np.isfinite(xe).any() else np.nan     # '시작' 대용 = 이완
            row[f"{b}_d{suf}"] = row[f"{b}_c{suf}"] - row[f"{b}_s{suf}"]
            row[f"{b}_max{suf}"] = float(np.nanmax(xu)) if np.isfinite(xu).any() else np.nan
            row[f"{b}_min{suf}"] = float(np.nanmin(xu)) if np.isfinite(xu).any() else np.nan
            row[f"{b}_dmax{suf}"] = row[f"{b}_max{suf}"] - row[f"{b}_s{suf}"]
        rows.append(row)
    print(f"[aihub] 클립 {N}, 반복 판정 가능 {int(valid.sum())}, C 실제 정면 {int(okv.sum())}, 분석 {len(rows)}")
    B.write_csv(OUT / "aihub_clips.csv", rows)

    normsets = {
        "elbow_shrug_spine_true": [r for r in rows if r[COND_ELBOW] and r[COND_SHRUG] and r[COND_SPINE]],
        "elbow_true": [r for r in rows if r[COND_ELBOW]],
        "all_valid": rows,
        "elbow_false(팔꿈치 내밀기)": [r for r in rows if not r[COND_ELBOW]],
    }
    drows, brows = [], []
    for name, rs in normsets.items():
        for r in B.dist_rows(rs, ["lat_c", "lat_d", "lat_max", "lat_dmax", "lat_s", "rise_c", "rise_d", "rise_max", "rise_dmax", "rise_s", "wrist_h_c", "wrist_h_s", "wrist_h_max",
                                  "fa_ua_c", "fa_ua_s", "ua_len_c", "ua_len_s", "ua_out_c", "ua_out_d"], "performer", f"aihub_C:{name}"):
            r["n_clips"] = len(rs)
            drows.append(r)
        for r in B.band_rates(rs, "performer"):
            r["source"] = f"aihub_C:{name}"
            r["n_clips"] = len(rs)
            brows.append(r)
    B.write_csv(OUT / "aihub_dist.csv", drows)
    B.write_csv(OUT / "aihub_bands.csv", brows)

    # ---- 다른 조건 위반이 정면 2D 반동·벌림 단서를 얼마나 움직이나 (교차 설계: 팔꿈치 고정 충족 안에서 조건 X 만 비교)
    crows = []
    conds_all = [c for c in conds.columns]
    for cx in conds_all:
        if cx == COND_ELBOW:
            continue
        for val in (True, False):
            rs = [r for r in rows if r[COND_ELBOW] and bool(conds[cx].loc[r["clip_id"]]) == val]
            if len(rs) < 20:
                continue
            for k in ("rise_d", "rise_c", "rise_dmax", "lat_d", "lat_c", "wrist_h_c", "wrist_h_max", "ua_len_c"):
                v = B.rep_level(rs, k)
                p = B.pct(v)
                crows.append({"condition": cx, "value": val, "n": len(rs), "cand": k, "p5": p[0], "p50": p[1], "p95": p[2], "p99": p[3],
                              "rate_rise_d≥0.10": float(np.nanmean(B.rep_level(rs, "rise_d") >= 0.10)),
                              "rate_rise_d≥0.15": float(np.nanmean(B.rep_level(rs, "rise_d") >= 0.15)),
                              "rate_flare_0.15_0.30": float(np.nanmean((B.rep_level(rs, "lat_d") >= 0.15) & (B.rep_level(rs, "lat_c") >= 0.30)))})
    B.write_csv(OUT / "aihub_cond_effect.csv", crows)

    # ---- '팔꿈치 위치 고정' 위반이 정면 2D 에 새는 정도 (AUC 원값 / 세션 중심화)
    y = np.array([0.0 if r[COND_ELBOW] else 1.0 for r in rows])
    s = np.array([r["session"] for r in rows])
    arows = []
    cands = []
    for base in ("rise", "lat", "wrist_h", "ua_len", "fa_ua", "ua_out", "wrist_lat"):
        for st in ("c", "c1", "d", "max", "dmax", "min", "s"):
            cands.append((base, st))
    for base, st in cands:
        for how in ("max", "min", "mean"):
            x = B.rep_level(rows, f"{base}_{st}", how)
            m = np.isfinite(x)
            if m.sum() < 30:
                continue
            xc = x[m] - pd.Series(x[m]).groupby(s[m]).transform("mean").to_numpy()
            auc = float(roc_auc_score(y[m], x[m]))
            aucw = float(roc_auc_score(y[m], xc))
            sign = 1 if auc >= 0.5 else -1
            xs = sign * x[m]
            thr = np.percentile(xs[y[m] == 0], 95)
            arows.append({"cand": f"{base}_{st}", "arms": how, "n": int(m.sum()), "n_pos": int(y[m].sum()), "auc": auc, "auc_within": aucw,
                          "direction": "+" if sign > 0 else "−", "auc_signed": max(auc, 1 - auc), "tpr_at_fpr05": float(np.mean(xs[y[m] == 1] > thr)),
                          "normal_p50": float(np.median(x[m][y[m] == 0])), "error_p50": float(np.median(x[m][y[m] == 1]))})
    for k in ("elbow_gap_c", "elbow_gap_max", "elbow_gap_d", "elbow_gap_x_c", "elbow_gap_x_max"):
        x = np.array([r.get(k, np.nan) for r in rows], float)
        m = np.isfinite(x)
        xc = x[m] - pd.Series(x[m]).groupby(s[m]).transform("mean").to_numpy()
        auc = float(roc_auc_score(y[m], x[m]))
        arows.append({"cand": k, "arms": "-", "n": int(m.sum()), "n_pos": int(y[m].sum()), "auc": auc, "auc_within": float(roc_auc_score(y[m], xc)),
                      "direction": "+" if auc >= 0.5 else "−", "auc_signed": max(auc, 1 - auc), "tpr_at_fpr05": np.nan,
                      "normal_p50": float(np.median(x[m][y[m] == 0])), "error_p50": float(np.median(x[m][y[m] == 1]))})
    arows.sort(key=lambda r: -r["auc_signed"])
    B.write_csv(OUT / "aihub_auc.csv", arows)
    # ---- '으쓱' 라벨(어깨 개입의 대용)이 정면 2D 반동 단서로 얼마나 잡히나 — 팔꿈치 고정·척추 중립 충족 클립 안에서
    srows = []
    sub = [r for r in rows if r[COND_ELBOW] and r[COND_SPINE]]
    ys = np.array([0.0 if r[COND_SHRUG] else 1.0 for r in sub])
    ss = np.array([r["session"] for r in sub])
    for base, st in [(b, s_) for b in ("rise", "wrist_h", "lat", "ua_len") for s_ in ("c", "d", "max", "dmax")]:
        x = B.rep_level(sub, f"{base}_{st}", "max")
        m = np.isfinite(x)
        if m.sum() < 30:
            continue
        auc = float(roc_auc_score(ys[m], x[m]))
        xc = x[m] - pd.Series(x[m]).groupby(ss[m]).transform("mean").to_numpy()
        sign = 1 if auc >= 0.5 else -1
        xs = sign * x[m]
        thr = np.percentile(xs[ys[m] == 0], 95)
        srows.append({"cand": f"{base}_{st}", "n": int(m.sum()), "n_pos": int(ys[m].sum()), "auc": auc, "auc_within": float(roc_auc_score(ys[m], xc)),
                      "direction": "+" if sign > 0 else "−", "tpr_at_fpr05": float(np.mean(xs[ys[m] == 1] > thr)),
                      "normal_p50": float(np.median(x[m][ys[m] == 0])), "normal_p95": float(np.percentile(x[m][ys[m] == 0], 95)), "error_p50": float(np.median(x[m][ys[m] == 1])),
                      "tpr_rise_dmax≥0.15": float(np.mean(B.rep_level(sub, "rise_dmax")[m & (ys == 1)] >= 0.15)) if base == "rise" and st == "dmax" else np.nan,
                      "fpr_rise_dmax≥0.15": float(np.mean(B.rep_level(sub, "rise_dmax")[m & (ys == 0)] >= 0.15)) if base == "rise" and st == "dmax" else np.nan})
    srows.sort(key=lambda r: -max(r["auc"], 1 - r["auc"]))
    B.write_csv(OUT / "aihub_shrug_auc.csv", srows)

    # ---- 가동 범위 2D 대리: GT 수축각·진폭 vs 2D (정상 = 팔꿈치 고정·긴장 유지 충족)
    rrows = []
    norm = [r for r in rows if r[COND_ELBOW] and r[COND_TENSION]]
    for label, rs in (("elbow&tension_true", norm), ("all_valid", rows)):
        gc = np.array([r["gt_elbow_c"] for r in rs])
        ga = np.array([r["gt_elbow_e"] - r["gt_elbow_c"] for r in rs])
        ss = np.array([r["session"] for r in rs])
        for cand in ("wrist_h_c", "wrist_h_max", "wrist_h_d", "fa_ua_c", "fa_ua_min", "ua_len_c", "rise_c", "wrist_lat_c"):
            for how in ("mean", "max", "min"):
                x = B.rep_level(rs, cand, how)
                for tname, tv in (("gt_elbow_c", gc), ("gt_amp", ga)):
                    m = np.isfinite(x) & np.isfinite(tv)
                    if m.sum() < 30:
                        continue
                    rho = float(spearmanr(x[m], tv[m])[0])
                    xw = x[m] - pd.Series(x[m]).groupby(ss[m]).transform("mean").to_numpy()
                    tw = tv[m] - pd.Series(tv[m]).groupby(ss[m]).transform("mean").to_numpy()
                    rhow = float(spearmanr(xw, tw)[0]) if np.std(xw) > 0 else np.nan
                    rrows.append({"normset": label, "cand": cand, "arms": how, "truth": tname, "n": int(m.sum()), "rho": rho, "rho_within": rhow,
                                  "p5": float(np.percentile(x[m], 5)), "p50": float(np.percentile(x[m], 50)), "p95": float(np.percentile(x[m], 95))})
        # 사람 간 / 사람 안 (GT 수축각, 2D 손목 높이)
        for cand, vals in (("gt_elbow_c", gc), ("wrist_h_c(mean)", B.rep_level(rs, "wrist_h_c", "mean")), ("wrist_h_d(mean)", B.rep_level(rs, "wrist_h_d", "mean")),
                           ("fa_ua_c(mean)", B.rep_level(rs, "fa_ua_c", "mean"))):
            m = np.isfinite(vals)
            sr = pd.Series(vals[m]).groupby(ss[m])
            rrows.append({"normset": label, "cand": cand, "arms": "-", "truth": "between/within", "n": int(m.sum()), "rho": np.nan, "rho_within": np.nan,
                          "p5": float(np.percentile(vals[m], 5)), "p50": float(np.percentile(vals[m], 50)), "p95": float(np.percentile(vals[m], 95)),
                          "between_sd": float(sr.mean().std()), "within_sd": float(np.sqrt(sr.var(ddof=1).mean())), "n_sessions": sr.ngroups})
    B.write_csv(OUT / "aihub_rom.csv", rrows)
    print("[done]", OUT)


if __name__ == "__main__":
    main()

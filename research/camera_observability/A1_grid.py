# -*- coding: utf-8 -*-
"""A1 격자 실행 — 배치 × 잡음마다 2D 후보·지면 역투영(GPL)을 3D 진실과 비교한다.

격자(기본): 방위 0, ±20, ±40, ±60, ±90 (+ = 사용자 왼쪽) × 높이 0.05, 0.15, 0.3, 0.6, 1.0, 1.4 m
           × 거리 2, 2.5, 3, 4 m × 세로 화각 68°, 100° × 가로 위치 중앙/30% × 잡음 σ 0, 2, 4, 8 px
  - 0.15 m 는 요청 격자 밖에서 더했다: 바닥에 세로로 세운 폰의 실제 렌즈 높이(앱의 기본 사용 형태).
GPL 민감도(별도): 피치 오차 ±2°, 카메라 높이 ×1.2 — 방위 0/±40/±90, d 2.5, 68°, 중앙, σ 0/4.

출력 (outputs/A1/)
  grid_metrics.parquet 배치×σ×변수×후보: n, pearson, spearman, bias(중앙값), mae, rmse, cal_rmse, slope, sign_agree, within_rho, amp_ratio
  grid_auc.parquet   배치×σ×조건×후보 AUC (방향은 3D 진실 기준으로 고정 — 뒤집히면 0.5 아래로 보인다)
  grid_configs.csv   배치별 전신 프레이밍 비율·몸 높이(px)·피치
  gt_reference.csv   3D 진실 분포·조건 AUC(GT)
"""
from __future__ import annotations

import itertools
import os
import sys
import time
import warnings
from concurrent.futures import ProcessPoolExecutor

import numpy as np
import pandas as pd
from scipy.stats import pearsonr, spearmanr
from sklearn.metrics import roc_auc_score

warnings.filterwarnings("ignore")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import A1_common as C  # noqa: E402

AZ = [0, 20, -20, 40, -40, 60, -60, 90, -90]
HS = [0.05, 0.15, 0.3, 0.6, 1.0, 1.4]
DS = [2.0, 2.5, 3.0, 4.0]
FOVS = [68, 100]
EDGES = [False, True]
SIGMAS = [0, 2, 4, 8]

SAME_UNIT = {  # 후보가 진실과 같은 단위인가 (편향·MAE 의미)
    ("R_hip_drop", "img_v"), ("R_depth", "img_angle"), ("R_depth", "v_ratio"), ("R_knee_min", "img_angle"),
    ("R_foot_drift", "sh_norm"), ("F_spine", "img_curv"), ("T_lean", "img_angle"), ("T_lean", "img_angle_imu"),
    ("T_lean", "foreshort"), ("F_hip_first_dlean", "img_angle"), ("F_hip_first_dlean", "img_angle_imu"),
    ("F_hip_first_dlean", "foreshort"), ("F_hip_first_ratio", "img_v"), ("F_lat_pelvis_tilt", "img"),
    ("F_lat_shift", "img"), ("F_lat_knee_asym", "img"), ("S_toe", "img_angle"), ("S_toe", "body_prop"),
    ("S_stance", "x_ratio"), ("S_stance", "euclid"), ("R_knee_min", "recon2d"), ("F_knee_foot", "recon2d"),
    ("F_lat_knee_asym", "recon2d"),
}
SIGNED = {"F_knee_foot": 3.0, "F_lat_pelvis_tilt": 0.01, "F_lat_shift": 0.01, "F_lat_knee_asym": 1.0, "S_toe": 3.0}
SIGN_CANDS = {"fppa", "knee_out2d", "d_knee_out2d", "img", "img_angle", "body_prop", "gpl", "recon2d"}
FRAME_VARS = ("R_hip_drop", "R_depth", "R_knee_min", "T_lean")
# 조건 → (진실 변수, 후보 변수들)
AUC_MAP = {
    "knee": ["F_knee_foot", "knee_out3d"],
    "heel": ["F_heel"],
    "spine": ["F_spine", "T_lean"],
}

_G = {}


def _init():
    D = C.load_squat()
    sc = C.build_scene(D["arr"])
    tr = C.truth(sc)
    k_foot = float(np.nanmedian(C.TOE_LEN_H / tr["rep"]["_legS"]))
    rng = np.random.default_rng(20260925)
    noise = rng.standard_normal(sc.P.shape[:3] + (2,))
    # 조건 AUC 방향: 3D 진실 기준
    dirs = {}
    for cond, vars_ in AUC_MAP.items():
        y = D["viol"][cond]
        for v in vars_:
            x = tr["rep"][v]
            m = np.isfinite(x)
            a = roc_auc_score(y[m], x[m])
            dirs[(cond, v)] = 1.0 if a >= 0.5 else -1.0
    _G.update(D=D, sc=sc, tr=tr, k_foot=k_foot, noise=noise, dirs=dirs)


def row_spearman(a, b):
    """행별 순위상관 (NaN 쌍 제외)."""
    m = np.isfinite(a) & np.isfinite(b)
    a = np.where(m, a, np.inf)
    b = np.where(m, b, np.inf)
    ra = np.argsort(np.argsort(a, 1), 1).astype(float)
    rb = np.argsort(np.argsort(b, 1), 1).astype(float)
    ra[~m] = np.nan
    rb[~m] = np.nan
    ra -= np.nanmean(ra, 1, keepdims=True)
    rb -= np.nanmean(rb, 1, keepdims=True)
    num = np.nansum(ra * rb, 1)
    den = np.sqrt(np.nansum(ra * ra, 1) * np.nansum(rb * rb, 1))
    out = np.where((den > 0) & (m.sum(1) >= 5), num / np.where(den > 0, den, 1), np.nan)
    return out


def metrics(var, cand, t, e, same_unit):
    m = np.isfinite(t) & np.isfinite(e)
    n = int(m.sum())
    r = dict(var=var, cand=cand, n=n)
    if n < 20:
        return r
    t, e = t[m], e[m]
    r["pearson"] = float(pearsonr(t, e)[0]) if np.std(e) > 0 else np.nan
    r["spearman"] = float(spearmanr(t, e)[0]) if np.std(e) > 0 else np.nan
    # 선형 보정 후 잔차 SD (진실 단위) 와 기울기(2D 가 진실 1 단위에 몇 단위 움직이나)
    if np.std(e) > 0:
        A = np.vstack([e, np.ones_like(e)]).T
        coef, *_ = np.linalg.lstsq(A, t, rcond=None)
        r["cal_rmse"] = float(np.sqrt(np.mean((t - A @ coef) ** 2)))
        r["slope"] = float(np.polyfit(t, e, 1)[0])
    r["t_sd"] = float(np.std(t))
    r["e_med"] = float(np.median(e))
    r["t_med"] = float(np.median(t))
    if same_unit:
        d = e - t
        r["bias"] = float(np.median(d))
        r["mae"] = float(np.median(np.abs(d)))
        r["rmse"] = float(np.sqrt(np.mean(d ** 2)))
        r["p90_abs"] = float(np.percentile(np.abs(d), 90))
    if var in SIGNED and (cand[:-4] if cand.endswith("_lvl") else cand) in SIGN_CANDS:
        dz = SIGNED[var]
        mm = np.abs(t) >= dz
        if mm.sum() >= 10:
            r["sign_agree"] = float(np.mean(np.sign(e[mm]) == np.sign(t[mm])))
            r["n_sign"] = int(mm.sum())
    return r


def run_config(args):
    az, h, d, fov, edge, sigmas, variant = args
    sc, tr, D = _G["sc"], _G["tr"], _G["D"]
    cam = C.make_cam(sc, az, h, d, fov, edge)
    uv0, Z = C.project(cam, sc.P)
    # 전신 프레이밍: 모든 관절·가상점이 영상 안 (클립 비율)
    inside = (uv0[..., 0] >= 0) & (uv0[..., 0] <= C.W) & (uv0[..., 1] >= 0) & (uv0[..., 1] <= C.H) & (Z > 0)
    inside |= ~np.isfinite(uv0[..., 0])
    fit = float(inside.all(axis=(1, 2)).mean())
    body_px = float(np.nanmedian(C.mmean(uv0[:, :, C.J["LToe"], 1] - uv0[:, :, C.J["HeadTop"], 1], sc.S)))
    cfg = dict(az=az, h=h, d=d, fov=fov, edge=edge, variant=variant)
    if variant == "base":
        cam_est = cam
    elif variant.startswith("pitch"):
        cam_est = C.make_cam(sc, az, h, d, fov, edge, pitch_err=float(variant[5:]))
    elif variant.startswith("hscale"):
        cam_est = C.make_cam(sc, az, h, d, fov, edge, height_scale=float(variant[6:]))
    rows, arows = [], []
    for sg in sigmas:
        uv = uv0 + sg * _G["noise"]
        est = {}
        if variant == "base":
            f2 = C.feats2d(sc, uv, cam, _G["k_foot"])
            est = {v: dict(c) for v, c in f2["rep"].items()}
            fr = f2["frame"]
            # IMU 피치 보정: 수평 가상 카메라로 회전 보정한 영상에서 같은 후보를 다시 계산 (접미사 _lvl)
            cam_l = C.level_cam(cam)
            fl = C.feats2d(sc, C.rectify(cam, cam_l, uv), cam_l, _G["k_foot"])
            for v, cands in fl["rep"].items():
                for c, x in cands.items():
                    est[v][c + "_lvl"] = x
            for v, cands in fl["frame"].items():
                for c, x in cands.items():
                    fr[v][c + "_lvl"] = x
        else:
            fr = {}
        g = C.lift_gpl(sc, uv, cam_est)
        for v, x in g["rep"].items():
            est.setdefault(v, {})["gpl"] = x
        for v, cands in est.items():
            t = tr["rep"][v]
            for c, e in cands.items():
                cb = c[:-4] if c.endswith("_lvl") else c
                r = metrics(v, c, t, e, (v, cb) in SAME_UNIT or c == "gpl")
                if v in FRAME_VARS:
                    ef = g["frame"].get(v) if c == "gpl" else fr.get(v, {}).get(c)
                    if ef is not None:
                        r["within_rho"] = float(np.nanmedian(row_spearman(tr["frame"][v], ef)))
                if v == "R_hip_drop":   # 진폭 비(2D 하강 ÷ 3D 하강) — 임계 이전 가능성
                    tt = tr["rep"][v]
                    mm = np.isfinite(tt) & np.isfinite(e) & (tt > 0.2)
                    if mm.sum() > 20:
                        r["amp_ratio"] = float(np.median(e[mm] / tt[mm]))
                rows.append({**cfg, "sigma": sg, **r})
        # 조건 AUC
        for cond, vars_ in AUC_MAP.items():
            y = D["viol"][cond]
            for v in vars_:
                if v not in est:
                    continue
                dr = _G["dirs"][(cond, v)]
                for c, e in est[v].items():
                    m = np.isfinite(e)
                    if m.sum() < 50 or len(np.unique(y[m])) < 2:
                        continue
                    arows.append({**cfg, "sigma": sg, "cond": cond, "var": v, "cand": c,
                                  "auc": float(roc_auc_score(y[m], dr * e[m])), "n": int(m.sum())})
    crow = {**cfg, "fit": fit, "body_px": body_px, "pitch_deg": float(np.degrees(np.median(np.arcsin(np.clip(cam.z[:, 1], -1, 1)))))}
    return rows, arows, crow


def gt_reference():
    _init()
    D, tr = _G["D"], _G["tr"]
    rows = []
    for v, x in tr["rep"].items():
        if v.startswith("_"):
            continue
        rows.append(dict(kind="dist", var=v, n=int(np.isfinite(x).sum()), p5=np.nanpercentile(x, 5), p25=np.nanpercentile(x, 25),
                         p50=np.nanpercentile(x, 50), p75=np.nanpercentile(x, 75), p95=np.nanpercentile(x, 95), sd=np.nanstd(x)))
    for cond, vars_ in AUC_MAP.items():
        y = D["viol"][cond]
        for v in vars_:
            x = tr["rep"][v]
            m = np.isfinite(x)
            a = roc_auc_score(y[m], _G["dirs"][(cond, v)] * x[m])
            rows.append(dict(kind="auc", var=v, cond=cond, n=int(m.sum()), auc=a, dir=_G["dirs"][(cond, v)],
                             p50_normal=np.nanmedian(x[m & ~y]), p50_viol=np.nanmedian(x[m & y])))
    for v, x in tr["rep"].items():   # 조건별 정상 분포
        if v.startswith("_") or v.startswith("F_hip_first"):
            continue
        for cond in ("knee", "heel", "spine"):
            y = D["viol"][cond]
            m = np.isfinite(x)
            rows.append(dict(kind="by_cond", var=v, cond=cond, p50_normal=np.nanmedian(x[m & ~y]), p50_viol=np.nanmedian(x[m & y])))
    meta = D["meta"]
    rows.append(dict(kind="sample", var="clips", n=len(D["ids"]), n_total=D["n_total"], performers=meta.performer.nunique(),
                     frames_ok=D["n_frames_ok"], events=len(_G["sc"].events),
                     event_clips=len(np.unique(_G["sc"].events[:, 0])) if len(_G["sc"].events) else 0))
    pd.DataFrame(rows).to_csv(C.OUT / "gt_reference.csv", index=False, encoding="utf-8-sig")


def main():
    C.OUT.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    gt_reference()
    jobs = [(az, h, d, fov, edge, SIGMAS, "base") for az, h, d, fov, edge in itertools.product(AZ, HS, DS, FOVS, EDGES)]
    for az, h, var in itertools.product([0, 40, -40, 90, -90], HS, ["pitch2", "pitch-2", "hscale1.2", "hscale0.8"]):
        jobs.append((az, h, 2.5, 68, False, [0, 4], var))
    print(f"[grid] {len(jobs)} 배치", flush=True)
    rows, arows, crows = [], [], []
    workers = max(1, (os.cpu_count() or 2) - 2)
    with ProcessPoolExecutor(max_workers=workers, initializer=_init) as ex:
        for k, (r, a, c) in enumerate(ex.map(run_config, jobs, chunksize=4)):
            rows += r
            arows += a
            crows.append(c)
            if (k + 1) % 50 == 0:
                print(f"  {k+1}/{len(jobs)}  {time.time()-t0:.0f}s", flush=True)
    pd.DataFrame(rows).to_parquet(C.OUT / "grid_metrics.parquet", index=False, compression="zstd")
    pd.DataFrame(arows).to_parquet(C.OUT / "grid_auc.parquet", index=False, compression="zstd")
    pd.DataFrame(crows).to_csv(C.OUT / "grid_configs.csv", index=False, encoding="utf-8-sig")
    print(f"[done] {len(rows)} 지표행, {len(arows)} AUC행, {time.time()-t0:.0f}s")


if __name__ == "__main__":
    main()

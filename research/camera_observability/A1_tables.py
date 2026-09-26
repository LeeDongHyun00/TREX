# -*- coding: utf-8 -*-
"""A1 결과표 — grid_*.parquet·csv 를 보고서용 마크다운 표로 요약한다 (outputs/A1/tables.md).

규칙
  - 좌·우 방위(±az)는 지표를 평균해 |az| 로 묶는다(좌우 차이 최대값은 tables.md 끝에 남긴다).
  - 기준 배치 = 거리 2.5 m · 세로 화각 68° · 가운데. 지도는 높이 1.0 m, 높이 표는 방위 0°/40°.
  - 판정: ● 보임 = ρ(σ0) ≥ 0.9 이고 ρ(σ4) ≥ 0.8 · ◐ 조건부 = ρ(σ0) ≥ 0.5 · ○ 안 보임 = ρ(σ0) < 0.5
    (ρ = 반복(클립) 단위 Spearman, 3D 진실 vs 2D 추정).
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import A1_common as C  # noqa: E402

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

M = pd.read_parquet(C.OUT / "grid_metrics.parquet")
A = pd.read_parquet(C.OUT / "grid_auc.parquet")
CF = pd.read_csv(C.OUT / "grid_configs.csv")
GT = pd.read_csv(C.OUT / "gt_reference.csv")
for df in (M, A, CF):
    df["aaz"] = df["az"].abs()

KEYS = ["aaz", "h", "d", "fov", "edge", "variant", "sigma"]
NUM = ["n", "pearson", "spearman", "bias", "mae", "rmse", "cal_rmse", "slope", "sign_agree", "within_rho", "amp_ratio",
       "t_sd", "e_med", "t_med", "p90_abs"]
MP = M.groupby(KEYS + ["var", "cand"], as_index=False)[[c for c in NUM if c in M.columns]].mean()
AP = A.groupby(KEYS + ["cond", "var", "cand"], as_index=False)["auc"].mean()
REF = dict(d=2.5, fov=68, edge=False, variant="base")
AZS = [0, 20, 40, 60, 90]
HS = [0.05, 0.15, 0.3, 0.6, 1.0, 1.4]


def sel(df, **kw):
    m = np.ones(len(df), bool)
    for k, v in kw.items():
        m &= (df[k] == v).to_numpy() if not isinstance(v, float) else np.isclose(df[k], v)
    return df[m]


def val(df, col, **kw):
    s = sel(df, **kw)
    return float(s[col].iloc[0]) if len(s) else np.nan


def f(x, nd=2):
    return "–" if not np.isfinite(x) else f"{x:.{nd}f}"


def judge(r0, r4):
    if not np.isfinite(r0):
        return "–"
    if r0 >= 0.9 and np.isfinite(r4) and r4 >= 0.8:
        return "●"
    return "◐" if r0 >= 0.5 else "○"


L = []
# ---------------------------------------------------------------- 0. 표본
s = GT[GT.kind == "sample"].iloc[0]
L += [f"# A1 표 (자동 생성)\n", f"표본: 클립 {int(s.n)}/{int(s.n_total)} (QC 양호 프레임 ≥ 8), 수행자 {int(s.performers)}, 양호 프레임 {int(s.frames_ok)}, "
      f"hip-first 사건 {int(s.events)} (클립 {int(s.event_clips)})\n"]
L += ["## GT 조건 AUC (3D 진실)\n", "| 조건 | 변수 | AUC | 정상 중앙값 | 위반 중앙값 |", "|---|---|---|---|---|"]
for _, r in GT[GT.kind == "auc"].iterrows():
    L.append(f"| {r.cond} | {r['var']} | {r.auc:.3f} | {r.p50_normal:.3f} | {r.p50_viol:.3f} |")
L += ["", "## GT 분포 (반복=클립 단위)\n", "| 변수 | n | p5 | p50 | p95 | SD |", "|---|---|---|---|---|---|"]
for _, r in GT[GT.kind == "dist"].iterrows():
    L.append(f"| {r['var']} | {int(r.n)} | {r.p5:.3f} | {r.p50:.3f} | {r.p95:.3f} | {r.sd:.3f} |")

# ---------------------------------------------------------------- 1. 관측성 지도
ROWS = [("R_hip_drop", "img_v"), ("R_depth", "v_ratio"), ("R_depth", "img_angle"), ("R_knee_min", "img_angle"), ("R_knee_min", "recon2d"),
        ("R_foot_drift", "sh_norm"), ("R_foot_drift", "leg_norm"),
        ("F_knee_foot", "kasr"), ("F_knee_foot", "knee_out2d"), ("F_knee_foot", "fppa"), ("F_knee_foot", "toe_ratio"),
        ("F_knee_foot", "d_knee_out2d"), ("F_knee_foot", "recon2d"), ("F_knee_foot", "gpl"),
        ("F_heel", "toe_rel"), ("F_heel", "ankle_rise"), ("F_heel", "gpl"), ("F_spine", "img_curv"),
        ("F_hip_first_dlean", "img_angle"), ("F_hip_first_dlean", "foreshort"), ("F_hip_first_ratio", "img_v"),
        ("F_lat_pelvis_tilt", "img"), ("F_lat_shift", "img"), ("F_lat_knee_asym", "img"),
        ("S_toe", "img_angle"), ("S_toe", "body_prop"), ("S_toe", "gpl"), ("S_stance", "x_ratio"), ("S_stance", "gpl"),
        ("T_lean", "img_angle"), ("T_lean", "foreshort"), ("T_lean", "gpl"),
        ("R_depth", "gpl"), ("R_knee_min", "gpl"), ("F_lat_knee_asym", "recon2d"), ("S_stance", "x_ratio_lvl")]
for hh in (1.0, 0.15):
    L += ["", f"## 1. 관측성 지도 — h {hh} m, d 2.5 m, 68°, 가운데. 칸 = ρ(σ0)/ρ(σ4) + 판정\n",
          "| 변수 | 후보 | " + " | ".join(f"{a}°" for a in AZS) + " |", "|---|---|" + "---|" * len(AZS)]
    for v, c in ROWS:
        cells = []
        for a in AZS:
            r0 = val(MP, "spearman", var=v, cand=c, aaz=a, h=hh, sigma=0, **REF)
            r4 = val(MP, "spearman", var=v, cand=c, aaz=a, h=hh, sigma=4, **REF)
            cells.append(f"{f(r0)}/{f(r4)} {judge(r0, r4)}")
        L.append(f"| {v} | {c} | " + " | ".join(cells) + " |")

# 프레임 신호(반복 모양) — within-clip ρ
L += ["", "## 1b. 반복 신호 모양 (클립 안 프레임 Spearman 중앙값) — h 1.0 / 0.15, d 2.5, 68°\n",
      "| 변수 | 후보 | h | " + " | ".join(f"{a}° σ0/σ4" for a in AZS) + " |", "|---|---|---|" + "---|" * len(AZS)]
for v, c in [("R_hip_drop", "img_v"), ("R_depth", "v_ratio"), ("R_depth", "img_angle"), ("R_knee_min", "img_angle"),
             ("R_hip_drop", "gpl"), ("R_depth", "gpl"), ("R_knee_min", "gpl"), ("T_lean", "img_angle"), ("T_lean", "foreshort")]:
    for hh in (1.0, 0.15):
        cells = [f"{f(val(MP, 'within_rho', var=v, cand=c, aaz=a, h=hh, sigma=0, **REF))}/{f(val(MP, 'within_rho', var=v, cand=c, aaz=a, h=hh, sigma=4, **REF))}" for a in AZS]
        L.append(f"| {v} | {c} | {hh} | " + " | ".join(cells) + " |")

# ---------------------------------------------------------------- 2. 높이별 편향 (σ0)
L += ["", "## 2. 높이별 편향·기울기 (σ0, d 2.5, 68°, 가운데). 칸 = 편향(중앙값, 2D−3D) / 기울기(2D 가 3D 1단위에 움직이는 양)\n"]
for a in (0, 40, 90):
    L += [f"### 방위 {a}°\n", "| 변수 | 후보 | " + " | ".join(f"h {h}" for h in HS) + " |", "|---|---|" + "---|" * len(HS)]
    for v, c in [("R_depth", "v_ratio"), ("R_depth", "v_ratio_lvl"), ("R_depth", "img_angle"), ("R_depth", "gpl"), ("R_hip_drop", "img_v"),
                 ("R_knee_min", "recon2d"), ("R_knee_min", "recon2d_lvl"), ("R_knee_min", "gpl"),
                 ("S_toe", "img_angle"), ("S_toe", "img_angle_lvl"), ("S_toe", "body_prop"), ("S_toe", "body_prop_lvl"), ("S_toe", "gpl"),
                 ("S_stance", "x_ratio"), ("S_stance", "x_ratio_lvl"), ("S_stance", "gpl"),
                 ("F_knee_foot", "recon2d"), ("F_knee_foot", "recon2d_lvl"), ("F_knee_foot", "gpl"),
                 ("T_lean", "foreshort"), ("T_lean", "foreshort_lvl"), ("T_lean", "img_angle"), ("T_lean", "gpl"), ("F_heel", "gpl")]:
        cells = [f"{f(val(MP, 'bias', var=v, cand=c, aaz=a, h=h, sigma=0, **REF))} / {f(val(MP, 'slope', var=v, cand=c, aaz=a, h=h, sigma=0, **REF))}" for h in HS]
        L.append(f"| {v} | {c} | " + " | ".join(cells) + " |")
    # 무릎 정렬: 비교 단위가 달라 정상 중앙값(2D)과 AUC 로
    cells = []
    for h in HS:
        em = val(MP, "e_med", var="F_knee_foot", cand="knee_out2d", aaz=a, h=h, sigma=0, **REF)
        au = val(AP, "auc", cond="knee", var="F_knee_foot", cand="knee_out2d", aaz=a, h=h, sigma=0, **REF)
        cells.append(f"{f(em, 3)} / AUC {f(au)}")
    L.append("| F_knee_foot | knee_out2d 전체 중앙값 / AUC | " + " | ".join(cells) + " |")
    cells = []
    for h in HS:
        em = val(MP, "e_med", var="F_knee_foot", cand="fppa", aaz=a, h=h, sigma=0, **REF)
        au = val(AP, "auc", cond="knee", var="F_knee_foot", cand="fppa", aaz=a, h=h, sigma=0, **REF)
        cells.append(f"{f(em, 1)} / AUC {f(au)}")
    L.append("| F_knee_foot | FPPA 전체 중앙값 / AUC | " + " | ".join(cells) + " |")
    L.append("")

# 높이별 ρ (σ4) — 바닥 카메라의 정보 손실
L += ["## 2b. 높이별 ρ(σ4) — d 2.5, 68°, 가운데\n", "| 변수 | 후보 | 방위 | " + " | ".join(f"h {h}" for h in HS) + " |", "|---|---|---|" + "---|" * len(HS)]
for v, c, a in [("R_depth", "v_ratio", 0), ("R_depth", "img_angle", 90), ("R_hip_drop", "img_v", 0), ("S_toe", "img_angle", 0),
                ("S_toe", "gpl", 0), ("S_toe", "gpl", 40), ("S_stance", "x_ratio", 0), ("F_knee_foot", "kasr", 0),
                ("F_knee_foot", "gpl", 0), ("F_knee_foot", "gpl", 40), ("F_heel", "toe_rel", 0), ("F_heel", "gpl", 40),
                ("T_lean", "foreshort", 0), ("T_lean", "img_angle", 90), ("R_depth", "gpl", 0), ("R_knee_min", "gpl", 0)]:
    cells = [f(val(MP, "spearman", var=v, cand=c, aaz=a, h=h, sigma=4, **REF)) for h in HS]
    L.append(f"| {v} | {c} | {a}° | " + " | ".join(cells) + " |")

# ---------------------------------------------------------------- 3. 화각·거리·가장자리
L += ["", "## 3. 화각·거리 — 서 있을 때 몸 높이(px)와 ρ(σ4), 방위 0°(무릎·발) / 90°(깊이·굴곡), h 0.6\n"]
L += ["| 화각 | d | 몸 px | 프레이밍 | S_toe img(0°) | F_knee kasr(0°) | F_heel toe_rel(0°) | R_depth v_ratio(0°) | R_depth img(90°) | F_spine(90°) | S_stance x(0°) |",
      "|---|---|---|---|---|---|---|---|---|---|---|"]
for fov in (68, 100):
    for d in (2.0, 2.5, 3.0, 4.0):
        kw = dict(h=0.6, d=d, fov=fov, edge=False, variant="base")
        bp = sel(CF, aaz=0, **kw).body_px.mean()
        fit = sel(CF, **kw).fit.min()
        cells = [val(MP, "spearman", var=v, cand=c, aaz=a, sigma=4, **kw) for v, c, a in
                 [("S_toe", "img_angle", 0), ("F_knee_foot", "kasr", 0), ("F_heel", "toe_rel", 0), ("R_depth", "v_ratio", 0),
                  ("R_depth", "img_angle", 90), ("F_spine", "img_curv", 90), ("S_stance", "x_ratio", 0)]]
        L.append(f"| {fov}° | {d} | {bp:.0f} | {fit:.3f} | " + " | ".join(f(x) for x in cells) + " |")
L += ["", "### 가장자리(가로 30%) − 가운데: 편향 차 (σ0, h 0.6, d 2.5)\n",
      "| 화각 | S_toe img(0°) | S_stance x(0°) | R_depth img(90°) | T_lean img(90°) | F_knee fppa 중앙값(0°) | R_depth v_ratio(0°) |", "|---|---|---|---|---|---|---|"]
for fov in (68, 100):
    out = []
    for v, c, a, col in [("S_toe", "img_angle", 0, "bias"), ("S_stance", "x_ratio", 0, "bias"), ("R_depth", "img_angle", 90, "bias"),
                         ("T_lean", "img_angle", 90, "bias"), ("F_knee_foot", "fppa", 0, "e_med"), ("R_depth", "v_ratio", 0, "bias")]:
        e1 = val(MP, col, var=v, cand=c, aaz=a, h=0.6, d=2.5, fov=fov, edge=True, variant="base", sigma=0)
        e0 = val(MP, col, var=v, cand=c, aaz=a, h=0.6, d=2.5, fov=fov, edge=False, variant="base", sigma=0)
        out.append(e1 - e0)
    L.append(f"| {fov}° | " + " | ".join(f(x) for x in out) + " |")
# 전체 프레이밍 불가 조합
bad = CF[(CF.variant == "base") & (CF.fit < 0.95)]
L += ["", f"프레이밍 불가(전신이 들어오는 클립 < 95%) 조합: {len(bad)}개 / {len(CF[CF.variant=='base'])}. "
      f"전체 최소 비율 {CF[CF.variant=='base'].fit.min():.3f}. 몸 높이 px 범위 {CF[CF.variant=='base'].body_px.min():.0f}~{CF[CF.variant=='base'].body_px.max():.0f}"]
if len(bad):
    L.append(bad[["az", "h", "d", "fov", "edge", "fit"]].to_string())

# ---------------------------------------------------------------- 4. 조건 AUC
L += ["", "## 4. 조건 AUC — 2D 추정의 위반 판별 (σ0 / σ4, d 2.5, 68°, 가운데)\n"]
cfgs = [(0, 1.0), (0, 0.15), (20, 1.0), (40, 1.0), (40, 0.15), (60, 1.0), (90, 1.0), (90, 0.15)]
L += ["| 조건 | 후보 | GT 3D | " + " | ".join(f"{a}°·{h}m" for a, h in cfgs) + " |", "|---|---|---|" + "---|" * len(cfgs)]
gta = GT[GT.kind == "auc"].set_index(["cond", "var"]).auc
for cond, v, c in [("knee", "F_knee_foot", "kasr"), ("knee", "F_knee_foot", "knee_out2d"), ("knee", "F_knee_foot", "fppa"),
                   ("knee", "F_knee_foot", "d_knee_out2d"), ("knee", "F_knee_foot", "toe_ratio"), ("knee", "F_knee_foot", "gpl"),
                   ("heel", "F_heel", "toe_rel"), ("heel", "F_heel", "ankle_rise"), ("heel", "F_heel", "gpl"),
                   ("spine", "F_spine", "img_curv"), ("spine", "T_lean", "img_angle"), ("spine", "T_lean", "foreshort"), ("spine", "T_lean", "gpl")]:
    g = gta.get((cond, v), np.nan)
    cells = [f"{f(val(AP, 'auc', cond=cond, var=v, cand=c, aaz=a, h=h, sigma=0, **REF))}/{f(val(AP, 'auc', cond=cond, var=v, cand=c, aaz=a, h=h, sigma=4, **REF))}" for a, h in cfgs]
    L.append(f"| {cond} | {v}:{c} | {g:.3f} | " + " | ".join(cells) + " |")
L.append(f"\n(참고) knee_out3d GT AUC {gta.get(('knee', 'knee_out3d'), np.nan):.3f}")

# ---------------------------------------------------------------- 5. 잡음 민감도
L += ["", "## 5. 잡음 민감도 ρ(σ 0/2/4/8) — h 0.6, d 2.5, 68°, 가운데\n", "| 변수 | 후보 | 방위 | σ0 | σ2 | σ4 | σ8 | cal_rmse σ0→σ8 |", "|---|---|---|---|---|---|---|---|"]
for v, c, a in [("R_hip_drop", "img_v", 0), ("R_depth", "v_ratio", 0), ("R_depth", "img_angle", 90), ("R_knee_min", "img_angle", 90),
                ("R_foot_drift", "leg_norm", 0), ("F_knee_foot", "kasr", 0), ("F_knee_foot", "gpl", 0), ("F_heel", "toe_rel", 0),
                ("F_heel", "toe_rel", 90), ("F_spine", "img_curv", 90), ("F_hip_first_dlean", "img_angle", 90), ("F_hip_first_ratio", "img_v", 90),
                ("F_lat_pelvis_tilt", "img", 0), ("F_lat_shift", "img", 0), ("F_lat_knee_asym", "img", 90),
                ("S_toe", "img_angle", 0), ("S_toe", "gpl", 40), ("S_stance", "x_ratio", 0), ("T_lean", "img_angle", 90), ("T_lean", "foreshort", 0)]:
    rr = [val(MP, "spearman", var=v, cand=c, aaz=a, h=0.6, sigma=s, **REF) for s in (0, 2, 4, 8)]
    c0 = val(MP, "cal_rmse", var=v, cand=c, aaz=a, h=0.6, sigma=0, **REF)
    c8 = val(MP, "cal_rmse", var=v, cand=c, aaz=a, h=0.6, sigma=8, **REF)
    L.append(f"| {v} | {c} | {a}° | " + " | ".join(f(x) for x in rr) + f" | {f(c0, 3)}→{f(c8, 3)} |")

# ---------------------------------------------------------------- 6. GPL 민감도
L += ["", "## 6. 지면 역투영(GPL) 민감도 — 편향(σ0) · ρ(σ4). d 2.5, 68°, 가운데\n",
      "| 변수 | 방위 | h | 정확 | 피치+2° | 피치−2° | 높이×1.2 | 높이×0.8 |", "|---|---|---|---|---|---|---|---|"]
for v in ("S_toe", "F_knee_foot", "R_depth", "S_stance", "T_lean", "F_heel"):
    for a in (0, 40, 90):
        for h in (0.15, 0.3, 0.6, 1.0, 1.4):
            cells = []
            for var in ("base", "pitch2", "pitch-2", "hscale1.2", "hscale0.8"):
                b = val(MP, "bias", var=v, cand="gpl", aaz=a, h=h, d=2.5, fov=68, edge=False, variant=var, sigma=0)
                r = val(MP, "spearman", var=v, cand="gpl", aaz=a, h=h, d=2.5, fov=68, edge=False, variant=var, sigma=4)
                cells.append(f"{f(b, 1)} · {f(r)}")
            L.append(f"| {v} | {a}° | {h} | " + " | ".join(cells) + " |")

# ---------------------------------------------------------------- 7. 최적 배치 탐색 (σ4 ρ 최대, 가운데·68°)
L += ["", "## 7. 변수별 최적 배치 (σ4 에서 ρ 최대, 68°·가운데, 전 높이·거리)\n", "| 변수 | 후보 | 최적 |az| | h | d | ρ(σ4) | ρ(σ0) | 같은 방위 최악 높이 ρ(σ4) |", "|---|---|---|---|---|---|---|---|"]
for v, c in ROWS:
    s4 = MP[(MP["var"] == v) & (MP.cand == c) & (MP.sigma == 4) & (MP.fov == 68) & (~MP.edge) & (MP.variant == "base")]
    if not len(s4):
        continue
    b = s4.sort_values("spearman", ascending=False).iloc[0]
    r0 = val(MP, "spearman", var=v, cand=c, aaz=b.aaz, h=b.h, d=b.d, fov=68, edge=False, variant="base", sigma=0)
    worst = s4[(s4.aaz == b.aaz) & (s4.d == b.d)].spearman.min()
    L.append(f"| {v} | {c} | {int(b.aaz)} | {b.h} | {b.d} | {b.spearman:.2f} | {f(r0)} | {f(worst)} |")

# ---------------------------------------------------------------- 8. 바닥 카메라 보정 요약
L += ["", "## 8. 바닥 카메라 보정 요약 — 방위 0°, d 2.5, 68°, 가운데. 칸 = 편향(σ0) · ρ(σ4)\n",
      "| 변수 | 후보 | h 0.05 | h 0.15 | h 0.3 | h 1.0 |", "|---|---|---|---|---|---|"]
for v, cs in [("R_depth", ["v_ratio", "v_ratio_lvl", "gpl"]), ("R_knee_min", ["recon2d", "recon2d_lvl", "gpl"]),
              ("S_toe", ["img_angle", "img_angle_lvl", "body_prop", "body_prop_lvl", "gpl"]),
              ("S_stance", ["x_ratio", "x_ratio_lvl", "gpl"]), ("F_knee_foot", ["recon2d", "recon2d_lvl", "gpl"]),
              ("T_lean", ["foreshort", "foreshort_lvl", "gpl"])]:
    for c in cs:
        cells = [f"{f(val(MP, 'bias', var=v, cand=c, aaz=0, h=h, sigma=0, **REF), 2)} · {f(val(MP, 'spearman', var=v, cand=c, aaz=0, h=h, sigma=4, **REF))}"
                 for h in (0.05, 0.15, 0.3, 1.0)]
        L.append(f"| {v} | {c} | " + " | ".join(cells) + " |")
L += ["", "### 무릎 정렬 knee_out2d 전체 중앙값 (원본 → 피치 보정)\n", "| h | 0° 원본 | 0° _lvl | 40° 원본 | 40° _lvl |", "|---|---|---|---|---|"]
for h in HS:
    L.append(f"| {h} | " + " | ".join(f(val(MP, 'e_med', var='F_knee_foot', cand=c, aaz=a, h=h, sigma=0, **REF), 3)
                                       for a in (0, 40) for c in ('knee_out2d', 'knee_out2d_lvl')) + " |")

# 좌우 비대칭 점검
chk = M[(M.variant == "base") & (M.sigma == 0) & (M.az != 0)].copy()
chk["side"] = np.sign(chk.az)
pv = chk.pivot_table(index=["aaz", "h", "d", "fov", "edge", "var", "cand"], columns="side", values="spearman")
dd = (pv[1.0] - pv[-1.0]).abs()
L += ["", f"좌우(±az) ρ 차이: 중앙값 {dd.median():.3f}, p95 {dd.quantile(0.95):.3f}, 최대 {dd.max():.3f} ({dd.idxmax()})"]

(C.OUT / "tables.md").write_text("\n".join(L), encoding="utf-8")
print("\n".join(L))

#!/usr/bin/env python
"""A2 요약 — A2_analysis.py 의 CSV 를 보고서용 압축 표로 만든다 (outputs/A2/A2_summary_tables.md).

표 1  변수 × 표현(GT2D 상한 / MP2D / MP월드) × 뷰 : Spearman ρ (세션 중심화 ρw)
표 2  같은 단위 변수의 오차 크기: 절대오차 중앙값 · 중앙값 편향 (뷰별)
표 3  조건 라벨 AUC (원값 / 세션 중심화): GT3D · GT2D · MP2D · MP월드
표 4  랜드마크 품질 (가시성 < 0.5 비율 · px 오차/몸통 · 좌우 뒤바뀜) + 월드 축별 오차
표 5  app(480×640) − full(1920×1080) 의 ρ 차이
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
OUT = Path(__file__).resolve().parent / "outputs" / "A2"
VIEWS = ["A", "B", "C", "D", "E"]

# (보고서 행 이름, SPECS group, 후보 키) — 표현별로 같은 줄에
ROWS = [
    ("R_depth 허벅지각", "R_depth 허벅지각(°)", "depth_deg"),
    ("R_depth 비(무릎−골반 높이÷허벅지)", "R_depth 비(sin)", "depth_ratio"),
    ("R_hip_drop", "R_hip_drop", "hip_drop"),
    ("F_knee_foot 3D각 (월드)", "F_knee_foot 3D각(°)", "kf"),
    ("F_knee_foot ← knee_out", "F_knee_foot ← knee_out", "knee_out"),
    ("F_knee_foot ← FPPA", "F_knee_foot ← FPPA", "fppa"),
    ("F_knee_foot ← KASR", "F_knee_foot ← KASR", "kasr"),
    ("F_knee_foot ← 무릎x/발끝x", "F_knee_foot ← 무릎x/발끝x 비", "kratio"),
    ("F_heel ← 발목 상승(2D)", "F_heel ← 발목 상승", "heel_ankle"),
    ("F_heel ← 발목−발끝 높이차", "F_heel ← 발목−발끝 높이차 변화", "heel_aot"),
    ("F_heel ← heel_lift", "F_heel ← 뒤꿈치−발끝(heel_lift) 변화", "heel_lift"),
    ("T_lean 바닥", "T_lean 바닥(°)", "lean_b"),
    ("T_lean 바닥−서있음", "T_lean 바닥−서있음(°)", "lean_d"),
    ("F_lateral 골반 기울기", "F_lateral 골반 기울기", "obl"),
    ("F_lateral 골반 좌우 이동", "F_lateral 골반 좌우 이동", "shift"),
    ("F_lateral 무릎 굽힘 차", "F_lateral 무릎 굽힘 차(°)", "kasym"),
    ("S_toe 발끝각", "S_toe 발끝각(°)", "toe"),
    ("S_stance 발목÷어깨", "S_stance 발목÷어깨 간격", "stance"),
]


def md(df: pd.DataFrame) -> str:
    L = ["| " + " | ".join(map(str, df.columns)) + " |", "|" + "---|" * len(df.columns)]
    for r in df.itertuples(index=False):
        L.append("| " + " | ".join("" if (isinstance(x, float) and np.isnan(x)) else str(x) for x in r) + " |")
    return "\n".join(L)


def cell(x, key="rho", key2="rho_w"):
    if x is None or pd.isna(x.get(key)):
        return "·"
    s = f"{x[key]:.2f}"
    if key2 and pd.notna(x.get(key2)):
        s += f" ({x[key2]:.2f})"
    return s


def main(variant="full"):
    fid = pd.read_csv(OUT / "A2_var_fidelity.csv")
    auc = pd.read_csv(OUT / "A2_condition_auc.csv")
    lq = pd.read_csv(OUT / "A2_landmark_quality.csv")
    wa = pd.read_csv(OUT / "A2_world_axes.csv")
    fs = pd.read_csv(OUT / "A2_frame_select.csv")
    L = [f"# A2 요약 표 (variant={variant})", ""]

    # 표 1
    L += ["## 표 1. GT 3D 진실 대비 Spearman ρ (괄호 = 세션 중심화 ρw)", ""]
    recs = []
    for name, group, ck in ROWS:
        g = fid[(fid.group == group) & (fid.cand == ck)]
        g3 = g[g.rep == "GT3D"]
        for rep, lab in (("GT2D", "GT2D(기하 상한)"), ("MP2D", "MP 2D"), ("MPW0", "MP 월드(카메라 축)"), ("MPWg", "MP 월드(IMU 중력)")):
            gg = g[(g.rep == rep) & (g.variant.isin(["-", variant]))]
            if gg.empty:
                continue
            row = {"변수": name, "표현": lab}
            for vl in VIEWS:
                x = gg[gg.view == vl]
                row[vl] = cell(x.iloc[0]) if len(x) else "·"
            row["n(C)"] = int(gg[gg.view == "C"].n.iloc[0]) if len(gg[gg.view == "C"]) else ""
            recs.append(row)
        if len(g3):
            x = g3.iloc[0]
            recs.append({"변수": name, "표현": "GT3D 같은 후보(뷰 무관)", "A": cell(x), "B": "", "C": "", "D": "", "E": "", "n(C)": int(x.n)})
    L += [md(pd.DataFrame(recs)), ""]

    # 표 1b: MP2D vs GT2D (순수 MediaPipe 2D 손실)
    L += ["## 표 1b. MP 2D vs 같은 뷰 GT 2D (기하 손실을 뺀 MediaPipe 자체 손실) — ρ (ρw)", ""]
    recs = []
    for name, group, ck in ROWS:
        gg = fid[(fid.group == group) & (fid.cand == ck) & (fid.rep == "MP2D_vs_GT2D") & (fid.variant == variant)]
        if gg.empty:
            continue
        row = {"변수": name}
        for vl in VIEWS:
            x = gg[gg.view == vl]
            row[vl] = cell(x.iloc[0]) if len(x) else "·"
        recs.append(row)
    L += [md(pd.DataFrame(recs)), ""]

    # 표 2: 같은 단위 오차
    L += ["## 표 2. 같은 단위 변수의 오차 — 절대오차 중앙값 / 중앙값 편향(후보−진실)", ""]
    recs = []
    for name, group, ck in ROWS:
        g = fid[(fid.group == group) & (fid.cand == ck) & fid.mdae.notna()]
        for rep, lab in (("GT2D", "GT2D"), ("MP2D", "MP 2D"), ("MPWg", "MP 월드(g)")):
            gg = g[(g.rep == rep) & (g.variant.isin(["-", variant]))]
            if gg.empty:
                continue
            row = {"변수": name, "표현": lab}
            for vl in VIEWS:
                x = gg[gg.view == vl]
                row[vl] = f"{x.iloc[0].mdae:.3g} / {x.iloc[0].bias_med:+.3g}" if len(x) and pd.notna(x.iloc[0].mdae) else "·"
            row["진실 SD(C)"] = f"{gg[gg.view=='C'].truth_sd.iloc[0]:.3g}" if len(gg[gg.view == "C"]) else ""
            recs.append(row)
    L += [md(pd.DataFrame(recs)), ""]

    # 표 2b: 부호 일치 (F_lateral · 깊이)
    L += ["## 표 2b. 부호 일치율(같은 개념) — 전체 / |진실| 상위 50 %", ""]
    recs = []
    for name, group, ck in ROWS:
        g = fid[(fid.group == group) & (fid.cand == ck) & fid["sign"].notna()]
        for rep, lab in (("GT2D", "GT2D"), ("MP2D", "MP 2D"), ("MPWg", "MP 월드(g)")):
            gg = g[(g.rep == rep) & (g.variant.isin(["-", variant]))]
            if gg.empty:
                continue
            row = {"변수": name, "표현": lab}
            for vl in VIEWS:
                x = gg[gg.view == vl]
                row[vl] = f"{x.iloc[0]['sign']*100:.0f}% / {x.iloc[0].sign_big*100:.0f}%" if len(x) else "·"
            recs.append(row)
    L += [md(pd.DataFrame(recs)), ""]

    # 표 3: AUC
    L += ["## 표 3. AIHub 조건 라벨 AUC (오류 방향 고정) — 원값 / 세션 중심화", ""]
    recs = []
    for (cond, cand), g in auc.groupby(["condition", "cand"], sort=False):
        g3 = g[(g.rep == "GT3D") & g.auc.notna()]
        if len(g3):
            x = g3.iloc[0]
            recs.append({"조건": cond, "후보": cand, "표현": "GT3D", **{vl: (f"{x.auc:.2f} / {x.auc_within:.2f}" if vl == "A" else "") for vl in VIEWS}})
        for rep in ("GT2D", "MP2D", "MPW0", "MPWg"):
            gg = g[(g.rep == rep) & (g.variant.isin(["-", variant]))]
            if gg.empty:
                continue
            row = {"조건": cond, "후보": cand, "표현": rep}
            for vl in VIEWS:
                x = gg[gg.view == vl]
                row[vl] = f"{x.iloc[0].auc:.2f} / {x.iloc[0].auc_within:.2f}" if len(x) and pd.notna(x.iloc[0].get("auc")) else "·"
            recs.append(row)
    L += [md(pd.DataFrame(recs)), ""]

    # 표 4: 랜드마크 품질
    L += ["## 표 4. 랜드마크 품질 (full) — 가시성<0.5 비율(L/R), px 오차 중앙값(몸통=1; 서 있음/바닥), 좌우 뒤바뀜", ""]
    q = lq[lq.variant == variant]
    recs = []
    for vl in VIEWS:
        qv = q[q.view == vl].set_index("joint")
        row = {"뷰": vl, "검출": f"{qv.loc['(검출)', 'detect']*100:.1f}%"}
        for j in ("hip", "knee", "ankle", "heel", "toe"):
            r = qv.loc[j]
            row[f"{j} vis<.5 L/R"] = f"{r.vis_lt05_L*100:.1f}/{r.vis_lt05_R*100:.1f}%"
        for j in ("hip", "knee", "ankle"):
            r = qv.loc[j]
            row[f"{j} 오차 서/바닥"] = f"{r.err_med_stand:.3f}/{r.err_med_bottom:.3f}"
        row["골반 바닥 세로편향"] = f"{qv.loc['hip', 'dy_med_bottom']:+.3f}±{qv.loc['hip', 'dy_mad_bottom']:.3f}"
        row["하체 좌우 뒤바뀜"] = f"{qv.loc['(하체 좌우 전체)', 'swap']*100:.1f}%"
        recs.append(row)
    L += [md(pd.DataFrame(recs)), ""]
    w = wa[wa.variant == variant]
    recs = []
    for r in w.itertuples():
        recs.append({"뷰": r.view, "월드↔카메라축 회전 중앙값(°)": f"{r.kabsch_deg_med:.1f}", "스케일 GT/MP": f"{r.gt_over_mp_scale:.2f}",
                     "무릎 오차 x/y/z(cm)": f"{r.knee_x_lat_mae:.1f}/{r.knee_y_up_mae:.1f}/{r.knee_z_depth_mae:.1f}",
                     "발목 오차 x/y/z(cm)": f"{r.ankle_x_lat_mae:.1f}/{r.ankle_y_up_mae:.1f}/{r.ankle_z_depth_mae:.1f}",
                     "어깨 오차 x/y/z(cm)": f"{r.shoulder_x_lat_mae:.1f}/{r.shoulder_y_up_mae:.1f}/{r.shoulder_z_depth_mae:.1f}"})
    L += ["### 월드 좌표: 카메라 축 정렬과 축별 오차 중앙값 (x=화면 가로, y=세로, z=깊이)", "", md(pd.DataFrame(recs)), ""]

    # 표 5: app − full
    if (fid.variant == "app").any():
        L += ["## 표 5. app(480×640 세로 잘라내기) − full(1920×1080) ρ 차이", ""]
        recs = []
        for name, group, ck in ROWS:
            for rep in ("MP2D", "MPWg"):
                a = fid[(fid.group == group) & (fid.cand == ck) & (fid.rep == rep) & (fid.variant == "app")].set_index("view")
                f = fid[(fid.group == group) & (fid.cand == ck) & (fid.rep == rep) & (fid.variant == variant)].set_index("view")
                if a.empty or f.empty:
                    continue
                row = {"변수": name, "표현": rep}
                for vl in VIEWS:
                    row[vl] = f"{a.loc[vl, 'rho'] - f.loc[vl, 'rho']:+.2f}" if vl in a.index and vl in f.index and pd.notna(a.loc[vl, 'rho']) else "·"
                recs.append(row)
        L += [md(pd.DataFrame(recs)), ""]

    # 표 6: 프레임 선택
    L += ["## 표 6. MediaPipe 가 스스로 고른 바닥 프레임 (월드 무릎각 최소)", ""]
    f = fs[fs.variant == variant]
    recs = []
    for r in f.itertuples():
        recs.append({"뷰": r.view, "n": r.n, "GT 바닥과 같은 프레임": f"{r.same_b*100:.0f}%", "무릎각 차 평균/p90": f"{r.gt_knee_gap_b_mean:.1f}/{r.gt_knee_gap_b_p90:.1f}",
                     **{k: f"{getattr(r, k + '_rho_gtframe'):.2f}→{getattr(r, k + '_rho_mpframe'):.2f}" for k in ("depth_deg", "depth_ratio", "kf", "lean_b", "toe")}})
    L += [md(pd.DataFrame(recs)), ""]
    text = "\n".join(L)
    (OUT / "A2_summary_tables.md").write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "full")

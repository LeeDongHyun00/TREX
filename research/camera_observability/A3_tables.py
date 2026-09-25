# -*- coding: utf-8 -*-
"""A3 — outputs/A3/*.csv 에서 보고서 표를 마크다운으로 찍는다(보고서 A3_REPORT.md 의 숫자 출처)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

OUT = Path(__file__).resolve().parent / "outputs" / "A3"
INTERVALS = [100, 150, 200, 300, 333, 450]


def fmt(x, d=2):
    return "—" if not np.isfinite(x) else f"{x:.{d}f}"


def max_ok(per_I: dict) -> str:
    best = None
    for I in INTERVALS:
        if per_I.get(I) in ("OK", "OK(잡음)"):
            best = I
        else:
            break
    return "<100" if best is None else str(best)


def cad_verdict(rel, fail):
    if fail <= 5 and rel <= 0.10:
        return "OK"
    if fail <= 10 and rel <= 0.20:
        return "경계"
    return "부족"


def main() -> int:
    sub = pd.read_csv(OUT / "subsample_errors.csv")
    trk = pd.read_csv(OUT / "tracker_effect.csv")
    s = json.loads((OUT / "summary.json").read_text(encoding="utf-8"))
    print("## 판정 표")
    print("| 통계 | 단위 | S | 샘플링만 300: rel90·실패% | 앱 주기 300: rel90·실패% | 333(실기기): rel90·실패% | 충분 최대 간격 샘플링만 / 앱 주기 |")
    print("|---|---|---|---|---|---|---|")
    for sid, v in s["verdict"].items():
        a = sub[(sub.stat == sid) & (sub.interval_ms == 300)].iloc[0]
        t = trk[trk.stat == sid].set_index("interval_ms")
        cad = {I: cad_verdict(t.loc[I, "cad_rel90"], t.loc[I, "cad_fail_pct"]) for I in INTERVALS if I in t.index}
        print(f"| {sid} | {v['unit']} | {fmt(v['S'], 3)} | {fmt(a.rel90)}·{a.fail_pct:.0f} | "
              f"{fmt(t.loc[300, 'cad_rel90'])}·{t.loc[300, 'cad_fail_pct']:.0f} | {fmt(t.loc[333, 'cad_rel90'])}·{t.loc[333, 'cad_fail_pct']:.0f} | "
              f"{max_ok({int(k): x for k, x in v['per_interval'].items()})} / {max_ok(cad)} | 샘플링300 {v['per_interval'].get('300')} · 앱300 {cad.get(300)} · 앱333 {cad.get(333)} |")
    ph = pd.read_csv(OUT / "phase_durations.csv")
    ps = pd.read_csv(OUT / "phase_samples.csv")
    print("\n## 국면")
    for pn in ph.phase.unique():
        r = ph[(ph.phase == pn) & (ph.group == "rehab")]
        m = ph[(ph.phase == pn) & (ph.group == "mmfit")]
        if r.empty:
            continue
        r, m = r.iloc[0], m.iloc[0]
        f = ps[(ps.phase == pn) & (ps.dataset == "all")].set_index("interval_ms")
        fr = ps[(ps.phase == pn) & (ps.dataset == "rehab")].set_index("interval_ms")
        fm = ps[(ps.phase == pn) & (ps.dataset == "mmfit")].set_index("interval_ms")
        cells = " | ".join(f"{fr.loc[I, 'fail01_pct']:.0f}/{fm.loc[I, 'fail01_pct']:.0f}" if I in fr.index else "—" for I in (100, 150, 200, 300, 333))
        med = f"{fr.loc[300, 'median_samples']:.0f}/{fm.loc[300, 'median_samples']:.0f}" if 300 in fr.index else "—"
        print(f"| {pn} | {r.p5_ms:.0f}·{r.p50_ms:.0f}·{r.p95_ms:.0f} | {m.p5_ms:.0f}·{m.p50_ms:.0f}·{m.p95_ms:.0f} | {med} | {cells} |")
    bt = pd.read_csv(OUT / "by_tempo.csv")
    print("\n## 템포")
    order = ["동작<1.2s", "동작1.2~1.6s", "동작1.6~2.0s", "동작2.0~2.5s", "동작≥2.5s"]
    for sid in ["F_knee_foot.kout_w.bot", "R_hip_drop.img.max", "R_depth.w.min", "T_torso.dmax", "F_hip_first.dtorso_i", "F_descent.vmean"]:
        for I in (200, 300, 333):
            x = bt[(bt.stat == sid) & (bt.interval_ms == I)].set_index("tempo")
            print(f"| {sid} {I} | " + " | ".join(f"{x.loc[o, 'cad_rel90']:.2f}·{x.loc[o, 'cad_fail_pct']:.0f}" if o in x.index else "—" for o in order) + " |")
    n = bt[(bt.stat == "R_hip_drop.img.max") & (bt.interval_ms == 300)].set_index("tempo").n_reps
    print("n per tempo:", {o: int(n.get(o, 0)) for o in order})
    ks = ps[ps.phase.isin(["바닥(앱: 무릎각 진폭 1/3)", "바닥→상승1/3 창", "하강"])]
    print(ks[ks.interval_ms.isin([150, 200, 300, 333]) & ks.dataset.isin(order)].pivot_table(
        index=["phase", "interval_ms"], columns="dataset", values="fail01_pct")[order].round(0).to_string())
    # 뷰
    r = pd.read_csv(OUT / "reps.csv")
    vd = pd.read_csv(OUT / "view_distributions.csv")
    vj = pd.read_csv(OUT / "view_jitter.csv")
    vv = pd.read_csv(OUT / "view_visibility.csv").set_index("view")
    print("\n## 뷰")
    views = ["rehab17_front", "mmfit_C", "mmfit_BD", "rehab18_obl", "rehab17_half", "rehab18_side"]
    for vw in views:
        x = r[r.view == vw]
        print(vw, len(x), "err", int((x.correct == 0).sum()), "|yaw| p5/50/95", np.percentile(x.abs_yaw_med, [5, 50, 95]).round(0),
              "move p50", round(x.move_ms.median()))
    keys = ["F_knee_foot.kout_w.bot", "F_knee_foot.kout_img.bot", "F_knee_foot.fppa.bot", "F_knee_foot.kasr_img.bot", "R_knee_min.max",
            "R_hip_drop.img.max", "R_depth.w.min", "T_torso.dmax", "S_toe.stand", "S_stance.img.stand", "S_stance.w.stand",
            "F_heel.heeltoe_img.bot", "F_lateral.knee_asym.bot", "F_hip_first.dtorso_i"]
    for k in keys:
        row = []
        for vw in views:
            y = vd[(vd.view == vw) & (vd.stat == k)]
            if y.empty or y.iloc[0].n == 0:
                row.append("—")
            else:
                y = y.iloc[0]
                row.append(f"{y.p50:.3g} ({y.p5:.3g}~{y.p95:.3g}) n{int(y.n)}")
        print(f"| {k} | " + " | ".join(row) + " |")
    print("\n jitter f30 / c300 (robust sigma)")
    for var in ["pel_y/leg", "knee_mean", "torso_incl", "kout_w_mean", "kout_img_mean", "fppa_mean", "toe_max", "stance_2d", "stance_w",
                "pelvis_tilt_w", "heel_lift_w_mean", "heeltoe_img_mean/leg", "anky_mean/leg"]:
        row = []
        for vw in views:
            y = vj[(vj.view == vw) & (vj["var"] == var)]
            row.append("—" if y.empty else f"{y.iloc[0].f30_sigma:.3g}/{y.iloc[0].c300_sigma:.3g}")
        print(f"| {var} | " + " | ".join(row) + " |")
    print(vv.T.round(3).to_string())
    return 0


if __name__ == "__main__":
    sys.exit(main())

# -*- coding: utf-8 -*-
import sys
import numpy as np, pandas as pd
from sklearn.metrics import roc_auc_score
c = pd.read_parquet("mpfull_clipview.parquet")
fr = pd.read_parquet("mpfull_frames.parquet")


def auc(y, x):
    m = np.isfinite(x)
    return roc_auc_score(y[m], x[m]) if len(np.unique(y[m])) == 2 else np.nan


def within(d, f, cond):
    a = []
    for _, g in d.groupby("performer"):
        g = g.dropna(subset=[f])
        y = (~g[cond].astype(bool)).astype(int).to_numpy()
        if len(np.unique(y)) == 2:
            a.append(roc_auc_score(y, g[f]))
    return (np.mean(a) if a else np.nan), len(a)


def q(s):
    s = s.dropna()
    if len(s) == 0:
        return "n=0"
    return f"p10 {s.quantile(.1):+.3f} p50 {s.median():+.3f} p90 {s.quantile(.9):+.3f} n={len(s)}"


def rep(ex, cond, feats, views="CE"):
    print(f"\n### {ex} — {cond}  (AUC>0.5 = 위반이 큼)")
    for f in feats:
        for v in views:
            d = c[(c.exercise == ex) & (c.view == v)].dropna(subset=[f])
            y = (~d[cond].astype(bool)).astype(int).to_numpy()
            w, n = within(d, f, cond)
            print(f"{f:24s} {v} AUC {auc(y, d[f].to_numpy()):.3f} 수행자내 {w:.3f}(n={n}) 충족[{q(d[f][y==0])}] 위반[{q(d[f][y==1])}]")


part = sys.argv[1]
if part == "plank":
    p = fr[fr.exercise == "플랭크"]
    print("앱 관측조건 통과율", p.groupby("view_letter")[["plank_hip_offset", "plank_head_pitch", "plank_neck_pitch"]].apply(lambda d: d.notna().mean()).round(3).to_dict())
    cc = "몸통과 엉덩이의 정렬 유지"
    rep("플랭크", cc, ["plank_hip_offset__med", "plank_hip_offset__p90", "plank_hip_offset__rr"])
    out = lambda s: float(((s < -0.06) | (s > 0.124)).mean())
    for v in "CE":
        d = p[(p.view_letter == v) & p.plank_hip_offset.notna()]
        y = ~d[cc].astype(bool); x = d.plank_hip_offset
        print(v, "프레임 충족 p2/p10/p50/p90/p98", np.quantile(x[~y], [.02, .1, .5, .9, .98]).round(3).tolist(), "n", int((~y).sum()), "| 위반", np.quantile(x[y], [.02, .1, .5, .9, .98]).round(3).tolist(), "n", int(y.sum()))
        print(v, "  띠[-0.06,0.124] 밖: 충족", round(out(x[~y]), 3), "위반", round(out(x[y]), 3), "| 위반 중 처짐", round(float((x[y] < -0.06).mean()), 3), "솟음", round(float((x[y] > 0.124).mean()), 3))
        a = d[d.type_key == "553"].plank_hip_offset
        print(v, "  전조건정상(553) p2/p50/p98", np.quantile(a, [.02, .5, .98]).round(3).tolist(), "n", len(a), "띠 밖", round(out(a), 3))
        cl = c[(c.exercise == "플랭크") & (c.view == v)].dropna(subset=["plank_hip_offset__med"])
        yy = ~cl[cc].astype(bool)
        print(v, "  클립중앙값 띠 밖: 충족", round(out(cl.plank_hip_offset__med[~yy]), 3), "위반", round(out(cl.plank_hip_offset__med[yy]), 3), "클립 n", len(cl), "수행자", cl.performer.nunique())
    for f, lo, hi in (("plank_head_pitch", -42.748, 25.0), ("plank_neck_pitch", -76.181, 31.474)):
        for v in "CE":
            x = p[p.view_letter == v][f].dropna()
            print(f, v, "p2/p10/p50/p90/p98", np.quantile(x, [.02, .1, .5, .9, .98]).round(1).tolist(), "n", len(x), "앱 띠 밖", round(float(((x < lo) | (x > hi)).mean()), 3))
    for cond in ("팔꿈치가 어깨보다 안쪽에 위치하지 않음", "상체의 지면으로부터 충분한 거리 유지"):
        rep("플랭크", cond, ["plank_hip_offset__med", "plank_head_pitch__med", "plank_neck_pitch__med"])
if part == "crunch":
    rep("크런치", "견갑골이 지면으로부터 충분히 올라옴", ["app_head_ground__max", "app_head_ground__p90", "app_head_ground__rr", "cr_trunk_lift__p90", "cr_trunk_lift__rr", "cr_sh_ground__p90", "cr_ear_ground__p90", "cr_head_lift__rr", "cr_neck_flex__p90"])
    rep("크런치", "이완시 긴장 유지", ["cr_trunk_lift__p10", "cr_ear_ground__p10", "cr_sh_ground__p10", "app_head_ground__min", "app_head_ground__p10"])
    rep("크런치", "어깨반동 없음", ["cr_trunk_lift__rr", "cr_trunk_lift__p90", "app_head_ground__rr"])
    rep("크런치", "허리 지면 고정", ["cr_trunk_lift__p90", "cr_trunk_lift__p10", "app_head_ground__rr"])
    k = c[c.exercise == "크런치"]
    for v in "CE":
        d = k[k.view == v]; ok = d["견갑골이 지면으로부터 충분히 올라옴"].astype(bool); a = d.type_key == "489"
        print(v, "ROM head_ground max>=0.2953 통과: 전조건정상", round(float((d.app_head_ground__max[a] >= 0.2953).mean()), 3), "견갑골충족", round(float((d.app_head_ground__max[ok] >= 0.2953).mean()), 3),
              "견갑골위반", round(float((d.app_head_ground__max[~ok] >= 0.2953).mean()), 3), "| 스윙 rng>=0.15", round(float((d.app_head_ground__rng >= 0.15).mean()), 3))
        print(v, "  사이클수 head_ground", d.cyc_hg.value_counts().sort_index().to_dict(), "몸통", d.cyc_trunk.value_counts().sort_index().to_dict())
        print(v, "  전조건정상 hg max", q(d.app_head_ground__max[a]), "| hg rr", q(d.app_head_ground__rr[a]), "| trunk p90", q(d.cr_trunk_lift__p90[a]), "| trunk rr", q(d.cr_trunk_lift__rr[a]))
if part == "leg":
    LR = ["app_hip_ang__min", "app_hip_ang__p10", "app_hip_ang__max", "app_hip_ang__rr", "lr_leg_elev__p90", "lr_leg_elev__p10", "lr_knee_ang__med", "lr_knee_ang__p10", "lr_ankle_h__p10", "lr_head_lift__med", "lr_head_trunk__med"]
    for cond in ("이완 시 다리 긴장유지", "허벅지와 종아리 각도 고정", "고개 숙임 여부", "허리 지면 고정"):
        rep("라잉 레그 레이즈", cond, LR)
    k = c[c.exercise == "라잉 레그 레이즈"]
    for v in "CE":
        d = k[k.view == v]; a = d.type_key == "473"
        print(v, "ROM hip_ang min<=119.5 통과: 전조건정상", round(float((d.app_hip_ang__min[a] <= 119.4981).mean()), 3), "전체", round(float((d.app_hip_ang__min <= 119.4981).mean()), 3),
              "| 스윙 rng>=25", round(float((d.app_hip_ang__rng >= 25).mean()), 3), "| 사이클", d.cyc_hip.value_counts().sort_index().to_dict())
        print(v, "  전조건정상 hip min", q(d.app_hip_ang__min[a]), "| max", q(d.app_hip_ang__max[a]), "| rr", q(d.app_hip_ang__rr[a]), "| rng", q(d.app_hip_ang__rng[a]))
        print(v, "  전체 hip min", q(d.app_hip_ang__min), "| rr", q(d.app_hip_ang__rr), "| knee med", q(d.lr_knee_ang__med))

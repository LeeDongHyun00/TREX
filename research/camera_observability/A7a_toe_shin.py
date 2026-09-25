#!/usr/bin/env python
"""A7a — MediaPipe 발끝 판독은 발을 보나, 정강이를 보나 (AIHub 바벨 스쿼트 720클립 · GT 3D/2D · MediaPipe full/app).

배경(2026-09-25): 실기기에서 발은 그대로 두고 넓게만 서면 발끝 판독이 +20~35° 벌어진다(원근으로 설명되는 몫은 +2~4°, A6).
같은 날 16:16 세트에서는 무릎이 벌어진 회(무릎 간격÷발목 간격 1.67)가 49/48° 로 읽혔다.
가설: MediaPipe 가 발끝 방향을 발 픽셀이 아니라 정강이(무릎→발목) 방향·다리 배치에서 추론한다.

방법. 관측 단위 = 클립 × 뷰 × 프레임(서 있음 s = GT 무릎각 최대, 바닥 b = 최소) × 다리(L/R).
  판독 = a + b·GT발방향 + c·GT정강이기울기 + d·GT발너비   (확장: + e·GT knee_out + f·GT KASR)
을 판독 후보(MP 2D full/app · MP 월드 · GT 2D 주석)마다 최소제곱으로 맞춘다. 95 % 구간은 세션(수행자×촬영일) 클러스터 부트스트랩.
  · GT 2D 주석 판독(사람이 찍은 점 = 기하만)도 정강이에 의존하면 → 투영 기하 문제.
  · MP 판독만 의존하면 → 모델 추론 문제.
부호: 바깥 = +, 사람 기준 좌우(A2_vars 규약).

입력(읽기 전용): A2_analysis 의 적재 함수(load_gt/load_mp/load_cams), outputs/A2/mp/lm_*_{full,app}.parquet, outputs/A2/cameras.parquet
출력: outputs/A7a/
  A7a_obs.parquet      관측 표(클립×뷰×프레임×다리: GT 진실 + 모든 판독 후보)
  A7a_regression.csv   회귀 계수·95 % 구간·부분 R²·잔차 SD·검출 가능성 지표
  A7a_bins.csv         GT 발 너비·정강이 기울기·KASR 구간별 잔차 편향(비선형성)
  A7a_tables.md        보고서용 표
"""
from __future__ import annotations

import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "aihub_fitness"))
import A2_vars as V  # noqa: E402
from A2_analysis import GJ, IX_GT, IX_MP, MIN_VIS, VIEWS, load_cams, load_gt, load_mp  # noqa: E402

OUT = HERE / "outputs" / "A7a"
OUT.mkdir(parents=True, exist_ok=True)
UP = np.array([0.0, 1.0, 0.0])
N_BOOT = 1000
RNG = np.random.default_rng(20260925)
MIN_FOOT_PX = 0.03      # 2D 발 벡터 최소 길이(정강이 px 대비) — 그보다 짧으면 각도 유보
FRAMES = ("stand", "bottom")
MAIN_VIEWS = ("C", "B", "D")
BASE_X = ["gt_toe", "gt_shin", "gt_stance"]
EXT_X = BASE_X + ["gt_knee_out", "gt_kasr"]
# 후보 (열 이름, 표시 이름, 단위가 각도인가)
CANDS = [
    ("MP2D_full:toe_at", "MP2D full 발목→발끝 각", True),
    ("MP2D_full:toe_ht", "MP2D full 뒤꿈치→발끝 각", True),
    ("MP2D_full:r_at", "MP2D full (발끝x−발목x)÷정강이", False),
    ("MP2D_full:r_ht", "MP2D full (발끝x−뒤꿈치x)÷정강이", False),
    ("MP2D_full:flen", "MP2D full 발 길이÷정강이", False),
    ("MPW0_full:toe_w", "MP월드 full 발목→발끝 수평각(y↑)", True),
    ("MPWg_full:toe_w", "MP월드 full 발목→발끝 수평각(중력)", True),
    ("MPW0_full:toe_wht", "MP월드 full 뒤꿈치→발끝 수평각(y↑)", True),
    ("MP2D_app:toe_at", "MP2D app 발목→발끝 각", True),
    ("MP2D_app:toe_ht", "MP2D app 뒤꿈치→발끝 각", True),
    ("MP2D_app:r_at", "MP2D app (발끝x−발목x)÷정강이", False),
    ("MP2D_app:r_ht", "MP2D app (발끝x−뒤꿈치x)÷정강이", False),
    ("MP2D_app:flen", "MP2D app 발 길이÷정강이", False),
    ("MPW0_app:toe_w", "MP월드 app 발목→발끝 수평각(y↑)", True),
    ("GT2D:toe_at", "GT2D 주석 발목→Foot 각", True),
    ("GT2D:r_at", "GT2D 주석 (Foot x−발목x)÷정강이", False),
    ("GT2D:flen", "GT2D 주석 발목–Foot 길이÷정강이", False),
    ("DIFF:toe_at", "MP2D full − GT2D 발목→발 각 차", True),
    ("DIFF_app:toe_at", "MP2D app − GT2D 발목→발 각 차", True),
]


# ------------------------------------------------------------------ 진실·판독 정의
def gt_truths(P: np.ndarray) -> dict:
    """P (N, 24, 3) GT cm(y 위). 다리별 발 방향·정강이 기울기·knee_out + 몸 수준 발 너비·KASR·무릎각."""
    F = V.frame_vars_3d(P, UP, IX_GT)
    g = lambda n: P[:, IX_GT[n], :]
    LH, RH = g("LHip"), g("RHip")
    up = np.broadcast_to(UP, LH.shape)
    xb = V._unit(V._flat(LH - RH, up))
    zb = V._unit(np.cross(xb, up))
    out = {"gt_stance": F["stance"], "gt_kasr": F["kasr"], "gt_knee": F["knee"]}
    for s, sg, kn, an in (("L", 1.0, "LKnee", "LAnkle"), ("R", -1.0, "RKnee", "RAnkle")):
        Kn, An = g(kn), g(an)
        sh = An - Kn
        # 정면(xb·up) 평면에서 무릎→발목이 수직에서 바깥으로 기운 각. 발목이 무릎보다 바깥 = +
        out[f"gt_shin_{s}"] = np.degrees(np.arctan2(sg * V._dot(sh, xb), -V._dot(sh, up)))
        # 수평면 정강이 방위(무릎이 발목보다 바깥 = +) — 바닥 프레임에서만 뜻이 있다
        shin_h = V._flat(Kn - An, up)
        out[f"gt_shinaz_{s}"] = np.where(np.linalg.norm(shin_h, axis=-1) >= 8.0,
                                         np.degrees(np.arctan2(sg * V._dot(shin_h, xb), V._dot(shin_h, zb))), np.nan)
        out[f"gt_toe_{s}"] = F[f"toe_{s}"]
        out[f"gt_knee_out_{s}"] = F[f"knee_out_{s}"]
    return out


def mp2d_readings(X: np.ndarray, VIS: np.ndarray) -> dict:
    """X (N, 33, 2) 원본 px(y 아래), VIS (N, 33) = min(visibility, presence)."""
    g = lambda i: X[:, i, :]
    LH, RH = g(23), g(24)
    lat = np.sign(LH[:, 0] - RH[:, 0])
    lat = np.where(lat == 0, np.nan, lat)
    out = {}
    for s, sg, K, A_, He, To in (("L", 1.0, 25, 27, 29, 31), ("R", -1.0, 26, 28, 30, 32)):
        ox = sg * lat
        Kn, An, Hl, Tp = g(K), g(A_), g(He), g(To)
        shin_px = np.linalg.norm(An - Kn, axis=-1)
        okv = (VIS[:, [K, A_, He, To, 23, 24]] >= MIN_VIS).all(1) & (shin_px > 1)
        dx_at, dy_at = Tp[:, 0] - An[:, 0], Tp[:, 1] - An[:, 1]
        dx_ht, dy_ht = Tp[:, 0] - Hl[:, 0], Tp[:, 1] - Hl[:, 1]
        ok_at = okv & (np.hypot(dx_at, dy_at) >= MIN_FOOT_PX * shin_px)
        ok_ht = okv & (np.hypot(dx_ht, dy_ht) >= MIN_FOOT_PX * shin_px)
        r = {
            "toe_at": (np.degrees(np.arctan2(ox * dx_at, np.abs(dy_at))), ok_at),
            "toe_ht": (np.degrees(np.arctan2(ox * dx_ht, np.abs(dy_ht))), ok_ht),
            "r_at": (ox * dx_at / shin_px, okv),
            "r_ht": (ox * dx_ht / shin_px, okv),
            "flen": (np.hypot(dx_ht, dy_ht) / shin_px, okv),
            "shin2d": (np.degrees(np.arctan2(ox * (An[:, 0] - Kn[:, 0]), An[:, 1] - Kn[:, 1])), okv),
            "kx": (ox * (Kn[:, 0] - An[:, 0]) / shin_px, okv),          # 무릎이 발목보다 화면에서 바깥(+)으로 얼마나(정강이 px 대비)
        }
        for k, (v, ok) in r.items():
            out[f"{k}_{s}"] = np.where(ok, v, np.nan)
    return out


def mpw_readings(W: np.ndarray, up: np.ndarray, VIS: np.ndarray) -> dict:
    """W (N, 33, 3) 월드 cm(y 위로 뒤집은 것), up (N, 3) 또는 (3,)."""
    g = lambda i: W[:, i, :]
    LH, RH = g(23), g(24)
    up = np.broadcast_to(up, LH.shape)
    xb = V._unit(V._flat(LH - RH, up))
    zb = V._unit(np.cross(xb, up))
    out = {}
    for s, sg, K, A_, He, To in (("L", 1.0, 25, 27, 29, 31), ("R", -1.0, 26, 28, 30, 32)):
        Kn, An, Hl, Tp = g(K), g(A_), g(He), g(To)
        okv = (VIS[:, [K, A_, He, To, 23, 24]] >= MIN_VIS).all(1)
        fh = V._flat(Tp - An, up)
        hh = V._flat(Tp - Hl, up)
        sh = An - Kn
        out[f"toe_w_{s}"] = np.where(okv & (np.linalg.norm(fh, axis=-1) >= 3.0),
                                     np.degrees(np.arctan2(sg * V._dot(fh, xb), V._dot(fh, zb))), np.nan)
        out[f"toe_wht_{s}"] = np.where(okv & (np.linalg.norm(hh, axis=-1) >= 3.0),
                                       np.degrees(np.arctan2(sg * V._dot(hh, xb), V._dot(hh, zb))), np.nan)
        out[f"shin_w_{s}"] = np.where(okv, np.degrees(np.arctan2(sg * V._dot(sh, xb), -V._dot(sh, up))), np.nan)
    return out


def gt2d_readings(Q: np.ndarray) -> dict:
    """Q (N, 24, 2) GT 2D px(y 아래). 발끝 대신 Foot(중족부)·뒤꿈치 없음."""
    g = lambda n: Q[:, GJ[n], :]
    LH, RH = g("LHip"), g("RHip")
    lat = np.sign(LH[:, 0] - RH[:, 0])
    lat = np.where(lat == 0, np.nan, lat)
    LS, RS, LA, RA = g("LShoulder"), g("RShoulder"), g("LAnkle"), g("RAnkle")
    out = {"stance2d": np.abs(LA[:, 0] - RA[:, 0]) / np.where(np.abs(LS[:, 0] - RS[:, 0]) < 1, np.nan, np.abs(LS[:, 0] - RS[:, 0]))}
    for s, sg, kn, an, ft in (("L", 1.0, "LKnee", "LAnkle", "LFoot"), ("R", -1.0, "RKnee", "RAnkle", "RFoot")):
        ox = sg * lat
        Kn, An, Ft = g(kn), g(an), g(ft)
        shin_px = np.linalg.norm(An - Kn, axis=-1)
        dx, dy = Ft[:, 0] - An[:, 0], Ft[:, 1] - An[:, 1]
        ok = np.isfinite(shin_px) & (shin_px > 1)
        ok_a = ok & (np.hypot(dx, dy) >= MIN_FOOT_PX * shin_px)
        out[f"toe_at_{s}"] = np.where(ok_a, np.degrees(np.arctan2(ox * dx, np.abs(dy))), np.nan)
        out[f"r_at_{s}"] = np.where(ok, ox * dx / shin_px, np.nan)
        out[f"flen_{s}"] = np.where(ok, np.hypot(dx, dy) / shin_px, np.nan)
        out[f"shin2d_{s}"] = np.where(ok, np.degrees(np.arctan2(ox * (An[:, 0] - Kn[:, 0]), An[:, 1] - Kn[:, 1])), np.nan)
        out[f"kx_{s}"] = np.where(ok, ox * (Kn[:, 0] - An[:, 0]) / shin_px, np.nan)
    return out


def take_frame(arr: np.ndarray, f: np.ndarray) -> np.ndarray:
    """arr (N, T, ...) 에서 클립별 프레임 f(−1 = 없음) 를 뽑아 (N, ...). 없는 클립은 NaN."""
    N = arr.shape[0]
    out = arr[np.arange(N), np.maximum(f, 0)].astype(float).copy()
    out[f < 0] = np.nan
    return out


# ------------------------------------------------------------------ 관측 표
def build_obs(sq, ids, A, G, s, b, valid, OKV, UPMP, mp) -> pd.DataFrame:
    N = len(ids)
    perf = (sq.performer + "@" + sq.day).to_numpy()
    rows = []
    for fname, f in (("stand", s), ("bottom", b)):
        Pf = take_frame(A, f)
        Tr = gt_truths(Pf)
        for vi, vl in enumerate(VIEWS):
            if vl not in MAIN_VIEWS:
                continue
            cols = {}
            g2 = gt2d_readings(take_frame(G[:, vi], f))
            for k, v in g2.items():
                cols[f"GT2D:{k}"] = v
            for variant, M in mp.items():
                Xf = take_frame(M["X"][:, vi], f)
                Wf = take_frame(M["W"][:, vi], f)
                VISf = take_frame(M["VIS"][:, vi], f)
                for k, v in mp2d_readings(Xf, VISf).items():
                    cols[f"MP2D_{variant}:{k}"] = v
                for k, v in mpw_readings(Wf, UP, VISf).items():
                    cols[f"MPW0_{variant}:{k}"] = v
                for k, v in mpw_readings(Wf, UPMP[:, vi], VISf).items():
                    cols[f"MPWg_{variant}:{k}"] = v
            use = valid & OKV[:, vi] & (f >= 0)
            for sd in ("L", "R"):
                d = pd.DataFrame({"clip_id": ids, "perf": perf, "view": vl, "frame": fname, "side": sd,
                                  "gt_toe": Tr[f"gt_toe_{sd}"], "gt_shin": Tr[f"gt_shin_{sd}"], "gt_shinaz": Tr[f"gt_shinaz_{sd}"],
                                  "gt_knee_out": Tr[f"gt_knee_out_{sd}"], "gt_stance": Tr["gt_stance"], "gt_kasr": Tr["gt_kasr"],
                                  "gt_knee": Tr["gt_knee"], "gt_stance2d": cols["GT2D:stance2d"]})
                for k, v in cols.items():
                    if k.endswith(f"_{sd}"):
                        d[k[:-2]] = v
                d = d[use].reset_index(drop=True)
                rows.append(d)
    obs = pd.concat(rows, ignore_index=True)
    for variant in mp:
        obs[f"DIFF{'' if variant == 'full' else '_' + variant}:toe_at"] = obs[f"MP2D_{variant}:toe_at"] - obs["GT2D:toe_at"]
    return obs


# ------------------------------------------------------------------ 회귀
def _lstsq(X, y):
    return np.linalg.lstsq(X, y, rcond=None)[0]


def fit(df: pd.DataFrame, ycol: str, xcols: list, n_boot: int = N_BOOT) -> dict | None:
    m = df[[ycol] + xcols].notna().all(1)
    d = df[m]
    if len(d) < 30:
        return None
    y = d[ycol].to_numpy(float)
    X = np.column_stack([np.ones(len(d))] + [d[c].to_numpy(float) for c in xcols])
    beta = _lstsq(X, y)
    res = y - X @ beta
    sse = float((res ** 2).sum())
    sst = float(((y - y.mean()) ** 2).sum())
    out = dict(n=int(len(d)), n_clip=int(d.clip_id.nunique()), n_cl=int(d.perf.nunique()), r2=1 - sse / sst if sst > 0 else np.nan,
               sd=float(np.sqrt(sse / max(len(d) - X.shape[1], 1))), mad_sd=float(1.4826 * np.median(np.abs(res - np.median(res)))),
               y_sd=float(y.std()))
    for j, c in enumerate(xcols):
        Xr = np.delete(X, j + 1, axis=1)
        sser = float(((y - Xr @ _lstsq(Xr, y)) ** 2).sum())
        out[f"pR2_{c}"] = (sser - sse) / sser if sser > 0 else np.nan
        out[f"x_sd_{c}"] = float(d[c].std())
    # 세션 클러스터 부트스트랩
    cl = d.perf.to_numpy()
    uniq, inv = np.unique(cl, return_inverse=True)
    groups = [np.flatnonzero(inv == i) for i in range(len(uniq))]
    B = np.empty((n_boot, X.shape[1]))
    for i in range(n_boot):
        pick = RNG.integers(0, len(uniq), len(uniq))
        idx = np.concatenate([groups[p] for p in pick])
        B[i] = _lstsq(X[idx], y[idx])
    lo, hi = np.percentile(B, [2.5, 97.5], axis=0)
    out["a"] = float(beta[0])
    for j, c in enumerate(xcols):
        out[f"b_{c}"], out[f"lo_{c}"], out[f"hi_{c}"] = float(beta[j + 1]), float(lo[j + 1]), float(hi[j + 1])
    out["_resid"] = pd.Series(res, index=d.index)
    return out


def run_regressions(obs: pd.DataFrame) -> tuple[pd.DataFrame, dict]:
    rows, resid = [], {}
    for vl in MAIN_VIEWS:
        for fname in FRAMES:
            sub = obs[(obs.view == vl) & (obs.frame == fname)]
            side_sets = [("LR", sub)] + ([("L", sub[sub.side == "L"]), ("R", sub[sub.side == "R"])] if vl != "C" else
                                         [("L", sub[sub.side == "L"]), ("R", sub[sub.side == "R"])])
            for sname, ss in side_sets:
                for col, name, is_deg in CANDS:
                    if col not in ss:
                        continue
                    for model, xcols in (("base", BASE_X), ("ext", EXT_X)):
                        if model == "ext" and (sname != "LR"):
                            continue
                        r = fit(ss, col, xcols, n_boot=N_BOOT if (sname == "LR") else 300)
                        if r is None:
                            continue
                        rr = {k: v for k, v in r.items() if k != "_resid"}
                        rr.update(view=vl, frame=fname, side=sname, cand=col, name=name, model=model, is_deg=is_deg)
                        b, c, dd, sd = r["b_gt_toe"], r["b_gt_shin"], r["b_gt_stance"], r["sd"]
                        rr["d15"] = 15 * b
                        rr["dprime15"] = 15 * b / sd if sd > 0 else np.nan
                        rr["dprime30"] = 30 * b / sd if sd > 0 else np.nan
                        rr["shin_equiv15"] = 15 * b / c if abs(c) > 1e-9 else np.nan        # 발끝 15° 와 같은 판독 변화를 내는 정강이 기울기(°)
                        rr["stance_equiv15"] = 15 * b / dd if abs(dd) > 1e-9 else np.nan    # 같은 변화를 내는 발 너비 변화(×)
                        rr["shin_1sd_over_sd"] = c * r["x_sd_gt_shin"] / sd if sd > 0 else np.nan   # 정강이 1 SD 효과 / 잔차 SD
                        rr["stance_1sd_over_sd"] = dd * r["x_sd_gt_stance"] / sd if sd > 0 else np.nan
                        rr["c_over_b"] = c / b if abs(b) > 1e-9 else np.nan
                        rows.append(rr)
                        if model == "base" and sname == "LR":
                            resid[(vl, fname, col)] = r["_resid"]
                    # 무릎 끌림 모형(기전 판별): MP 발끝 가로 오프셋 = a + b'·GT2D Foot 오프셋 + k·무릎 가로 오프셋 + d·발 너비
                    #   k > 0 → 발끝이 무릎 쪽으로 끌린다('무릎이 발끝 위' 사전지식), k < 0 → 정강이 선을 연장한다
                    if sname == "LR" and col.startswith("MP2D_") and col.split(":")[1] == "r_at":
                        variant = col.split(":")[0].split("_")[1]
                        for tag, kcol in (("knee_pull_gt", "GT2D:kx"), ("knee_pull_mp", f"MP2D_{variant}:kx")):
                            r = fit(ss, col, ["GT2D:r_at", kcol, "gt_stance"], n_boot=300)
                            if r is not None:
                                rr = {k: v for k, v in r.items() if k != "_resid"}
                                rr.update(view=vl, frame=fname, side=sname, cand=col, name=name, model=tag, is_deg=is_deg, kcol=kcol)
                                rows.append(rr)
                    # 기하 통제 모형: MP 판독 = a + b'·GT2D 같은 판독 + c·정강이 + d·발 너비 (같은 뷰·같은 프레임)
                    if sname == "LR" and col.startswith("MP2D_") and col.split(":")[1] in ("toe_at", "r_at"):
                        gcol = "GT2D:" + col.split(":")[1]
                        r = fit(ss, col, [gcol, "gt_shin", "gt_stance"], n_boot=300)
                        if r is not None:
                            rr = {k: v for k, v in r.items() if k != "_resid"}
                            rr.update(view=vl, frame=fname, side=sname, cand=col, name=name, model="geom_ctrl", is_deg=is_deg)
                            rows.append(rr)
    return pd.DataFrame(rows), resid


def run_bins(obs: pd.DataFrame, resid: dict) -> pd.DataFrame:
    bins = {
        "gt_stance": [-np.inf, 1.0, 1.15, 1.3, 1.4, np.inf],
        "gt_shin": [-np.inf, 0, 5, 10, 15, np.inf],
        "gt_kasr": [-np.inf, 0.9, 1.0, 1.1, 1.25, np.inf],
    }
    rows = []
    for (vl, fname, col), res in resid.items():
        if vl != "C":
            continue
        sub = obs.loc[res.index]
        for var, edges in bins.items():
            cat = pd.cut(sub[var], edges)
            for iv, g in res.groupby(cat, observed=True):
                if len(g) == 0:
                    continue
                rows.append(dict(view=vl, frame=fname, cand=col, var=var, bin=str(iv), lo=float(iv.left), n=int(len(g)), n_clip=int(sub.loc[g.index].clip_id.nunique()),
                                 resid_mean=float(g.mean()), resid_median=float(g.median()),
                                 resid_se=float(g.std(ddof=1) / np.sqrt(len(g))) if len(g) > 1 else np.nan))
    return pd.DataFrame(rows)


# ------------------------------------------------------------------ 표
def fmt(v, k=2, sign=False):
    if v is None or (isinstance(v, float) and not np.isfinite(v)):
        return "—"
    return f"{v:+.{k}f}" if sign else f"{v:.{k}f}"


def write_tables(obs: pd.DataFrame, reg: pd.DataFrame, bins: pd.DataFrame) -> None:
    L = ["# A7a 표 (자동 생성 — A7a_toe_shin.py)\n"]
    P = L.append
    # 분포
    P("## T1. GT 진실 분포 (C 뷰 관측 단위 = 클립×다리)\n")
    P("| 프레임 | n | GT 발 방향° p5/50/95 | GT 정강이 기울기° p5/50/95 | GT 발 너비 p5/50/95 | GT 2D 발 너비 p5/50/95 | GT KASR p5/50/95 | GT 무릎각° p50 |")
    P("|---|---|---|---|---|---|---|---|")
    for fname in FRAMES:
        d = obs[(obs.view == "C") & (obs.frame == fname)]
        q = lambda c: " / ".join(f"{d[c].quantile(p):.2f}" if c not in ("gt_toe", "gt_shin") else f"{d[c].quantile(p):.1f}" for p in (.05, .5, .95))
        P(f"| {fname} | {len(d)} | {q('gt_toe')} | {q('gt_shin')} | {q('gt_stance')} | {q('gt_stance2d')} | {q('gt_kasr')} | {d.gt_knee.median():.0f} |")
    P("")
    # 규제 변수 상관
    d = obs[(obs.view == "C")]
    for fname in FRAMES:
        dd = d[d.frame == fname][EXT_X]
        P(f"규제 변수 상관(C, {fname}, n={len(dd.dropna())}):\n")
        cm = dd.corr().round(2)
        P("| | " + " | ".join(cm.columns) + " |")
        P("|---|" + "---|" * len(cm.columns))
        for i, row in cm.iterrows():
            P(f"| {i} | " + " | ".join(f"{v:.2f}" for v in row) + " |")
        P("")
    # 정강이 단서의 충실도
    P("## T1b. 정강이 기울기 단서 — MP 2D·GT 2D 가 GT 3D 정강이 기울기를 얼마나 따르나 (C)\n")
    P("| 프레임 | r(MP2D full shin2d, GT shin) | 기울기 | r(GT2D shin2d, GT shin) | 기울기 | r(MP월드 shin, GT shin) |")
    P("|---|---|---|---|---|---|")
    for fname in FRAMES:
        dd = obs[(obs.view == "C") & (obs.frame == fname)]
        def rs(a, b):
            m = dd[[a, b]].dropna()
            if len(m) < 30:
                return "—", "—"
            r = np.corrcoef(m[a], m[b])[0, 1]
            sl = np.polyfit(m[b], m[a], 1)[0]
            return f"{r:.2f} (n={len(m)})", f"{sl:.2f}"
        r1, s1 = rs("MP2D_full:shin2d", "gt_shin")
        r2, s2 = rs("GT2D:shin2d", "gt_shin")
        r3, _ = rs("MPW0_full:shin_w", "gt_shin")
        P(f"| {fname} | {r1} | {s1} | {r2} | {s2} | {r3} |")
    P("")

    def reg_table(vl, fname, model="base", side="LR", cands=None):
        P(f"| 판독 | n(클립) | b 발방향 [95%] | c 정강이 [95%] | d 발너비 [95%] | pR² 발/정강이/너비 | R² | 잔차 SD | Δ15 | d′15 | 정강이 등가° | 너비 등가× |")
        P("|---|---|---|---|---|---|---|---|---|---|---|---|")
        sub = reg[(reg.view == vl) & (reg.frame == fname) & (reg.model == model) & (reg.side == side)]
        for col, name, is_deg in CANDS:
            if cands and col not in cands:
                continue
            r = sub[sub.cand == col]
            if r.empty:
                continue
            r = r.iloc[0]
            k = 2 if is_deg else 3
            kk = 1 if is_deg else 3
            P(f"| {name} | {r.n} ({r.n_clip}) | {fmt(r.b_gt_toe, k)} [{fmt(r.lo_gt_toe, k)}, {fmt(r.hi_gt_toe, k)}] "
              f"| {fmt(r.b_gt_shin, k, True)} [{fmt(r.lo_gt_shin, k, True)}, {fmt(r.hi_gt_shin, k, True)}] "
              f"| {fmt(r.b_gt_stance, kk, True)} [{fmt(r.lo_gt_stance, kk, True)}, {fmt(r.hi_gt_stance, kk, True)}] "
              f"| {fmt(r.pR2_gt_toe)}/{fmt(r.pR2_gt_shin)}/{fmt(r.pR2_gt_stance)} | {fmt(r.r2)} | {fmt(r.sd, 1 if is_deg else 3)} "
              f"| {fmt(r.d15, 1 if is_deg else 3)} | {fmt(r.dprime15)} | {fmt(r.shin_equiv15, 0)} | {fmt(r.stance_equiv15, 1)} |")
        P("")

    for fname in FRAMES:
        P(f"## T2{'a' if fname == 'stand' else 'b'}. C 정면 · {fname} — 기본 모형 (판독 = a + b·발방향 + c·정강이 + d·발너비), 다리 합산\n")
        reg_table("C", fname)
    # 확장 모형
    P("## T3. C 정면 — 확장 모형 (+ e·knee_out + f·KASR)\n")
    P("| 프레임 | 판독 | n | b 발방향 | c 정강이 [95%] | d 발너비 [95%] | e knee_out [95%] | f KASR [95%] | pR² 정강이/너비/knee_out/KASR | 잔차 SD |")
    P("|---|---|---|---|---|---|---|---|---|---|")
    for fname in FRAMES:
        sub = reg[(reg.view == "C") & (reg.frame == fname) & (reg.model == "ext") & (reg.side == "LR")]
        for col, name, is_deg in CANDS:
            r = sub[sub.cand == col]
            if r.empty:
                continue
            r = r.iloc[0]
            k = 2 if is_deg else 3
            P(f"| {fname} | {name} | {r.n} | {fmt(r.b_gt_toe, k)} | {fmt(r.b_gt_shin, k, True)} [{fmt(r.lo_gt_shin, k, True)}, {fmt(r.hi_gt_shin, k, True)}] "
              f"| {fmt(r.b_gt_stance, k, True)} [{fmt(r.lo_gt_stance, k, True)}, {fmt(r.hi_gt_stance, k, True)}] "
              f"| {fmt(r.b_gt_knee_out, 1 if is_deg else 3, True)} [{fmt(r.lo_gt_knee_out, 1 if is_deg else 3, True)}, {fmt(r.hi_gt_knee_out, 1 if is_deg else 3, True)}] "
              f"| {fmt(r.b_gt_kasr, 1 if is_deg else 3, True)} [{fmt(r.lo_gt_kasr, 1 if is_deg else 3, True)}, {fmt(r.hi_gt_kasr, 1 if is_deg else 3, True)}] "
              f"| {fmt(r.pR2_gt_shin)}/{fmt(r.pR2_gt_stance)}/{fmt(r.pR2_gt_knee_out)}/{fmt(r.pR2_gt_kasr)} | {fmt(r.sd, 1 if is_deg else 3)} |")
    P("")
    # 기하 통제 모형
    P("## T4. C 정면 — 기하 통제 모형 (MP 판독 = a + b′·같은 뷰 GT2D 판독 + c·정강이 + d·발너비)\n")
    P("| 프레임 | 판독 | n | b′ GT2D [95%] | c 정강이 [95%] | d 발너비 [95%] | pR² GT2D/정강이/너비 | R² | 잔차 SD |")
    P("|---|---|---|---|---|---|---|---|---|")
    for fname in FRAMES:
        sub = reg[(reg.view == "C") & (reg.frame == fname) & (reg.model == "geom_ctrl")]
        for r in sub.to_dict("records"):
            gc = "GT2D:" + r["cand"].split(":")[1]
            k = 2 if r["is_deg"] else 3
            gv = lambda pre: r.get(f"{pre}_{gc}", np.nan)
            P(f"| {fname} | {r['name']} | {r['n']} | {fmt(gv('b'), k)} [{fmt(gv('lo'), k)}, {fmt(gv('hi'), k)}] "
              f"| {fmt(r['b_gt_shin'], k, True)} [{fmt(r['lo_gt_shin'], k, True)}, {fmt(r['hi_gt_shin'], k, True)}] "
              f"| {fmt(r['b_gt_stance'], k, True)} [{fmt(r['lo_gt_stance'], k, True)}, {fmt(r['hi_gt_stance'], k, True)}] "
              f"| {fmt(gv('pR2'))}/{fmt(r['pR2_gt_shin'])}/{fmt(r['pR2_gt_stance'])} | {fmt(r['r2'])} | {fmt(r['sd'], 1 if r['is_deg'] else 3)} |")
    P("")
    # 비선형
    P("## T5. C 정면 — 구간별 잔차 편향 (기본 모형 잔차 평균 ± SE, n 관측(클립))\n")
    show = ["MP2D_full:toe_at", "MP2D_app:toe_at", "MP2D_full:toe_ht", "GT2D:toe_at", "MPW0_full:toe_w", "DIFF:toe_at"]
    for var, label in (("gt_stance", "GT 발 너비(발목÷어깨)"), ("gt_shin", "GT 정강이 바깥 기울기°"), ("gt_kasr", "GT KASR(무릎 간격÷발목 간격)")):
        for fname in FRAMES:
            sub = bins[(bins["var"] == var) & (bins.frame == fname) & (bins.cand.isin(show))]
            if sub.empty:
                continue
            piv = sub.pivot(index="bin", columns="cand", values=["resid_mean", "resid_se", "n", "n_clip"])
            piv = piv.loc[sub.drop_duplicates("bin").sort_values("lo").bin.tolist()]
            P(f"**{label} · {fname}**\n")
            P("| 구간 | " + " | ".join(show) + " |")
            P("|---|" + "---|" * len(show))
            for bn in piv.index:
                cells = []
                for c in show:
                    try:
                        m, se, n, nc = piv.loc[bn, ("resid_mean", c)], piv.loc[bn, ("resid_se", c)], piv.loc[bn, ("n", c)], piv.loc[bn, ("n_clip", c)]
                        cells.append(f"{m:+.1f}±{se:.1f} (n={int(n)}/{int(nc)})" if np.isfinite(m) else "—")
                    except KeyError:
                        cells.append("—")
                P(f"| {bn} | " + " | ".join(cells) + " |")
            P("")
    # B/D 참고
    P("## T6. B/D 앞 사선 — 참고 (기본 모형, 다리별)\n")
    P("| 뷰 | 프레임 | 다리 | 판독 | n | b 발방향 [95%] | c 정강이 [95%] | d 발너비 [95%] | 잔차 SD | d′15 |")
    P("|---|---|---|---|---|---|---|---|---|---|")
    for vl in ("B", "D"):
        for fname in FRAMES:
            for sd in ("L", "R"):
                sub = reg[(reg.view == vl) & (reg.frame == fname) & (reg.model == "base") & (reg.side == sd)]
                for col in ("MP2D_full:toe_at", "MP2D_full:toe_ht", "GT2D:toe_at", "MPW0_full:toe_w"):
                    r = sub[sub.cand == col]
                    if r.empty:
                        continue
                    r = r.iloc[0]
                    P(f"| {vl} | {fname} | {sd} | {col} | {r.n} | {fmt(r.b_gt_toe)} [{fmt(r.lo_gt_toe)}, {fmt(r.hi_gt_toe)}] "
                      f"| {fmt(r.b_gt_shin, 2, True)} [{fmt(r.lo_gt_shin, 2, True)}, {fmt(r.hi_gt_shin, 2, True)}] "
                      f"| {fmt(r.b_gt_stance, 1, True)} [{fmt(r.lo_gt_stance, 1, True)}, {fmt(r.hi_gt_stance, 1, True)}] | {fmt(r.sd, 1)} | {fmt(r.dprime15)} |")
    P("")
    # C 다리별
    P("## T7. C 정면 — 다리별 (좌우 대칭 확인, 기본 모형, 발목→발끝 각)\n")
    P("| 프레임 | 다리 | 판독 | n | b [95%] | c [95%] | d [95%] | 잔차 SD |")
    P("|---|---|---|---|---|---|---|---|")
    for fname in FRAMES:
        for sd in ("L", "R"):
            sub = reg[(reg.view == "C") & (reg.frame == fname) & (reg.model == "base") & (reg.side == sd)]
            for col in ("MP2D_full:toe_at", "GT2D:toe_at"):
                r = sub[sub.cand == col]
                if r.empty:
                    continue
                r = r.iloc[0]
                P(f"| {fname} | {sd} | {col} | {r.n} | {fmt(r.b_gt_toe)} [{fmt(r.lo_gt_toe)}, {fmt(r.hi_gt_toe)}] "
                  f"| {fmt(r.b_gt_shin, 2, True)} [{fmt(r.lo_gt_shin, 2, True)}, {fmt(r.hi_gt_shin, 2, True)}] "
                  f"| {fmt(r.b_gt_stance, 1, True)} [{fmt(r.lo_gt_stance, 1, True)}, {fmt(r.hi_gt_stance, 1, True)}] | {fmt(r.sd, 1)} |")
    P("")
    P("## T8. C 정면 — 무릎 끌림 모형 (MP 발끝 가로 오프셋÷정강이 = a + b′·GT2D Foot 오프셋 + k·무릎 가로 오프셋 + d·발너비)\n")
    P("k > 0 이면 발끝이 무릎의 가로 위치 쪽으로 끌린다('무릎이 발끝 위' 사전지식). k < 0 이면 정강이 선을 연장한다. 오프셋은 정강이 px 로 나눈 값, 바깥 +.\n")
    P("| 프레임 | 판독 | 무릎 오프셋 출처 | n | b′ [95%] | k [95%] | d 발너비 [95%] | pR² Foot/무릎/너비 | R² | 잔차 SD |")
    P("|---|---|---|---|---|---|---|---|---|---|")
    for fname in FRAMES:
        sub = reg[(reg.view == "C") & (reg.frame == fname) & (reg.model.isin(["knee_pull_gt", "knee_pull_mp"]))]
        for r in sub.to_dict("records"):
            kc = r["kcol"]
            g = lambda pre, c: r.get(f"{pre}_{c}", np.nan)
            P(f"| {fname} | {r['name']} | {kc} | {r['n']} | {fmt(g('b', 'GT2D:r_at'))} [{fmt(g('lo', 'GT2D:r_at'))}, {fmt(g('hi', 'GT2D:r_at'))}] "
              f"| {fmt(g('b', kc), 2, True)} [{fmt(g('lo', kc), 2, True)}, {fmt(g('hi', kc), 2, True)}] "
              f"| {fmt(r['b_gt_stance'], 3, True)} [{fmt(r['lo_gt_stance'], 3, True)}, {fmt(r['hi_gt_stance'], 3, True)}] "
              f"| {fmt(g('pR2', 'GT2D:r_at'))}/{fmt(g('pR2', kc))}/{fmt(r['pR2_gt_stance'])} | {fmt(r['r2'])} | {fmt(r['sd'], 3)} |")
    P("")
    kxd = obs[obs.view == "C"]
    P("무릎 가로 오프셋(GT2D kx, 정강이 px 대비, 바깥 +) 분포 C: " + " · ".join(
        f"{fname} p5/50/95 = {kxd[kxd.frame == fname]['GT2D:kx'].quantile(.05):.2f}/{kxd[kxd.frame == fname]['GT2D:kx'].median():.2f}/{kxd[kxd.frame == fname]['GT2D:kx'].quantile(.95):.2f}"
        for fname in FRAMES))
    P("")
    (OUT / "A7a_tables.md").write_text("\n".join(L), encoding="utf-8")


# ------------------------------------------------------------------ 메인
def main():
    sq, ids, A, G, ok, conds, keymap = load_gt()
    N = len(ids)
    cam, OKV, UPMP, RC, AZ = load_cams(ids, sq)
    Fgt = V.frame_vars_3d(A, UP, IX_GT)
    k = Fgt["knee"]
    s = np.where(np.isnan(k), -np.inf, k).argmax(1)
    b = np.where(np.isnan(k), np.inf, k).argmin(1)
    valid = (np.nanmax(k, 1) - np.nanmin(k, 1)) >= 40
    s[~valid], b[~valid] = -1, -1
    print(f"[gt] 클립 {N}, 반복 판정 가능 {valid.sum()} | 카메라 OK C {OKV[:, 2].sum()} B {OKV[:, 1].sum()} D {OKV[:, 3].sum()}")
    mp = {v: load_mp(keymap, N, v) for v in ("full", "app")}
    obs = build_obs(sq, ids, A, G, s, b, valid, OKV, UPMP, mp)
    obs.to_parquet(OUT / "A7a_obs.parquet", index=False)
    print(f"[obs] {len(obs)} 관측 (뷰 {sorted(obs.view.unique())}, 프레임 {FRAMES}, 다리 L/R)")
    reg, resid = run_regressions(obs)
    reg.to_csv(OUT / "A7a_regression.csv", index=False, encoding="utf-8-sig")
    bins = run_bins(obs, resid)
    bins.to_csv(OUT / "A7a_bins.csv", index=False, encoding="utf-8-sig")
    write_tables(obs, reg, bins)
    print("[done]", OUT)


if __name__ == "__main__":
    main()

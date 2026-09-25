#!/usr/bin/env python
"""A2 분석 — 실제 MediaPipe 가 뷰마다 스쿼트 변수를 GT 대비 얼마나 잃는가 (AIHub 바벨 스쿼트, 5뷰 동시 촬영 + GT 3D).

입력(읽기 전용)
  research/aihub_fitness/outputs/{clips,conditions,kp3d,kp3d_frame_ok,kp2d}.parquet
  research/camera_observability/outputs/A2/mp/lm_<tar>_{full,app}.parquet   (A2_mp_infer.py)
  research/camera_observability/outputs/A2/cameras.parquet                    (A2_camera.py, DLT)
출력: research/camera_observability/outputs/A2/
  A2_rep_values.parquet     클립 × 표현(GT3D/GT2D/MP2D/MPW0/MPWg) × 변형 × 뷰 의 반복 값(긴 형식)
  A2_var_fidelity.csv       변수·후보·뷰별 n, 적용률, r, ρ, 수행자 내 r, 편향, MAE, 보정 잔차 SD, 부호 일치
  A2_condition_auc.csv      AIHub 조건 라벨 AUC (원값 / 수행자 중심화), GT3D·GT2D·MP 비교
  A2_landmark_quality.csv   뷰별 관절 가시성·존재·px 오차(몸통 정규화)·좌우 뒤바뀜
  A2_foot_point.csv         GT 'Foot' 가 MediaPipe 뒤꿈치→발끝 축의 어디에 있는가
  A2_world_axes.csv         MediaPipe 월드 좌표의 카메라 축 정렬(회전각)·축별 오차(깊이 축 포함)
  A2_frame_select.csv       MediaPipe 가 스스로 고른 서 있음/바닥 프레임의 일치율과 그 때문의 오차
  A2_tables.md              위 표의 요약 피벗(보고서 작성용)

반복 단위: AIHub 클립은 16프레임이 여러 반복(보통 3~4회)에 걸친 성긴 표본이다. '반복 통계' = 클립의 GT 3D 무릎각 최대 프레임(서 있음)과
최소 프레임(바닥). MediaPipe 값도 **같은 프레임**에서 잰다(측정 충실도 분리). MediaPipe 가 스스로 고른 프레임의 효과는 A2_frame_select.csv.
"""
from __future__ import annotations

import glob
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import pearsonr, spearmanr
from sklearn.metrics import roc_auc_score

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "aihub_fitness"))
from parse_labels import JOINTS_SHORT, KP2D_COLS, KP3D_COLS  # noqa: E402

import A2_vars as V  # noqa: E402

SRC = HERE.parent / "aihub_fitness" / "outputs"
OUT = HERE / "outputs" / "A2"
VIEWS = ["A", "B", "C", "D", "E"]
VNAME = {"A": "A 뒤-오른", "B": "B 앞-오른", "C": "C 정면", "D": "D 앞-왼", "E": "E 뒤-왼"}
T = 17
MIN_VIS = 0.5  # 앱 PostureAnalyzer.MIN_VISIBILITY (min(visibility, presence))
GJ = {n: i for i, n in enumerate(JOINTS_SHORT)}
IX_GT = dict(LHip=GJ["LHip"], RHip=GJ["RHip"], LKnee=GJ["LKnee"], RKnee=GJ["RKnee"], LAnkle=GJ["LAnkle"], RAnkle=GJ["RAnkle"],
             LShoulder=GJ["LShoulder"], RShoulder=GJ["RShoulder"], LToe=GJ["LFoot"], RToe=GJ["RFoot"], LHeel=None, RHeel=None)
IX_MP = dict(LHip=23, RHip=24, LKnee=25, RKnee=26, LAnkle=27, RAnkle=28, LShoulder=11, RShoulder=12,
             LToe=31, RToe=32, LHeel=29, RHeel=30)
LOWER = {"hip": (23, 24, "LHip", "RHip"), "knee": (25, 26, "LKnee", "RKnee"), "ankle": (27, 28, "LAnkle", "RAnkle"),
         "heel": (29, 30, None, None), "toe": (31, 32, "LFoot", "RFoot"), "shoulder": (11, 12, "LShoulder", "RShoulder")}


# ------------------------------------------------------------------ 적재
def load_gt():
    clips = pd.read_parquet(SRC / "clips.parquet", columns=["clip_id", "exercise", "performer", "type_key", "day"])
    sq = clips[clips.exercise == "바벨 스쿼트"].sort_values("clip_id").reset_index(drop=True)
    ids = sq.clip_id.tolist()
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
    k2 = pd.read_parquet(SRC / "kp2d.parquet", filters=[("clip_id", "in", ids)])
    k2 = k2[k2.img_key != ""]
    G = np.full((N, 5, T, 24, 2), np.nan)
    vi = k2.view_letter.map({v: i for i, v in enumerate(VIEWS)}).to_numpy()
    G[k2.clip_id.map(cix).to_numpy(), vi, k2.frame_idx.to_numpy().astype(int)] = k2[KP2D_COLS].to_numpy(float).reshape(-1, 24, 2)
    G[~np.broadcast_to(ok[:, None, :], G.shape[:3])] = np.nan    # GT 3D 불량 프레임은 2D 도 비교에서 제외
    conds = pd.read_parquet(SRC / "conditions.parquet")
    conds = conds[conds.clip_id.isin(ids)].pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first")
    keymap = k2[["img_key", "clip_id", "view_letter", "frame_idx"]].copy()
    keymap["ci"] = keymap.clip_id.map(cix)
    keymap["vi"] = keymap.view_letter.map({v: i for i, v in enumerate(VIEWS)})
    return sq, ids, A, G, ok, conds.reindex(ids), keymap


def load_mp(keymap: pd.DataFrame, N: int, variant: str):
    files = sorted(glob.glob(str(OUT / "mp" / f"lm_*_{variant}.parquet")))
    df = pd.concat([pd.read_parquet(f) for f in files], ignore_index=True).merge(keymap, on="img_key", how="inner")
    X = np.full((N, 5, T, 33, 2), np.nan)
    Z = np.full((N, 5, T, 33), np.nan)
    VIS = np.full((N, 5, T, 33), np.nan)
    VV = np.full((N, 5, T, 33), np.nan)
    PP = np.full((N, 5, T, 33), np.nan)
    W = np.full((N, 5, T, 33, 3), np.nan)
    DET = np.zeros((N, 5, T), bool)
    HAS = np.zeros((N, 5, T), bool)
    ci, vi, fi = df.ci.to_numpy(), df.vi.to_numpy(), df.frame_idx.to_numpy().astype(int)
    HAS[ci, vi, fi] = True
    d = df.detected.to_numpy().astype(bool)
    DET[ci, vi, fi] = d
    x = df[[f"l{i}_x" for i in range(33)]].to_numpy(float)
    y = df[[f"l{i}_y" for i in range(33)]].to_numpy(float)
    s = df.crop_s.to_numpy(float)[:, None]
    x = df.crop_x0.to_numpy(float)[:, None] + x / s          # app 변형 → 원본 px (full 은 x0=0, s=1)
    y = df.crop_y0.to_numpy(float)[:, None] + y / s
    X[ci, vi, fi] = np.stack([x, y], -1)
    v = df[[f"l{i}_v" for i in range(33)]].to_numpy(float)
    p = df[[f"l{i}_p" for i in range(33)]].to_numpy(float)
    VV[ci, vi, fi], PP[ci, vi, fi] = v, p
    VIS[ci, vi, fi] = np.minimum(v, p)
    w = df[[f"w{i}_{a}" for i in range(33) for a in "xyz"]].to_numpy(float).reshape(-1, 33, 3) * 100.0
    w[..., 1] *= -1.0   # 앱과 같은 변환: y 위 +, z 반전 (spec §3)
    w[..., 2] *= -1.0
    W[ci, vi, fi] = w
    for arr in (X, W):
        arr[~DET] = np.nan
    return dict(X=X, W=W, VIS=VIS, V=VV, P=PP, DET=DET, HAS=HAS)


def load_cams(ids, sq):
    cam = pd.read_parquet(OUT / "cameras.parquet")
    nom = {"A": -133, "B": -35, "C": 0, "D": 41, "E": 133}
    cam["daz"] = (cam.az_deg - cam.view_letter.map(nom) + 180) % 360 - 180
    cam["cam_ok"] = (cam.reproj_px < 8) & (cam.daz.abs() < 30)
    good = cam[cam.cam_ok]
    up = good.groupby(["day", "view_letter"])[["up_cam_x", "up_cam_y", "up_cam_z"]].median()
    up = up.div(np.linalg.norm(up.to_numpy(), axis=1), axis=0)
    Rm = good.groupby(["day", "view_letter"])[[f"R{i}{j}" for i in range(3) for j in range(3)]].median()
    N = len(ids)
    OKV = np.zeros((N, 5), bool)
    UPMP = np.full((N, 5, 3), np.nan)
    RC = np.full((N, 5, 3, 3), np.nan)
    AZ = np.full((N, 5), np.nan)
    cix = {c: i for i, c in enumerate(ids)}
    day = dict(zip(sq.clip_id, sq.day))
    for r in cam.itertuples():
        i, v = cix.get(r.clip_id), VIEWS.index(r.view_letter)
        if i is None:
            continue
        OKV[i, v] = bool(r.cam_ok)
        AZ[i, v] = r.az_deg
    for i, c in enumerate(ids):
        for v, vl in enumerate(VIEWS):
            key = (day[c], vl)
            if key in up.index:
                u = up.loc[key].to_numpy()
                UPMP[i, v] = (u[0], -u[1], -u[2])          # OpenCV 카메라 좌표 → MediaPipe 월드(뒤집은 것) 좌표
                Mr = Rm.loc[key].to_numpy().reshape(3, 3)
                U_, _, Vt_ = np.linalg.svd(Mr)                 # 원소별 중앙값 → 가장 가까운 회전
                RC[i, v] = U_ @ Vt_
    return cam, OKV, UPMP, RC, AZ


# ------------------------------------------------------------------ 통계
def fid_metrics(t, c, perf, same_unit, signed):
    """t = 진실, c = 후보. 이상치(GT Foot 잡음 등)에 강한 지표를 주로 쓴다.
    rho = Spearman, rho_w = 세션(수행자×날짜) 평균을 뺀 뒤 Spearman(= 개인 기준선 대비 변화 추적력),
    cal_sd = Theil–Sen 으로 t ≈ a + b·c 보정한 잔차의 강건 SD(1.4826·MAD, 진실 단위) — 후보로 진실을 얼마나 정밀하게 되살리나,
    bias_med / mdae = 같은 단위일 때 중앙값 편향 / 절대오차 중앙값, sign = 같은 개념(같은 키)일 때만 부호 일치율."""
    from scipy.stats import theilslopes
    m = np.isfinite(t) & np.isfinite(c)
    n = int(m.sum())
    out = dict(n=n)
    if n < 20:
        return out
    t, c, perf = t[m], c[m], perf[m]
    out["r"] = float(pearsonr(t, c)[0])
    out["rho"] = float(spearmanr(t, c)[0])
    tc = t - pd.Series(t).groupby(perf).transform("mean").to_numpy()
    cc = c - pd.Series(c).groupby(perf).transform("mean").to_numpy()
    if np.std(tc) > 0 and np.std(cc) > 0:
        out["rho_w"] = float(spearmanr(tc, cc)[0])
    mad = lambda x: 1.4826 * float(np.median(np.abs(x - np.median(x))))
    try:
        sl, ic, _, _ = theilslopes(t, c)
        out["cal_sd"] = mad(t - (ic + sl * c))
        out["cal_slope"] = float(sl)
    except Exception:
        pass
    out["truth_sd"] = mad(t)
    out["truth_sd_w"] = mad(tc)
    if same_unit:
        d = c - t
        out["bias_med"] = float(np.median(d))
        out["mae"] = float(np.mean(np.abs(d)))
        out["mdae"] = float(np.median(np.abs(d)))
        out["mdae_debiased"] = float(np.median(np.abs(d - np.median(d))))
        # 세션(수행자×날짜)별 평균 오차를 뺀 오차의 강건 SD — '시작 자세·개인 기준 대비' 로 쓸 때 남는 잡음
        dw = d - pd.Series(d).groupby(perf).transform("mean").to_numpy()
        out["err_sd_w"] = mad(dw)
        if signed:
            out["sign"] = float(np.mean(np.sign(c) == np.sign(t)))
            big = np.abs(t) >= np.quantile(np.abs(t), 0.5)
            out["sign_big"] = float(np.mean(np.sign(c[big]) == np.sign(t[big])))
    return out


def auc_pair(y, x, perf, direction):
    m = np.isfinite(x) & np.isfinite(y)
    if m.sum() < 30 or len(np.unique(y[m])) < 2:
        return dict(n=int(m.sum()))
    y, x, perf = y[m], x[m] * direction, perf[m]
    xc = x - pd.Series(x).groupby(perf).transform("mean").to_numpy()
    return dict(n=int(m.sum()), auc=float(roc_auc_score(y, x)), auc_within=float(roc_auc_score(y, xc)))


# (변수 묶음, 진실 키, 후보 키, 후보 표현 목록, 같은 단위?, 부호 있음?)
SPECS = [
    ("R_depth 허벅지각(°)", "depth_deg", "depth_deg", ("GT2D", "MP2D", "MPW0", "MPWg"), True, True),
    ("R_depth 비(sin)", "depth_ratio", "depth_ratio", ("GT2D", "MP2D", "MPW0", "MPWg"), True, True),
    ("R_hip_drop", "hip_drop", "hip_drop", ("GT2D", "MP2D", "MPW0", "MPWg"), True, False),
    ("F_knee_foot 3D각(°)", "kf", "kf", ("MPW0", "MPWg"), True, True),
    ("F_knee_foot ← knee_out", "kf", "knee_out", ("GT3D", "GT2D", "MP2D", "MPW0", "MPWg"), False, True),
    ("F_knee_foot ← FPPA", "kf", "fppa", ("GT2D", "MP2D"), False, True),
    ("F_knee_foot ← KASR", "kf", "kasr", ("GT3D", "GT2D", "MP2D", "MPW0", "MPWg"), False, False),
    ("F_knee_foot ← 무릎x/발끝x 비", "kf", "kratio", ("GT2D", "MP2D"), False, False),
    ("knee_out 자체(진실=GT3D knee_out)", "knee_out", "knee_out", ("GT2D", "MP2D", "MPW0", "MPWg"), True, True),
    ("F_heel ← 발목 상승", "heel_ankle", "heel_ankle", ("GT2D", "MP2D"), False, False),
    ("F_heel ← 발목−발끝 높이차 변화", "heel_ankle", "heel_aot", ("GT3D", "GT2D", "MP2D", "MPW0", "MPWg"), False, False),
    ("F_heel ← 뒤꿈치−발끝(heel_lift) 변화", "heel_ankle", "heel_lift", ("MP2D", "MPW0", "MPWg"), False, False),
    ("T_lean 바닥(°)", "lean_b", "lean_b", ("GT2D", "MP2D", "MPW0", "MPWg"), True, False),
    ("T_lean 바닥−서있음(°)", "lean_d", "lean_d", ("GT2D", "MP2D", "MPW0", "MPWg"), True, True),
    ("F_lateral 골반 기울기", "obl", "obl", ("GT2D", "MP2D", "MPW0", "MPWg"), True, True),
    ("F_lateral 골반 좌우 이동", "shift", "shift", ("GT2D", "MP2D", "MPW0", "MPWg"), True, True),
    ("F_lateral 무릎 굽힘 차(°)", "kasym", "kasym", ("GT2D", "MP2D", "MPW0", "MPWg"), True, True),
    ("S_toe 발끝각(°)", "toe", "toe", ("GT2D", "MP2D", "MPW0", "MPWg"), True, True),
    ("S_stance 발목÷어깨 간격", "stance", "stance", ("GT2D", "MP2D", "MPW0", "MPWg"), True, False),
]
# 조건 라벨 → (후보 키, 기대 방향: 오류 쪽이 +1 이면 값이 큼)
COND = {
    "발과 무릎의 방향 일치": [("kf", -1), ("knee_out", -1), ("kasr", -1), ("fppa", -1), ("kratio", -1)],
    "발바닥 지면 고정": [("heel_ankle", 1), ("heel_aot", 1), ("heel_lift", 1)],
    "척추의 중립": [("lean_b", 1), ("lean_d", 1)],
}


# ------------------------------------------------------------------ 메인
def main():
    sq, ids, A, G, ok, conds, keymap = load_gt()
    N = len(ids)
    perf = (sq.performer + "@" + sq.day).to_numpy()      # 세션(수행자×촬영일) — 두 번 찍힌 수행자는 날짜별로 분리
    cam, OKV, UPMP, RC, AZ = load_cams(ids, sq)

    # 서 있음 / 바닥 프레임 (GT 3D 무릎각)
    Fgt = V.frame_vars_3d(A, np.array([0.0, 1.0, 0.0]), IX_GT)
    k = Fgt["knee"]
    s = np.where(np.isnan(k), -np.inf, k).argmax(1)
    b = np.where(np.isnan(k), np.inf, k).argmin(1)
    valid = (np.nanmax(k, 1) - np.nanmin(k, 1)) >= 40
    s[~valid], b[~valid] = -1, -1
    print(f"[gt] 클립 {N}, 반복 판정 가능 {valid.sum()} (무릎각 범위 ≥ 40°)")
    Rgt = V.rep_vars_3d(Fgt, s, b, absolute_height=True)

    rows = []           # 긴 형식 반복 값
    def push(rep, variant, v, R):
        d = pd.DataFrame(R)
        d["clip_id"], d["rep"], d["variant"], d["view"] = ids, rep, variant, v
        rows.append(d)
    push("GT3D", "-", "-", Rgt)
    for vi, vl in enumerate(VIEWS):
        Fg2 = V.frame_vars_2d(G[:, vi], IX_GT)
        R = V.rep_vars_2d(Fg2, s, b)
        for kk in R:
            R[kk] = np.where(OKV[:, vi], R[kk], np.nan)
        push("GT2D", "-", vl, R)

    mp = {}
    fsel_rows = []
    for variant in ("full", "app"):
        files = glob.glob(str(OUT / "mp" / f"lm_*_{variant}.parquet"))
        if not files:
            continue
        M = load_mp(keymap, N, variant)
        mp[variant] = M
        for gated in (True, False):
            X = M["X"].copy()
            W = M["W"].copy()
            if gated:
                bad = ~(M["VIS"] >= MIN_VIS)
                X[bad] = np.nan
                W[bad] = np.nan
            tag = "" if gated else "_ungated"
            for vi, vl in enumerate(VIEWS):
                F2 = V.frame_vars_2d(X[:, vi], IX_MP)
                R = V.rep_vars_2d(F2, s, b)
                for kk in R:
                    R[kk] = np.where(OKV[:, vi], R[kk], np.nan)
                push("MP2D", variant + tag, vl, R)
                for rep, up in (("MPW0", np.array([0.0, 1.0, 0.0])), ("MPWg", UPMP[:, vi][:, None, :])):
                    Fw = V.frame_vars_3d(W[:, vi], up, IX_MP)
                    R = V.rep_vars_3d(Fw, s, b, absolute_height=False)
                    for kk in R:
                        R[kk] = np.where(OKV[:, vi], R[kk], np.nan)
                    push(rep, variant + tag, vl, R)
                    # 프레임 선택 민감도: MediaPipe 가 스스로 고른 프레임(월드 무릎각)
                    if rep == "MPWg" and gated:
                        km = Fw["knee"]
                        s2 = np.where(np.isnan(km), -np.inf, km).argmax(1)
                        b2 = np.where(np.isnan(km), np.inf, km).argmin(1)
                        has = np.isfinite(km).sum(1) >= 8
                        s2[~(has & valid)], b2[~(has & valid)] = -1, -1
                        R2 = V.rep_vars_3d(Fw, s2, b2, absolute_height=False)
                        m = valid & has & OKV[:, vi]
                        gt_at_b2 = V._take(Fgt["knee"], np.where(m, b2, -1))
                        gap = gt_at_b2[m] - V._take(Fgt["knee"], b)[m]
                        row = dict(variant=variant, view=vl, n=int(m.sum()),
                                   same_b=float(np.mean(b2[m] == b[m])), same_s=float(np.mean(s2[m] == s[m])),
                                   gt_knee_gap_b_mean=float(np.nanmean(gap)), gt_knee_gap_b_p90=float(np.nanpercentile(gap, 90)))
                        for kk in ("depth_deg", "depth_ratio", "hip_drop", "kf", "knee_out", "lean_b", "heel_aot", "toe"):
                            a1 = fid_metrics(Rgt[kk], np.where(m, R[kk], np.nan), perf, True, True)
                            a2 = fid_metrics(Rgt[kk], np.where(m, R2[kk], np.nan), perf, True, True)
                            row[f"{kk}_rho_gtframe"], row[f"{kk}_rho_mpframe"] = a1.get("rho"), a2.get("rho")
                            row[f"{kk}_mdae_gtframe"], row[f"{kk}_mdae_mpframe"] = a1.get("mdae"), a2.get("mdae")
                        fsel_rows.append(row)
    long = pd.concat(rows, ignore_index=True)
    long.to_parquet(OUT / "A2_rep_values.parquet", index=False)

    # ---------------- 변수 충실도
    gt = long[long.rep == "GT3D"].set_index("clip_id")
    frows = []
    n_possible = {vl: int((valid & OKV[:, vi]).sum()) for vi, vl in enumerate(VIEWS)}
    for group, tkey, ckey, reps, same_unit, signed in SPECS:
        t_all = gt[tkey].reindex(ids).to_numpy()
        for rep in reps:
            if rep == "GT3D":
                c = gt[ckey].reindex(ids).to_numpy()
                for vl in ["-"]:
                    met = fid_metrics(t_all, c, perf, same_unit and ckey == tkey, signed)
                    frows.append(dict(group=group, truth=tkey, cand=ckey, rep=rep, variant="-", view=vl,
                                      coverage=met.get("n", 0) / max(valid.sum(), 1), **met))
                continue
            variants = ["-"] if rep == "GT2D" else [v for v in ("full", "app", "full_ungated", "app_ungated") if v.split("_")[0] in mp]
            for variant in variants:
                sub = long[(long.rep == rep) & (long.variant == variant)]
                for vl in VIEWS:
                    c = sub[sub.view == vl].set_index("clip_id")[ckey].reindex(ids).to_numpy() if ckey in sub else np.full(N, np.nan)
                    met = fid_metrics(t_all, c, perf, same_unit and ckey == tkey, signed)
                    frows.append(dict(group=group, truth=tkey, cand=ckey, rep=rep, variant=variant, view=vl,
                                      coverage=met.get("n", 0) / max(n_possible[vl], 1), **met))
                    if rep == "MP2D":   # 같은 뷰의 GT 2D 같은 정의 대비 — 기하 손실을 뺀 순수 MediaPipe 2D 손실
                        g2 = long[(long.rep == "GT2D") & (long.view == vl)].set_index("clip_id")
                        if ckey in g2:
                            t2 = g2[ckey].reindex(ids).to_numpy()
                            met2 = fid_metrics(t2, c, perf, True, signed)
                            frows.append(dict(group=group, truth="GT2D:" + ckey, cand=ckey, rep="MP2D_vs_GT2D", variant=variant, view=vl,
                                              coverage=met2.get("n", 0) / max(n_possible[vl], 1), **met2))
    fid = pd.DataFrame(frows)
    fid.to_csv(OUT / "A2_var_fidelity.csv", index=False, encoding="utf-8-sig")

    # ---------------- 조건 AUC
    arows = []
    for cond, cands in COND.items():
        y = (~conds[cond].astype(bool)).astype(float).to_numpy()   # 1 = 오류(조건 불충족)
        for ckey, dirn in cands:
            if ckey in gt:
                arows.append(dict(condition=cond, cand=ckey, rep="GT3D", variant="-", view="-",
                                  **auc_pair(y, gt[ckey].reindex(ids).to_numpy(), perf, dirn)))
            for rep in ("GT2D", "MP2D", "MPW0", "MPWg"):
                variants = ["-"] if rep == "GT2D" else [v for v in ("full", "app", "full_ungated") if v.split("_")[0] in mp]
                for variant in variants:
                    sub = long[(long.rep == rep) & (long.variant == variant)]
                    if ckey not in sub or sub[ckey].notna().sum() == 0:
                        continue
                    for vl in VIEWS:
                        x = sub[sub.view == vl].set_index("clip_id")[ckey].reindex(ids).to_numpy()
                        # GT3D 를 같은 클립 부분집합에서 다시 잰다(공정 비교)
                        m = np.isfinite(x)
                        gt_sub = auc_pair(np.where(m, y, np.nan), np.where(m, gt[ckey].reindex(ids).to_numpy(), np.nan), perf, dirn) \
                            if ckey in gt else {}
                        arows.append(dict(condition=cond, cand=ckey, rep=rep, variant=variant, view=vl,
                                          gt3d_auc_same=gt_sub.get("auc"), gt3d_auc_within_same=gt_sub.get("auc_within"),
                                          **auc_pair(y, x, perf, dirn)))
    auc = pd.DataFrame(arows)
    auc.to_csv(OUT / "A2_condition_auc.csv", index=False, encoding="utf-8-sig")

    # ---------------- 랜드마크 품질
    lq = landmark_quality(mp, G, s, b, valid, OKV, ok)
    lq.to_csv(OUT / "A2_landmark_quality.csv", index=False, encoding="utf-8-sig")
    fp = foot_point(mp, G, A, OKV, valid)
    fp.to_csv(OUT / "A2_foot_point.csv", index=False, encoding="utf-8-sig")
    wa = world_axes(mp, A, RC, OKV, valid)
    wa.to_csv(OUT / "A2_world_axes.csv", index=False, encoding="utf-8-sig")
    fs = pd.DataFrame(fsel_rows)
    fs.to_csv(OUT / "A2_frame_select.csv", index=False, encoding="utf-8-sig")
    write_tables(fid, auc, lq, fp, wa, fs, cam, n_possible, valid)
    print("[done]", OUT)


def landmark_quality(mp, G, s, b, valid, OKV, ok):
    rows = []
    # 몸 크기: 클립·뷰별 서 있는 프레임의 GT 2D 몸통(목–골반 중점) 길이
    idx = np.arange(G.shape[0])
    for variant, M in mp.items():
        for vi, vl in enumerate(VIEWS):
            g = G[:, vi]                                          # (N,T,24,2)
            torso = np.linalg.norm(g[idx, np.maximum(s, 0), GJ["Neck"]] - (g[idx, np.maximum(s, 0), GJ["LHip"]] + g[idx, np.maximum(s, 0), GJ["RHip"]]) / 2, axis=-1)
            use = valid & OKV[:, vi] & np.isfinite(torso) & (torso > 40)
            fr = use[:, None] & ok & M["HAS"][:, vi]              # 평가 프레임
            det = M["DET"][:, vi]
            rows.append(dict(variant=variant, view=vl, joint="(검출)", n_frames=int(fr.sum()), detect=float(det[fr].mean())))
            X = M["X"][:, vi]
            for jn, (li, ri, gl, gr) in LOWER.items():
                vis = M["VIS"][:, vi][..., [li, ri]][fr & det]
                vv = M["V"][:, vi][..., [li, ri]][fr & det]
                pp = M["P"][:, vi][..., [li, ri]][fr & det]
                r = dict(variant=variant, view=vl, joint=jn, n_frames=int((fr & det).sum()),
                         vis_med=float(np.nanmedian(vis)), vis_p10=float(np.nanpercentile(vis, 10)),
                         vis_lt05=float(np.mean(vis < MIN_VIS)), v_med=float(np.nanmedian(vv)), p_med=float(np.nanmedian(pp)),
                         vis_lt05_L=float(np.mean(vis[:, 0] < MIN_VIS)), vis_lt05_R=float(np.mean(vis[:, 1] < MIN_VIS)))
                if gl is not None:
                    tn = torso[:, None].repeat(T, 1)[fr & det]
                    pl, pr = X[..., li, :][fr & det], X[..., ri, :][fr & det]
                    ql, qr = g[..., GJ[gl], :][fr & det], g[..., GJ[gr], :][fr & det]
                    e_dir = (np.linalg.norm(pl - ql, axis=-1) + np.linalg.norm(pr - qr, axis=-1)) / 2 / tn
                    e_sw = (np.linalg.norm(pl - qr, axis=-1) + np.linalg.norm(pr - ql, axis=-1)) / 2 / tn
                    sep = np.linalg.norm(ql - qr, axis=-1) / tn
                    clear = sep >= 0.15                            # GT 좌우 간격이 몸통 15 % 이상일 때만 뒤바뀜 판정
                    r.update(err_med=float(np.nanmedian(e_dir)), err_p90=float(np.nanpercentile(e_dir, 90)),
                             pck10=float(np.nanmean(e_dir < 0.10)), swap=float(np.nanmean((e_sw < e_dir)[clear])), n_swap_eval=int(clear.sum()))
                    # 서 있음 / 바닥 프레임별 오차와 바닥에서의 세로 편향(+ = MediaPipe 가 GT 보다 화면 아래)
                    for tag, fidx in (("stand", s), ("bottom", b)):
                        sel = use & (fidx >= 0)
                        ii, ff = idx[sel], fidx[sel]
                        okd = det[ii, ff]
                        ii, ff = ii[okd], ff[okd]
                        pl_, pr_ = X[ii, ff, li], X[ii, ff, ri]
                        ql_, qr_ = g[ii, ff, GJ[gl]], g[ii, ff, GJ[gr]]
                        tn_ = torso[ii]
                        e_ = (np.linalg.norm(pl_ - ql_, axis=-1) + np.linalg.norm(pr_ - qr_, axis=-1)) / 2 / tn_
                        r[f"err_med_{tag}"] = float(np.nanmedian(e_))
                        r[f"dy_med_{tag}"] = float(np.nanmedian(((pl_[:, 1] + pr_[:, 1]) - (ql_[:, 1] + qr_[:, 1])) / 2 / tn_))
                        r[f"dy_mad_{tag}"] = float(1.4826 * np.nanmedian(np.abs(((pl_[:, 1] + pr_[:, 1]) - (ql_[:, 1] + qr_[:, 1])) / 2 / tn_
                                                                               - r[f"dy_med_{tag}"])))
                    # 가시성 낮은 관절의 오차
                    lowv = (vis < MIN_VIS).mean(1) > 0
                    if lowv.sum() >= 20:
                        r["err_med_lowvis"] = float(np.nanmedian(e_dir[lowv]))
                        r["err_med_hivis"] = float(np.nanmedian(e_dir[~lowv]))
                rows.append(r)
            # 하체 전체 뒤바뀜: 골반·무릎·발목 세 쌍 합
            fdm = fr & det
            ed = es = 0
            for jn in ("hip", "knee", "ankle"):
                li, ri, gl, gr = LOWER[jn]
                pl, pr = X[..., li, :], X[..., ri, :]
                ql, qr = g[..., GJ[gl], :], g[..., GJ[gr], :]
                ed = ed + np.linalg.norm(pl - ql, axis=-1) + np.linalg.norm(pr - qr, axis=-1)
                es = es + np.linalg.norm(pl - qr, axis=-1) + np.linalg.norm(pr - ql, axis=-1)
            sep = np.linalg.norm(g[..., GJ["LHip"], :] - g[..., GJ["RHip"], :], axis=-1)
            tn = torso[:, None].repeat(T, 1)
            clear = fdm & (sep / tn >= 0.15)
            rows.append(dict(variant=variant, view=vl, joint="(하체 좌우 전체)", n_frames=int(clear.sum()),
                             swap=float(np.mean((es < ed)[clear])),
                             swap_clip_major=float(np.mean(np.nanmean(np.where(clear, (es < ed).astype(float), np.nan), 1)[use] > 0.5))))
    return pd.DataFrame(rows)


def foot_point(mp, G, A, OKV, valid):
    """GT 'Foot' 은 발끝인가? — MediaPipe 뒤꿈치(29/30)→발끝(31/32) 축 위 위치 t(0=뒤꿈치, 1=발끝)와 GT 3D 발목→Foot 길이."""
    rows = []
    d3 = A[:, :, GJ["LFoot"]] - A[:, :, GJ["LAnkle"]]
    rows.append(dict(what="GT3D 발목→Foot 길이(cm) 중앙값", value=float(np.nanmedian(np.linalg.norm(d3, axis=-1)))))
    for variant, M in mp.items():
        if variant != "full":
            continue
        for vi, vl in enumerate(VIEWS):
            X, g = M["X"][:, vi], G[:, vi]
            ts, perp = [], []
            for (he, to, gf) in ((29, 31, "LFoot"), (30, 32, "RFoot")):
                okm = (M["VIS"][:, vi][..., he] >= MIN_VIS) & (M["VIS"][:, vi][..., to] >= MIN_VIS) & (valid & OKV[:, vi])[:, None]
                a, c, q = X[..., he, :][okm], X[..., to, :][okm], g[..., GJ[gf], :][okm]
                ax = c - a
                L2 = (ax ** 2).sum(-1)
                good = (L2 > 25) & np.isfinite(q).all(-1)
                t = ((q - a) * ax).sum(-1)[good] / L2[good]
                ts.append(t)
                dq = (q - a)[good]
                perp.append(np.abs(ax[good][:, 0] * dq[:, 1] - ax[good][:, 1] * dq[:, 0]) / L2[good])
            t = np.concatenate(ts)
            pp = np.concatenate(perp)
            rows.append(dict(what=f"{vl}: GT Foot 의 MP 뒤꿈치→발끝 축 위치 t 중앙값(0=뒤꿈치,1=발끝)", value=float(np.median(t)),
                             p25=float(np.percentile(t, 25)), p75=float(np.percentile(t, 75)), n=int(len(t)),
                             perp_over_len=float(np.median(pp))))
            # 발목 대비
            ts2 = []
            for (an, to, gf) in ((27, 31, "LFoot"), (28, 32, "RFoot")):
                okm = (M["VIS"][:, vi][..., an] >= MIN_VIS) & (M["VIS"][:, vi][..., to] >= MIN_VIS) & (valid & OKV[:, vi])[:, None]
                a, c, q = X[..., an, :][okm], X[..., to, :][okm], g[..., GJ[gf], :][okm]
                ax = c - a
                L2 = (ax ** 2).sum(-1)
                good = (L2 > 25) & np.isfinite(q).all(-1)
                ts2.append(((q - a) * ax).sum(-1)[good] / L2[good])
            t2 = np.concatenate(ts2)
            rows.append(dict(what=f"{vl}: GT Foot 의 MP 발목→발끝 축 위치 중앙값(0=발목,1=발끝)", value=float(np.median(t2)),
                             p25=float(np.percentile(t2, 25)), p75=float(np.percentile(t2, 75)), n=int(len(t2))))
        # MP 월드 발 길이
        W = M["W"]
        rows.append(dict(what="MP월드 발목→발끝(31) 길이(cm) 중앙값", value=float(np.nanmedian(np.linalg.norm(W[..., 31, :] - W[..., 27, :], axis=-1)))))
        rows.append(dict(what="MP월드 뒤꿈치→발끝 길이(cm) 중앙값", value=float(np.nanmedian(np.linalg.norm(W[..., 31, :] - W[..., 29, :], axis=-1)))))
    return pd.DataFrame(rows)


def world_axes(mp, A, RC, OKV, valid):
    """MediaPipe 월드(뒤집은 cm)가 카메라 축 정렬인가, 오차는 어느 축(특히 깊이)에 몰리나.
    GT 3D 를 DLT 회전(촬영일·뷰 중앙값)으로 카메라 좌표에 옮겨 MediaPipe 월드 부호로 맞춘 뒤, 골반 중점 원점으로 비교."""
    rows = []
    names = [("LShoulder", 11), ("RShoulder", 12), ("LHip", 23), ("RHip", 24), ("LKnee", 25), ("RKnee", 26), ("LAnkle", 27), ("RAnkle", 28)]
    gi = [GJ[n] for n, _ in names]
    mi = [m for _, m in names]
    for variant, M in mp.items():
        for vi, vl in enumerate(VIEWS):
            R = RC[:, vi]                                           # (N,3,3) world→cam (OpenCV)
            P = A[:, :, gi]                                         # (N,T,8,3)
            hip = (A[:, :, GJ["LHip"]] + A[:, :, GJ["RHip"]]) / 2
            Pc = np.einsum("nij,ntkj->ntki", R, P - hip[:, :, None])
            Pc[..., 1] *= -1
            Pc[..., 2] *= -1                                        # MediaPipe 월드(뒤집은 것) 부호
            W = M["W"][:, vi][:, :, mi]
            W = W - (W[:, :, 2:3] + W[:, :, 3:4]) / 2               # 골반 중점 원점
            use = (valid & OKV[:, vi])[:, None] & np.isfinite(Pc).all((-1, -2)) & np.isfinite(W).all((-1, -2))
            p, w = Pc[use], W[use]                                  # (F,8,3)
            # 축 정렬: 프레임별 Kabsch 회전각
            angs, scl = [], []
            for a_, b_ in zip(w, p):
                H = a_.T @ b_
                U, S, Vt = np.linalg.svd(H)
                dd = np.sign(np.linalg.det(Vt.T @ U.T))
                Rk = Vt.T @ np.diag([1, 1, dd]) @ U.T
                angs.append(np.degrees(np.arccos(np.clip((np.trace(Rk) - 1) / 2, -1, 1))))
                scl.append(np.linalg.norm(b_) / max(np.linalg.norm(a_), 1e-6))
            e = w - p
            r = dict(variant=variant, view=vl, n_frames=int(len(p)), kabsch_deg_med=float(np.median(angs)),
                     kabsch_deg_p90=float(np.percentile(angs, 90)), gt_over_mp_scale=float(np.median(scl)))
            for part, cols in (("knee", [4, 5]), ("ankle", [6, 7]), ("shoulder", [0, 1])):
                for ax, an in ((0, "x_lat"), (1, "y_up"), (2, "z_depth")):
                    r[f"{part}_{an}_mae"] = float(np.median(np.abs(e[:, cols, ax])))
            rows.append(r)
    return pd.DataFrame(rows)


def md(df: pd.DataFrame, index=True) -> str:
    """tabulate 없이 마크다운 표."""
    d = df.reset_index() if index else df
    def f(x):
        if isinstance(x, float):
            return "" if np.isnan(x) else f"{x:.3g}"
        return str(x)
    L = ["| " + " | ".join(map(str, d.columns)) + " |", "|" + "---|" * len(d.columns)]
    L += ["| " + " | ".join(f(x) for x in r) + " |" for r in d.itertuples(index=False)]
    return "\n".join(L)


def write_tables(fid, auc, lq, fp, wa, fs, cam, n_possible, valid):
    L = ["# A2 자동 요약 표 (A2_analysis.py 출력)", ""]
    L.append(f"- 반복 판정 가능 클립 {int(valid.sum())} / 720, 뷰별 카메라 QC 통과 {n_possible}")
    cg = cam[cam.cam_ok].groupby("view_letter")[["az_deg", "elev_deg", "cam_h_cm", "dist_cm", "pitch_deg"]].median().round(1)
    L += ["", "## 카메라(DLT) 뷰별 중앙값", "", md(cg), ""]
    for variant in ("full", "app"):
        f = fid[(fid.variant.isin(["-", variant]))]
        if f.empty:
            continue
        L += [f"## 변수 충실도 — variant={variant} (ρ Spearman | ρw 세션 중심화 | cal 보정 잔차 강건SD(진실 단위) | mdae 절대오차 중앙값 | b 중앙값 편향 | sg 부호일치 | cov 적용률)", ""]
        for group in dict.fromkeys(f.group):
            g = f[f.group == group]
            L.append(f"### {group}")
            L.append("")
            L.append("| 표현 | " + " | ".join(VIEWS) + " |")
            L.append("|---|" + "---|" * len(VIEWS))
            for rep in dict.fromkeys(g.rep):
                gg = g[g.rep == rep]
                if rep == "GT3D":
                    r = gg.iloc[0]
                    L.append(f"| GT3D(뷰 무관) | ρ={r.get('rho', np.nan):.2f} ρw={r.get('rho_w', np.nan):.2f} n={int(r.n)} |" + " |" * (len(VIEWS) - 1))
                    continue
                cells = []
                for vl in VIEWS:
                    x = gg[gg.view == vl]
                    if x.empty or pd.isna(x.iloc[0].get("rho")):
                        cells.append(f"n={int(x.iloc[0].n) if len(x) else 0}")
                        continue
                    x = x.iloc[0]
                    c = f"ρ={x.rho:.2f} ρw={x.get('rho_w', np.nan):.2f} cal={x.get('cal_sd', np.nan):.3g}"
                    if pd.notna(x.get("mdae")):
                        c += f" mdae={x.mdae:.3g} b={x.bias_med:+.3g}"
                    if pd.notna(x.get("sign")):
                        c += f" sg={x.sign*100:.0f}%"
                    c += f" cov={x.coverage*100:.0f}%"
                    cells.append(c)
                L.append(f"| {rep} | " + " | ".join(cells) + " |")
            L.append("")
    L += ["## 조건 AUC (오류 방향 고정, 원값 / 수행자 중심화)", ""]
    for (cond, cand), g in auc.groupby(["condition", "cand"], sort=False):
        L.append(f"### {cond} ← {cand}")
        L.append("")
        L.append("| 표현 | " + " | ".join(VIEWS) + " |")
        L.append("|---|" + "---|" * len(VIEWS))
        g3 = g[g.rep == "GT3D"]
        if len(g3):
            L.append(f"| GT3D | {g3.iloc[0].get('auc', np.nan):.3f} / {g3.iloc[0].get('auc_within', np.nan):.3f} |" + " |" * (len(VIEWS) - 1))
        for (rep, variant), gg in g[g.rep != "GT3D"].groupby(["rep", "variant"], sort=False):
            cells = []
            for vl in VIEWS:
                x = gg[gg.view == vl]
                cells.append(f"{x.iloc[0].auc:.3f} / {x.iloc[0].auc_within:.3f} (n={int(x.iloc[0].n)})" if len(x) and pd.notna(x.iloc[0].get("auc")) else "-")
            L.append(f"| {rep} {variant} | " + " | ".join(cells) + " |")
        L.append("")
    L += ["## 랜드마크 품질", "", md(lq, index=False), ""]
    L += ["## GT Foot 위치", "", md(fp, index=False), ""]
    L += ["## 월드 축 정렬·축별 오차(cm)", "", md(wa, index=False), ""]
    L += ["## 프레임 선택", "", md(fs, index=False), ""]
    (OUT / "A2_tables.md").write_text("\n".join(L), encoding="utf-8")


if __name__ == "__main__":
    main()

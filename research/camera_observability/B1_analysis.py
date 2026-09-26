#!/usr/bin/env python
"""B1 분석 — 덤벨 컬: 팔꿈치 이탈·몸통 반동·팔꿈치 벌어짐·가동 범위 후보를 GT 3D 진실 · GT 2D 주석 · MediaPipe 2D · MediaPipe 월드 × 뷰(B·C·D)로 잰다.

입력(읽기 전용)
  aihub_fitness/outputs/{clips,conditions,kp3d,kp3d_frame_ok,kp2d}.parquet
  camera_observability/outputs/B1/cameras.parquet          (B1_camera.py, DLT 방위각)
  camera_observability/outputs/B1/mp/lm_<tar>_full.parquet (B1_mp_infer.py)
출력: camera_observability/outputs/B1/
  B1_rep_values.parquet   클립 × 표현(GT3D/GT2D/MP2D/MPW) × 뷰 의 반복 값(긴 형식)
  B1_condition_auc.csv    AIHub 조건 라벨 AUC (원값 / 세션 중심화), 표현·뷰별, 같은 부분집합의 GT3D AUC 동반
  B1_fidelity.csv         GT3D 진실 대비 ρ·ρw·편향·세션 중심화 오차 SD
  B1_normal_dist.csv      정상 클립 분포(p5/50/95/99)와 띠 후보의 예상 오탐률
  B1_rom.csv              가동 범위 분포·조건 효과·2D 대리 충실도
  B1_landmark_quality.csv 뷰별 팔 관절 가시성·px 오차
  B1_tables.md            보고서용 표

반복 단위: 클립 16프레임(여러 반복에 걸친 성긴 표본) 중 GT 3D 팔꿈치각 평균 최소 프레임 = 수축(c), 최대 = 이완(e).
MediaPipe 도 **같은 프레임**에서 잰다(측정 충실도 분리). 세트 통계(mean/max/min/range)는 유효 프레임 전체.
"""
from __future__ import annotations

import glob
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import spearmanr, pearsonr
from sklearn.metrics import roc_auc_score

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "aihub_fitness"))
from parse_labels import JOINTS_SHORT, KP2D_COLS, KP3D_COLS  # noqa: E402
import B1_vars as V  # noqa: E402

SRC = HERE.parent / "aihub_fitness" / "outputs"
OUT = HERE / "outputs" / "B1"
EXERCISE = "덤벨 컬"
VIEWS = ["B", "C", "D"]
ALL_LETTERS = ["A", "B", "C", "D", "E"]
T = 16
MIN_VIS = 0.5
MIN_ROM = 60.0     # 반복 판정: GT 팔꿈치각 범위 ≥ 60°

GJ = {n: i for i, n in enumerate(JOINTS_SHORT)}
IX_GT = dict(LShoulder=GJ["LShoulder"], RShoulder=GJ["RShoulder"], LElbow=GJ["LElbow"], RElbow=GJ["RElbow"], LWrist=GJ["LWrist"], RWrist=GJ["RWrist"],
             LHip=GJ["LHip"], RHip=GJ["RHip"], LAnkle=GJ["LAnkle"], RAnkle=GJ["RAnkle"], LEar=GJ["LEar"], REar=GJ["REar"], Neck=GJ["Neck"])
IX_MP = dict(LShoulder=11, RShoulder=12, LElbow=13, RElbow=14, LWrist=15, RWrist=16, LHip=23, RHip=24, LAnkle=27, RAnkle=28, LEar=7, REar=8, Neck=None)
ARM = {"shoulder": (11, 12, "LShoulder", "RShoulder"), "elbow": (13, 14, "LElbow", "RElbow"), "wrist": (15, 16, "LWrist", "RWrist"),
       "hip": (23, 24, "LHip", "RHip"), "ear": (7, 8, "LEar", "REar")}

COND_ELBOW = "팔꿈치 위치 고정"
COND_SHRUG = "수축 시 어깨 으쓱 없음"
COND_SPINE = "척추의 중립"
COND_TENSION = "이완 시 팔 긴장 유지"
COND_WRIST = "손목의 중립"


# ------------------------------------------------------------------ 적재
def load_gt():
    clips = pd.read_parquet(SRC / "clips.parquet", columns=["clip_id", "exercise", "performer", "type_key", "day", "description"])
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
    k2 = pd.read_parquet(SRC / "kp2d.parquet", filters=[("clip_id", "in", ids)])
    k2 = k2[k2.img_key != ""]
    G = np.full((N, 5, T, 24, 2), np.nan)
    vi = k2.view_letter.map({v: i for i, v in enumerate(ALL_LETTERS)}).to_numpy()
    G[k2.clip_id.map(cix).to_numpy(), vi, k2.frame_idx.to_numpy().astype(int)] = k2[KP2D_COLS].to_numpy(float).reshape(-1, 24, 2)
    G[~np.broadcast_to(ok[:, None, :], G.shape[:3])] = np.nan
    conds = pd.read_parquet(SRC / "conditions.parquet")
    conds = conds[conds.clip_id.isin(ids)].pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first").reindex(ids)
    keymap = k2[["img_key", "clip_id", "view_letter", "frame_idx"]].copy()
    keymap["ci"] = keymap.clip_id.map(cix)
    keymap["vi"] = keymap.view_letter.map({v: i for i, v in enumerate(ALL_LETTERS)})
    return sel, ids, A, G, ok, conds, keymap


def load_cams(ids):
    cam = pd.read_parquet(OUT / "cameras.parquet")
    cix = {c: i for i, c in enumerate(ids)}
    N = len(ids)
    OKV = np.zeros((N, 5), bool)
    AZ = np.full((N, 5), np.nan)
    for r in cam.itertuples():
        i = cix.get(r.clip_id)
        if i is None:
            continue
        v = ALL_LETTERS.index(r.view_letter)
        OKV[i, v] = bool(r.cam_ok) and (r.view_actual == r.view_letter)     # 코드 = 실제 방향인 (클립, 뷰)만
        AZ[i, v] = r.az_deg
    return cam, OKV, AZ


def load_mp(keymap: pd.DataFrame, N: int):
    files = sorted(glob.glob(str(OUT / "mp" / "lm_*_full.parquet")))
    if not files:
        return None
    df = pd.concat([pd.read_parquet(f) for f in files], ignore_index=True).merge(keymap, on="img_key", how="inner")
    X = np.full((N, 5, T, 33, 2), np.nan)
    VIS = np.full((N, 5, T, 33), np.nan)
    W = np.full((N, 5, T, 33, 3), np.nan)
    DET = np.zeros((N, 5, T), bool)
    HAS = np.zeros((N, 5, T), bool)
    ci, vi, fi = df.ci.to_numpy(), df.vi.to_numpy(), df.frame_idx.to_numpy().astype(int)
    HAS[ci, vi, fi] = True
    DET[ci, vi, fi] = df.detected.to_numpy().astype(bool)
    x = df[[f"l{i}_x" for i in range(33)]].to_numpy(float)
    y = df[[f"l{i}_y" for i in range(33)]].to_numpy(float)
    X[ci, vi, fi] = np.stack([x, y], -1)
    v = df[[f"l{i}_v" for i in range(33)]].to_numpy(float)
    p = df[[f"l{i}_p" for i in range(33)]].to_numpy(float)
    VIS[ci, vi, fi] = np.minimum(v, p)
    w = df[[f"w{i}_{a}" for i in range(33) for a in "xyz"]].to_numpy(float).reshape(-1, 33, 3) * 100.0
    w[..., 1] *= -1.0      # 앱과 같은 변환: y 위 +, z 반전 (spec §3)
    w[..., 2] *= -1.0
    W[ci, vi, fi] = w
    for arr in (X, W):
        arr[~DET] = np.nan
    return dict(X=X, W=W, VIS=VIS, DET=DET, HAS=HAS)


# ------------------------------------------------------------------ 통계
def mad(x):
    x = x[np.isfinite(x)]
    return 1.4826 * float(np.median(np.abs(x - np.median(x)))) if len(x) else np.nan


def fid_metrics(t, c, sess, same_unit):
    m = np.isfinite(t) & np.isfinite(c)
    n = int(m.sum())
    out = dict(n=n)
    if n < 20:
        return out
    t, c, sess = t[m], c[m], sess[m]
    out["r"] = float(pearsonr(t, c)[0])
    out["rho"] = float(spearmanr(t, c)[0])
    tc = t - pd.Series(t).groupby(sess).transform("mean").to_numpy()
    cc = c - pd.Series(c).groupby(sess).transform("mean").to_numpy()
    if np.std(tc) > 0 and np.std(cc) > 0:
        out["rho_w"] = float(spearmanr(tc, cc)[0])
    out["truth_sd"] = mad(t)
    out["truth_sd_w"] = mad(tc)
    if same_unit:
        d = c - t
        out["bias_med"] = float(np.median(d))
        out["mdae"] = float(np.median(np.abs(d)))
        dw = d - pd.Series(d).groupby(sess).transform("mean").to_numpy()
        out["err_sd_w"] = mad(dw)
        out["err_sd"] = mad(d)
    return out


def auc_pair(y, x, sess, direction):
    m = np.isfinite(x) & np.isfinite(y)
    if m.sum() < 30 or len(np.unique(y[m])) < 2:
        return dict(n=int(m.sum()))
    y, x, sess = y[m], x[m] * direction, sess[m]
    xc = x - pd.Series(x).groupby(sess).transform("mean").to_numpy()
    out = dict(n=int(m.sum()), n_pos=int(y.sum()), auc=float(roc_auc_score(y, x)), auc_within=float(roc_auc_score(y, xc)))
    # 정상 p95 임계에서의 검출률 (오탐 5 % 고정)
    thr = np.nanpercentile(x[y == 0], 95)
    out["tpr_at_fpr05"] = float(np.mean(x[y == 1] > thr))
    thr = np.nanpercentile(x[y == 0], 90)
    out["tpr_at_fpr10"] = float(np.mean(x[y == 1] > thr))
    return out


# 조건 → (반복 변수, 오류 방향: 오류면 값이 커지는 쪽 = +1)
def elbow_candidates(keys):
    dirs = {}
    for k in keys:
        base = k
        d = None
        for pre, sgn in (("elbow_torso", 1), ("ua_fwd", 1), ("ua_out", 1), ("ua_dev", 1), ("upperarm_vert", -1), ("elbow_h", 1), ("elbow_fwd", 1),
                         ("elbow_out", 1), ("elbow_x", 1), ("shoulder_ang", 1), ("elbow_disp", 1), ("elbow_gap", 1), ("wrist_gap", 1),
                         ("elbow_wrist_h", -1), ("wrist_h", 1), ("forearm_vert", 1)):
            if base.startswith(pre):
                d = sgn
                break
        if base.startswith("elbow_") and base.split("_")[1] in ("L", "R", "mean", "min", "max") and not base.startswith("elbow_h") \
                and not base.startswith("elbow_x"):
            d = -1          # 팔꿈치각: 오류(팔꿈치 앞으로) 면 수축 각이 더 작다는 가설 방향
        if d is not None:
            dirs[k] = d
    return dirs


COND_CANDS = {
    COND_SHRUG: {"ear_shoulder_gap_c": -1, "ear_shoulder_gap_d": -1, "ear_shoulder_gap_min": -1, "ear_shoulder_gap_mean": -1, "ear_shoulder_gap_range": 1,
                 "shoulder_neck_gap_c": -1, "shoulder_neck_gap_d": -1, "shoulder_neck_gap_min": -1,
                 "shoulder_h_mean_c": 1, "shoulder_h_mean_d": 1, "shoulder_h_mean_max": 1, "shoulder_h_mean_range": 1,
                 "torso_incl_c": 1, "torso_incl_d": 1},
    COND_SPINE: {"torso_incl_mean": 1, "torso_incl_c": 1, "torso_incl_e": 1, "torso_pitch_mean": 1, "torso_pitch_c": 1, "torso_pitch_d": 1,
                 "ear_shoulder_gap_mean": -1, "hip_ankle_fwd_mean": 1, "shoulder_h_mean_mean": -1},
    COND_TENSION: {"elbow_mean_e": 1, "elbow_mean_max": 1, "elbow_max_max": 1, "elbow_min_max": 1, "elbow_mean_mean": 1, "elbow_mean_d": -1, "elbow_mean_range": 1,
                   "forearm_vert_mean_e": 1, "elbow_wrist_h_mean_e": 1, "wrist_h_mean_e": -1, "wrist_h_mean_min": -1, "elbow_mean_c": 1,
                   "ua_fwd_mean_e": 1, "elbow_torso_mean_e": 1, "torso_pitch_e": 1, "torso_incl_e": 1},
}


# ------------------------------------------------------------------ 메인
def main():
    sel, ids, A, G, ok, conds, keymap = load_gt()
    N = len(ids)
    sess = (sel.performer + "@" + sel.day).to_numpy()
    cam, OKV, AZ = load_cams(ids)
    OUT.mkdir(parents=True, exist_ok=True)

    # 수축 / 이완 프레임 (GT 3D 팔꿈치각 평균)
    Fgt = V.frame_vars_3d(A, np.array([0.0, 1.0, 0.0]), IX_GT)
    el = Fgt["elbow_mean"]
    c = np.where(np.isnan(el), np.inf, el).argmin(1)
    e = np.where(np.isnan(el), -np.inf, el).argmax(1)
    rng = np.nanmax(el, 1) - np.nanmin(el, 1)
    valid = np.isfinite(rng) & (rng >= MIN_ROM) & (np.isfinite(el).sum(1) >= 8)
    c[~valid], e[~valid] = -1, -1
    lr_corr = np.array([np.corrcoef(Fgt["elbow_L"][i][ok[i]], Fgt["elbow_R"][i][ok[i]])[0, 1] if ok[i].sum() >= 6 else np.nan for i in range(N)])
    print(f"[gt] 클립 {N}, 반복 판정 가능 {valid.sum()} (팔꿈치각 범위 ≥ {MIN_ROM:.0f}°, 유효 프레임 ≥ 8) | 좌/우 팔꿈치각 시간 상관 중앙값 {np.nanmedian(lr_corr):.2f}, "
          f"< 0.5 인 클립 {int(np.nansum(lr_corr < 0.5))}")
    Rgt = V.rep_vars(Fgt, c, e, V.REP_KEYS_3D, ok, is2d=False)

    rows = []
    def push(rep, v, R):
        d = pd.DataFrame(R)
        d["clip_id"], d["rep"], d["view"] = ids, rep, v
        rows.append(d)
    push("GT3D", "-", Rgt)
    for vl in VIEWS:
        vi = ALL_LETTERS.index(vl)
        azs = np.where(vl == "C", 0.0, np.sign(AZ[:, vi]))
        F2 = V.frame_vars_2d(G[:, vi], IX_GT, azs[:, None])
        R = V.rep_vars(F2, c, e, V.REP_KEYS_2D, ok, is2d=True)
        for kk in R:
            R[kk] = np.where(OKV[:, vi], R[kk], np.nan)
        push("GT2D", vl, R)

    M = load_mp(keymap, N)
    if M is not None:
        Xg = M["X"].copy()
        Wg = M["W"].copy()
        bad = ~(M["VIS"] >= MIN_VIS)
        Xg[bad] = np.nan
        Wg[bad] = np.nan
        for vl in VIEWS:
            vi = ALL_LETTERS.index(vl)
            azs = np.where(vl == "C", 0.0, np.sign(AZ[:, vi]))
            okf = ok & M["DET"][:, vi]
            F2 = V.frame_vars_2d(Xg[:, vi], IX_MP, azs[:, None])
            R = V.rep_vars(F2, c, e, V.REP_KEYS_2D, okf, is2d=True)
            for kk in R:
                R[kk] = np.where(OKV[:, vi], R[kk], np.nan)
            push("MP2D", vl, R)
            Fw = V.frame_vars_3d(Wg[:, vi], np.array([0.0, 1.0, 0.0]), IX_MP)
            R = V.rep_vars(Fw, c, e, V.REP_KEYS_3D, okf, is2d=False)
            for kk in R:
                R[kk] = np.where(OKV[:, vi], R[kk], np.nan)
            push("MPW", vl, R)
            # MediaPipe 가 스스로 고른 수축/이완 프레임(월드 팔꿈치각) — 프레임 선택 민감도
            em = Fw["elbow_mean"]
            em = np.where(okf, em, np.nan)
            c2 = np.where(np.isnan(em), np.inf, em).argmin(1)
            e2 = np.where(np.isnan(em), -np.inf, em).argmax(1)
            has = np.isfinite(em).sum(1) >= 8
            c2[~(has & valid)], e2[~(has & valid)] = -1, -1
            R2 = V.rep_vars(Fw, c2, e2, V.REP_KEYS_3D, okf, is2d=False)
            for kk in R2:
                R2[kk] = np.where(OKV[:, vi], R2[kk], np.nan)
            push("MPWself", vl, R2)
    long = pd.concat(rows, ignore_index=True)
    long["valid"] = np.tile(valid, len(rows))
    long.to_parquet(OUT / "B1_rep_values.parquet", index=False)

    gt = long[long.rep == "GT3D"].set_index("clip_id").reindex(ids)
    reps_avail = [r for r in ("GT2D", "MP2D", "MPW", "MPWself") if (long.rep == r).any()]

    def get(rep, vl, key):
        sub = long[(long.rep == rep) & (long.view == vl)]
        if key not in sub.columns or sub.empty:
            return np.full(N, np.nan)
        return sub.set_index("clip_id")[key].reindex(ids).to_numpy()

    # ---------------- 조건 AUC
    arows = []
    cands = {COND_ELBOW: elbow_candidates([k for k in gt.columns if k not in ("rep", "view", "valid")] +
                                          [k for k in long.columns if k not in gt.columns and k not in ("rep", "view", "valid")])}
    cands.update(COND_CANDS)
    for cond, cd in cands.items():
        y = (~conds[cond].astype(bool)).astype(float).to_numpy()   # 1 = 오류(조건 불충족)
        y = np.where(valid, y, np.nan)
        for key, dirn in cd.items():
            if key in gt.columns:
                arows.append(dict(condition=cond, cand=key, rep="GT3D", view="-", **auc_pair(y, gt[key].to_numpy(), sess, dirn)))
            for rep in reps_avail:
                for vl in VIEWS:
                    x = get(rep, vl, key)
                    if np.isfinite(x).sum() < 30:
                        continue
                    m = np.isfinite(x)
                    g_same = auc_pair(np.where(m, y, np.nan), gt[key].to_numpy(), sess, dirn) if key in gt.columns else {}
                    arows.append(dict(condition=cond, cand=key, rep=rep, view=vl, gt3d_auc_same=g_same.get("auc"), gt3d_auc_within_same=g_same.get("auc_within"),
                                      **auc_pair(y, x, sess, dirn)))
    auc = pd.DataFrame(arows)
    auc.to_csv(OUT / "B1_condition_auc.csv", index=False, encoding="utf-8-sig")

    # ---------------- 충실도 (GT3D 진실 대비)
    SPECS = [
        ("ROM 수축 팔꿈치각", "elbow_mean_c", "elbow_mean_c", True), ("ROM 이완 팔꿈치각", "elbow_mean_e", "elbow_mean_e", True),
        ("ROM 진폭", "elbow_mean_d", "elbow_mean_d", True), ("ROM 진폭 ← 손목 상승÷전완(2D)", "elbow_mean_d", "wrist_rise_ratio_mean", False),
        ("ROM 세트 최소각", "elbow_mean_min", "elbow_mean_min", True), ("ROM 세트 최대각", "elbow_mean_max", "elbow_mean_max", True),
        ("ROM 수축각 R", "elbow_R_c", "elbow_R_c", True), ("ROM 수축각 L", "elbow_L_c", "elbow_L_c", True),
        ("elbow_torso R 세트평균(현 규칙)", "elbow_torso_R_mean", "elbow_torso_R_mean", True), ("elbow_torso L 세트평균", "elbow_torso_L_mean", "elbow_torso_L_mean", True),
        ("elbow_torso mean 수축", "elbow_torso_mean_c", "elbow_torso_mean_c", True), ("elbow_torso mean 변화", "elbow_torso_mean_d", "elbow_torso_mean_d", True),
        ("elbow_torso max 세트최대", "elbow_torso_max_max", "elbow_torso_max_max", True),
        ("상완 앞 기울기 mean 수축", "ua_fwd_mean_c", "ua_fwd_mean_c", True), ("상완 앞 기울기 mean 변화", "ua_fwd_mean_d", "ua_fwd_mean_d", True),
        ("상완 이탈각 mean 수축", "ua_dev_mean_c", "ua_dev_mean_c", True), ("상완 이탈각 mean 변화", "ua_dev_mean_d", "ua_dev_mean_d", True),
        ("상완 이탈각 max 세트최대", "ua_dev_max_max", "ua_dev_max_max", True),
        ("팔꿈치 높이 mean 수축", "elbow_h_mean_c", "elbow_h_mean_c", True), ("팔꿈치 높이 mean 변화", "elbow_h_mean_d", "elbow_h_mean_d", True),
        ("팔꿈치 앞 오프셋 mean 수축", "elbow_fwd_mean_c", "elbow_fwd_mean_c", True), ("팔꿈치 앞 오프셋 mean 변화", "elbow_fwd_mean_d", "elbow_fwd_mean_d", True),
        ("팔꿈치 앞 오프셋 ← 2D 가로 오프셋", "elbow_fwd_mean_c", "elbow_x_mean_c", False),
        ("팔꿈치 이동(어깨 기준) mean", "elbow_disp_sh_mean_d", "elbow_disp_sh_mean_d", True), ("팔꿈치 이동(골반 기준) mean", "elbow_disp_hip_mean_d", "elbow_disp_hip_mean_d", True),
        ("팔꿈치 이동(어깨 기준) 세트 폭", "elbow_disp_sh_mean_range", "elbow_disp_sh_mean_range", True),
        ("팔꿈치 간격 수축", "elbow_gap_c", "elbow_gap_c", True), ("팔꿈치 간격 변화", "elbow_gap_d", "elbow_gap_d", True), ("팔꿈치 간격 세트최대", "elbow_gap_max", "elbow_gap_max", True),
        ("팔꿈치 수평 간격 ← 2D x 간격", "elbow_gap_h_c", "elbow_gap_x_c", False), ("팔꿈치 수평 간격 변화 ← 2D", "elbow_gap_h_d", "elbow_gap_x_d", False),
        ("몸통 기울기 변화(이완→수축)", "torso_incl_d", "torso_incl_d", True), ("몸통 앞 숙임 변화", "torso_pitch_d", "torso_pitch_d", True),
        ("몸통 앞 숙임 변화 ← 2D 비", "torso_pitch_d", "torso_pitch_d", False), ("몸통 기울기 세트 범위", "torso_incl_range", "torso_incl_range", True),
        ("귀–어깨 간격 변화(으쓱)", "ear_shoulder_gap_d", "ear_shoulder_gap_d", True), ("귀–어깨 간격 수축", "ear_shoulder_gap_c", "ear_shoulder_gap_c", True),
        ("골반 앞뒤 이동 변화", "hip_ankle_fwd_d", "hip_ankle_fwd_d", True), ("골반 앞뒤 세트 범위", "hip_ankle_fwd_range", "hip_ankle_fwd_range", True),
    ]
    frows = []
    for group, tkey, ckey, same in SPECS:
        t = gt[tkey].to_numpy() if tkey in gt.columns else np.full(N, np.nan)
        for rep in reps_avail:
            for vl in VIEWS:
                x = get(rep, vl, ckey)
                met = fid_metrics(t, x, sess, same and (tkey == ckey))
                frows.append(dict(group=group, truth=tkey, cand=ckey, rep=rep, view=vl, **met))
                if rep == "MP2D":
                    t2 = get("GT2D", vl, ckey)
                    met2 = fid_metrics(t2, x, sess, True)
                    frows.append(dict(group=group, truth="GT2D:" + ckey, cand=ckey, rep="MP2D_vs_GT2D", view=vl, **met2))
    fid = pd.DataFrame(frows)
    fid.to_csv(OUT / "B1_fidelity.csv", index=False, encoding="utf-8-sig")

    # ---------------- 정상 분포·띠 후보 오탐률
    all_true = conds.astype(bool).all(1).to_numpy() & valid
    elbow_true = conds[COND_ELBOW].astype(bool).to_numpy() & valid
    torso_norm = (conds[COND_ELBOW].astype(bool) & conds[COND_SHRUG].astype(bool) & conds[COND_SPINE].astype(bool)).to_numpy() & valid
    NORMSETS = {"all5_true": all_true, "elbow_true": elbow_true, "elbow_shrug_spine_true": torso_norm, "all_valid": valid}
    DIST_KEYS = ["torso_incl_d", "torso_incl_range", "torso_pitch_d", "torso_pitch_range", "hip_ankle_fwd_d", "hip_ankle_fwd_range",
                 "ear_shoulder_gap_d", "ear_shoulder_gap_range", "elbow_gap_c", "elbow_gap_e", "elbow_gap_d", "elbow_gap_max", "elbow_gap_range",
                 "elbow_gap_h_c", "elbow_gap_h_d", "elbow_gap_h_max", "elbow_gap_x_c", "elbow_gap_x_d", "elbow_gap_x_max",
                 "elbow_torso_R_mean", "elbow_torso_L_mean", "elbow_torso_mean_c", "elbow_torso_mean_d", "elbow_torso_max_max",
                 "ua_fwd_mean_c", "ua_fwd_mean_d", "ua_fwd_max_max", "ua_out_mean_c", "ua_out_max_max", "ua_dev_mean_c", "ua_dev_mean_d", "ua_dev_max_max",
                 "elbow_h_mean_c", "elbow_h_mean_d", "elbow_h_max_max", "elbow_fwd_mean_c", "elbow_fwd_mean_d", "elbow_x_mean_c", "elbow_x_max_max",
                 "elbow_disp_sh_mean_d", "elbow_disp_hip_mean_d", "elbow_disp_sh_max_range", "elbow_disp_hip_max_range",
                 "elbow_mean_c", "elbow_mean_e", "elbow_mean_d", "elbow_mean_min", "elbow_mean_max", "elbow_L_c", "elbow_R_c", "elbow_max_min",
                 "wrist_rise_ratio_mean", "shoulder_ang_mean_c", "shoulder_ang_max_max"]
    drows = []
    for nsname, nmask in NORMSETS.items():
        for rep in ["GT3D"] + reps_avail:
            for vl in (["-"] if rep == "GT3D" else VIEWS):
                for key in DIST_KEYS:
                    x = gt[key].to_numpy() if rep == "GT3D" else get(rep, vl, key)
                    if key not in (gt.columns if rep == "GT3D" else long.columns):
                        continue
                    xv = x[nmask & np.isfinite(x)]
                    if len(xv) < 10:
                        continue
                    row = dict(normset=nsname, rep=rep, view=vl, cand=key, n=len(xv), n_sessions=len(set(sess[nmask & np.isfinite(x)])),
                               p1=np.percentile(xv, 1), p5=np.percentile(xv, 5), p50=np.percentile(xv, 50), p95=np.percentile(xv, 95), p99=np.percentile(xv, 99),
                               mean=xv.mean(), sd=xv.std(), sd_w=mad(xv - pd.Series(xv).groupby(sess[nmask & np.isfinite(x)]).transform("mean").to_numpy()))
                    # 띠 후보: GT3D 정상 p95·p99 임계를 이 표현의 값에 그대로 적용했을 때의 오탐률 (같은 정상 집합)
                    if rep != "GT3D" and key in gt.columns:
                        g = gt[key].to_numpy()
                        gv = g[nmask & np.isfinite(g)]
                        if len(gv) >= 10:
                            for q in (90, 95, 99):
                                thr = np.percentile(gv, q)
                                row[f"fpr_at_gt_p{q}"] = float(np.mean(xv > thr))
                                row[f"gt_p{q}"] = thr
                    drows.append(row)
    dist = pd.DataFrame(drows)
    dist.to_csv(OUT / "B1_normal_dist.csv", index=False, encoding="utf-8-sig")

    # ---------------- 가동 범위
    rrows = []
    for cond in (COND_TENSION, COND_ELBOW, COND_SHRUG, COND_SPINE, COND_WRIST):
        for key in ("elbow_mean_c", "elbow_mean_e", "elbow_mean_d", "elbow_mean_min", "elbow_mean_max", "elbow_mean_mean", "forearm_vert_mean_e",
                    "ua_fwd_mean_e", "ua_fwd_mean_c", "elbow_torso_mean_e", "wrist_h_mean_e", "shoulder_ang_mean_e", "torso_pitch_e", "torso_pitch_c"):
            x = gt[key].to_numpy()
            for val in (True, False):
                m = valid & (conds[cond].astype(bool).to_numpy() == val) & np.isfinite(x)
                rrows.append(dict(kind="cond_effect", condition=cond, value=val, cand=key, n=int(m.sum()), mean=x[m].mean(), sd=x[m].std(),
                                  p5=np.percentile(x[m], 5), p50=np.percentile(x[m], 50), p95=np.percentile(x[m], 95)))
    # 사람 간 / 사람 안 분산 (정상 = 긴장 유지 & 팔꿈치 고정)
    rom_norm = (conds[COND_TENSION].astype(bool) & conds[COND_ELBOW].astype(bool)).to_numpy() & valid
    for key in ("elbow_mean_c", "elbow_mean_e", "elbow_mean_d", "elbow_L_c", "elbow_R_c", "elbow_mean_min", "elbow_mean_max"):
        x = gt[key].to_numpy()
        m = rom_norm & np.isfinite(x)
        s = pd.Series(x[m]).groupby(sess[m])
        rrows.append(dict(kind="between_within", condition="tension&elbow_true", value=True, cand=key, n=int(m.sum()), n_sessions=s.ngroups,
                          mean=x[m].mean(), sd=x[m].std(), p5=np.percentile(x[m], 5), p50=np.percentile(x[m], 50), p95=np.percentile(x[m], 95),
                          between_sd=float(s.mean().std()), within_sd=float(np.sqrt((s.var(ddof=1)).mean())),
                          person_mean_p5=float(s.mean().quantile(0.05)), person_mean_p95=float(s.mean().quantile(0.95))))
    # 프레임 수준: 팔꿈치각 분포 (수축 시 각의 최소 프레임 = 성긴 표본이라 진짜 최소보다 클 수 있음)
    rom = pd.DataFrame(rrows)
    rom.to_csv(OUT / "B1_rom.csv", index=False, encoding="utf-8-sig")

    # ---------------- 현 규칙 기준선 (elbow_torso_R__mean > 0.193, D 뷰, 월드) 와 대안
    brows = []
    y = np.where(valid, (~conds[COND_ELBOW].astype(bool)).astype(float).to_numpy(), np.nan)
    for rep in ["GT3D"] + reps_avail:
        for vl in (["-"] if rep == "GT3D" else VIEWS):
            for key, thr, dirn in (("elbow_torso_R_mean", 0.193326, 1), ("elbow_torso_L_mean", 0.193326, 1), ("elbow_torso_mean_mean", 0.193326, 1),
                                   ("elbow_max_min", 91.210014, 1)):
                x = gt[key].to_numpy() if rep == "GT3D" else get(rep, vl, key)
                m = np.isfinite(x) & np.isfinite(y)
                if m.sum() < 30:
                    continue
                pred = (x[m] * dirn) > (thr * dirn)
                brows.append(dict(rep=rep, view=vl, rule=f"{key} {'>' if dirn > 0 else '<'} {thr:.4g}", n=int(m.sum()),
                                  auc=float(roc_auc_score(y[m], x[m] * dirn)), fpr=float(np.mean(pred[y[m] == 0])), tpr=float(np.mean(pred[y[m] == 1])),
                                  normal_median=float(np.median(x[m][y[m] == 0])), error_median=float(np.median(x[m][y[m] == 1]))))
    base = pd.DataFrame(brows)
    base.to_csv(OUT / "B1_baseline_rule.csv", index=False, encoding="utf-8-sig")

    # ---------------- 랜드마크 품질 (팔 관절)
    lq = landmark_quality(M, G, c, e, valid, OKV, ok) if M is not None else pd.DataFrame()
    if len(lq):
        lq.to_csv(OUT / "B1_landmark_quality.csv", index=False, encoding="utf-8-sig")

    # ---------------- 카메라 요약
    camsum = cam[cam.view_letter.isin(VIEWS)].groupby("view_letter")[["az_deg", "elev_deg", "cam_h_cm", "dist_cm", "pitch_deg"]].median().round(1)
    write_tables(auc, fid, dist, rom, base, lq, camsum, valid, all_true, elbow_true, conds, lr_corr, gt, sess, reps_avail)
    print("[done]", OUT)


def landmark_quality(M, G, c, e, valid, OKV, ok):
    rows = []
    idx = np.arange(G.shape[0])
    for vl in VIEWS:
        vi = ALL_LETTERS.index(vl)
        g = G[:, vi]
        torso = np.linalg.norm(g[idx, np.maximum(e, 0), GJ["Neck"]] - (g[idx, np.maximum(e, 0), GJ["LHip"]] + g[idx, np.maximum(e, 0), GJ["RHip"]]) / 2, axis=-1)
        use = valid & OKV[:, vi] & np.isfinite(torso) & (torso > 40)
        fr = use[:, None] & ok & M["HAS"][:, vi]
        det = M["DET"][:, vi]
        rows.append(dict(view=vl, joint="(검출)", n_frames=int(fr.sum()), detect=float(det[fr].mean())))
        X = M["X"][:, vi]
        for jn, (li, ri, gl, gr) in ARM.items():
            sel_f = fr & det
            vis = M["VIS"][:, vi][..., [li, ri]][sel_f]
            tn = torso[:, None].repeat(T, 1)[sel_f]
            pl, pr = X[..., li, :][sel_f], X[..., ri, :][sel_f]
            ql, qr = g[..., GJ[gl], :][sel_f], g[..., GJ[gr], :][sel_f]
            e_dir = (np.linalg.norm(pl - ql, axis=-1) + np.linalg.norm(pr - qr, axis=-1)) / 2 / tn
            e_sw = (np.linalg.norm(pl - qr, axis=-1) + np.linalg.norm(pr - ql, axis=-1)) / 2 / tn
            sep = np.linalg.norm(ql - qr, axis=-1) / tn
            clear = sep >= 0.15
            # 수축 프레임 vs 이완 프레임 오차
            isc = np.zeros((G.shape[0], T), bool)
            isc[idx[valid], c[valid]] = True
            ise = np.zeros((G.shape[0], T), bool)
            ise[idx[valid], e[valid]] = True
            rows.append(dict(view=vl, joint=jn, n_frames=int(sel_f.sum()), vis_med=float(np.nanmedian(vis)), vis_p10=float(np.nanpercentile(vis, 10)),
                             vis_lt05=float(np.mean(vis < MIN_VIS)), vis_lt05_L=float(np.mean(vis[:, 0] < MIN_VIS)), vis_lt05_R=float(np.mean(vis[:, 1] < MIN_VIS)),
                             px_err_med=float(np.nanmedian(e_dir)), px_err_L=float(np.nanmedian(np.linalg.norm(pl - ql, axis=-1) / tn)),
                             px_err_R=float(np.nanmedian(np.linalg.norm(pr - qr, axis=-1) / tn)),
                             px_err_c=float(np.nanmedian(e_dir[isc[sel_f]])), px_err_e=float(np.nanmedian(e_dir[ise[sel_f]])),
                             swap_rate=float(np.mean((e_sw < e_dir)[clear])) if clear.sum() else np.nan, n_clear=int(clear.sum())))
    return pd.DataFrame(rows)


def md_table(df: pd.DataFrame, index: bool = False) -> str:
    """tabulate 없이 마크다운 표."""
    d = df.reset_index() if index else df
    cols = [str(c) for c in d.columns]
    def cell(v):
        if isinstance(v, float):
            return "—" if not np.isfinite(v) else f"{v:.3f}"
        return str(v)
    lines = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    for r in d.itertuples(index=False):
        lines.append("| " + " | ".join(cell(v) for v in r) + " |")
    return "\n".join(lines)


def write_tables(auc, fid, dist, rom, base, lq, camsum, valid, all_true, elbow_true, conds, lr_corr, gt, sess, reps_avail):
    L = []
    P = L.append
    P(f"# B1 표 (자동 생성) — 덤벨 컬 {int(valid.sum())}클립 반복 판정 가능 / 세션 {len(set(sess[valid]))} / 정상(5조건 전부 충족) {int(all_true.sum())} / 팔꿈치 고정 충족 {int(elbow_true.sum())}\n")
    P("좌/우 팔꿈치각 시간 상관 중앙값 %.2f (양팔 동시 컬), < 0.5 인 클립 %d\n" % (np.nanmedian(lr_corr), int(np.nansum(lr_corr < 0.5))))
    P("## 카메라 (DLT 중앙값)\n")
    P(md_table(camsum, index=True) + "\n")

    def fmt(v, nd=2):
        return "—" if v is None or (isinstance(v, float) and not np.isfinite(v)) else f"{v:.{nd}f}"

    # 조건 AUC 표: 후보 × 표현/뷰
    for cond in auc.condition.unique():
        a = auc[auc.condition == cond]
        g3 = a[a.rep == "GT3D"].set_index("cand")
        top = g3.sort_values("auc", ascending=False)
        P(f"\n## 조건 '{cond}' — GT3D AUC 상위 (원값 / 세션 중심화, n)\n")
        P("| 후보 | AUC | AUC_w | 검출률@오탐5% | n |\n|---|---|---|---|---|")
        for k, r in top.head(40).iterrows():
            P(f"| {k} | {fmt(r.auc)} | {fmt(r.auc_within)} | {fmt(r.tpr_at_fpr05)} | {int(r.n)} |")
        # 표현·뷰별 상위 후보
        for rep in reps_avail:
            sub = a[a.rep == rep]
            if sub.empty:
                continue
            P(f"\n### '{cond}' — {rep} 뷰별 AUC (원값/중심화) [같은 부분집합 GT3D]\n")
            keys = list(top.head(25).index) + [k for k in sub.cand.unique() if k not in g3.index][:15]
            P("| 후보 | " + " | ".join(VIEWS) + " |\n|---|" + "---|" * len(VIEWS))
            for k in keys:
                cells = []
                for vl in VIEWS:
                    r = sub[(sub.cand == k) & (sub.view == vl)]
                    if r.empty or not np.isfinite(r.auc.iloc[0]):
                        cells.append("—")
                    else:
                        r = r.iloc[0]
                        gs = f" [{fmt(r.gt3d_auc_same)}]" if np.isfinite(r.gt3d_auc_same) else ""
                        cells.append(f"{fmt(r.auc)}/{fmt(r.auc_within)}{gs} n{int(r.n)}")
                P(f"| {k} | " + " | ".join(cells) + " |")

    P("\n## 충실도 (GT3D 진실 대비): ρ (ρw) · 편향 · 세션 중심화 오차 SD\n")
    P("| 변수 | 표현 | " + " | ".join(VIEWS) + " |\n|---|---|" + "---|" * len(VIEWS))
    for (group, cand), sub in fid.groupby(["group", "cand"], sort=False):
        for rep in ["GT2D", "MP2D", "MPW", "MP2D_vs_GT2D", "MPWself"]:
            s2 = sub[sub.rep == rep]
            if s2.empty:
                continue
            cells = []
            for vl in VIEWS:
                r = s2[s2.view == vl]
                if r.empty or "rho" not in r or not np.isfinite(r.rho.iloc[0]):
                    cells.append("—")
                else:
                    r = r.iloc[0]
                    extra = f" b{fmt(r.bias_med)} sd{fmt(r.err_sd_w)}" if "bias_med" in r and np.isfinite(r.get("bias_med", np.nan)) else ""
                    cells.append(f"{fmt(r.rho)} ({fmt(r.rho_w)}){extra} n{int(r.n)}")
            P(f"| {group} | {rep} | " + " | ".join(cells) + " |")

    P("\n## 정상 분포 (p5 / p50 / p95 / p99, n, 세션) 와 GT p95·p99 띠의 오탐률\n")
    for nsname in dist.normset.unique():
        P(f"\n### 정상 집합 = {nsname}\n")
        P("| 변수 | 표현 | 뷰 | n | p5 | p50 | p95 | p99 | sd_w | FPR@GTp90 | FPR@GTp95 | FPR@GTp99 |\n|---|---|---|---|---|---|---|---|---|---|---|---|")
        for r in dist[dist.normset == nsname].itertuples():
            P(f"| {r.cand} | {r.rep} | {r.view} | {r.n} | {fmt(r.p5, 3)} | {fmt(r.p50, 3)} | {fmt(r.p95, 3)} | {fmt(r.p99, 3)} | {fmt(r.sd_w, 3)} | "
              f"{fmt(getattr(r, 'fpr_at_gt_p90', np.nan))} | {fmt(getattr(r, 'fpr_at_gt_p95', np.nan))} | {fmt(getattr(r, 'fpr_at_gt_p99', np.nan))} |")

    P("\n## 가동 범위\n")
    P(md_table(rom.round(3)) + "\n")
    P("\n## 현 규칙 기준선\n")
    P(md_table(base.round(3)) + "\n")
    if len(lq):
        P("\n## 랜드마크 품질\n")
        P(md_table(lq.round(3)) + "\n")
    (OUT / "B1_tables.md").write_text("\n".join(L), encoding="utf-8")


if __name__ == "__main__":
    main()

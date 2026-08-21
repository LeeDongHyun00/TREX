"""3D 키포인트(24관절, cm) → 클립 단위 각도/기하 피처.

좌표계 가정: 데이터셋 월드좌표, +y = 위(발목 y≈10, 선 자세 목 y≈160 으로 확인).
신체 좌표계(body frame): 매 프레임 골반 중점을 원점으로
  x_b = 좌우 (좌골반-우골반의 수평 성분, + = 사용자 왼쪽)
  y_b = 월드 수직
  z_b = 전방 (x_b × y_b)
카메라/리그 방위각과 무관하게 정의되므로, 실제 앱에서 MediaPipe world landmark에 같은 변환을 적용할 수 있다.

피처 철학 (이전 논의 반영):
  - 3점 각도는 체형/스케일 불변
  - 거리류는 몸통 길이·다리 길이·어깨 폭으로 정규화 (체형 정규화)
  - 클립 집계: mean/min/max/std/range + 시계열(무릎-고관절 동시성 등)
"""
from __future__ import annotations

import warnings
from pathlib import Path

import numpy as np
import pandas as pd

from parse_labels import JOINTS_SHORT, KP3D_COLS

warnings.filterwarnings("ignore", category=RuntimeWarning)

J = {name: i for i, name in enumerate(JOINTS_SHORT)}
UP = np.array([0.0, 1.0, 0.0], dtype=np.float32)


# ---------------------------------------------------------------- IO
def load_kp3d(out_dir: Path | str):
    """kp3d.parquet → (clip_ids[list], arr[N, T, 24, 3] float32; 결측=NaN)."""
    out_dir = Path(out_dir)
    df = pd.read_parquet(out_dir / "kp3d.parquet")
    counts = df.groupby("clip_id", sort=False).size()
    clip_ids = list(counts.index)
    T = int(counts.max())
    N = len(clip_ids)
    arr = np.full((N, T, len(JOINTS_SHORT), 3), np.nan, dtype=np.float32)
    codes = pd.Categorical(df["clip_id"], categories=clip_ids).codes
    arr[codes, df["frame_idx"].to_numpy().astype(int)] = df[KP3D_COLS].to_numpy(dtype=np.float32).reshape(-1, len(JOINTS_SHORT), 3)
    return clip_ids, arr


# ---------------------------------------------------------------- 기하 유틸
def _norm(v, keepdims=False):
    return np.linalg.norm(v, axis=-1, keepdims=keepdims)


def _unit(v):
    n = _norm(v, keepdims=True)
    return v / np.where(n < 1e-6, np.nan, n)


def _ang(a, b, c):
    """b를 꼭짓점으로 하는 각도(도)."""
    u = _unit(a - b)
    w = _unit(c - b)
    return np.degrees(np.arccos(np.clip((u * w).sum(-1), -1.0, 1.0)))


def _ang_vec(u, w):
    u = _unit(u)
    w = _unit(w)
    return np.degrees(np.arccos(np.clip((u * w).sum(-1), -1.0, 1.0)))


def _horiz(v):
    h = v.copy()
    h[..., 1] = 0.0
    return h


def _perp_from_line(p, a, b):
    """점 p에서 직선 a→b 까지의 수직 벡터와 직선 길이."""
    ax = b - a
    L = _norm(ax, keepdims=True)
    u = ax / np.where(L < 1e-6, np.nan, L)
    d = p - a
    t = (d * u).sum(-1, keepdims=True)
    return d - t * u, L[..., 0]


def _nancorr_rows(a, b):
    m = ~(np.isnan(a) | np.isnan(b))
    a = np.where(m, a, np.nan)
    b = np.where(m, b, np.nan)
    am = a - np.nanmean(a, axis=1, keepdims=True)
    bm = b - np.nanmean(b, axis=1, keepdims=True)
    num = np.nansum(am * bm, axis=1)
    den = np.sqrt(np.nansum(am ** 2, axis=1) * np.nansum(bm ** 2, axis=1))
    return np.where(den > 1e-9, num / den, np.nan)


def _nanargext_rows(a, mode="max"):
    allnan = np.all(np.isnan(a), axis=1)
    if mode == "max":
        filled = np.where(np.isnan(a), -np.inf, a)
        idx = filled.argmax(axis=1).astype(float)
    else:
        filled = np.where(np.isnan(a), np.inf, a)
        idx = filled.argmin(axis=1).astype(float)
    idx[allnan] = np.nan
    return idx


# ---------------------------------------------------------------- 프레임 피처
def compute_frame_features(P: np.ndarray) -> dict[str, np.ndarray]:
    """P: (N, T, 24, 3) → {feature_name: (N, T)}"""
    g = lambda n: P[:, :, J[n], :]
    Nose, LEar, REar = g("Nose"), g("LEar"), g("REar")
    LSh, RSh, LEl, REl, LWr, RWr = g("LShoulder"), g("RShoulder"), g("LElbow"), g("RElbow"), g("LWrist"), g("RWrist")
    LHip, RHip, LKn, RKn, LAn, RAn = g("LHip"), g("RHip"), g("LKnee"), g("RKnee"), g("LAnkle"), g("RAnkle")
    Neck, LPa, RPa, Back, Waist, LFt, RFt = g("Neck"), g("LPalm"), g("RPalm"), g("Back"), g("Waist"), g("LFoot"), g("RFoot")

    hip_mid = (LHip + RHip) / 2
    sh_mid = (LSh + RSh) / 2
    kn_mid = (LKn + RKn) / 2
    an_mid = (LAn + RAn) / 2
    pa_mid = (LPa + RPa) / 2
    ear_mid = (LEar + REar) / 2

    # 신체 좌표계
    xb = _unit(_horiz(LHip - RHip))
    up = np.broadcast_to(UP, xb.shape)
    fwd = _unit(np.cross(xb, up))

    def body(p):
        d = p - hip_mid
        return np.stack([(d * xb).sum(-1), (d * up).sum(-1), (d * fwd).sum(-1)], axis=-1)

    chord = Neck - hip_mid                       # 몸통 축
    # 정규화 분모: 해부학적으로 불가능한 값(3D 복원 실패 프레임)은 NaN 처리해 폭주 방지
    torso_len = _norm(chord)
    torso_len = np.where(torso_len < 20.0, np.nan, torso_len)
    leg_L, leg_R = _norm(LHip - LAn), _norm(RHip - RAn)
    leg_len = (leg_L + leg_R) / 2
    leg_len = np.where(leg_len < 40.0, np.nan, leg_len)
    sh_w = _norm(LSh - RSh)
    sh_w = np.where(sh_w < 15.0, np.nan, sh_w)
    hip_w = _norm(LHip - RHip)
    hip_w = np.where(hip_w < 8.0, np.nan, hip_w)
    body_h = Neck[..., 1] - an_mid[..., 1]
    body_h = np.where(np.abs(body_h) < 30.0, np.nan, body_h)

    F: dict[str, np.ndarray] = {}
    # --- 관절 각도 (체형 불변)
    F["knee_L"], F["knee_R"] = _ang(LHip, LKn, LAn), _ang(RHip, RKn, RAn)
    F["hip_L"], F["hip_R"] = _ang(LSh, LHip, LKn), _ang(RSh, RHip, RKn)
    F["elbow_L"], F["elbow_R"] = _ang(LSh, LEl, LWr), _ang(RSh, REl, RWr)
    F["shoulder_L"], F["shoulder_R"] = _ang(LEl, LSh, LHip), _ang(REl, RSh, RHip)
    F["ankle_L"], F["ankle_R"] = _ang(LKn, LAn, LFt), _ang(RKn, RAn, RFt)
    F["wrist_L"], F["wrist_R"] = _ang(LEl, LWr, LPa), _ang(REl, RWr, RPa)
    F["knee_mean"] = (F["knee_L"] + F["knee_R"]) / 2
    F["hip_mean"] = (F["hip_L"] + F["hip_R"]) / 2
    F["elbow_mean"] = (F["elbow_L"] + F["elbow_R"]) / 2
    # 좌우 무관(side-agnostic) 각도: 런지처럼 앞/뒷다리가 L/R 어느 쪽인지 모를 때 — 더 굽힌 쪽 / 더 편 쪽
    F["knee_minside"] = np.fmin(F["knee_L"], F["knee_R"])
    F["knee_maxside"] = np.fmax(F["knee_L"], F["knee_R"])
    F["elbow_minside"] = np.fmin(F["elbow_L"], F["elbow_R"])
    F["elbow_maxside"] = np.fmax(F["elbow_L"], F["elbow_R"])

    # --- 척추 (Neck-Back-Waist-HipMid 4점 폴리라인)
    F["spine_upper"] = _ang(Neck, Back, Waist)
    F["spine_lower"] = _ang(Back, Waist, hip_mid)
    F["spine_dev_total"] = (180.0 - F["spine_upper"]) + (180.0 - F["spine_lower"])
    for name, pt in (("back", Back), ("waist", Waist)):
        perp, L = _perp_from_line(pt, hip_mid, Neck)
        F[f"spine_{name}_sag"] = (perp * fwd).sum(-1) / L    # + 전방 볼록, - 후방 볼록(굽음)
        F[f"spine_{name}_lat"] = (perp * xb).sum(-1) / L

    tb = body(Neck)
    F["torso_incl"] = _ang_vec(chord, up)                                # 0 = 직립
    F["torso_pitch"] = np.degrees(np.arctan2(tb[..., 2], tb[..., 1]))    # + 앞으로 숙임
    F["torso_roll"] = np.degrees(np.arctan2(tb[..., 0], tb[..., 1]))     # 좌우 기울기

    # --- 머리/시선
    F["neck_angle"] = _ang(ear_mid, Neck, Back)
    face = Nose - ear_mid
    fb = np.stack([(face * xb).sum(-1), (face * up).sum(-1), (face * fwd).sum(-1)], axis=-1)
    F["head_pitch"] = np.degrees(np.arctan2(fb[..., 1], np.hypot(fb[..., 0], fb[..., 2])))  # + 위를 봄
    F["head_yaw"] = np.degrees(np.arctan2(fb[..., 0], fb[..., 2]))                          # 0 = 전방
    F["face_vs_torso"] = _ang_vec(face, chord)                                              # 직립+정면 ≈ 90
    F["face_vs_forward"] = _ang_vec(face, fwd)                                              # 시선-전방 3D 각 (상하좌우 통합, 0 = 정면)

    # --- 무릎/발
    for s, (Hp, Kn, An, Ft, sign) in (("L", (LHip, LKn, LAn, LFt, 1.0)), ("R", (RHip, RKn, RAn, RFt, -1.0))):
        # 무릎-발 방향 불일치(도). 정강이/허벅지가 거의 수직이면 수평 성분이 노이즈라 NaN 처리(게이팅, 8cm)
        shin_h, thigh_h, foot_h = _horiz(Kn - An), _horiz(Kn - Hp), _horiz(Ft - An)
        kf_shin = _ang_vec(shin_h, foot_h)
        kf_thigh = _ang_vec(thigh_h, foot_h)
        F[f"kneefoot_{s}"] = np.where(_norm(shin_h) > 8.0, kf_shin, np.nan)
        F[f"kneefoot_thigh_{s}"] = np.where(_norm(thigh_h) > 8.0, kf_thigh, np.nan)
        F[f"foot_pitch_{s}"] = np.degrees(np.arcsin(np.clip(_unit(Ft - An)[..., 1], -1, 1)))
        F[f"ankle_y_{s}"] = An[..., 1]
        F[f"foot_y_{s}"] = Ft[..., 1]
        hb, kb, ab = body(Hp), body(Kn), body(An)
        denom = hb[..., 1] - ab[..., 1]
        t = (kb[..., 1] - ab[..., 1]) / np.where(np.abs(denom) < 1e-3, np.nan, denom)
        exp_x = ab[..., 0] + t * (hb[..., 0] - ab[..., 0])
        leg = _norm(Hp - An)
        F[f"knee_out_{s}"] = sign * (kb[..., 0] - exp_x) / leg     # + 바깥(varus), - 안쪽(valgus)
        F[f"knee_fwd_{s}"] = (kb[..., 2] - ab[..., 2]) / _norm(Kn - An)  # 무릎 전방 이동(정강이 길이 정규화)
    F["kneefoot_mean"] = np.nanmean(np.stack([F["kneefoot_L"], F["kneefoot_R"]]), axis=0)
    F["kneefoot_thigh_mean"] = np.nanmean(np.stack([F["kneefoot_thigh_L"], F["kneefoot_thigh_R"]]), axis=0)
    F["knee_out_mean"] = (F["knee_out_L"] + F["knee_out_R"]) / 2
    F["ankle_y_mean"] = (F["ankle_y_L"] + F["ankle_y_R"]) / 2
    F["foot_y_mean"] = (F["foot_y_L"] + F["foot_y_R"]) / 2

    # --- 손/바 경로 프록시
    pb, kbm, abm = body(pa_mid), body(kn_mid), body(an_mid)
    F["palm_fwd_hip"] = pb[..., 2] / torso_len
    F["palm_fwd_knee"] = (pb[..., 2] - kbm[..., 2]) / torso_len
    F["palm_fwd_ankle"] = (pb[..., 2] - abm[..., 2]) / torso_len
    F["palm_lat"] = pb[..., 0] / sh_w
    F["palm_h_rel"] = (pa_mid[..., 1] - an_mid[..., 1]) / body_h
    F["palm_dist_body"] = np.hypot(pb[..., 0], pb[..., 2]) / torso_len
    for s, (Sh, El, Wr, Hp) in (("L", (LSh, LEl, LWr, LHip)), ("R", (RSh, REl, RWr, RHip))):
        F[f"forearm_vert_{s}"] = _ang_vec(Wr - El, up)       # 0 = 전완 수직(위)
        F[f"upperarm_vert_{s}"] = _ang_vec(El - Sh, up)
        perp, L = _perp_from_line(El, Hp, Sh)
        F[f"elbow_torso_{s}"] = _norm(perp) / L              # 팔꿈치-몸통 거리
    F["forearm_vert_mean"] = (F["forearm_vert_L"] + F["forearm_vert_R"]) / 2

    # --- 어깨/견갑
    F["shoulder_h_L"] = (LSh[..., 1] - hip_mid[..., 1]) / torso_len
    F["shoulder_h_R"] = (RSh[..., 1] - hip_mid[..., 1]) / torso_len
    F["shoulder_neck_gap"] = (Neck[..., 1] - sh_mid[..., 1]) / torso_len     # 작아지면 으쓱(shrug)
    F["shoulder_asym"] = (LSh[..., 1] - RSh[..., 1]) / sh_w
    F["shoulder_fwd"] = (body(sh_mid)[..., 2] - tb[..., 2]) / torso_len        # 어깨 말림

    # --- 대칭
    F["knee_asym"] = F["knee_L"] - F["knee_R"]
    F["hip_asym"] = F["hip_L"] - F["hip_R"]
    F["elbow_asym"] = F["elbow_L"] - F["elbow_R"]
    F["hand_h_asym"] = (LPa[..., 1] - RPa[..., 1]) / torso_len

    # --- 폭/균형/깊이
    F["stance_w"] = _norm(LAn - RAn) / hip_w
    F["grip_w"] = _norm(LPa - RPa) / sh_w
    F["neck_over_ankle"] = (tb[..., 2] - abm[..., 2]) / leg_len       # 상체 전방 이동(COM 프록시)
    F["hip_height_rel"] = (hip_mid[..., 1] - an_mid[..., 1]) / leg_len  # 깊이
    F["hip_below_knee"] = (hip_mid[..., 1] - kn_mid[..., 1]) / leg_len
    F["sh_over_hip_fwd"] = tb[..., 2] / torso_len                       # 어깨-골반 전후 오프셋

    # --- 룰엔진 v0 보조 피처 (머리-손, 무릎 높이/간격/측방, 팔꿈치 높이, 귀-어깨 간격)
    F["palm_head_dist"] = _norm(pa_mid - ear_mid) / torso_len           # 양손-머리 거리 (머리 뒤 손 위치)
    F["palm_h_sh"] = (pa_mid[..., 1] - sh_mid[..., 1]) / torso_len       # 손 높이 vs 어깨
    F["ear_shoulder_gap"] = (ear_mid[..., 1] - sh_mid[..., 1]) / torso_len  # 귀-어깨 간격: MediaPipe 가능한 으쓱/목 프록시
    F["knee_gap"] = _norm(LKn - RKn) / hip_w                             # 두 무릎 간격
    F["knee_elbow_dist"] = np.minimum(_norm(LKn - LEl), _norm(RKn - REl)) / torso_len  # 같은쪽 무릎-팔꿈치 최소 거리
    for s, (Hp, Kn, El, Sh, Wr, sign) in (("L", (LHip, LKn, LEl, LSh, LWr, 1.0)), ("R", (RHip, RKn, REl, RSh, RWr, -1.0))):
        F[f"knee_h_{s}"] = (Kn[..., 1] - hip_mid[..., 1]) / leg_len                 # 무릎 높이 (니업)
        F[f"knee_lat_{s}"] = sign * (body(Kn)[..., 0] - body(Hp)[..., 0]) / hip_w   # 무릎 측방 위치(+바깥)
        F[f"elbow_h_{s}"] = (El[..., 1] - Sh[..., 1]) / torso_len                   # 팔꿈치 높이 vs 어깨
        F[f"elbow_wrist_h_{s}"] = (El[..., 1] - Wr[..., 1]) / torso_len             # 팔꿈치 vs 손목 높이 (팔꿈치 리드)

    # --- 좌/우 쌍 피처의 side-agnostic 집계 (좌우 미러 불변 규칙용)
    # 모든 _L/_R 쌍은 '같은 물리량의 좌/우 측정치'이고 방향성 피처(knee_out, knee_lat)는 이미 부호를 정규화해 두었으므로
    # mean(평균) / minside(더 작은 쪽) / maxside(더 큰 쪽) 모두 좌우 반전에 불변이다.
    for base in sorted({k[:-2] for k in list(F) if k.endswith("_L") and (k[:-2] + "_R") in F}):
        lv, rv = F[base + "_L"], F[base + "_R"]
        F.setdefault(f"{base}_mean", (lv + rv) / 2)
        F.setdefault(f"{base}_minside", np.fmin(lv, rv))
        F.setdefault(f"{base}_maxside", np.fmax(lv, rv))
    return F


def mediapipe_computable(base: str) -> bool:
    """MediaPipe Pose 33 랜드마크로 계산 가능한 기본 피처인가.
    불가: Back/Waist 사용(spine_*), Neck이 어깨선 위에 별도 존재해야 하는 것(neck_angle, shoulder_neck_gap).
    가능: Neck≈어깨 중점, Palm≈index/pinky 중점, Foot≈foot_index(+heel) 로 대체 가능한 나머지."""
    if base.startswith("spine_"):
        return False
    # shoulder_fwd = 어깨중점 vs Neck 전후 오프셋 → MediaPipe 에선 Neck≈어깨중점이라 항등적으로 0 (실험 A 에서 AUC 0.5 로 확인)
    if base in ("neck_angle", "shoulder_neck_gap", "shoulder_fwd"):
        return False
    return True


# ---------------------------------------------------------------- 클립 집계
def aggregate_clip_features(F: dict[str, np.ndarray]) -> pd.DataFrame:
    cols: dict[str, np.ndarray] = {}
    for k, v in F.items():
        cols[f"{k}__mean"] = np.nanmean(v, axis=1)
        cols[f"{k}__min"] = np.nanmin(v, axis=1)
        cols[f"{k}__max"] = np.nanmax(v, axis=1)
        cols[f"{k}__std"] = np.nanstd(v, axis=1)
        cols[f"{k}__range"] = cols[f"{k}__max"] - cols[f"{k}__min"]
    # 시계열 관계 피처
    knee, hip = F["knee_mean"], F["hip_mean"]
    cols["ts_corr_knee_hip"] = _nancorr_rows(knee, hip)
    cols["ts_lag_argmax_hip_minus_knee"] = _nanargext_rows(hip, "max") - _nanargext_rows(knee, "max")
    cols["ts_lag_argmin_hip_minus_knee"] = _nanargext_rows(hip, "min") - _nanargext_rows(knee, "min")
    cols["ts_corr_knee_torso"] = _nancorr_rows(knee, F["torso_pitch"])
    cols["ts_corr_elbow_torso"] = _nancorr_rows(F["elbow_mean"], F["torso_pitch"])
    cols["ts_corr_elbow_knee"] = _nancorr_rows(F["elbow_mean"], knee)
    for k in ("torso_pitch", "knee_mean", "hip_mean", "elbow_mean", "hip_height_rel"):
        cols[f"ts_delta_{k}"] = F[k][:, -1] - F[k][:, 0]
    return pd.DataFrame(cols)


def compute_features(arr: np.ndarray) -> pd.DataFrame:
    return aggregate_clip_features(compute_frame_features(arr))


def apply_qc_mask(clip_ids: list[str], arr: np.ndarray, out_dir: Path, min_good_frames: int = 8):
    """qc_kp3d.py 가 만든 kp3d_frame_ok.parquet 로 불량 프레임을 NaN 처리하고, 양호 프레임이 부족한 클립은 제외."""
    qc_path = out_dir / "kp3d_frame_ok.parquet"
    if not qc_path.exists():
        print("[qc] kp3d_frame_ok.parquet 없음 — QC 마스킹 생략 (python qc_kp3d.py 먼저 실행 권장)")
        return clip_ids, arr, None
    m = pd.read_parquet(qc_path)
    codes = pd.Categorical(m["clip_id"], categories=clip_ids).codes
    valid = codes >= 0
    ok = np.zeros(arr.shape[:2], dtype=bool)
    ok[codes[valid], m["frame_idx"].to_numpy()[valid]] = m["ok"].to_numpy()[valid]
    arr = np.where(ok[..., None, None], arr, np.nan)
    good = ok.sum(axis=1)
    keep = good >= min_good_frames
    print(f"[qc] 불량 프레임 {int((~ok).sum() - (arr.shape[1]*len(clip_ids) - m.shape[0])):,}개 마스킹, 클립 {int(keep.sum()):,}/{len(clip_ids):,} 유지 (양호 프레임 ≥ {min_good_frames})")
    return [c for c, k in zip(clip_ids, keep) if k], arr[keep], keep


def build_or_load_features(out_dir: Path | str, recompute: bool = False, use_qc: bool = True, min_good_frames: int = 8) -> pd.DataFrame:
    """features.parquet 캐시(인덱스 clip_id). use_qc=True면 QC 마스크 적용 후 계산."""
    out_dir = Path(out_dir)
    cache = out_dir / "features.parquet"
    if cache.exists() and not recompute:
        return pd.read_parquet(cache)
    clip_ids, arr = load_kp3d(out_dir)
    if use_qc:
        clip_ids, arr, _ = apply_qc_mask(clip_ids, arr, out_dir, min_good_frames)
    feats = compute_features(arr)
    feats.insert(0, "clip_id", clip_ids)
    feats = feats.set_index("clip_id")
    feats.to_parquet(cache)
    return feats


if __name__ == "__main__":
    import sys
    out = sys.argv[1] if len(sys.argv) > 1 else str(Path(__file__).resolve().parent / "outputs")
    f = build_or_load_features(out, recompute=True)
    print(f.shape)
    print(f.describe().T.head(40))

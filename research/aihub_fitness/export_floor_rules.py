#!/usr/bin/env python
"""바닥 운동 규칙 내보내기 — rules_floor_v0.json + Kotlin 패리티 픽스처 (spec §25).

floor_2d_rules.py(탐색)와의 차이 — 앱 배포를 위한 세 가지 정합화:
 1) **부호 정준화**: 부호 있는 이탈(_signed_dev)을 '화면 위쪽 = 양수'로 고정한다.
    사용자가 왼쪽/오른쪽 어느 방향으로 누워도(좌우 반전) 값이 불변이 된다.
 2) **종목당 뷰 1개**: 폰은 1대라 조건마다 다른 뷰를 쓸 수 없다. 사용 가능 조건 수가
    최대인 뷰 하나를 종목별로 고르고, 그 뷰에서의 성능으로만 규칙을 채택한다
    (조건별 최적 뷰 체리픽 제거 → 낙관 편향도 줄어든다).
 3) **스트리밍 접지선**: 앱은 프레임이 순서대로 들어오므로 접지선을 '지금까지 본
    프레임의 중앙값'으로 추정한다(prefix median). 임계값도 같은 정의로 적합해
    연구↔앱 피처가 비트 단위로 일치한다(픽스처로 검증).

출력:
  rules/rules_floor_v0.json                  — 앱 자산 (PostureRuleSet 로더 호환 스키마)
  outputs/floor_fixture.json                 — Kotlin 패리티 픽스처 (프레임 px 좌표 + 기대 피처)
  outputs/floor_export_report.md             — 채택/탈락 내역
"""
from __future__ import annotations

import json
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import balanced_accuracy_score, roc_auc_score, roc_curve
from sklearn.model_selection import GroupKFold

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
# 파서 산출물(clips/kp2d/conditions)이 이 워크트리에 없으면 구 워크트리 outputs 를 입력으로 쓴다 (floor_mp_gap.py 와 동일)
_SRC_FALLBACK = Path(r"C:\Users\hp276\Desktop\trex\.claude\worktrees\correct-exercise-form-6ddf55\research\aihub_fitness\outputs")
SRC = OUT if (OUT / "clips.parquet").exists() else _SRC_FALLBACK
FLOOR = ["크런치", "푸시업", "니푸쉬업", "플랭크", "라잉 레그 레이즈", "힙쓰러스트", "시저크로스", "바이시클 크런치", "Y - Exercise"]
# MediaPipe 33점으로 계산 가능한 관절만 (Neck/Back/Waist/Foot 제외 — Neck 은 어깨 중점으로 대체 가능하지만 여기선 불필요)
JOINTS = ["Nose", "LEar", "REar", "LShoulder", "RShoulder", "LElbow", "RElbow", "LWrist", "RWrist",
          "LHip", "RHip", "LKnee", "RKnee", "LAnkle", "RAnkle"]
STATS = ("mean", "min", "max", "std", "range")
MIN_FRAMES = 8
CV_AUC_CUT = 0.72          # 단일 뷰 고정 후 채택 컷 (탐색의 0.75 체리픽보다 완화·정직)
MIN_PERFORMERS = 3
# MP 측정 충실도 게이트 (v0.1, floor_mp_gap.py 실측): GT↔MP Spearman(뷰 풀링 n≈100)이 이 미만인
# (종목, 피처__통계) 는 후보에서 제외 — 임계값을 아무리 보정해도 판정이 복원되지 않는다.
# 0.35~0.5 는 채택하되 caution. 주의: ρ 는 20클립 표본 추정이라 CI 가 넓다(±~0.2) — 재보정 데이터로 재확인 대상.
RHO_CUT = 0.35
RHO_CAUTION = 0.5
# 좌우 반전 시 다른 쪽 사지를 재거나 부호가 뒤집히는 베이스 (정준화로도 안 잡히는 것)
NOT_MIRROR_SAFE = {"elbow_ang", "elbow_width", "shoulder_asym2d"}
# 뷰 코드 → 실제 기하. **관측으로 확정**(view_geometry.py): 바닥에서는 C 가 측면이다
# (몸길이/어깨폭 C=15.8 vs 다른 뷰 3.0~5.7). 카메라는 방에 고정이라 사람이 누우면
# 서 있을 때 정면이던 카메라가 몸의 측면을 보게 된다 — 서서 하는 종목과 뜻이 정반대.
VIEW_DESC = {
    "C": "측면 — 몸 옆에서, 바닥 높이",
    "A": "머리 쪽 또는 발 쪽 — 몸 축 방향, 바닥 높이",
    "B": "몸 옆에서 약간 비스듬히, 바닥 높이",
    "D": "몸 옆에서 약간 비스듬히, 바닥 높이",
    "E": "몸 옆에서 약간 비스듬히, 바닥 높이",
}


# ---------------------------------------------------------------- 피처 (앱과 동일 정의)
def dev_up(px: np.ndarray, py: np.ndarray, ax, ay, bx, by) -> np.ndarray:
    """직선 a→b 에서 점 p 의 수직 이탈 / |a−b|. **화면 위쪽(이미지 −y)이 양수**가 되도록 법선을 고정.

    n0 = (−u_y, u_x) 가 화면 아래(양의 y)를 향하면 뒤집는다 → 좌우 반전(사람이 반대로 누움)에 불변.
    """
    ux, uy = bx - ax, by - ay
    L = np.maximum(np.hypot(ux, uy), 1e-6)
    ux, uy = ux / L, uy / L
    nx, ny = -uy, ux
    flip = ny > 0
    nx = np.where(flip, -nx, nx)
    ny = np.where(flip, -ny, ny)
    return ((px - ax) * nx + (py - ay) * ny) / L


def ang(ax, ay, bx, by, cx, cy) -> np.ndarray:
    ux, uy = ax - bx, ay - by
    wx, wy = cx - bx, cy - by
    nu = np.maximum(np.hypot(ux, uy) * np.hypot(wx, wy), 1e-6)
    cos = np.clip((ux * wx + uy * wy) / nu, -1.0, 1.0)
    return np.degrees(np.arccos(cos))


class StreamingGround:
    """접지선 스트리밍 추정 — 지금까지 본 프레임에서 이동량이 작은 접지점 쌍(골반↔발목 vs 손목↔발목)의
    prefix 중앙값. 앱 FloorFeatureExtractor 와 동일 알고리즘(패리티 픽스처로 검증)."""

    def __init__(self):
        self.hp, self.an, self.wr = [], [], []
        self.move_ha = 0.0   # hip+ankle 누적 이동
        self.move_wa = 0.0   # wrist+ankle 누적 이동

    def add(self, hp, an, wr) -> tuple[np.ndarray, np.ndarray]:
        if self.hp:
            self.move_ha += float(np.hypot(*(hp - self.hp[-1]))) + float(np.hypot(*(an - self.an[-1])))
            self.move_wa += float(np.hypot(*(wr - self.wr[-1]))) + float(np.hypot(*(an - self.an[-1])))
        self.hp.append(hp); self.an.append(an); self.wr.append(wr)
        p = self.hp if self.move_ha <= self.move_wa else self.wr
        a = np.median(np.asarray(p), axis=0)
        b = np.median(np.asarray(self.an), axis=0)
        return a, b


def frame_features_stream(frames: np.ndarray, names: list[str]) -> dict[str, np.ndarray]:
    """클립 하나(프레임 순서대로)의 피처. frames: (T, len(JOINTS), 2) px 좌표."""
    ix = {j: i for i, j in enumerate(names)}

    def pt(j):
        return frames[:, ix[j], 0], frames[:, ix[j], 1]

    def mid(a, b):
        ax_, ay_ = pt(a); bx_, by_ = pt(b)
        return (ax_ + bx_) / 2, (ay_ + by_) / 2

    shx, shy = mid("LShoulder", "RShoulder")
    hpx, hpy = mid("LHip", "RHip")
    knx, kny = mid("LKnee", "RKnee")
    anx, any_ = mid("LAnkle", "RAnkle")
    wrx, wry = mid("LWrist", "RWrist")
    elx, ely = mid("LElbow", "RElbow")
    earx, eary = mid("LEar", "REar")
    nox, noy = pt("Nose")
    torso = np.maximum(np.hypot(shx - hpx, shy - hpy), 1e-6)

    F: dict[str, np.ndarray] = {}
    F["hip_dev_ankle"] = dev_up(hpx, hpy, shx, shy, anx, any_)
    F["hip_dev_knee"] = dev_up(hpx, hpy, shx, shy, knx, kny)
    F["knee_dev"] = dev_up(knx, kny, hpx, hpy, anx, any_)
    F["shoulder_dev"] = dev_up(shx, shy, hpx, hpy, wrx, wry)
    lsx, lsy = pt("LShoulder"); lex, ley = pt("LElbow"); lwx, lwy = pt("LWrist")
    F["elbow_ang"] = ang(lsx, lsy, lex, ley, lwx, lwy)
    F["knee_ang"] = ang(hpx, hpy, knx, kny, anx, any_)
    F["hip_ang"] = ang(shx, shy, hpx, hpy, knx, kny)
    F["trunk_ankle_ang"] = ang(shx, shy, hpx, hpy, anx, any_)
    F["head_trunk_ang"] = ang(nox, noy, earx, eary, hpx, hpy)
    F["shoulder_arm_ang"] = ang(hpx, hpy, shx, shy, elx, ely)
    F["hand_shoulder_off"] = dev_up(wrx, wry, shx, shy, hpx, hpy)
    F["wrist_shoulder_d"] = np.hypot(wrx - shx, wry - shy) / torso
    F["knee_shoulder_d"] = np.hypot(knx - shx, kny - shy) / torso
    F["ankle_hip_d"] = np.hypot(anx - hpx, any_ - hpy) / torso
    F["elbow_width"] = np.abs(dev_up(lex, ley, lsx, lsy, lwx, lwy))
    rkx, rky = pt("RKnee"); lkx, lky = pt("LKnee")
    rax, ray = pt("RAnkle"); lax, lay = pt("LAnkle")
    F["knee_gap2d"] = np.hypot(lkx - rkx, lky - rky) / torso
    F["ankle_gap2d"] = np.hypot(lax - rax, lay - ray) / torso
    rsx, rsy = pt("RShoulder")
    F["shoulder_asym2d"] = dev_up(lsx, lsy, rsx, rsy, hpx, hpy)

    # 접지선 (스트리밍)
    gr = StreamingGround()
    ga = np.empty((len(frames), 2)); gb = np.empty((len(frames), 2))
    for t in range(len(frames)):
        ga[t], gb[t] = gr.add(np.array([hpx[t], hpy[t]]), np.array([anx[t], any_[t]]), np.array([wrx[t], wry[t]]))
    F["shoulder_ground"] = dev_up(shx, shy, ga[:, 0], ga[:, 1], gb[:, 0], gb[:, 1])
    F["hip_ground"] = dev_up(hpx, hpy, ga[:, 0], ga[:, 1], gb[:, 0], gb[:, 1])
    F["knee_ground"] = dev_up(knx, kny, ga[:, 0], ga[:, 1], gb[:, 0], gb[:, 1])
    F["ankle_ground"] = dev_up(anx, any_, ga[:, 0], ga[:, 1], gb[:, 0], gb[:, 1])
    F["head_ground"] = dev_up(earx, eary, ga[:, 0], ga[:, 1], gb[:, 0], gb[:, 1])
    return F


def aggregate_clip(F: dict[str, np.ndarray]) -> dict[str, float]:
    out = {}
    for k, v in F.items():
        v = v[np.isfinite(v)]
        if len(v) == 0:
            continue
        out[f"{k}__mean"] = float(v.mean()); out[f"{k}__min"] = float(v.min()); out[f"{k}__max"] = float(v.max())
        out[f"{k}__std"] = float(v.std()); out[f"{k}__range"] = float(v.max() - v.min())
    return out


# ---------------------------------------------------------------- 데이터 → 클립 피처 테이블
def build_features() -> pd.DataFrame:
    cols = ["clip_id", "view_letter", "frame_idx"] + [f"{j}_{a}" for j in JOINTS for a in "xy"]
    clips = pd.read_parquet(SRC / "clips.parquet")[["clip_id", "exercise", "performer"]]
    k2 = pd.read_parquet(SRC / "kp2d.parquet", columns=cols).merge(clips, on="clip_id")
    k2 = k2[k2.exercise.isin(FLOOR)].sort_values(["clip_id", "view_letter", "frame_idx"])
    rows = []
    for (cid, view), d in k2.groupby(["clip_id", "view_letter"]):
        if len(d) < MIN_FRAMES:
            continue
        frames = np.stack([d[[f"{j}_x", f"{j}_y"]].to_numpy(dtype=np.float64) for j in JOINTS], axis=1)
        r = aggregate_clip(frame_features_stream(frames, JOINTS))
        r.update(clip_id=cid, view=view, exercise=d.exercise.iloc[0], performer=str(d.performer.iloc[0]))
        rows.append(r)
    return pd.DataFrame(rows)


def youden(score, y):
    fpr, tpr, thr = roc_curve(y, score)
    t = thr[int(np.argmax(tpr - fpr))]
    return float(t) if np.isfinite(t) else float(np.median(score))


def cv_rule(X, y, g, names):
    """GroupKFold 로 피처 선택 + AUC. floor_2d_rules.fit_rule 과 동일 로직."""
    ng = len(np.unique(g))
    if min(int(y.sum()), len(y) - int(y.sum())) < 12 or ng < MIN_PERFORMERS:
        return None
    aucs, baccs, chosen = [], [], []
    for tr, te in GroupKFold(min(5, ng)).split(X, y, g):
        ytr, yte = y[tr], y[te]
        if len(np.unique(ytr)) < 2 or len(np.unique(yte)) < 2:
            continue
        med = np.nanmedian(X[tr], axis=0)
        med = np.where(np.isnan(med), 0.0, med)
        Xtr = np.where(np.isnan(X[tr]), med, X[tr])
        Xte = np.where(np.isnan(X[te]), med, X[te])
        keep = np.flatnonzero([np.unique(c).size >= 2 for c in Xtr.T])
        if keep.size == 0:
            continue
        a = np.array([roc_auc_score(ytr, Xtr[:, j]) for j in keep])
        b = keep[int(np.argmax(np.abs(a - 0.5)))]
        sgn = 1.0 if roc_auc_score(ytr, Xtr[:, b]) >= 0.5 else -1.0
        t = youden(sgn * Xtr[:, b], ytr)
        aucs.append(roc_auc_score(yte, sgn * Xte[:, b]))
        baccs.append(balanced_accuracy_score(yte, (sgn * Xte[:, b] >= t).astype(int)))
        chosen.append(b)
    if not aucs:
        return None
    return dict(auc=float(np.mean(aucs)), bacc=float(np.mean(baccs)), n_folds=len(aucs),
                feature=names[max(set(chosen), key=chosen.count)])


def main():
    feats = build_features()
    conds = pd.read_parquet(SRC / "conditions.parquet")
    fcols = [c for c in feats.columns if "__" in c]

    # 0) 충실도 게이트 로드 — floor_mp_gap.py 산출. 표에 없는(표본 부족) 조합도 보수적으로 제외.
    fid_path = OUT / "floor_stat_fidelity_all.csv"
    if not fid_path.exists():
        sys.exit("[err] outputs/floor_stat_fidelity_all.csv 없음 — 먼저 `python floor_mp_gap.py` 실행 (v0.1 게이트 입력)")
    fid = pd.read_csv(fid_path)
    fid_map = {(r.exercise, r.feature): float(r.spearman) for r in fid.itertuples() if np.isfinite(r.spearman)}
    faithful = {ex: [c for c in fcols if fid_map.get((ex, c), -1.0) >= RHO_CUT] for ex in FLOOR}
    print(f"[features] 클립×뷰 {len(feats)} · 피처 {len(fcols)} · 충실도 게이트(ρ≥{RHO_CUT}) 통과 "
          + ", ".join(f"{ex} {len(v)}" for ex, v in faithful.items()), flush=True)

    # 1) 종목당 뷰 1개: 각 뷰에서 CV 를 돌려 '사용 가능 조건 수(≥컷)' 최대 뷰 선택 — 후보는 충실 피처만
    per = []
    for (ex, view), d in feats.groupby(["exercise", "view"]):
        cands = faithful.get(ex, [])
        if not cands:
            continue
        X_all = d[cands].to_numpy(dtype=np.float64)
        g = d.performer.to_numpy()
        for cond, cg in conds[conds.exercise == ex].groupby("condition"):
            y = cg.drop_duplicates("clip_id").set_index("clip_id")["value"].reindex(d.clip_id)
            m = y.notna().to_numpy()
            if m.sum() < 60:
                continue
            yviol = (~y[m].astype(bool)).to_numpy().astype(int)   # 위반=1 (value=False 가 위반)
            r = cv_rule(X_all[m], yviol, g[m], cands)
            if r:
                per.append(dict(exercise=ex, view=view, condition=cond, n=int(m.sum()), **r))
    per = pd.DataFrame(per)

    view_pick = {}
    for ex, d in per.groupby("exercise"):
        score = d.groupby("view").agg(usable=("auc", lambda s: int((s >= CV_AUC_CUT).sum())), mean_auc=("auc", "mean"))
        view_pick[ex] = score.sort_values(["usable", "mean_auc"], ascending=False).index[0]

    # 2) 채택 뷰에서 규칙 확정: 전체 데이터 Youden 임계값 (방향 = AUC 부호)
    rules, report = [], []
    for _, row in per.iterrows():
        ex, view, cond = row.exercise, row.view, row.condition
        if view != view_pick[ex]:
            continue
        ok = row.auc >= CV_AUC_CUT
        report.append(dict(exercise=ex, view=view, condition=cond, cv_auc=row.auc, adopted=ok, feature=row.feature))
        if not ok:
            continue
        d = feats[(feats.exercise == ex) & (feats.view == view)]
        y = conds[(conds.exercise == ex) & (conds.condition == cond)].drop_duplicates("clip_id").set_index("clip_id")["value"].reindex(d.clip_id)
        m = y.notna().to_numpy()
        yviol = (~y[m].astype(bool)).to_numpy().astype(int)
        x = d[row.feature].to_numpy(dtype=np.float64)[m]
        fin = np.isfinite(x)
        x, yviol = x[fin], yviol[fin]
        sgn = 1.0 if roc_auc_score(yviol, x) >= 0.5 else -1.0
        t = youden(sgn * x, yviol)
        op = ">" if sgn > 0 else "<"
        thr = t if sgn > 0 else -t
        base = row.feature.rsplit("__", 1)[0]
        rho = fid_map.get((ex, row.feature))
        cautions = (["좌우 반전(반대로 누움) 시 다른 쪽 사지를 잼"] if base in NOT_MIRROR_SAFE else [])
        if rho is not None and rho < RHO_CAUTION:
            cautions.append(f"MP 충실도 낮음(ρ={rho:.2f}<{RHO_CAUTION}) — 재보정 데이터로 확인 필요")
        # 정상-앵커 재배치 (v0.2, spec §25a / FLOOR_QUANTILE_TRANSFER): 임계값은 채택 뷰의 투영에 묶여
        # 있으므로, 사용자의 실제 폰 시점에서 찍은 정자세 기준선으로 위치를 옮길 수 있게 앵커를 싣는다.
        #   normal_median — 채택 뷰 정상 클립들의 피처 중앙값. 중앙값 이동: t_user = 사용자중앙값 + (thr − normal_median)
        #     = 기존 personal_baseline 경로(값−기준선 vs threshold_rel)와 동일 → 앱 평가 코드 재사용.
        #   normal_fpr — 이 임계값이 채택 뷰 정상 클립을 오탐하는 비율(분위수 방식용, 진단·대안).
        x_norm = x[yviol == 0]
        normal_median = float(np.median(x_norm))
        normal_fpr = float((x_norm > thr).mean() if op == ">" else (x_norm < thr).mean())
        rules.append({
            "id": f"floor|{ex}|{cond}",
            "exercise": ex, "condition": cond, "subtype": None,
            "status": "beta",
            "reason": "바닥 2D 경로 — AIHub 는 바닥 높이 카메라가 없어 임계값 미보정(세트 로그로 재보정 필요)",
            "feature": row.feature, "base_feature": base, "stat": row.feature.rsplit("__", 1)[1],
            "family": "floor2d", "op": op, "threshold": round(float(thr), 6),
            "view_best_front": view, "view_best_front_desc": VIEW_DESC[view],
            "cv_auc": round(float(row.auc), 4), "cv_balacc": round(float(row.bacc), 4), "n": int(row.n),
            "mp_fidelity": round(rho, 3) if rho is not None else None,
            "mirror_safe": base not in NOT_MIRROR_SAFE,
            "cautions": cautions,
            "mode": "floor2d",
            "normal_median": round(normal_median, 6),
            "normal_fpr": round(normal_fpr, 4),
            "personal_baseline": {
                "eligible": True, "k": 3, "gain": None,
                "threshold_rel": round(float(thr) - normal_median, 6),
                "mode": "reanchor",
                "note": "바닥 시점 재배치 — 기준선(사용자 폰 위치의 정자세 k세트 중앙값)으로 임계값 위치를 옮긴다. 검증: FLOOR_ANCHOR_VALIDATION.md",
            },
        })

    doc = {"version": "floor_v0.2", "generated": "2026-08-24",
           "source": "export_floor_rules.py (AIHub 2D, 종목당 단일 뷰, 스트리밍 접지선, 위쪽=양수 정준화, "
                     f"MP 충실도 게이트 ρ≥{RHO_CUT} — floor_mp_gap.py 실측)",
           "rules": rules}
    (HERE / "rules").mkdir(exist_ok=True)
    with open(HERE / "rules" / "rules_floor_v0.json", "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)

    # 3) Kotlin 패리티 픽스처 — 라인 텍스트 포맷(유닛 테스트의 org.json 스텁 회피, PostureCoreParityTest 와 동일 방식)
    #    CLIP <종목> <뷰> / FRAME n / P <관절> x y … / F <피처> 값 … / ENDFRAME / ENDCLIP
    cols = ["clip_id", "view_letter", "frame_idx"] + [f"{j}_{a}" for j in JOINTS for a in "xy"]
    clips_meta = pd.read_parquet(SRC / "clips.parquet")[["clip_id", "exercise"]]
    k2 = pd.read_parquet(SRC / "kp2d.parquet", columns=cols).merge(clips_meta, on="clip_id")
    lines = ["# floor_port_fixture — export_floor_rules.py 생성. px 좌표를 순서대로 먹여 프레임 피처 일치를 검증."]
    for ex in ["푸시업", "힙쓰러스트", "시저크로스"]:
        view = view_pick.get(ex)
        if view is None:
            continue
        d = k2[(k2.exercise == ex) & (k2.view_letter == view)]
        cid = d.clip_id.iloc[0]
        d = d[d.clip_id == cid].sort_values("frame_idx")
        frames = np.stack([d[[f"{j}_x", f"{j}_y"]].to_numpy(dtype=np.float64) for j in JOINTS], axis=1)
        F = frame_features_stream(frames, JOINTS)
        lines.append(f"CLIP {ex} {view}")
        for t in range(len(frames)):
            lines.append(f"FRAME {t}")
            for ji, j in enumerate(JOINTS):
                lines.append(f"P {j} {frames[t, ji, 0]:.4f} {frames[t, ji, 1]:.4f}")
            for k in F:
                if np.isfinite(F[k][t]):
                    lines.append(f"F {k} {F[k][t]:.6f}")
            lines.append("ENDFRAME")
        lines.append("ENDCLIP")
    (OUT / "floor_port_fixture.txt").write_text("\n".join(lines), encoding="utf-8")

    # 4) 리포트
    rep = pd.DataFrame(report).sort_values(["exercise", "adopted", "cv_auc"], ascending=[True, False, False])
    L = ["# 바닥 규칙 내보내기 (rules_floor_v0.1 — MP 충실도 게이트)\n",
         f"- 종목당 단일 뷰 고정, **MP 충실도 게이트 ρ≥{RHO_CUT}**(floor_mp_gap 실측, 뷰 풀링 n≈100), CV AUC ≥ {CV_AUC_CUT}, 수행자 ≥ {MIN_PERFORMERS} → **채택 {len(rules)}규칙 / {len({r['exercise'] for r in rules})}종목**",
         "- v0 대비: 측정-사망 피처(std/min 형 팔다리 각도 등)가 후보에서 빠지고, 같은 조건이 충실한 대체 피처로 살아나면 그걸로 재적합된다",
         f"- ρ<{RHO_CAUTION} 채택분은 caution 표기. ρ 는 20클립 표본 추정(CI ±~0.2) — 재보정 데이터로 재확인 대상",
         "- 전 규칙 status=beta: AIHub 에 바닥 높이 카메라가 없어 임계값은 미보정 — 세트 로그 재보정 전까지 참고용\n",
         "| 종목 | 뷰 | 조건 | CV AUC | ρ(MP) | 채택 | 피처 |", "|---|---|---|---|---|---|---|"]
    for _, r in rep.iterrows():
        rho = fid_map.get((r.exercise, r.feature))
        L.append(f"| {r.exercise} | {r.view} | {r.condition} | {r.cv_auc:.3f} | {f'{rho:.2f}' if rho is not None else '—'} | {'✅' if r.adopted else '—'} | `{r.feature}` |")
    (OUT / "floor_export_report.md").write_text("\n".join(L), encoding="utf-8")
    print(f"[export] 규칙 {len(rules)}개 → rules/rules_floor_v0.json")
    for ex, v in sorted(view_pick.items()):
        n_ok = sum(1 for r in rules if r["exercise"] == ex)
        print(f"  {ex}: 뷰 {v}, 채택 {n_ok}")
    print("[fixture] outputs/floor_fixture.json")


if __name__ == "__main__":
    main()

#!/usr/bin/env python
"""체형보존(anthropometry-invariant) 알고리즘 — 정준 골격 리타게팅.

아이디어: 각 프레임의 골격을 '관절 방향(단위벡터)' 과 '뼈 길이' 로 분해하고, 뼈 길이만 **정준(인구 중앙값) 체형**으로
바꿔 다시 조립한다(forward kinematics). 관절각은 그대로이고(각도는 방향만으로 결정), 위치·거리 피처(손 위치, 그립폭,
손-머리 거리 …)는 팔·몸통 길이 차이가 제거된 '표준 체형 위에서의 자세' 가 된다.
→ 기존 정규화(몸통 길이·어깨폭으로 나눔)가 못 지우는 **체절 간 비율 차이**(팔/몸통, 대퇴/경골 …)까지 지운다.

검증: 리타게팅 전/후 피처로 (a) 수행자 홀드아웃 AUC, (b) 체형 4분위 이식 초과분(§20), (c) 체형 회귀 R² 를 비교.
특히 §20 에서 '체형 의존' 으로 분류된 규칙(grip_w, torso_incl, face_vs_forward …) 이 개선되는지.

출력: outputs/features_canonical.parquet, outputs/canonical_retarget.csv, outputs/CANONICAL_RETARGET.md
"""
from __future__ import annotations

import json
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.linear_model import Ridge
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import GroupKFold

from features import J, JOINTS_SHORT, apply_qc_mask, compute_features, load_kp3d
from personalization_experiments import BODY_COLS, body_ratios
import invariance_analysis as inv

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"

# 골격 트리: (자식, 부모). 부모가 "HIPMID"/"NOSE" 등 파생점이면 별도 처리. 순서 = 부모가 먼저 나오도록.
TREE = [
    ("LHip", "HIPMID"), ("RHip", "HIPMID"), ("Neck", "HIPMID"), ("Waist", "HIPMID"), ("Back", "HIPMID"),
    ("LKnee", "LHip"), ("RKnee", "RHip"), ("LAnkle", "LKnee"), ("RAnkle", "RKnee"), ("LFoot", "LAnkle"), ("RFoot", "RAnkle"),
    ("LShoulder", "Neck"), ("RShoulder", "Neck"), ("Nose", "Neck"), ("LEar", "Neck"), ("REar", "Neck"),
    ("LEye", "Nose"), ("REye", "Nose"),
    ("LElbow", "LShoulder"), ("RElbow", "RShoulder"), ("LWrist", "LElbow"), ("RWrist", "RElbow"), ("LPalm", "LWrist"), ("RPalm", "RWrist"),
]
# 좌우 대칭 뼈는 같은 정준 길이를 쓴다
SYM = {"LHip": "hip_half", "RHip": "hip_half", "LKnee": "thigh", "RKnee": "thigh", "LAnkle": "shin", "RAnkle": "shin",
       "LFoot": "foot", "RFoot": "foot", "LShoulder": "sh_half", "RShoulder": "sh_half", "LEar": "ear", "REar": "ear",
       "LEye": "eye", "REye": "eye", "LElbow": "uarm", "RElbow": "uarm", "LWrist": "farm", "RWrist": "farm", "LPalm": "hand", "RPalm": "hand",
       "Neck": "torso", "Waist": "waist", "Back": "back", "Nose": "nose"}


def parent_pos(arr, name):
    if name == "HIPMID":
        return (arr[:, :, J["LHip"]] + arr[:, :, J["RHip"]]) / 2
    return arr[:, :, J[name]]


def canonical_lengths(arr: np.ndarray) -> dict[str, float]:
    """뼈별 정준 길이 = 전 프레임 중앙값 (좌우 합산)."""
    acc: dict[str, list] = {}
    for child, parent in TREE:
        d = np.linalg.norm(arr[:, :, J[child]] - parent_pos(arr, parent), axis=-1)
        acc.setdefault(SYM[child], []).append(d[np.isfinite(d)])
    return {k: float(np.median(np.concatenate(v))) for k, v in acc.items()}


HEAD = ["Nose", "LEar", "REar", "LEye", "REye"]


def retarget(arr: np.ndarray, L: dict[str, float], rigid_head: bool = True) -> np.ndarray:
    """관절 방향은 유지, 뼈 길이만 정준으로. 루트(골반 중점)는 원위치.

    머리(코·귀·눈)는 **강체**로 취급해 목 기준 단일 배율(정준 귀중점 거리 / 개인 귀중점 거리)로만 스케일한다 —
    코·귀를 개별 뼈로 리타게팅하면 얼굴 방향(코 − 귀중점)이 왜곡되어 head_pitch/face_* 피처가 깨진다.
    """
    out = np.full_like(arr, np.nan)
    hip_mid = (arr[:, :, J["LHip"]] + arr[:, :, J["RHip"]]) / 2
    newpos = {"HIPMID": hip_mid}
    for child, parent in TREE:
        if rigid_head and child in HEAD:
            continue
        p_old = parent_pos(arr, parent)
        c_old = arr[:, :, J[child]]
        d = c_old - p_old
        n = np.linalg.norm(d, axis=-1, keepdims=True)
        u = d / np.where(n < 1e-6, np.nan, n)
        p_new = newpos[parent]
        c_new = p_new + u * L[SYM[child]]
        out[:, :, J[child]] = c_new
        newpos[child] = c_new
    if rigid_head:
        neck_old = arr[:, :, J["Neck"]]
        neck_new = newpos["Neck"]
        ear_mid = (arr[:, :, J["LEar"]] + arr[:, :, J["REar"]]) / 2
        head_size = np.linalg.norm(ear_mid - neck_old, axis=-1, keepdims=True)
        scale = L["ear"] / np.where(head_size < 1e-6, np.nan, head_size)     # L["ear"] = 목→귀 정준 거리의 대리
        for name in HEAD:
            out[:, :, J[name]] = neck_new + (arr[:, :, J[name]] - neck_old) * scale
    return out


def single_rule_auc(v, y, grp, sign):
    s = sign * v
    ok = np.isfinite(s)
    s, y, grp = s[ok], y[ok], grp[ok]
    if len(y) < 40 or len(np.unique(y)) < 2:
        return np.nan
    gkf = GroupKFold(n_splits=min(5, len(np.unique(grp))))
    aucs = []
    for tr, te in gkf.split(s, y, grp):
        if len(np.unique(y[te])) < 2:
            continue
        aucs.append(roc_auc_score(y[te], s[te]))
    return float(np.mean(aucs)) if aucs else np.nan


def body_r2(v, y, perf, body):
    m = (y == 1) & np.isfinite(v)
    B = body.reindex(perf[m])[BODY_COLS].to_numpy(dtype=np.float64)
    ok = np.isfinite(B).all(axis=1)
    if ok.sum() < 40:
        return np.nan
    Z = (B[ok] - B[ok].mean(0)) / (B[ok].std(0) + 1e-9)
    vv = v[m][ok]
    reg = Ridge(alpha=1.0).fit(Z, vv)
    pred = reg.predict(Z)
    ss_res = np.sum((vv - pred) ** 2); ss_tot = np.sum((vv - vv.mean()) ** 2)
    return float(1 - ss_res / ss_tot) if ss_tot > 0 else 0.0


def main():
    clip_ids, arr = load_kp3d(OUT)
    clip_ids, arr, _ = apply_qc_mask(clip_ids, arr, OUT)
    L = canonical_lengths(arr)
    print("[canon] 정준 뼈 길이(cm):", {k: round(v, 1) for k, v in L.items()}, flush=True)
    arr_c = retarget(arr, L)
    # 리타게팅 검증: 관절각 보존 (무릎각 차이)
    def knee(a):
        u = a[:, :, J["LHip"]] - a[:, :, J["LKnee"]]; w = a[:, :, J["LAnkle"]] - a[:, :, J["LKnee"]]
        return np.degrees(np.arccos(np.clip((u * w).sum(-1) / (np.linalg.norm(u, axis=-1) * np.linalg.norm(w, axis=-1)), -1, 1)))
    dk = np.nanmax(np.abs(knee(arr) - knee(arr_c)))
    print(f"[check] 리타게팅 후 무릎각 최대 변화 {dk:.4f}° (0 이어야 정상)", flush=True)
    feats_c = compute_features(arr_c)
    feats_c.insert(0, "clip_id", clip_ids)
    feats_c = feats_c.set_index("clip_id")
    feats_c.to_parquet(OUT / "features_canonical.parquet")
    feats_o = pd.read_parquet(OUT / "features.parquet")

    doc = json.load(open(HERE / "rules" / "rules_mp_v0.json", encoding="utf-8"))
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(OUT / "conditions.parquet")
    body = body_ratios()
    inv_prev = pd.read_csv(OUT / "invariance_analysis.csv") if (OUT / "invariance_analysis.csv").exists() else None
    tiers = {}
    if inv_prev is not None:
        inv_prev["q_excess_mean"] = inv_prev[[f"{k}_q_excess_spread" for k, _ in inv.QUARTILE_KEYS if f"{k}_q_excess_spread" in inv_prev]].mean(axis=1)
        for r in inv_prev.itertuples():
            tiers[(r.exercise, r.condition)] = (inv.tier(r._asdict()) if hasattr(r, "_asdict") else "")
    inv.NULL_REPEATS = 10
    rows, seen = [], set()
    for r in doc["rules"]:
        if r["status"] == "exclude":
            continue
        key = (r["exercise"], r["condition"])
        if key in seen:
            continue
        seen.add(key)
        col = r["feature"]
        if col not in feats_o.columns or col not in feats_c.columns:
            continue
        g = clips[clips.exercise == r["exercise"]]
        ids = g.index.intersection(feats_o.index).intersection(feats_c.index)
        sub = conds[(conds.clip_id.isin(ids)) & (conds.condition == r["condition"])]
        if sub.empty:
            continue
        yv = sub.drop_duplicates("clip_id").set_index("clip_id")["value"].reindex(ids)
        m = yv.notna().to_numpy()
        ids = ids[m]; y = yv[m].to_numpy().astype(int)
        perf = g.loc[ids, "performer"].to_numpy()
        vo = feats_o.loc[ids, col].to_numpy(dtype=np.float64)
        vc = feats_c.loc[ids, col].to_numpy(dtype=np.float64)
        sign = 1.0 if r["op"] == "<" else -1.0
        row = dict(exercise=r["exercise"], condition=r["condition"], feature=col, stat=col.rsplit("__", 1)[1],
                   family=col.rsplit("__", 1)[0], n=len(y), tier_prev=tiers.get(key, ""),
                   auc_orig=single_rule_auc(vo, y, perf, sign), auc_canon=single_rule_auc(vc, y, perf, sign),
                   r2_orig=body_r2(vo, y, perf, body), r2_canon=body_r2(vc, y, perf, body),
                   feature_changed=float(np.nanmean(np.abs(vo - vc)) / (np.nanstd(vo) + 1e-9)))
        for k, _ in inv.QUARTILE_KEYS:
            qo = inv.quartile_stress(sign * vo, y, perf, body, k)
            qc = inv.quartile_stress(sign * vc, y, perf, body, k)
            if qo and qc:
                row[f"{k}_excess_orig"] = qo.get("q_excess_spread", np.nan)
                row[f"{k}_excess_canon"] = qc.get("q_excess_spread", np.nan)
        rows.append(row)
        print(f"  {r['exercise']} | {r['condition']} | Δauc {row['auc_canon']-row['auc_orig']:+.3f}", flush=True)
    res = pd.DataFrame(rows)
    res["excess_orig"] = res[[c for c in res if c.endswith("_excess_orig")]].mean(axis=1)
    res["excess_canon"] = res[[c for c in res if c.endswith("_excess_canon")]].mean(axis=1)
    res.to_csv(OUT / "canonical_retarget.csv", index=False, encoding="utf-8-sig")
    write_report(res, L)


def write_report(res: pd.DataFrame, L: dict):
    changed = res[res.feature_changed > 0.01]
    same = res[res.feature_changed <= 0.01]
    dep = res[res.tier_prev == "체형 의존"]
    L_ = ["# 체형보존 알고리즘 — 정준 골격 리타게팅 검증\n",
          f"- 정준 뼈 길이(cm, 전 프레임 중앙값): " + ", ".join(f"{k} {v:.1f}" for k, v in L.items()),
          f"- 활성 규칙 {len(res)}개 중 리타게팅으로 값이 바뀌는 규칙 {len(changed)}개 (각도 피처 {len(same)}개는 정의상 불변 — 무릎각 최대 변화 0°)\n",
          "## 1. 전체 효과\n",
          "| 지표 | 원본 | 정준 리타게팅 | Δ |", "|---|---|---|---|",
          f"| 수행자 홀드아웃 AUC 중앙값 (값 바뀐 규칙 {len(changed)}개) | {changed.auc_orig.median():.3f} | {changed.auc_canon.median():.3f} | {(changed.auc_canon-changed.auc_orig).median():+.3f} |",
          f"| 체형 4분위 이식 초과분 중앙값 (값 바뀐 규칙) | {changed.excess_orig.median():+.3f} | {changed.excess_canon.median():+.3f} | {(changed.excess_canon-changed.excess_orig).median():+.3f} |",
          f"| 체형 회귀 R² 중앙값 (값 바뀐 규칙) | {changed.r2_orig.median():.2f} | {changed.r2_canon.median():.2f} | {(changed.r2_canon-changed.r2_orig).median():+.2f} |",
          "",
          "## 2. §20 에서 '체형 의존' 으로 분류된 규칙\n",
          "| 종목 | 조건 | 피처 | 값 변화(σ) | AUC 원본→정준 | 초과분 원본→정준 | R² 원본→정준 |", "|---|---|---|---|---|---|---|"]
    for r in dep.sort_values("excess_orig", ascending=False).itertuples():
        L_.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.feature_changed:.2f} | {r.auc_orig:.3f}→{r.auc_canon:.3f} | {r.excess_orig:+.3f}→{r.excess_canon:+.3f} | {r.r2_orig:.2f}→{r.r2_canon:.2f} |")
    L_ += ["", "## 3. 값이 바뀐 규칙 전체 (변화량 큰 순)\n",
           "| 종목 | 조건 | 피처 | 값 변화(σ) | AUC Δ | 초과분 Δ | R² Δ |", "|---|---|---|---|---|---|---|"]
    for r in changed.sort_values("feature_changed", ascending=False).itertuples():
        L_.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.feature_changed:.2f} | {r.auc_canon-r.auc_orig:+.3f} | {r.excess_canon-r.excess_orig:+.3f} | {r.r2_canon-r.r2_orig:+.2f} |")
    fam = changed.groupby("family").agg(n=("exercise", "size"), d_auc=("auc_canon", "median"), d_ex=("excess_canon", "median"),
                                       a0=("auc_orig", "median"), e0=("excess_orig", "median")).reset_index()
    L_ += ["", "## 4. 패밀리별 (값 바뀐 규칙)\n", "| 패밀리 | n | AUC 원본→정준 | 초과분 원본→정준 |", "|---|---|---|---|"]
    for r in fam.sort_values("n", ascending=False).itertuples():
        L_.append(f"| {r.family} | {r.n} | {r.a0:.3f}→{r.d_auc:.3f} | {r.e0:+.3f}→{r.d_ex:+.3f} |")
    (OUT / "CANONICAL_RETARGET.md").write_text("\n".join(L_), encoding="utf-8")
    print("\n".join(L_[:14]))


if __name__ == "__main__":
    main()

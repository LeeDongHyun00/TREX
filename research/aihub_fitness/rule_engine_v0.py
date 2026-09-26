#!/usr/bin/env python
"""룰엔진 v0 — 조건별 '물리 피처 화이트리스트' 안에서 단일 피처 + 임계값 규칙을 학습/검증.

왜 화이트리스트인가: 실험 B에서 AUC 최고 피처가 조건과 물리적으로 무관한 경우(동반 증상, 시나리오 아티팩트)가 있었다.
폰 카메라로 전이되려면 규칙은 조건의 해부학적 정의에 해당하는 피처 패밀리 안에서만 골라야 한다.

절차 (종목×조건 마다, '척추의 중립'은 하위유형별로):
  1) 조건명 키워드 → 허용 피처 패밀리 (아래 COND_RULES)
  2) 수행자 GroupKFold: 학습폴드에서 허용 피처 중 단변량 AUC 최대 피처 선택 + Youden J 임계값 → 테스트폴드 AUC / 균형정확도
  3) 같은 절차를 (a) 전체 피처(비제약), (b) MediaPipe 33점으로 계산 가능한 허용 피처만 — 로 반복해 비교
  4) 전체 데이터로 최종 규칙(피처·방향·임계값) 적합

출력: outputs/rules_v0.json, rules_v0.csv, rule_engine_v0.md
"""
from __future__ import annotations

import json
import re
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import balanced_accuracy_score, roc_auc_score, roc_curve
from sklearn.model_selection import GroupKFold

from experiment_b import univariate_auc
from features import build_or_load_features, mediapipe_computable

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

STATS = ("mean", "min", "max", "std", "range")

FAMILIES: dict[str, list[str]] = {
    "knee_angle": ["knee_L", "knee_R", "knee_mean", "knee_asym", "knee_minside", "knee_maxside"],
    "knee_align": ["knee_out_L", "knee_out_R", "knee_out_mean", "kneefoot_L", "kneefoot_R", "kneefoot_mean",
                   "kneefoot_thigh_L", "kneefoot_thigh_R", "kneefoot_thigh_mean", "knee_fwd_L", "knee_fwd_R"],
    "knee_height": ["knee_h_L", "knee_h_R", "knee_gap", "knee_lat_L", "knee_lat_R", "knee_elbow_dist", "hip_below_knee", "hip_height_rel",
                    "stance_w"],
    "hip_angle": ["hip_L", "hip_R", "hip_mean", "hip_asym"],
    "foot": ["foot_pitch_L", "foot_pitch_R", "ankle_y_L", "ankle_y_R", "ankle_y_mean", "foot_y_L", "foot_y_R", "foot_y_mean",
             "ankle_L", "ankle_R", "stance_w"],
    "elbow_angle": ["elbow_L", "elbow_R", "elbow_mean", "elbow_asym", "elbow_minside", "elbow_maxside"],
    "elbow_pos": ["elbow_torso_L", "elbow_torso_R", "upperarm_vert_L", "upperarm_vert_R", "shoulder_L", "shoulder_R",
                  "elbow_h_L", "elbow_h_R", "elbow_wrist_h_L", "elbow_wrist_h_R"],
    "forearm": ["forearm_vert_L", "forearm_vert_R", "forearm_vert_mean"],
    "wrist": ["wrist_L", "wrist_R"],
    "hand": ["palm_fwd_hip", "palm_fwd_knee", "palm_fwd_ankle", "palm_lat", "palm_h_rel", "palm_dist_body", "grip_w",
             "hand_h_asym", "palm_head_dist", "palm_h_sh"],
    "spine_sag": ["spine_upper", "spine_lower", "spine_dev_total", "spine_back_sag", "spine_waist_sag"],
    "spine_lat": ["spine_back_lat", "spine_waist_lat"],
    "torso": ["torso_incl", "torso_pitch", "sh_over_hip_fwd", "neck_over_ankle", "shoulder_fwd"],
    "torso_roll": ["torso_roll", "shoulder_asym", "hand_h_asym"],
    "head": ["head_pitch", "head_yaw", "face_vs_torso", "face_vs_forward", "neck_angle"],
    "shoulder": ["shoulder_neck_gap", "ear_shoulder_gap", "shoulder_h_L", "shoulder_h_R", "shoulder_asym"],
    "temporal": ["__ts__"],   # ts_* 전체
}

# 조건명 정규식 → 허용 패밀리 (매칭되는 규칙 모두 합집합). '척추'는 하위유형 테이블로 별도 처리.
# ※ 일부 조건은 라벨명과 '연기된 편차'(description)가 다르다 — 이 데이터에서의 실제 의미를 반영해 패밀리를 보강했고 주석에 근거를 남김.
COND_RULES: list[tuple[str, list[str]]] = [
    (r"발과 무릎|발 무릎|무릎 방향|발방향|방향 일치|방향일치", ["knee_align"]),
    (r"발바닥|뒤꿈치", ["foot"]),
    # 런지 '뒤다리 90도'는 '뒷다리 다 펴고'로 연기 → 무릎각 + 깊이(골반 높이)도 물리적으로 동치라 knee_height 포함
    (r"무릎.*(각도|90도|구부린|펴짐|긴장)|앞다리|뒤다리|다리 긴장|다리 펴짐|다리 접힘|허벅지와 종아리|무릎 너무 굽히", ["knee_angle", "hip_angle", "knee_height"]),
    (r"무릎.*(올라|모아|가까|측면)|다리 사이|무릎 엉덩이|양무릎 교차|다리와 지면", ["knee_height", "knee_angle", "knee_align", "foot"]),
    (r"무릎\s*반동", ["knee_angle"]),
    (r"고개|시선|경추|후인", ["head", "shoulder"]),
    (r"손목", ["wrist"]),
    # OHP '전완 지면과 수직'은 '팔꿈치 내밀고'로 연기 → 전완 각 + 팔꿈치 위치/그립폭
    (r"전완", ["forearm", "elbow_pos", "hand"]),
    (r"팔꿈치.*(각도|90도|리드|구부)|팔 긴장|팔 펴짐|팔 당김", ["elbow_angle", "elbow_pos"]),
    (r"팔꿈치", ["elbow_pos", "elbow_angle"]),
    # 페이스 풀 '상완의 외회전'은 '팔꿈치 모이게'로 연기 → 팔꿈치 위치/손 위치
    (r"상완|상관|외회전|내회전", ["elbow_pos", "forearm", "hand"]),
    (r"궤적|밀착|몸에서", ["hand"]),
    (r"손|덤벨|바벨|이마|양 팔|팔 높이", ["hand"]),
    # 행잉 레그 레이즈 '어깨와 귀 사이 거리'는 '좁게 잡고'(좁은 그립)로 연기 → 어깨 + 그립폭
    (r"견갑|숄더|어깨|으쓱|승모|귀 사이", ["shoulder", "hand"]),
    (r"상체|몸통|체스트|가슴|벤치", ["torso"]),
    # 크로스 런지 '상체 정면 균형'은 '뒤로 젖힘/하늘 보고/옆으로'로 연기 → 몸통 + 롤 + 머리
    (r"흔들림|균형", ["torso", "torso_roll", "head"]),
    (r"동시에|우선", ["temporal", "knee_angle", "hip_angle", "elbow_angle"]),
    (r"등 아치|등의 굽힘|허리", ["spine_sag", "torso"]),
    (r"긴장", ["elbow_angle", "knee_angle"]),   # '툭 내려놓기'(템포)로 연기 → 성긴 프레임에선 관측 불가, 약할 수밖에 없음
]

# 척추 하위유형 → 허용 패밀리
SPINE_FAMILIES: dict[str, list[str]] = {
    "flexion": ["spine_sag", "torso", "head", "shoulder"],
    "lateral": ["spine_lat", "torso_roll"],
    "extension": ["spine_sag", "torso"],
    "lumbar_swing": ["spine_sag", "torso"],
    "lumbar_sag": ["spine_sag", "torso"],
    "forward_lean": ["torso", "spine_sag"],
    "cervical": ["head", "shoulder"],          # 런지류: 시선 조건이 없어 경추 편차(왼쪽/하늘 보고)가 척추 비중립으로 코딩됨
    "all": ["spine_sag", "spine_lat", "torso", "torso_roll", "head", "shoulder"],
}
MIN_SUBTYPE_N = 30
SPINE_RE = re.compile(r"척추")


def families_for(condition: str) -> list[str]:
    fams: list[str] = []
    for pat, fs in COND_RULES:
        if re.search(pat, condition):
            for f in fs:
                if f not in fams:
                    fams.append(f)
    return fams


def expand_side_aggregates(bases: list[str]) -> list[str]:
    """패밀리에 `<base>_L` / `<base>_R` 이 있으면 side-agnostic 집계(mean/minside/maxside)도 후보에 포함."""
    out = list(bases)
    for b in bases:
        if b.endswith("_L") or b.endswith("_R"):
            stem = b[:-2]
            for suf in ("_mean", "_minside", "_maxside"):
                if stem + suf not in out:
                    out.append(stem + suf)
    return out


def columns_for(fams: list[str], all_cols: list[str], mp_only: bool = False) -> list[str]:
    bases: list[str] = []
    ts = False
    for f in fams:
        for b in FAMILIES[f]:
            if b == "__ts__":
                ts = True
            elif b not in bases:
                bases.append(b)
    bases = expand_side_aggregates(bases)
    cols = []
    for c in all_cols:
        if c.startswith("ts_"):
            if ts and (not mp_only or True):
                cols.append(c)
            continue
        base = c.rsplit("__", 1)[0]
        if base in bases and (not mp_only or mediapipe_computable(base)):
            cols.append(c)
    return cols


def youden_threshold(score: np.ndarray, y: np.ndarray) -> float:
    fpr, tpr, thr = roc_curve(y, score)
    j = tpr - fpr
    k = int(np.argmax(j))
    t = thr[k]
    if not np.isfinite(t):
        t = float(np.nanmedian(score))
    return float(t)


def fit_rule_cv(X: np.ndarray, y: np.ndarray, groups: np.ndarray, names: list[str], n_splits: int = 5) -> dict:
    """허용 피처 X 안에서 단일 피처 규칙을 GroupKFold 로 검증하고, 전체 데이터로 최종 규칙 적합."""
    res = dict(cv_auc=np.nan, cv_auc_std=np.nan, cv_balacc=np.nan, feature="", sign=0, threshold=np.nan, insample_auc=np.nan, n_folds=0)
    if X.shape[1] == 0:
        return res
    n_groups = len(np.unique(groups))
    if min(int(y.sum()), int(len(y) - y.sum())) < 15 or n_groups < 2:
        return res
    gkf = GroupKFold(n_splits=min(n_splits, n_groups))
    aucs, baccs, chosen = [], [], []
    for tr, te in gkf.split(X, y, groups):
        Xtr, Xte, ytr, yte = X[tr], X[te], y[tr], y[te]
        if len(np.unique(ytr)) < 2 or len(np.unique(yte)) < 2:
            continue
        keep = np.array([np.unique(c[~np.isnan(c)]).size >= 2 for c in Xtr.T])
        if not keep.any():
            continue
        idx = np.flatnonzero(keep)
        med = np.nanmedian(Xtr[:, keep], axis=0)
        med = np.where(np.isnan(med), 0.0, med)
        Xtr_i = np.where(np.isnan(Xtr[:, keep]), med, Xtr[:, keep])
        Xte_i = np.where(np.isnan(Xte[:, keep]), med, Xte[:, keep])
        a = univariate_auc(Xtr_i, ytr)
        b = int(np.nanargmax(np.abs(a - 0.5)))
        sign = 1.0 if a[b] >= 0.5 else -1.0
        s_tr, s_te = sign * Xtr_i[:, b], sign * Xte_i[:, b]
        t = youden_threshold(s_tr, ytr)
        aucs.append(roc_auc_score(yte, s_te))
        baccs.append(balanced_accuracy_score(yte, (s_te >= t).astype(int)))
        chosen.append(idx[b])
    if not aucs:
        return res
    best_idx = max(set(chosen), key=chosen.count)
    # 최종 규칙: 전체 데이터, 선택 피처 고정
    col = X[:, best_idx]
    med = float(np.nanmedian(col)) if np.isfinite(np.nanmedian(col)) else 0.0
    col_i = np.where(np.isnan(col), med, col)
    a_all = roc_auc_score(y, col_i)
    sign = 1.0 if a_all >= 0.5 else -1.0
    t = youden_threshold(sign * col_i, y)
    res.update(cv_auc=float(np.mean(aucs)), cv_auc_std=float(np.std(aucs)), cv_balacc=float(np.mean(baccs)),
               feature=names[best_idx], sign=int(sign), threshold=float(sign * t), insample_auc=float(max(a_all, 1 - a_all)),
               n_folds=len(aucs))
    return res


def rule_text(r: dict) -> str:
    """사람이 읽는 규칙: 위반 판정 조건."""
    if not r["feature"]:
        return ""
    # sign=+1: 값이 클수록 정상 → 위반 if value < thr ; sign=-1: 값이 클수록 위반 → 위반 if value > thr
    op = "<" if r["sign"] > 0 else ">"
    return f"위반 if {r['feature']} {op} {r['threshold']:.3g}"


def merge_only(out: Path):
    """rules_v0.csv 에 최신 experiment_b_results.csv 의 gbm/lr 컬럼만 다시 붙여 요약을 재생성."""
    res = pd.read_csv(out / "rules_v0.csv")
    res = res.drop(columns=[c for c in ("gbm_auc_cv", "lr_auc_cv") if c in res.columns])
    expb = pd.read_csv(out / "experiment_b_results.csv")
    gb = expb[["exercise", "condition", "gbm_auc_cv", "lr_auc_cv"]].rename(columns={"condition": "base_condition"})
    res = res.merge(gb, on=["exercise", "base_condition"], how="left")
    res["subtype"] = res["subtype"].fillna("")
    res["qc_flag"] = res["qc_flag"].fillna("")
    res.to_csv(out / "rules_v0.csv", index=False, encoding="utf-8-sig")
    write_summary(res, out)
    print("[merge-only] gbm/lr 컬럼 갱신 및 요약 재생성 완료")


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent / "outputs"
    if "--merge-only" in sys.argv:
        merge_only(out)
        return
    clips = pd.read_parquet(out / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(out / "conditions.parquet")
    feats = build_or_load_features(out)
    names = list(feats.columns)
    clips = clips.loc[clips.index.intersection(feats.index)]
    clips["group"] = np.where(clips["performer"].astype(str) != "", clips["performer"].astype(str), clips["day"].astype(str))
    qc = pd.read_csv(out / "qc_per_clip.csv") if (out / "qc_per_clip.csv").exists() else None
    bad_ex = set()
    if qc is not None:
        dp = qc.groupby("exercise")["drop_clip"].mean()
        bad_ex = set(dp[dp > 0.5].index)
    spine = pd.read_parquet(out / "spine_subtype.parquet").set_index("clip_id") if (out / "spine_subtype.parquet").exists() else None
    expb = pd.read_csv(out / "experiment_b_results.csv") if (out / "experiment_b_results.csv").exists() else None

    rows = []
    for ex_name, g in clips.groupby("exercise"):
        ids = g.index
        sub = conds[conds["clip_id"].isin(ids)]
        piv = sub.pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first").reindex(ids)
        cond_order = sub.groupby("condition")["cond_idx"].median().sort_values().index.tolist()
        for cname in cond_order:
            yv = piv[cname]
            mask = yv.notna().to_numpy()
            base_ids = ids[mask]
            y_all = yv[mask].to_numpy().astype(int)
            grp_all = g.loc[base_ids, "group"].to_numpy()
            X_all = feats.loc[base_ids].to_numpy(dtype=np.float64)

            if SPINE_RE.search(cname) and spine is not None:
                st = spine.reindex(base_ids)["subtype"].fillna("unspecified").to_numpy()
                variants = [("all", np.ones(len(base_ids), dtype=bool))]
                for s in ["flexion", "lateral", "extension", "lumbar_swing", "lumbar_sag", "forward_lean", "cervical", "unspecified"]:
                    m = (st == s) | (y_all == 1)
                    if int((st == s).sum()) >= MIN_SUBTYPE_N:
                        variants.append((s, m))
            else:
                variants = [(None, np.ones(len(base_ids), dtype=bool))]

            for subtype, m in variants:
                y = y_all[m]
                grp = grp_all[m]
                X = X_all[m]
                if subtype is None:
                    fams = families_for(cname)
                    label = cname
                else:
                    fams = SPINE_FAMILIES.get(subtype, SPINE_FAMILIES["all"])
                    label = f"{cname}[{subtype}]"
                wl_cols = columns_for(fams, names) if fams else names
                mp_cols = columns_for(fams, names, mp_only=True) if fams else [c for c in names if mediapipe_computable(c.rsplit('__', 1)[0]) or c.startswith("ts_")]
                wl_idx = [names.index(c) for c in wl_cols]
                mp_idx = [names.index(c) for c in mp_cols]
                r_wl = fit_rule_cv(X[:, wl_idx], y, grp, wl_cols)
                r_mp = fit_rule_cv(X[:, mp_idx], y, grp, mp_cols)
                r_un = fit_rule_cv(X, y, grp, names)
                row = dict(exercise=ex_name, condition=label, base_condition=cname, subtype=subtype or "",
                           n=int(len(y)), n_neg=int((y == 0).sum()), pos_rate=float(y.mean()), n_performers=int(len(np.unique(grp))),
                           qc_flag=("3D불량" if ex_name in bad_ex else ""),
                           families="+".join(fams) if fams else "(미정의→전체)", n_wl_features=len(wl_cols), n_mp_features=len(mp_cols),
                           wl_auc=r_wl["cv_auc"], wl_auc_std=r_wl["cv_auc_std"], wl_balacc=r_wl["cv_balacc"], wl_feature=r_wl["feature"],
                           wl_rule=rule_text(r_wl), wl_threshold=r_wl["threshold"], wl_sign=r_wl["sign"], wl_insample_auc=r_wl["insample_auc"],
                           mp_auc=r_mp["cv_auc"], mp_balacc=r_mp["cv_balacc"], mp_feature=r_mp["feature"], mp_rule=rule_text(r_mp),
                           mp_threshold=r_mp["threshold"], mp_sign=r_mp["sign"],
                           un_auc=r_un["cv_auc"], un_feature=r_un["feature"])
                rows.append(row)
        print(f"[{ex_name}] done", flush=True)

    res = pd.DataFrame(rows)
    if expb is not None:
        gb = expb[["exercise", "condition", "gbm_auc_cv", "lr_auc_cv"]].rename(columns={"condition": "base_condition"})
        res = res.merge(gb, on=["exercise", "base_condition"], how="left")
    res.to_csv(out / "rules_v0.csv", index=False, encoding="utf-8-sig")
    rules = [dict(exercise=r.exercise, condition=r.base_condition, subtype=r.subtype or None, families=r.families,
                  feature=r.wl_feature, sign=int(r.wl_sign) if r.wl_feature else None, threshold=(float(r.wl_threshold) if r.wl_feature else None),
                  rule=r.wl_rule, cv_auc=(None if np.isnan(r.wl_auc) else round(float(r.wl_auc), 4)),
                  cv_balacc=(None if np.isnan(r.wl_balacc) else round(float(r.wl_balacc), 4)),
                  mediapipe=dict(feature=r.mp_feature, rule=r.mp_rule, cv_auc=(None if np.isnan(r.mp_auc) else round(float(r.mp_auc), 4)),
                                 computable=bool(r.mp_feature) and mediapipe_computable(r.mp_feature.rsplit("__", 1)[0])),
                  n=int(r.n), n_performers=int(r.n_performers), qc_flag=r.qc_flag)
             for r in res.itertuples()]
    (out / "rules_v0.json").write_text(json.dumps(rules, ensure_ascii=False, indent=1), encoding="utf-8")
    write_summary(res, out)
    ok = res[(res.qc_flag != "3D불량") & res.wl_auc.notna() & (res.subtype == "")]
    print(f"\n[done] 규칙 {len(res)}개 (3D양호·기본조건 {len(ok)}개) | 화이트리스트 규칙 중앙값 AUC {ok.wl_auc.median():.3f} | MediaPipe-가능 {ok.mp_auc.median():.3f} | 비제약 {ok.un_auc.median():.3f}")
    print(f"       출력: {out/'rules_v0.json'}, {out/'rules_v0.csv'}, {out/'rule_engine_v0.md'}")


def bucket(a):
    if np.isnan(a):
        return "n/a"
    return "≥0.90" if a >= 0.9 else "0.80–0.90" if a >= 0.8 else "0.70–0.80" if a >= 0.7 else "<0.70"


def write_summary(res: pd.DataFrame, out: Path):
    good = res[(res.qc_flag != "3D불량") & res.wl_auc.notna()].copy()
    base = good[good.subtype == ""]
    sp = good[good.subtype != ""]
    L = ["# 룰엔진 v0 — 물리 피처 화이트리스트 단일 규칙 (수행자 GroupKFold)\n",
         f"- 규칙 후보 {len(res)}개 = 기본 조건 {int((res.subtype=='').sum())} + 척추 하위유형 {int((res.subtype!='').sum())} | 3D양호 종목에서 평가된 기본 조건 {len(base)}개",
         "- wl: 화이트리스트 안 최적 단일 피처 규칙 | mp: 그중 MediaPipe 33점으로 계산 가능한 피처만 | un: 전체 피처 비제약 | gbm: 실험 B 비선형 상한",
         "- 임계값: 학습폴드 Youden J, 균형정확도는 테스트폴드\n",
         "## 1. 분포 (기본 조건, 3D양호 종목)\n",
         "| 구간 | wl 규칙 | mp 규칙 | un(비제약) 규칙 | gbm |", "|---|---|---|---|---|"]
    for b in ["≥0.90", "0.80–0.90", "0.70–0.80", "<0.70"]:
        L.append(f"| {b} | {(base.wl_auc.map(bucket)==b).sum()} | {(base.mp_auc.map(bucket)==b).sum()} | {(base.un_auc.map(bucket)==b).sum()} | {(base.gbm_auc_cv.map(bucket)==b).sum() if 'gbm_auc_cv' in base else '-'} |")
    L.append(f"\n중앙값 — wl {base.wl_auc.median():.3f} | mp {base.mp_auc.median():.3f} | un {base.un_auc.median():.3f}" + (f" | gbm {base.gbm_auc_cv.median():.3f}" if "gbm_auc_cv" in base else ""))
    L.append(f"화이트리스트 비용(un−wl) 평균 {(base.un_auc-base.wl_auc).mean():+.3f}, MediaPipe 비용(wl−mp) 평균 {(base.wl_auc-base.mp_auc).mean():+.3f}, wl≥0.85 비율 {(base.wl_auc>=0.85).mean()*100:.0f}%, mp≥0.85 비율 {(base.mp_auc>=0.85).mean()*100:.0f}%\n")

    L += ["## 2. 척추의 중립 — 하위유형별 규칙 (neutral vs 해당 하위유형)\n",
          "| 종목 | 하위유형 | n(비중립) | wl AUC | wl 규칙 | mp AUC | mp 규칙 | un AUC (피처) |", "|---|---|---|---|---|---|---|---|"]
    for r in sp.sort_values(["subtype", "wl_auc"], ascending=[True, False]).itertuples():
        L.append(f"| {r.exercise} | {r.subtype} | {r.n_neg} | {r.wl_auc:.3f} | {r.wl_rule} | {r.mp_auc:.3f} | {r.mp_rule} | {r.un_auc:.3f} ({r.un_feature}) |")
    L.append("")

    L += ["## 3. 전체 규칙 (기본 조건)\n",
          "| 종목 | 조건 | n | 패밀리 | wl AUC (±) | 균형정확도 | wl 규칙 | mp AUC | mp 규칙 | un AUC | gbm |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in base.sort_values(["exercise", "wl_auc"], ascending=[True, False]).itertuples():
        g = f"{r.gbm_auc_cv:.3f}" if "gbm_auc_cv" in base and not np.isnan(r.gbm_auc_cv) else "-"
        L.append(f"| {r.exercise} | {r.condition} | {r.n} | {r.families} | {r.wl_auc:.3f} (±{r.wl_auc_std:.3f}) | {r.wl_balacc:.3f} | {r.wl_rule} | {r.mp_auc:.3f} | {r.mp_rule} | {r.un_auc:.3f} | {g} |")
    L.append("")

    weak = base[base.wl_auc < 0.75].sort_values("wl_auc")
    L += [f"## 4. 화이트리스트 규칙이 약한 조건 (wl AUC < 0.75, {len(weak)}개)\n",
          "| 종목 | 조건 | wl AUC | wl 피처 | un AUC | un 피처 | 해석 |", "|---|---|---|---|---|---|---|"]
    for r in weak.itertuples():
        gap = r.un_auc - r.wl_auc
        interp = "비제약도 약함 → 각도로 관측 불가(스코프 제외)" if r.un_auc < 0.78 else ("비제약이 훨씬 높음 → 패밀리 밖 신호(프록시/아티팩트 의심, 피처 보강 검토)" if gap > 0.08 else "경계")
        L.append(f"| {r.exercise} | {r.condition} | {r.wl_auc:.3f} | {r.wl_feature} | {r.un_auc:.3f} | {r.un_feature} | {interp} |")
    L.append("")
    nodef = res[res.families.str.startswith("(미정의")]
    if len(nodef):
        L.append("## 5. 화이트리스트 미정의 조건 (전체 피처 사용)\n")
        for r in nodef.drop_duplicates("condition").itertuples():
            L.append(f"- {r.condition}")
    (out / "rule_engine_v0.md").write_text("\n".join(L), encoding="utf-8")


if __name__ == "__main__":
    main()

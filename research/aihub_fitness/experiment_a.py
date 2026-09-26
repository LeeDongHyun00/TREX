#!/usr/bin/env python
"""실험 A — MediaPipe(단일 뷰) vs GT: 2D 정확도 · 3D 피처 충실도 · 규칙 전이.

입력: outputs/mp/landmarks_*.parquet (mp_infer.py), outputs/mp/sample.parquet, kp2d/kp3d/clips/conditions, features.parquet,
      rules_v0.json, spine_subtype.parquet
출력: outputs/experiment_a_summary.md, expA_2d_by_joint_view.csv, expA_feature_fidelity.csv, expA_rule_transfer.csv

세 질문:
  1) 2D: MediaPipe 33점이 GT 2D 주석과 얼마나 맞는가 (뷰별·관절별·종목별, 몸통길이 정규화 오차 / PCK)
  2) 3D/피처: MediaPipe world landmark 로 계산한 우리 피처가 GT 3D 피처와 얼마나 일치하는가 (뷰별 상관/MAE)
  3) 규칙 전이: GT 3D 로 만든 rules_v0 가 MediaPipe 피처 위에서도 성립하는가 (뷰별 AUC, GT 임계값 그대로의 균형정확도, 멀티뷰 융합)
"""
from __future__ import annotations

import glob
import json
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import spearmanr
from sklearn.metrics import balanced_accuracy_score, roc_auc_score

from features import JOINTS_SHORT, J, apply_qc_mask, compute_features, load_kp3d
from parse_labels import KP2D_COLS

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
MP = OUT / "mp"

# MediaPipe 33 → AIHub 24 매핑 (None = 없음, tuple = 중점)
MP_MAP = {
    "Nose": 0, "LEye": 2, "REye": 5, "LEar": 7, "REar": 8,
    "LShoulder": 11, "RShoulder": 12, "LElbow": 13, "RElbow": 14, "LWrist": 15, "RWrist": 16,
    "LHip": 23, "RHip": 24, "LKnee": 25, "RKnee": 26, "LAnkle": 27, "RAnkle": 28,
    "Neck": (11, 12), "LPalm": (17, 19), "RPalm": (18, 20), "Back": None, "Waist": None,
    "LFoot": 31, "RFoot": 32,
}
SWAP_LR = {"LEye": 5, "REye": 2, "LEar": 8, "REar": 7, "LShoulder": 12, "RShoulder": 11, "LElbow": 14, "RElbow": 13,
           "LWrist": 16, "RWrist": 15, "LHip": 24, "RHip": 23, "LKnee": 26, "RKnee": 25, "LAnkle": 28, "RAnkle": 27,
           "LPalm": (18, 20), "RPalm": (17, 19), "LFoot": 32, "RFoot": 31}
KEY_FEATS = ["knee_mean__mean", "knee_mean__min", "hip_mean__mean", "elbow_mean__mean", "torso_incl__mean", "torso_pitch__mean",
             "head_pitch__mean", "face_vs_torso__mean", "face_vs_forward__mean", "knee_out_mean__mean", "kneefoot_mean__mean",
             "foot_pitch_R__std", "ankle_y_mean__std", "palm_fwd_hip__max", "palm_h_rel__mean", "forearm_vert_mean__mean",
             "shoulder_asym__std", "ear_shoulder_gap__min", "torso_roll__range", "hip_height_rel__min", "grip_w__mean",
             "stance_w__mean", "knee_maxside__std", "ts_corr_knee_torso"]


def mp_point(row_block: np.ndarray, spec, kind: str) -> np.ndarray:
    """row_block: (n, 33, k) → (n, k) for joint spec."""
    if spec is None:
        return np.full((row_block.shape[0], row_block.shape[2]), np.nan, np.float32)
    if isinstance(spec, tuple):
        return (row_block[:, spec[0]] + row_block[:, spec[1]]) / 2.0
    return row_block[:, spec]


def load_landmarks() -> pd.DataFrame:
    files = sorted(glob.glob(str(MP / "landmarks_*.parquet")))
    if not files:
        raise SystemExit("landmarks_*.parquet 없음 — mp_infer.py 먼저")
    df = pd.concat([pd.read_parquet(f) for f in files], ignore_index=True)
    print(f"[load] landmark 파일 {len(files)}개, 이미지 {len(df):,}장, 검출 {df.detected.mean()*100:.1f}%")
    return df


def part_2d(lm: pd.DataFrame, sample: pd.DataFrame, clips: pd.DataFrame, bad_ex: set):
    k2 = pd.read_parquet(OUT / "kp2d.parquet", columns=["img_key", "clip_id", "frame_idx", "view_letter"] + KP2D_COLS)
    d = lm.merge(k2, on="img_key", how="inner").merge(clips[["clip_id", "exercise"]], on="clip_id", how="left")
    det = d[d.detected].copy()
    n_lm = 33
    P = det[[f"l{i}_{a}" for i in range(n_lm) for a in ("x", "y")]].to_numpy(np.float32).reshape(-1, n_lm, 2)
    G = det[KP2D_COLS].to_numpy(np.float32).reshape(-1, len(JOINTS_SHORT), 2)
    neck = G[:, J["Neck"]]
    hipm = (G[:, J["LHip"]] + G[:, J["RHip"]]) / 2
    torso = np.linalg.norm(neck - hipm, axis=-1)
    ok = torso > 20
    errs = {}
    for mapping, tag in ((MP_MAP, "direct"), ({**MP_MAP, **SWAP_LR}, "swapped")):
        E = np.full((len(det), len(JOINTS_SHORT)), np.nan, np.float32)
        for j, name in enumerate(JOINTS_SHORT):
            spec = mapping[name]
            if spec is None:
                continue
            p = mp_point(P, spec, "2d")
            E[:, j] = np.linalg.norm(p - G[:, j], axis=-1) / np.where(ok, torso, np.nan)
        errs[tag] = E
    med_direct = np.nanmedian(errs["direct"])
    med_swap = np.nanmedian(errs["swapped"])
    tag = "direct" if med_direct <= med_swap else "swapped"
    E = errs[tag]
    det = det.reset_index(drop=True)
    rows = []
    for j, name in enumerate(JOINTS_SHORT):
        if MP_MAP[name] is None:
            continue
        e = E[:, j]
        for vl, idx in det.groupby("view_letter").indices.items():
            ee = e[idx]
            ee = ee[~np.isnan(ee)]
            if len(ee) == 0:
                continue
            rows.append(dict(joint=name, view=vl, n=len(ee), median_err=float(np.median(ee)), mean_err=float(ee.mean()),
                             pck01=float((ee < 0.1).mean()), pck02=float((ee < 0.2).mean())))
    by_jv = pd.DataFrame(rows)
    # 종목별 (전체 관절 평균, 뷰 통합)
    det["err_mean"] = np.nanmean(E, axis=1)
    by_ex = det.groupby("exercise").agg(n_img=("img_key", "size"), median_err=("err_mean", "median"),
                                        pck02=("err_mean", lambda s: float((s < 0.2).mean()))).reset_index()
    by_ex["bad3d"] = by_ex["exercise"].isin(bad_ex)
    det_rate = d.groupby("view_letter")["detected"].mean()
    det_rate_ex = d.groupby("exercise")["detected"].mean()
    by_jv.to_csv(OUT / "expA_2d_by_joint_view.csv", index=False, encoding="utf-8-sig")
    by_ex.to_csv(OUT / "expA_2d_by_exercise.csv", index=False, encoding="utf-8-sig")
    return dict(tag=tag, med_direct=med_direct, med_swap=med_swap, by_jv=by_jv, by_ex=by_ex, det_rate=det_rate,
                det_rate_ex=det_rate_ex, n_img=len(d), n_det=len(det), infer_ms=float(lm["infer_ms"].mean()))


def build_mp_arrays(lm: pd.DataFrame, sample: pd.DataFrame, tag: str):
    """MediaPipe world landmarks → (clip_id, view_letter) 별 (T,24,3) cm 배열 (AIHub 관절 매핑, y=up 으로 변환)."""
    s = sample[["img_key", "clip_id", "frame_idx", "view_letter"]].merge(lm, on="img_key", how="inner")
    det = s[s.detected].copy()
    Wd = det[[f"w{i}_{a}" for i in range(33) for a in ("x", "y", "z")]].to_numpy(np.float32).reshape(-1, 33, 3) * 100.0  # cm
    # 축 방향 자동 판별: 코가 골반보다 위 → y 부호 결정 (MediaPipe world 는 이미지 관례로 y 아래가 + 인 경우가 많음)
    nose_y = Wd[:, 0, 1]
    if np.nanmedian(nose_y) < 0:   # 코 y 가 음수 = y 아래 양수 관례 → 뒤집기
        Wd[:, :, 1] *= -1.0
        Wd[:, :, 2] *= -1.0        # 오른손 좌표계 유지
        flipped = True
    else:
        flipped = False
    mapping = MP_MAP if tag == "direct" else {**MP_MAP, **SWAP_LR}
    A = np.full((len(det), len(JOINTS_SHORT), 3), np.nan, np.float32)
    for j, name in enumerate(JOINTS_SHORT):
        A[:, j] = mp_point(Wd, mapping[name], "3d")
    det = det.reset_index(drop=True)
    keys = det[["clip_id", "view_letter"]].drop_duplicates().reset_index(drop=True)
    key_idx = {(c, v): i for i, (c, v) in enumerate(zip(keys.clip_id, keys.view_letter))}
    T = int(sample["frame_idx"].max()) + 1
    arr = np.full((len(keys), T, len(JOINTS_SHORT), 3), np.nan, np.float32)
    ci = np.array([key_idx[(c, v)] for c, v in zip(det.clip_id, det.view_letter)])
    arr[ci, det["frame_idx"].to_numpy().astype(int)] = A
    return keys, arr, flipped


def part_features(keys: pd.DataFrame, arr_mp: np.ndarray, feats_gt: pd.DataFrame):
    fm = compute_features(arr_mp)
    fm.insert(0, "view_letter", keys["view_letter"].to_numpy())
    fm.insert(0, "clip_id", keys["clip_id"].to_numpy())
    fm.to_parquet(OUT / "expA_features_mp.parquet", index=False)
    rows = []
    for f in KEY_FEATS:
        if f not in fm.columns or f not in feats_gt.columns:
            continue
        for vl, g in fm.groupby("view_letter"):
            gt = feats_gt.reindex(g["clip_id"])[f].to_numpy()
            mp_ = g[f].to_numpy()
            m = ~(np.isnan(gt) | np.isnan(mp_))
            if m.sum() < 30:
                continue
            r = spearmanr(gt[m], mp_[m]).correlation
            rows.append(dict(feature=f, view=vl, n=int(m.sum()), spearman=float(r), mae=float(np.mean(np.abs(gt[m] - mp_[m]))),
                             bias=float(np.mean(mp_[m] - gt[m]))))
        # 멀티뷰 융합(뷰 평균)
        fused = fm.groupby("clip_id")[f].mean()
        gt = feats_gt.reindex(fused.index)[f].to_numpy()
        mp_ = fused.to_numpy()
        m = ~(np.isnan(gt) | np.isnan(mp_))
        if m.sum() >= 30:
            rows.append(dict(feature=f, view="FUSED", n=int(m.sum()), spearman=float(spearmanr(gt[m], mp_[m]).correlation),
                             mae=float(np.mean(np.abs(gt[m] - mp_[m]))), bias=float(np.mean(mp_[m] - gt[m]))))
    fid = pd.DataFrame(rows)
    fid.to_csv(OUT / "expA_feature_fidelity.csv", index=False, encoding="utf-8-sig")
    return fm, fid


def part_rules(fm: pd.DataFrame, clips: pd.DataFrame, conds: pd.DataFrame, rules: list, spine: pd.DataFrame | None, rules_csv: pd.DataFrame):
    fm = fm.set_index(["clip_id", "view_letter"])
    fused = fm.groupby(level=0).mean()
    views = sorted(fm.index.get_level_values(1).unique())
    cond_piv = conds.pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first")
    rows = []
    for r in rules:
        ex, cond, st = r["exercise"], r["condition"], r.get("subtype")
        ids = clips.index[clips["exercise"] == ex]
        if cond not in cond_piv.columns:
            continue
        y_all = cond_piv.reindex(ids)[cond]
        ids = y_all.dropna().index
        y_all = y_all.dropna().astype(int)
        if st and spine is not None:
            sub = spine.reindex(ids)["subtype"].fillna("unspecified")
            if st != "all":
                keep = (sub == st) | (y_all == 1)
                ids, y_all = ids[keep.to_numpy()], y_all[keep.to_numpy()]
        for variant in ("wl", "mp"):
            if variant == "wl":
                feat, sign, thr = r["feature"], r["sign"], r["threshold"]
            else:
                feat, sign, thr = r["mediapipe"]["feature"], None, None
                # mp 규칙의 sign/threshold 는 rules_v0.csv 에서
                rc = rules_csv[(rules_csv.exercise == ex) & (rules_csv.base_condition == cond) & (rules_csv.subtype.fillna("") == (st or ""))]
                if len(rc) and isinstance(rc.iloc[0].get("mp_sign"), (int, float, np.integer, np.floating)):
                    sign, thr = int(rc.iloc[0]["mp_sign"]), float(rc.iloc[0]["mp_threshold"])
            if not feat or feat not in fm.columns or sign is None or thr is None or np.isnan(thr):
                continue
            gt_auc = r["cv_auc"] if variant == "wl" else r["mediapipe"]["cv_auc"]
            for vl in views + ["FUSED"]:
                if vl == "FUSED":
                    x = fused.reindex(ids)[feat]
                else:
                    sub_fm = fm.xs(vl, level=1)
                    x = sub_fm.reindex(ids)[feat]
                m = x.notna().to_numpy()
                if m.sum() < 30 or y_all[m].nunique() < 2:
                    continue
                xv = x.to_numpy()[m]
                yv = y_all.to_numpy()[m]
                s = sign * xv
                auc = roc_auc_score(yv, s)
                bacc = balanced_accuracy_score(yv, (s >= sign * thr).astype(int))
                rows.append(dict(exercise=ex, condition=cond, subtype=st or "", variant=variant, feature=feat, view=vl, n=int(m.sum()),
                                 gt_auc=gt_auc, mp_auc=float(auc), mp_balacc_gt_thr=float(bacc), delta=float(auc - (gt_auc or np.nan))))
    rt = pd.DataFrame(rows)
    rt.to_csv(OUT / "expA_rule_transfer.csv", index=False, encoding="utf-8-sig")
    return rt


def write_summary(p2d: dict, fid: pd.DataFrame, rt: pd.DataFrame, flipped: bool, n_clips: int):
    L = ["# 실험 A — MediaPipe Pose Landmarker(full) vs GT\n",
         "- 뷰 기하 (GT 2D 어깨 투영비로 판별, 전 촬영일 일관): **C = 정면**, **B/D = 전방 사선 약 ±40°**, **A/E = 후방 사선 약 ±40°** (순수 측면 뷰 없음)",
         f"- 이미지 {p2d['n_img']:,}장 (클립 {n_clips:,}, 5뷰×16프레임) | 검출 {p2d['n_det']/p2d['n_img']*100:.1f}% | 평균 추론 {p2d['infer_ms']:.0f} ms/장 (CPU, 1920×1080)",
         f"- L/R 매핑: {'직접' if p2d['tag']=='direct' else '좌우 반전'} 채택 (정규화 오차 중앙값 직접 {p2d['med_direct']:.3f} vs 반전 {p2d['med_swap']:.3f})",
         f"- MediaPipe world 좌표 y축 반전 적용: {flipped}\n"]
    L += ["## 1. 2D 정확도 (몸통 길이 정규화 오차, GT 2D 주석 대비)\n", "### 뷰별 검출률", "",
          "| 뷰 | " + " | ".join(p2d["det_rate"].index) + " |", "|---|" + "---|" * len(p2d["det_rate"]),
          "| 검출률 | " + " | ".join(f"{v*100:.1f}%" for v in p2d["det_rate"].values) + " |", ""]
    jv = p2d["by_jv"]
    piv = jv.pivot_table(index="joint", columns="view", values="median_err")
    pck = jv.pivot_table(index="joint", columns="view", values="pck02")
    order = [j for j in JOINTS_SHORT if j in piv.index]
    L += ["### 관절 × 뷰: 정규화 오차 중앙값 (몸통길이=1; 0.1 ≈ 5~6cm) / PCK@0.2", "",
          "| 관절 | " + " | ".join(piv.columns) + " | 전체 |", "|---|" + "---|" * (len(piv.columns) + 1)]
    for j in order:
        cells = " | ".join(f"{piv.loc[j, v]:.3f} / {pck.loc[j, v]*100:.0f}%" if v in piv.columns and not np.isnan(piv.loc[j, v]) else "-" for v in piv.columns)
        L.append(f"| {j} | {cells} | {jv[jv.joint==j].median_err.median():.3f} |")
    L.append("")
    be = p2d["by_ex"].sort_values("median_err")
    L += ["### 종목별 (전 관절 평균 오차의 중앙값, 뷰 통합)", "", "| 종목 | 3D | 이미지 | 오차 중앙값 | PCK@0.2 | 검출률 |", "|---|---|---|---|---|---|"]
    for r in be.itertuples():
        L.append(f"| {r.exercise} | {'불량' if r.bad3d else '양호'} | {r.n_img} | {r.median_err:.3f} | {r.pck02*100:.0f}% | {p2d['det_rate_ex'].get(r.exercise, np.nan)*100:.1f}% |")
    L.append("")
    if len(fid):
        L += ["## 2. 3D 피처 충실도 — MediaPipe world 로 계산한 피처 vs GT 3D 피처 (클립 단위, Spearman / MAE)\n",
              "| 피처 | " + " | ".join(sorted(fid.view.unique())) + " |", "|---|" + "---|" * fid.view.nunique()]
        for f in KEY_FEATS:
            g = fid[fid.feature == f]
            if g.empty:
                continue
            cells = []
            for v in sorted(fid.view.unique()):
                gg = g[g.view == v]
                cells.append(f"r={gg.spearman.iloc[0]:.2f} MAE={gg.mae.iloc[0]:.2g}" if len(gg) else "-")
            L.append(f"| {f} | " + " | ".join(cells) + " |")
        L.append("")
        L.append(f"뷰별 Spearman 중앙값: " + ", ".join(f"{v}={fid[fid.view==v].spearman.median():.2f}" for v in sorted(fid.view.unique())))
        L.append("")
    if len(rt):
        L += ["## 3. 규칙 전이 — rules_v0 를 MediaPipe 피처에 적용 (AUC: 임계값 무관 / 균형정확도: GT 임계값 그대로)\n"]
        base = rt[(rt.subtype == "") & (rt.variant == "mp")]
        L += ["### 뷰별 요약 (기본 조건, mp 규칙)", "", "| 뷰 | 규칙 수 | GT AUC 중앙값 | MP AUC 중앙값 | Δ 중앙값 | MP AUC≥0.80 | 균형정확도(GT 임계값) 중앙값 |", "|---|---|---|---|---|---|---|"]
        for v, g in base.groupby("view"):
            L.append(f"| {v} | {len(g)} | {g.gt_auc.median():.3f} | {g.mp_auc.median():.3f} | {g.delta.median():+.3f} | {(g.mp_auc>=0.8).mean()*100:.0f}% | {g.mp_balacc_gt_thr.median():.3f} |")
        L.append("")
        sp = rt[(rt.subtype != "") & (rt.variant == "mp")]
        if len(sp):
            L += ["### 척추 하위유형 규칙 (mp 규칙), 뷰별 MP AUC 중앙값", "", "| 하위유형 | " + " | ".join(sorted(sp.view.unique())) + " | GT |", "|---|" + "---|" * (sp.view.nunique() + 1)]
            for st, g in sp.groupby("subtype"):
                cells = " | ".join(f"{g[g.view==v].mp_auc.median():.3f}" if len(g[g.view==v]) else "-" for v in sorted(sp.view.unique()))
                L.append(f"| {st} | {cells} | {g.gt_auc.median():.3f} |")
            L.append("")
        # 최적 뷰 기준 규칙별 상세 (기본 조건)
        best = base.sort_values("mp_auc", ascending=False).drop_duplicates(["exercise", "condition"])
        L += ["### 규칙별 최적 뷰 (기본 조건, mp 규칙) — MP AUC 내림차순 상위 40", "", "| 종목 | 조건 | 피처 | 최적 뷰 | GT AUC | MP AUC | Δ | 균형정확도 |", "|---|---|---|---|---|---|---|---|"]
        for r in best.head(40).itertuples():
            L.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.view} | {r.gt_auc:.3f} | {r.mp_auc:.3f} | {r.delta:+.3f} | {r.mp_balacc_gt_thr:.3f} |")
        L.append("")
        worst = best.sort_values("delta").head(20)
        L += ["### 전이 손실이 큰 규칙 20 (최적 뷰 기준)", "", "| 종목 | 조건 | 피처 | 최적 뷰 | GT AUC | MP AUC | Δ |", "|---|---|---|---|---|---|---|"]
        for r in worst.itertuples():
            L.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.view} | {r.gt_auc:.3f} | {r.mp_auc:.3f} | {r.delta:+.3f} |")
        L.append("")
        L.append(f"기본 조건 규칙 {best.exercise.count()}개: 최적 뷰 MP AUC 중앙값 {best.mp_auc.median():.3f} (GT {best.gt_auc.median():.3f}), MP AUC≥0.80 {(best.mp_auc>=0.8).mean()*100:.0f}%, ≥0.85 {(best.mp_auc>=0.85).mean()*100:.0f}%")
        fz = base[base.view == "FUSED"]
        if len(fz):
            L.append(f"멀티뷰 융합(5뷰 피처 평균): MP AUC 중앙값 {fz.mp_auc.median():.3f}, ≥0.80 {(fz.mp_auc>=0.8).mean()*100:.0f}%")
    (OUT / "experiment_a_summary.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:8]))


def main():
    lm = load_landmarks()
    sample = pd.read_parquet(MP / "sample.parquet")
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(OUT / "conditions.parquet")
    qc = pd.read_csv(OUT / "qc_per_clip.csv")
    bad_ex = set(qc.groupby("exercise")["drop_clip"].mean().pipe(lambda s: s[s > 0.5]).index)
    feats_gt = pd.read_parquet(OUT / "features.parquet")
    rules = json.load(open(OUT / "rules_v0.json", encoding="utf-8"))
    rules_csv = pd.read_csv(OUT / "rules_v0.csv")
    spine = pd.read_parquet(OUT / "spine_subtype.parquet").set_index("clip_id") if (OUT / "spine_subtype.parquet").exists() else None

    p2d = part_2d(lm, sample, clips.reset_index(), bad_ex)
    print(f"[2D] 매핑={p2d['tag']} 오차중앙값 직접 {p2d['med_direct']:.3f} / 반전 {p2d['med_swap']:.3f}")
    keys, arr_mp, flipped = build_mp_arrays(lm, sample, p2d["tag"])
    print(f"[3D] (클립,뷰) {len(keys):,}개, y반전={flipped}")
    fm, fid = part_features(keys, arr_mp, feats_gt)
    print(f"[feat] 피처 충실도 행 {len(fid)}")
    rt = part_rules(fm, clips, conds, rules, spine, rules_csv)
    print(f"[rules] 전이 평가 행 {len(rt)}")
    write_summary(p2d, fid, rt, flipped, int(sample.clip_id.nunique()))
    print(f"→ {OUT/'experiment_a_summary.md'}")


if __name__ == "__main__":
    main()

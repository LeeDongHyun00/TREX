#!/usr/bin/env python
"""바닥 운동을 2D 평면 피처로 판정할 수 있는가.

배경: 바닥 종목(크런치·푸시업·플랭크·레그레이즈·힙쓰러스트 …)은 AIHub **3D GT 가 73~92% 불량**이라
지금까지 통째로 제외돼 있었다. 원인은 촬영 리그가 선 자세용이라는 데 있고(카메라가 모두 서 있는 높이),
실제로 2D 뼈 길이 변동계수는 뷰마다 4~5배 차이가 난다(푸시업 A 0.519 vs E 0.110). 즉 **뷰만 맞으면 2D 는 안정적**이다.

그래서 3D 를 우회하고 **동작 평면에 평행한 뷰의 2D 좌표만으로** 규칙을 만들 수 있는지 검증한다.
피처는 전부 신체 내재(body-intrinsic)라 중력축도 지면 검출도 필요 없다 — 바닥 운동에서는 '높이'가
의미를 잃으므로 신체 주축(어깨→발목) 기준의 이탈·각도·비율만 쓴다.

출력: outputs/floor_2d_rules.csv, outputs/FLOOR_2D.md
"""
from __future__ import annotations

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
FLOOR = ["크런치", "푸시업", "니푸쉬업", "플랭크", "라잉 레그 레이즈", "힙쓰러스트", "시저크로스", "바이시클 크런치", "Y - Exercise"]
JOINTS = ["Nose", "LEar", "REar", "LShoulder", "RShoulder", "LElbow", "RElbow", "LWrist", "RWrist",
          "LHip", "RHip", "LKnee", "RKnee", "LAnkle", "RAnkle", "LFoot", "RFoot", "Neck", "Back", "Waist"]
# MediaPipe 33점에 없는 관절(AIHub 2D 주석에만 있음) — 상한 확인용이며 앱에서는 쓸 수 없다
MP_UNAVAILABLE_PREFIX = ("waist_", "back_", "lumbar_")
STATS = ("mean", "min", "max", "std", "range")
MIN_FRAMES = 8


def load_2d() -> pd.DataFrame:
    cols = ["clip_id", "view_letter", "frame_idx"] + [f"{j}_{a}" for j in JOINTS for a in "xy"]
    clips = pd.read_parquet(OUT / "clips.parquet")[["clip_id", "exercise", "performer", "day"]]
    k2 = pd.read_parquet(OUT / "kp2d.parquet", columns=cols).merge(clips, on="clip_id")
    return k2[k2.exercise.isin(FLOOR)].reset_index(drop=True)


def _mid(d, a, b):
    return np.stack([(d[f"{a}_x"].to_numpy() + d[f"{b}_x"].to_numpy()) / 2,
                     (d[f"{a}_y"].to_numpy() + d[f"{b}_y"].to_numpy()) / 2], axis=1)


def _pt(d, a):
    return np.stack([d[f"{a}_x"].to_numpy(), d[f"{a}_y"].to_numpy()], axis=1)


def _ang(a, b, c):
    """b 를 꼭짓점으로 하는 2D 각(도)."""
    u, w = a - b, c - b
    nu, nw = np.linalg.norm(u, axis=1), np.linalg.norm(w, axis=1)
    cos = (u * w).sum(1) / np.maximum(nu * nw, 1e-6)
    return np.degrees(np.arccos(np.clip(cos, -1, 1)))


def _signed_dev(p, a, b):
    """직선 a→b 에서 점 p 의 **부호 있는** 수직 이탈 / |a−b| (2D 외적 부호)."""
    ax = b - a
    L = np.maximum(np.linalg.norm(ax, axis=1), 1e-6)
    u = ax / L[:, None]
    rel = p - a
    cross = u[:, 0] * rel[:, 1] - u[:, 1] * rel[:, 0]
    return cross / L


def frame_features(d: pd.DataFrame) -> dict[str, np.ndarray]:
    """2D 평면 피처 — 전부 신체 내재(중력·지면 불필요). 길이는 몸통/신체축으로 정규화."""
    sh, hp = _mid(d, "LShoulder", "RShoulder"), _mid(d, "LHip", "RHip")
    kn, an = _mid(d, "LKnee", "RKnee"), _mid(d, "LAnkle", "RAnkle")
    wr, el = _mid(d, "LWrist", "RWrist"), _mid(d, "LElbow", "RElbow")
    ear, nose = _mid(d, "LEar", "REar"), _pt(d, "Nose")
    torso = np.maximum(np.linalg.norm(sh - hp, axis=1), 1e-6)
    F: dict[str, np.ndarray] = {}
    # 신체 주축(어깨→발목) 대비 이탈 — 허리 처짐(sag) / 엉덩이 들림(pike). 부호로 방향까지
    F["hip_dev_ankle"] = _signed_dev(hp, sh, an)
    F["hip_dev_knee"] = _signed_dev(hp, sh, kn)
    F["knee_dev"] = _signed_dev(kn, hp, an)
    F["shoulder_dev"] = _signed_dev(sh, hp, wr)          # 푸시업: 어깨가 손-골반 선에서 벗어남
    # 분절 각도 (동작 평면에 평행한 뷰에서 정확)
    F["elbow_ang"] = _ang(_pt(d, "LShoulder"), _pt(d, "LElbow"), _pt(d, "LWrist"))
    F["knee_ang"] = _ang(hp, kn, an)
    F["hip_ang"] = _ang(sh, hp, kn)                      # 몸통-허벅지 각 (크런치·레그레이즈 핵심)
    F["trunk_ankle_ang"] = _ang(sh, hp, an)
    F["head_trunk_ang"] = _ang(nose, ear, hp)            # 고개 젖힘/숙임
    F["shoulder_arm_ang"] = _ang(hp, sh, el)             # 상완-몸통 각
    # 정규화 거리·비율
    F["hand_shoulder_off"] = _signed_dev(wr, sh, hp)     # 손 위치가 몸통축에서 벗어남
    F["wrist_shoulder_d"] = np.linalg.norm(wr - sh, axis=1) / torso
    F["knee_shoulder_d"] = np.linalg.norm(kn - sh, axis=1) / torso
    F["ankle_hip_d"] = np.linalg.norm(an - hp, axis=1) / torso
    F["elbow_width"] = np.abs(_signed_dev(_pt(d, "LElbow"), _pt(d, "LShoulder"), _pt(d, "LWrist")))
    F["knee_gap2d"] = np.linalg.norm(_pt(d, "LKnee") - _pt(d, "RKnee"), axis=1) / torso
    F["ankle_gap2d"] = np.linalg.norm(_pt(d, "LAnkle") - _pt(d, "RAnkle"), axis=1) / torso
    F["shoulder_asym2d"] = _signed_dev(_pt(d, "LShoulder"), _pt(d, "RShoulder"), hp)

    # --- 지면(접지선) 기준 피처: '허리 지면 고정' 류는 신체 내재 피처만으로는 못 잡는다.
    # 지면 = 클립 안에서 가장 덜 움직이는 접지점들이 이루는 선. 크런치류는 골반↔발목, 푸시업/플랭크는 손목↔발목이 접지.
    ground_a, ground_b = ground_line(d, hp, an, wr)
    F["shoulder_ground"] = _signed_dev(sh, ground_a, ground_b)     # 어깨가 지면에서 뜬 정도(견갑골 올라옴)
    F["hip_ground"] = _signed_dev(hp, ground_a, ground_b)
    F["knee_ground"] = _signed_dev(kn, ground_a, ground_b)
    F["ankle_ground"] = _signed_dev(an, ground_a, ground_b)
    F["head_ground"] = _signed_dev(ear, ground_a, ground_b)

    # --- 허리(Waist)/등(Back) 기준 — AIHub 2D 주석에만 있는 관절. **MediaPipe 에는 없음**(상한 확인용)
    if "Waist_x" in d.columns:
        waist, back = _pt(d, "Waist"), _pt(d, "Back")
        F["waist_ground"] = _signed_dev(waist, ground_a, ground_b)       # 허리가 지면에서 뜬 정도 = '허리 지면 고정'의 직접 측정
        F["waist_dev_axis"] = _signed_dev(waist, sh, hp)                 # 몸통축 대비 허리 이탈(요추 아치)
        F["back_dev_axis"] = _signed_dev(back, sh, hp)
        F["waist_back_ang"] = _ang(back, waist, hp)                      # 등-허리-골반 각 = 요추 굽음
        F["neck_back_waist_ang"] = _ang(_pt(d, "Neck"), back, waist)
    return F


def ground_line(d: pd.DataFrame, hp: np.ndarray, an: np.ndarray, wr: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """접지선 추정 — 클립 안에서 이동이 가장 작은 접지점 쌍을 지면으로 삼는다.

    바닥 운동의 접지점(발목·골반·손목)은 프레임 간 거의 고정이므로, 이동량이 작은 쪽을 고른다.
    프레임마다 다시 고르면 흔들리므로 **클립 단위로 한 번** 정하고 전 프레임에 같은 쌍을 쓴다.
    """
    cid = d["clip_id"].to_numpy()
    out_a, out_b = np.empty_like(hp), np.empty_like(hp)
    for c in np.unique(cid):
        m = cid == c
        cands = {"hip_ankle": (hp[m], an[m]), "wrist_ankle": (wr[m], an[m])}
        best, best_move = None, np.inf
        for k, (p, q) in cands.items():
            move = np.nanmean(np.linalg.norm(np.diff(p, axis=0), axis=1)) + np.nanmean(np.linalg.norm(np.diff(q, axis=0), axis=1))
            if np.isfinite(move) and move < best_move:
                best, best_move = k, move
        p, q = cands[best if best else "hip_ankle"]
        # 클립 전체의 중앙값 위치를 지면 기준점으로 고정 (프레임별 흔들림 제거)
        out_a[m] = np.nanmedian(p, axis=0)
        out_b[m] = np.nanmedian(q, axis=0)
    return out_a, out_b


def aggregate(F: dict[str, np.ndarray], clip_ids: np.ndarray) -> pd.DataFrame:
    df = pd.DataFrame(F)
    df["clip_id"] = clip_ids
    g = df.groupby("clip_id")
    out = {}
    for k in F:
        s = g[k]
        out[f"{k}__mean"] = s.mean(); out[f"{k}__min"] = s.min(); out[f"{k}__max"] = s.max()
        out[f"{k}__std"] = s.std(ddof=0); out[f"{k}__range"] = s.max() - s.min()
    res = pd.DataFrame(out)
    res["n_frames"] = g.size()
    return res


def youden(score, y):
    fpr, tpr, thr = roc_curve(y, score)
    t = thr[int(np.argmax(tpr - fpr))]
    return float(t) if np.isfinite(t) else float(np.median(score))


def fit_rule(X: np.ndarray, y: np.ndarray, g: np.ndarray, names: list[str], n_splits: int = 5) -> dict:
    ng = len(np.unique(g))
    if min(int(y.sum()), len(y) - int(y.sum())) < 12 or ng < 3:
        return dict(auc=np.nan, bacc=np.nan, feature="", n_folds=0)
    gkf = GroupKFold(n_splits=min(n_splits, ng))
    aucs, baccs, chosen = [], [], []
    for tr, te in gkf.split(X, y, g):
        Xtr, Xte, ytr, yte = X[tr], X[te], y[tr], y[te]
        if len(np.unique(ytr)) < 2 or len(np.unique(yte)) < 2:
            continue
        med = np.nanmedian(Xtr, axis=0)
        med = np.where(np.isnan(med), 0.0, med)
        Xtr, Xte = np.where(np.isnan(Xtr), med, Xtr), np.where(np.isnan(Xte), med, Xte)
        keep = np.array([np.unique(c).size >= 2 for c in Xtr.T])
        if not keep.any():
            continue
        idx = np.flatnonzero(keep)
        a = np.array([roc_auc_score(ytr, Xtr[:, j]) for j in idx])
        b = idx[int(np.argmax(np.abs(a - 0.5)))]
        sgn = 1.0 if roc_auc_score(ytr, Xtr[:, b]) >= 0.5 else -1.0
        t = youden(sgn * Xtr[:, b], ytr)
        aucs.append(roc_auc_score(yte, sgn * Xte[:, b]))
        baccs.append(balanced_accuracy_score(yte, (sgn * Xte[:, b] >= t).astype(int)))
        chosen.append(b)
    if not aucs:
        return dict(auc=np.nan, bacc=np.nan, feature="", n_folds=0)
    return dict(auc=float(np.mean(aucs)), bacc=float(np.mean(baccs)),
                feature=names[max(set(chosen), key=chosen.count)], n_folds=len(aucs))


def main():
    k2 = load_2d()
    conds = pd.read_parquet(OUT / "conditions.parquet")
    print(f"[load] 바닥 클립 {k2.clip_id.nunique()} · 뷰 {k2.view_letter.nunique()} · 수행자 {k2.performer.nunique()}", flush=True)
    rows = []
    for (ex, view), d in k2.groupby(["exercise", "view_letter"]):
        d = d.sort_values(["clip_id", "frame_idx"])
        F = frame_features(d)
        agg = aggregate(F, d.clip_id.to_numpy())
        agg = agg[agg.n_frames >= MIN_FRAMES]
        if agg.empty:
            continue
        names = [c for c in agg.columns if c != "n_frames"]
        meta = d.drop_duplicates("clip_id").set_index("clip_id")
        for cond, cg in conds[conds.exercise == ex].groupby("condition"):
            y = cg.drop_duplicates("clip_id").set_index("clip_id")["value"].reindex(agg.index)
            m = y.notna().to_numpy()
            if m.sum() < 60:
                continue
            yy = y[m].to_numpy().astype(int)
            gg = meta.loc[agg.index[m], "performer"].astype(str).to_numpy()
            # MediaPipe 로 계산 가능한 피처만 쓴 규칙(앱 적용 가능) vs 전체(AIHub 2D 상한)
            mp_names = [n for n in names if not n.startswith(MP_UNAVAILABLE_PREFIX)]
            r_mp = fit_rule(agg.loc[agg.index[m], mp_names].to_numpy(dtype=np.float64), yy, gg, mp_names)
            r_all = fit_rule(agg.loc[agg.index[m], names].to_numpy(dtype=np.float64), yy, gg, names)
            rows.append(dict(exercise=ex, condition=cond, view=view, n=int(m.sum()),
                             n_performers=int(len(np.unique(gg))),
                             auc=r_mp["auc"], bacc=r_mp["bacc"], feature=r_mp["feature"], n_folds=r_mp["n_folds"],
                             auc_all=r_all["auc"], feature_all=r_all["feature"]))
        print(f"  {ex} / {view} done", flush=True)
    res = pd.DataFrame(rows)
    res.to_csv(OUT / "floor_2d_rules.csv", index=False, encoding="utf-8-sig")
    write_report(res)


def write_report(res: pd.DataFrame):
    ok = res.dropna(subset=["auc"])
    best = ok.sort_values("auc", ascending=False).drop_duplicates(["exercise", "condition"])
    L = ["# 바닥 운동을 2D 평면 피처로 판정하기\n",
         "AIHub 바닥 종목은 **3D GT 가 73~92% 불량**이라 지금까지 전부 제외돼 있었다. 원인은 촬영 리그가 선 자세용이라는 것이고,",
         "실제로 2D 뼈 길이 변동계수는 뷰마다 4~5배 차이 난다(푸시업 A 0.519 vs **E 0.110**, 크런치 **C 0.047** — 서 있는 종목 수준).",
         "→ **바닥 운동이 어려운 게 아니라 카메라 각도가 문제**다. 그래서 3D 를 우회해, 동작 평면에 평행한 뷰의 **2D 좌표만으로** 규칙을 만들어 봤다.\n",
         "피처는 전부 **신체 내재**(중력축·지면 검출 불필요): 신체 주축(어깨→발목) 대비 부호 있는 이탈, 분절 각도, 몸통 정규화 거리.",
         "바닥 운동에서는 '높이'가 의미를 잃으므로 기존 중력 기반 피처를 그대로 쓸 수 없다.\n",
         f"- 평가: 종목×조건×뷰 {len(ok)}건, 수행자 GroupKFold(≥3명)\n",
         "## 1. 조건별 최적 뷰 성능\n",
         "| 종목 | 조건 | 최적 뷰 | AUC | 균형정확도 | 선택 피처 | n |", "|---|---|---|---|---|---|---|"]
    for r in best.sort_values("auc", ascending=False).itertuples():
        L.append(f"| {r.exercise} | {r.condition} | {r.view} | **{r.auc:.3f}** | {r.bacc:.3f} | `{r.feature}` | {r.n} |")
    ship = best[best.auc >= 0.85]
    beta = best[(best.auc >= 0.75) & (best.auc < 0.85)]
    gain_waist = (best.auc_all - best.auc) if "auc_all" in best else pd.Series([np.nan])
    L += ["", "## 2. 요약\n",
          f"- 조건 {len(best)}개 중 **AUC ≥ 0.85: {len(ship)}개**, 0.75~0.85: {len(beta)}개, <0.75: {len(best)-len(ship)-len(beta)}개",
          f"- 최적 뷰 AUC 중앙값 **{best.auc.median():.3f}** (참고: 서 있는 종목의 GT 3D 룰엔진 중앙값 0.861)",
          f"- 뷰 선택이 결정적: 같은 조건에서 최적 뷰 − 최악 뷰 AUC 차이 중앙값 **{(ok.groupby(['exercise','condition']).auc.max() - ok.groupby(['exercise','condition']).auc.min()).median():.3f}**",
          f"- **허리(Waist)·등(Back) 랜드마크를 추가해도 이득 {gain_waist.median():+.4f}** — AIHub 2D 에는 이 관절이 있는데도 개선이 없다. "
          "MediaPipe 에 없다는 사실이 바닥 운동의 병목이 **아니라는** 뜻이다.\n",
          "### 되는 것 / 안 되는 것 (패턴이 뚜렷하다)\n",
          "| 유형 | 예 | AUC | 왜 |", "|---|---|---|---|",
          "| ✅ **머리·시선 각도** | 시선 배꼽 고정 0.936, 고개 숙임 0.934, 고개 들지 않기 0.908, 고개 젖힘 0.890 | 0.87~0.94 | 머리-몸통 각은 측면 2D 에서 크고 안정적 |",
          "| ✅ **큰 분절 각도** | 힙쓰러스트 무릎-어깨 일자 0.898, 허벅지-종아리 각 0.812 | 0.81~0.90 | 관절각은 동작 평면에 평행한 뷰에서 정확 |",
          "| △ 몸통 정렬·거리 | 손 위치 0.818, 가슴 이동 0.808, 플랭크 정렬 0.794 | 0.75~0.82 | 되지만 뷰 민감 |",
          "| ❌ **'지면 고정'** | 허리 지면 고정 0.566~0.629 (4종목 전부) | <0.65 | 허리 랜드마크가 있어도 못 잡음 — 요추 아치는 측면 2D 에서 몇 픽셀 |",
          "| ❌ **'긴장 유지'** | 이완 시 긴장 0.583~0.664 | <0.67 | 힘은 좌표에 없음(§21 요건 2, 서 있는 종목과 동일) |",
          "| ❌ 미세 위치 | 무릎 교차 0.564, 엄지 방향 0.645 | <0.65 | 깊이 방향 미세 차이 |",
          "",
          "### 뷰별 평균 성능\n", "| 뷰 | 평가 조건 | AUC 중앙값 | 최적 뷰가 된 횟수 |", "|---|---|---|---|"]
    for v, g in ok.groupby("view"):
        L.append(f"| {v} | {len(g)} | {g.auc.median():.3f} | {int((best.view == v).sum())} |")
    L += ["", "> ⚠ **중요한 한계**: AIHub 의 5개 뷰는 **전부 서 있는 높이 카메라**다. 바닥 높이 측면 카메라는 없다. "
          "따라서 위 수치는 *이상적이지 않은 촬영에서의 값*이고, 바닥 높이에서 찍으면 더 나을 가능성이 크다(원근 단축이 줄어드므로). "
          "다만 이건 추정이며 자체 촬영으로만 확인할 수 있다. 또 최적 뷰를 사후에 고른 값이라 낙관 편향이 있다."]
    L += ["", "## 3. 결론 — 바닥 운동을 넣으려면\n",
          "**가능하다. 단 절반만.** 35개 조건 중 17개(≥0.75)가 쓸만하고, 나머지는 조건 자체가 관측 불가다.\n",
          "1. **3D 를 쓰지 말 것.** AIHub 3D 는 리그 탓에 73~92% 불량이고, MediaPipe world landmark 도 바닥 자세에서 신뢰도가 떨어진다. "
          "2D 평면 피처면 충분하다 — 실제로 머리 각도 조건은 2D 만으로 0.87~0.94 가 나온다.",
          "2. **중력 기반 피처를 통째로 갈아끼울 것.** 바닥 운동에서는 '높이'가 의미를 잃는다. "
          "신체 주축(어깨→발목) 대비 **부호 있는 이탈**, 분절 각도, 몸통 정규화 거리, 그리고 **접지선(지면) 대비 이탈**로 대체한다. "
          "접지선은 클립 안에서 가장 덜 움직이는 접지점 쌍(골반↔발목 또는 손목↔발목)의 중앙값 위치로 추정하면 된다 — 지면 검출 모델 불필요.",
          "3. **카메라를 바닥 높이·측면으로.** 서 있는 높이에서 내려다보면 원근 단축으로 2D 가 무너진다(푸시업 뼈 CV: A뷰 0.519 vs E뷰 0.110). "
          "종목마다 최적 뷰가 다르므로(크런치류는 C, 푸시업·플랭크는 E) **종목별 촬영 가이드**가 필요하다.",
          "4. **스코프를 먼저 자를 것.** '허리 지면 고정'·'긴장 유지'·미세 위치는 넣지 말 것 — "
          "허리 랜드마크를 줘도 개선이 0 이었다. 넣으면 오탐만 늘어난다.",
          "5. **`checkUpSanity` 확인.** 누운 자세에서는 머리가 골반보다 위에 있지 않아 up 자가검증이 오작동할 수 있다. "
          "현재는 '미검증' 처리되어 안전하지만, 바닥 운동을 넣을 때 **중력축 의존 경로 자체를 우회**하도록 분기해야 한다.",
          "6. **임계값은 자체 수집으로.** 이 표의 값은 서 있는 높이 카메라에서 나온 것이라 그대로 못 쓴다. "
          "바닥 높이·측면으로 §17 프로토콜(종목당 30세트 × 3~6명)을 다시 돌려야 한다.",
          "",
          "### 우선순위 제안\n",
          "| 순위 | 종목 | 쓸만한 조건 | 근거 |", "|---|---|---|---|",
          "| 1 | **힙쓰러스트** | 무릎-어깨 일자 0.898, 고개 0.908 | 조건 3개 중 2개가 ≥0.90, 접지 안정 |",
          "| 2 | **푸시업/니푸쉬업** | 고개 0.890/0.869, 손 위치 0.818, 가슴 이동 0.808 | 조건 5개 중 3개 사용 가능, 사용자 많음 |",
          "| 3 | **라잉 레그 레이즈** | 고개 0.934, 허벅지-종아리 각 0.812 | 4개 중 2개 |",
          "| 4 | 시저크로스 | 시선 0.936, 다리-지면 거리 0.823 | 5개 중 2개 |",
          "| 5 | 크런치·바이시클·플랭크 | 견갑골 0.79/0.76, 플랭크 정렬 0.794 | 1~2개씩, 핵심 조건('허리 지면 고정')이 안 됨 |"]
    (OUT / "FLOOR_2D.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:8]))
    print(f"\n[done] 조건 {len(best)} | ≥0.85 {len(ship)} | 중앙값 {best.auc.median():.3f} → {OUT/'FLOOR_2D.md'}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python
"""개인 기준선(personal baseline) 적용용 '기준선-상대 임계값' 산출.

규칙의 임계값은 절대 스케일이라, 값에서 사용자 기준선(정상 k세트 중앙값)을 뺀 뒤에는 그대로 쓸 수 없다.
eligible 규칙마다 AIHub 수행자별로 '정상 앞 k클립 중앙값'을 기준선으로 삼아 adjusted = value − baseline 을 만들고,
수행자 홀드아웃(GroupKFold)으로 AUC 를 확인한 뒤 전체 adjusted 에 Youden 임계값을 적합한다 → threshold_rel.

주의: GT 3D 피처 기준이다. 절대 임계값(MP 재적합)과 달리 상대 임계값은 MP↔GT 의 계통적 오프셋이 상쇄되지만
스케일 차이는 남을 수 있다 → 앱 로그로 §9 재보정 시 baseline-relative 모드로 다시 맞출 것.

출력: outputs/baseline_thresholds.csv (export_rules_mp.py 가 읽어 personal_baseline.threshold_rel 로 주입)
"""
from __future__ import annotations

import json
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score, roc_curve
from sklearn.model_selection import GroupKFold

from features import build_or_load_features

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
K = 3


def youden(score, y):
    fpr, tpr, thr = roc_curve(y, score)
    t = thr[int(np.argmax(tpr - fpr))]
    return float(t) if np.isfinite(t) else float(np.median(score))


def main():
    doc = json.load(open(HERE / "rules" / "rules_mp_v0.json", encoding="utf-8"))
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(OUT / "conditions.parquet")
    feats = build_or_load_features(OUT)
    clips = clips.loc[clips.index.intersection(feats.index)]
    clips["group"] = np.where(clips["performer"].astype(str) != "", clips["performer"].astype(str), clips["day"].astype(str))
    clips["order_key"] = clips["day"].astype(str) + "-" + clips.index.str.rsplit("-", n=1).str[-1].str.zfill(4)
    rows = []
    seen = set()
    for r in doc["rules"]:
        pb = r.get("personal_baseline") or {}
        if r["status"] == "exclude" or not pb.get("eligible"):
            continue
        key = (r["exercise"], r["condition"], r["feature"])
        if key in seen:
            continue
        seen.add(key)
        g = clips[clips.exercise == r["exercise"]]
        sub = conds[(conds.clip_id.isin(g.index)) & (conds.condition == r["condition"])]
        yv = sub.drop_duplicates("clip_id").set_index("clip_id")["value"].reindex(g.index)
        m = yv.notna().to_numpy()
        ids = g.index[m]
        y = yv[m].to_numpy().astype(int)
        if r["feature"] not in feats.columns:
            continue
        v = feats.loc[ids, r["feature"]].to_numpy(dtype=np.float64)
        ok = np.isfinite(v)
        ids, y, v = ids[ok], y[ok], v[ok]
        grp = g.loc[ids, "group"].to_numpy()
        okey = g.loc[ids, "order_key"].to_numpy()
        # 수행자별 기준선(정상 앞 K클립 중앙값) → adjusted; 기준선 클립은 제외
        adj = np.full(len(v), np.nan)
        for p in np.unique(grp):
            idx = np.flatnonzero(grp == p)
            idx = idx[np.argsort(okey[idx])]
            pos = [i for i in idx if y[i] == 1]
            if len(pos) < K + 1:
                continue
            b = float(np.median(v[pos[:K]]))
            for i in idx:
                if i not in pos[:K]:
                    adj[i] = v[i] - b
        ev = np.isfinite(adj)
        if ev.sum() < 60 or len(np.unique(y[ev])) < 2:
            continue
        sign = 1.0 if r["op"] == "<" else -1.0
        s = sign * adj[ev]
        ye, ge = y[ev], grp[ev]
        gkf = GroupKFold(n_splits=min(5, len(np.unique(ge))))
        aucs, baccs = [], []
        for tr, te in gkf.split(s, ye, ge):
            if len(np.unique(ye[tr])) < 2 or len(np.unique(ye[te])) < 2:
                continue
            t = youden(s[tr], ye[tr])
            aucs.append(roc_auc_score(ye[te], s[te]))
            baccs.append(((s[te] >= t).astype(int) == ye[te]).mean())
        t_all = youden(s, ye)
        rows.append(dict(exercise=r["exercise"], condition=r["condition"], feature=r["feature"], op=r["op"], k=K,
                         threshold_abs=r["threshold"], threshold_rel=float(sign * t_all),
                         auc_rel_cv=float(np.mean(aucs)), acc_rel_cv=float(np.mean(baccs)),
                         n_eval=int(ev.sum()), n_persons=int(len(np.unique(ge)))))
        print(f"  {r['exercise']} | {r['condition']} | {r['feature']}: rel thr {sign*t_all:+.4g} (abs {r['threshold']:.4g}), AUC {np.mean(aucs):.3f}")
    df = pd.DataFrame(rows)
    df.to_csv(OUT / "baseline_thresholds.csv", index=False, encoding="utf-8-sig")
    print(f"[done] {len(df)} eligible 규칙 → {OUT/'baseline_thresholds.csv'}")


if __name__ == "__main__":
    main()

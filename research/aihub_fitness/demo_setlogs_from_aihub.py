#!/usr/bin/env python
"""재보정 툴체인 데모/검증용: AIHub MediaPipe 추론 결과를 앱 세트 로그 형식(trex.posture.setlog/1)으로 변환.

- 실험 A 의 MediaPipe world landmark(정면 뷰 C) → 클립 1개 = 세트 1개 (16프레임), subject_id = 수행자(Z코드)
- 프레임 피처는 연구 features.py 로 계산 (앱 PostureCore 와 파리티 검증됨), MediaPipe 계산 불가 피처 제외
- 라벨 CSV 는 AIHub 조건 라벨(+척추 하위유형)에서 생성
→ calibrate_from_logs.py 에 넣으면 rules_mp_v0 의 임계값과 같은 분포에서 재적합되므로, 새 임계값이 기존과 근접해야 정상.

사용: python demo_setlogs_from_aihub.py [--exercises "바벨 스쿼트,오버 헤드 프레스,바벨 데드리프트"] [--n 60] [--view C]
출력: outputs/calib_demo/sets-demo.jsonl, labels.csv
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from experiment_a import build_mp_arrays, load_landmarks  # noqa: E402
from features import compute_frame_features, mediapipe_computable  # noqa: E402

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
OUT = HERE / "outputs"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--exercises", default="바벨 스쿼트,오버 헤드 프레스,바벨 데드리프트,스텝 포워드 다이나믹 런지")
    ap.add_argument("--n", type=int, default=60)
    ap.add_argument("--view", default="C")
    ap.add_argument("--out", default=str(OUT / "calib_demo"))
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    exercises = [e.strip() for e in args.exercises.split(",") if e.strip()]

    lm = load_landmarks()
    sample = pd.read_parquet(OUT / "mp" / "sample.parquet")
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(OUT / "conditions.parquet")
    spine = pd.read_parquet(OUT / "spine_subtype.parquet").set_index("clip_id") if (OUT / "spine_subtype.parquet").exists() else None
    keys, arr, _ = build_mp_arrays(lm, sample, "direct")
    keys = keys.reset_index(drop=True)
    sel = keys[(keys.view_letter == args.view) & keys.clip_id.map(lambda c: clips.loc[c, "exercise"] in exercises)]
    sel = sel.groupby(sel.clip_id.map(lambda c: clips.loc[c, "exercise"])).head(args.n)
    idx = sel.index.to_numpy()
    F = compute_frame_features(arr[idx])          # dict name -> (n, T)
    names = [k for k in F if mediapipe_computable(k)]

    lines, label_rows = [], []
    for j, i in enumerate(idx):
        cid = keys.clip_id.iloc[i]
        ex = clips.loc[cid, "exercise"]
        perf = str(clips.loc[cid, "performer"])
        T = arr.shape[1]
        frames = []
        for t in range(T):
            feats = {}
            for k in names:
                v = float(F[k][j, t])
                if np.isfinite(v):
                    feats[k] = round(v, 5)
            if not feats:
                continue
            frames.append(dict(t_ms=t * 300, infer_ms=30, visible=33, vis=None, features=feats))
        if len(frames) < 8:
            continue
        lines.append(json.dumps(dict(schema="trex.posture.setlog/1", set_id=f"demo-{cid}-{args.view}", created_at="2026-08-22T00:00:00Z",
                                     subject_id=perf, exercise=ex, rules_version="mp_v0.1", model="full", delegate="CPU",
                                     front_camera=False, up_from_gravity=False, tilt_deg=None, sample_interval_ms=300,
                                     note="demo from AIHub view " + args.view, frames=frames, results=[]), ensure_ascii=False))
        for row in conds[conds.clip_id == cid].itertuples():
            st = ""
            if "척추" in row.condition and spine is not None and cid in spine.index:
                s = spine.loc[cid, "subtype"]
                st = "" if s in ("neutral",) else str(s)
            label_rows.append(dict(set_id=f"demo-{cid}-{args.view}", condition=row.condition, value=int(bool(row.value)), subtype=st, subject_id=perf))
    (out / "sets-demo.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    pd.DataFrame(label_rows).to_csv(out / "labels.csv", index=False, encoding="utf-8-sig")
    print(f"[done] 세트 {len(lines)}개 → {out/'sets-demo.jsonl'} | 라벨 {len(label_rows)}행 → {out/'labels.csv'}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python
"""실험 A 샘플링: MediaPipe 를 돌릴 이미지 목록을 만든다 (tar 전체가 아니라 필요한 파일만 읽기 위함).

- 3D 양호 종목: QC 통과 클립 중 종목당 N_GOOD 클립 (수행자/타입 분산되도록 셔플)
- 3D 불량 종목(바닥/누운 종목): 종목당 N_BAD 클립 — 2D 정확도 평가용 (2D GT 는 프레임별 주석이라 유효)
- 클립당 16프레임 × 5뷰 전부
출력: outputs/mp/sample.parquet (img_key, clip_id, frame_idx, view, view_letter, day, exercise, group)
      outputs/mp/tar_days.json   (tar 파일 → Day 폴더 매핑; tar 첫 엔트리로 판별)
"""
from __future__ import annotations

import glob
import json
import os
import sys
import tarfile
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

TAR_ROOTS = [
    r"C:\Users\hp276\Desktop\trex\data\013.피트니스자세\1.Training\원시데이터",
    r"T:\trex_allData\013.피트니스자세\1.Training\원시데이터",
]
N_GOOD, N_BAD, SEED = 60, 20, 0


def tar_day_map() -> dict[str, str]:
    out = {}
    for r in TAR_ROOTS:
        for t in sorted(glob.glob(os.path.join(r, "*.tar"))):
            try:
                with tarfile.open(t, "r:") as tf:
                    for m in tf:
                        if m.isfile():
                            seg = m.name.lstrip("./").split("/")[0]
                            out[t] = seg
                            break
            except Exception as e:  # noqa
                out[t] = f"ERROR:{e}"
    return out


def main():
    out = Path(__file__).resolve().parent / "outputs"
    mp_dir = out / "mp"
    mp_dir.mkdir(parents=True, exist_ok=True)
    clips = pd.read_parquet(out / "clips.parquet")
    qc = pd.read_csv(out / "qc_per_clip.csv")
    bad_ex = set(qc.groupby("exercise")["drop_clip"].mean().pipe(lambda s: s[s > 0.5]).index)
    ok_clips = set(qc[~qc["drop_clip"]]["clip_id"])
    rng = np.random.default_rng(SEED)
    chosen = []
    for ex, g in clips.groupby("exercise"):
        g = g[(g["n_views"] == 5) & (g["n_frames"] >= 8)]
        if ex in bad_ex:
            pool, n = g, N_BAD
        else:
            pool, n = g[g["clip_id"].isin(ok_clips)], N_GOOD
        idx = rng.permutation(len(pool))[:n]
        chosen.append(pool.iloc[idx])
    sel = pd.concat(chosen)
    print(f"선택 클립 {len(sel)} (종목 {sel.exercise.nunique()}, 수행자 {sel.performer.nunique()})")
    k2 = pd.read_parquet(out / "kp2d.parquet", columns=["clip_id", "frame_idx", "view", "view_letter", "img_key"])
    s = k2[k2["clip_id"].isin(set(sel["clip_id"]))].merge(sel[["clip_id", "day", "exercise", "performer"]], on="clip_id", how="left")
    s = s[s["img_key"] != ""]
    s["is_bad3d"] = s["exercise"].isin(bad_ex)
    s.to_parquet(mp_dir / "sample.parquet", index=False)
    print(f"이미지 {len(s):,}장 | 일자 {s.day.nunique()}개 | 3D불량 종목 이미지 {int(s.is_bad3d.sum()):,}")
    tm = tar_day_map()
    json.dump(tm, open(mp_dir / "tar_days.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    days_needed = set(s["day"])
    covered = {d for d in tm.values()}
    print(f"tar {len(tm)}개 | 필요한 일자 {len(days_needed)} 중 tar 보유 {len(days_needed & covered)} | 미보유: {sorted(days_needed - covered)}")
    per_tar = {os.path.basename(t): int((s["day"] == d).sum()) for t, d in tm.items()}
    print("tar별 이미지 수:", {k: v for k, v in per_tar.items() if v})


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""바닥 3종목 전 클립 C·E 뷰에 MediaPipe full(앱과 같은 모델) — 저장소 mp_infer 함수를 읽기 전용 재사용, 출력은 이 폴더."""
import sys, json, time
from pathlib import Path
from multiprocessing import Pool
import numpy as np, pandas as pd
import pyarrow.dataset as ds

REPO = Path(r"C:/Users/hp276/Desktop/trex/.claude/worktrees/exercise-form-correction-5fe80e/research/aihub_fitness")
sys.path.insert(0, str(REPO))
import mp_infer as M  # noqa: E402

OUT = Path(r"C:/Users/hp276/Desktop/trex/research/aihub_fitness/outputs")
MODEL = Path(r"C:/Users/hp276/Desktop/trex/research/aihub_fitness/models/pose_landmarker_full.task")
S = Path(__file__).resolve().parent / "mp_full"
EX = ["플랭크", "크런치", "라잉 레그 레이즈"]
VIEWS = "CE"


def main():
    S.mkdir(parents=True, exist_ok=True)
    clips = pd.read_parquet(OUT / "clips.parquet", columns=["clip_id", "exercise", "day"])
    clips = clips[clips.exercise.isin(EX)]
    k = ds.dataset(OUT / "kp2d.parquet").to_table(columns=["clip_id", "view_letter", "img_key"],
                                                  filter=ds.field("clip_id").isin(list(clips.clip_id))).to_pandas()
    k = k[k.view_letter.isin(list(VIEWS))].merge(clips, on="clip_id")
    tar_days = json.load(open(OUT / "mp" / "tar_days.json", encoding="utf-8"))
    pool = Pool(processes=10, initializer=M._init_worker, initargs=(str(MODEL),))
    t_all = time.time()
    for tar, day in tar_days.items():
        wanted = k[k.day == day]
        if wanted.empty:
            continue
        name = Path(tar).stem
        dst = S / f"landmarks_{name}_{VIEWS}.parquet"
        if dst.exists():
            continue
        idx = pd.read_parquet(OUT / "mp" / f"index_{name}.parquet")
        t0 = time.time()
        rows = list(pool.imap_unordered(M._infer, M.read_items(Path(tar), idx, wanted), chunksize=4))
        df = pd.DataFrame(rows)
        for c in M.LM_COLS:
            if c not in df.columns:
                df[c] = np.nan
        S.mkdir(parents=True, exist_ok=True)
        df[["img_key", "detected", "w", "h"] + M.LM_COLS].to_parquet(dst, index=False)
        print(f"[done] {name} {day} {len(df)}/{len(wanted)} det {df.detected.mean():.3f} {time.time()-t0:.0f}s 누적 {(time.time()-t_all)/60:.1f}분", flush=True)
    pool.close(); pool.join()


if __name__ == "__main__":
    main()

#!/usr/bin/env python
"""B1 추론 — AIHub 덤벨 컬 클립 × 뷰(B·C·D) × 16프레임에 MediaPipe Pose Landmarker(full, IMAGE 모드)를 돌린다.

왜 새로 돌리나: 기존 실험 A 표본(`aihub_fitness/outputs/mp/landmarks_*.parquet`)에는 덤벨 컬이 60클립뿐이다.
'팔꿈치 위치 고정' 판별 AUC·뷰별 잡음·정상 분포를 재려면 44명 × 32조건 조합의 균형 설계(1,439클립) 전부가 필요하다.
A2_mp_infer.py 와 같은 방식(tar 를 풀지 않고 offset seek-read, IMAGE 모드, num_poses=1)이고 종목·뷰·변형만 다르다.
B1_camera.py 결과 코드 B·C·D 가 실제 방향과 일치(코드 C 가 뒤를 보는 53클립은 분석에서 제외)하므로 뷰는 코드로 고른다.

입력(읽기 전용): aihub_fitness/outputs/{clips,kp2d}.parquet, outputs/mp/tar_days.json, outputs/mp/index_<tar>.parquet
출력: camera_observability/outputs/B1/mp/lm_<tar>_full.parquet (A2 와 같은 컬럼)
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
AIHUB = HERE.parent / "aihub_fitness"
SRC = AIHUB / "outputs"
MODEL = AIHUB / "models" / "pose_landmarker_full.task"
OUT = HERE / "outputs" / "B1" / "mp"
EXERCISE = "덤벨 컬"
N_LM = 33
LM_COLS = [f"l{i}_{a}" for i in range(N_LM) for a in ("x", "y", "z", "v", "p")]
W_COLS = [f"w{i}_{a}" for i in range(N_LM) for a in ("x", "y", "z")]

_lmk = None


def _init(model_path: str):
    global _lmk
    from mediapipe.tasks.python import BaseOptions, vision
    opts = vision.PoseLandmarkerOptions(base_options=BaseOptions(model_asset_path=model_path),
                                        running_mode=vision.RunningMode.IMAGE, num_poses=1,
                                        min_pose_detection_confidence=0.5, min_pose_presence_confidence=0.5,
                                        min_tracking_confidence=0.5, output_segmentation_masks=False)
    _lmk = vision.PoseLandmarker.create_from_options(opts)


def _work(item):
    import cv2
    import mediapipe as mp
    img_key, buf = item
    row = {"img_key": img_key, "variant": "full", "detected": False, "w": 0, "h": 0,
           "crop_x0": 0.0, "crop_y0": 0.0, "crop_s": 1.0}
    arr = cv2.imdecode(np.frombuffer(buf, np.uint8), cv2.IMREAD_COLOR)
    if arr is None:
        return row
    rgb = cv2.cvtColor(arr, cv2.COLOR_BGR2RGB)
    h, w = rgb.shape[:2]
    row["w"], row["h"] = int(w), int(h)
    res = _lmk.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb)))
    if not res.pose_landmarks:
        return row
    row["detected"] = True
    lm, wl = res.pose_landmarks[0], res.pose_world_landmarks[0]
    vals = np.empty(N_LM * 5, np.float32)
    for i, p in enumerate(lm):
        vals[i * 5:(i + 1) * 5] = (p.x * w, p.y * h, p.z, p.visibility, p.presence)
    wv = np.empty(N_LM * 3, np.float32)
    for i, p in enumerate(wl):
        wv[i * 3:(i + 1) * 3] = (p.x, p.y, p.z)
    row.update(dict(zip(LM_COLS, vals.tolist())))
    row.update(dict(zip(W_COLS, wv.tolist())))
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--views", default="B,C,D")
    ap.add_argument("--limit", type=int, default=0, help="tar 당 이미지 수 제한(디버그)")
    ap.add_argument("--tars", default="")
    args = ap.parse_args()
    views = [v.strip() for v in args.views.split(",") if v.strip()]
    OUT.mkdir(parents=True, exist_ok=True)

    clips = pd.read_parquet(SRC / "clips.parquet", columns=["clip_id", "exercise", "day"])
    sel = clips[clips.exercise == EXERCISE]
    k2 = pd.read_parquet(SRC / "kp2d.parquet", filters=[("clip_id", "in", sel.clip_id.tolist())],
                         columns=["clip_id", "frame_idx", "view_letter", "img_key"])
    k2 = k2[(k2.img_key != "") & k2.view_letter.isin(views)].merge(sel[["clip_id", "day"]], on="clip_id")
    tar_days = json.load(open(SRC / "mp" / "tar_days.json", encoding="utf-8"))
    days = set(k2.day)
    tars = sorted((Path(t), d) for t, d in tar_days.items() if d in days)
    if args.tars:
        keys = [k.strip() for k in args.tars.split(",")]
        tars = [(t, d) for t, d in tars if any(k in t.name for k in keys)]
    man_p = OUT / "manifest.json"
    man = json.load(open(man_p, encoding="utf-8")) if man_p.exists() else {}
    print(f"[plan] {EXERCISE} 이미지 {len(k2):,}장 (뷰 {views}), tar {len(tars)}개, workers={args.workers}", flush=True)

    pool = Pool(processes=args.workers, initializer=_init, initargs=(str(MODEL),))
    t_all = time.time()
    for tar_path, day in tars:
        name = tar_path.stem
        if man.get(name, {}).get("status") == "done" and not args.limit:
            continue
        want = k2[k2.day == day][["img_key"]]
        if args.limit:
            want = want.head(args.limit)
        idx = pd.read_parquet(SRC / "mp" / f"index_{name}.parquet")
        m = idx.merge(want, left_on="name", right_on="img_key", how="inner").sort_values("offset")
        t0 = time.time()
        if m.empty:
            man[name] = {"status": "done", "day": day, "n_img": 0, "n_want": int(len(want)), "note": "tar 에 해당 이미지 없음"}
            json.dump(man, open(man_p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
            print(f"[skip] {name} ({day}) 원하는 {len(want):,}장 중 tar 에 있는 것 0장", flush=True)
            continue

        def items():
            with open(tar_path, "rb") as f:
                for r in m.itertuples():
                    f.seek(r.offset)
                    yield (r.img_key, f.read(int(r.size)))

        rows = []
        for i, rr in enumerate(pool.imap_unordered(_work, items(), chunksize=8)):
            rows.append(rr)
            if (i + 1) % 2000 == 0:
                el = time.time() - t0
                print(f"   [{name}] {i+1}/{len(m)}  {el:.0f}s ({(i+1)/el:.1f} img/s)", flush=True)
        cols = ["img_key", "variant", "detected", "w", "h", "crop_x0", "crop_y0", "crop_s"] + LM_COLS + W_COLS
        df = pd.DataFrame(rows).reindex(columns=cols)
        suffix = "_test" if args.limit else ""
        df.to_parquet(OUT / f"lm_{name}_full{suffix}.parquet", index=False)
        el = time.time() - t0
        if not args.limit:
            man[name] = {"status": "done", "day": day, "n_img": int(len(m)), "n_want": int(len(want)), "sec": round(el, 1),
                         "views": views, "detect": float(df.detected.mean())}
            json.dump(man, open(man_p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f"[done] {name} ({day}) {len(m):,}장 {el:.0f}s | 검출 {df.detected.mean()*100:.1f}% | 누적 {(time.time()-t_all)/60:.1f}분", flush=True)
    pool.close()
    pool.join()


if __name__ == "__main__":
    main()

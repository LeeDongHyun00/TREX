#!/usr/bin/env python
"""실험 A 추론: tar 를 해제하지 않고 필요한 이미지 바이트만 읽어 MediaPipe Pose Landmarker 를 돌리고 결과를 기록.

디스크 전략 (용량 초과 방지):
  - 비압축 tar 의 헤더만 순차 스캔해 (name → data offset, size) 인덱스를 만들고 (outputs/mp/index_<tar>.parquet 에 캐시),
  - 샘플 목록(sample.parquet)에 있는 파일만 offset 으로 seek-read 해 메모리에서 디코드 → 추론.
  - 원시 이미지는 디스크에 쓰지 않는다. 출력은 outputs/mp/landmarks_<tar>.parquet (tar 당 수십 MB).
  - tar 단위로 완료 기록(manifest) → 중단 후 재실행하면 끝난 tar 는 건너뜀.

출력 컬럼: img_key, detected, w, h, infer_ms, l{i}_{x,y,z,v,p} (i=0..32, x/y 는 픽셀), w{i}_{x,y,z} (월드, 미터, 골반 원점)
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import tarfile
import time
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
MODEL = HERE / "models" / "pose_landmarker_full.task"
N_LM = 33
LM_COLS = [f"l{i}_{a}" for i in range(N_LM) for a in ("x", "y", "z", "v", "p")]
W_COLS = [f"w{i}_{a}" for i in range(N_LM) for a in ("x", "y", "z")]

_landmarker = None


def _init_worker(model_path: str):
    global _landmarker
    import mediapipe as mp  # noqa
    from mediapipe.tasks.python import BaseOptions, vision
    opts = vision.PoseLandmarkerOptions(base_options=BaseOptions(model_asset_path=model_path),
                                        running_mode=vision.RunningMode.IMAGE, num_poses=1,
                                        min_pose_detection_confidence=0.5, min_pose_presence_confidence=0.5,
                                        min_tracking_confidence=0.5, output_segmentation_masks=False)
    _landmarker = vision.PoseLandmarker.create_from_options(opts)


def _infer(item):
    """item = (img_key, jpeg_bytes) → dict row"""
    import cv2
    import mediapipe as mp
    img_key, buf = item
    row = {"img_key": img_key, "detected": False, "w": 0, "h": 0, "infer_ms": np.nan}
    arr = cv2.imdecode(np.frombuffer(buf, np.uint8), cv2.IMREAD_COLOR)
    if arr is None:
        return row
    h, w = arr.shape[:2]
    row["w"], row["h"] = int(w), int(h)
    rgb = cv2.cvtColor(arr, cv2.COLOR_BGR2RGB)
    t0 = time.perf_counter()
    res = _landmarker.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
    row["infer_ms"] = (time.perf_counter() - t0) * 1000.0
    if not res.pose_landmarks:
        return row
    row["detected"] = True
    lm = res.pose_landmarks[0]
    wl = res.pose_world_landmarks[0]
    vals = np.empty(N_LM * 5, np.float32)
    for i, p in enumerate(lm):
        vals[i * 5:(i + 1) * 5] = (p.x * w, p.y * h, p.z, p.visibility, p.presence)
    wv = np.empty(N_LM * 3, np.float32)
    for i, p in enumerate(wl):
        wv[i * 3:(i + 1) * 3] = (p.x, p.y, p.z)
    row.update(dict(zip(LM_COLS, vals.tolist())))
    row.update(dict(zip(W_COLS, wv.tolist())))
    return row


def build_index(tar_path: Path, cache: Path) -> pd.DataFrame:
    """tar 헤더 스캔 → (name, offset_data, size). 캐시가 있으면 재사용."""
    if cache.exists():
        return pd.read_parquet(cache)
    t0 = time.time()
    names, offs, sizes = [], [], []
    with tarfile.open(tar_path, "r:") as tf:
        for m in tf:
            if m.isfile():
                names.append(m.name.lstrip("./"))
                offs.append(m.offset_data)
                sizes.append(m.size)
    df = pd.DataFrame({"name": names, "offset": np.array(offs, dtype=np.int64), "size": np.array(sizes, dtype=np.int64)})
    df.to_parquet(cache, index=False)
    print(f"   [index] {tar_path.name}: {len(df):,} files, {time.time()-t0:.0f}s", flush=True)
    return df


def read_items(tar_path: Path, idx: pd.DataFrame, wanted: pd.DataFrame):
    """wanted(img_key) 에 해당하는 파일만 offset 순으로 seek-read 하는 제너레이터."""
    m = idx.merge(wanted[["img_key"]], left_on="name", right_on="img_key", how="inner").sort_values("offset")
    with open(tar_path, "rb") as f:
        for r in m.itertuples():
            f.seek(r.offset)
            yield (r.img_key, f.read(int(r.size)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=5)
    ap.add_argument("--tars", default="", help="콤마 구분 tar 이름 부분일치 필터 (디버그)")
    ap.add_argument("--limit", type=int, default=0, help="tar 당 이미지 수 제한 (디버그)")
    args = ap.parse_args()

    out = HERE / "outputs" / "mp"
    sample = pd.read_parquet(out / "sample.parquet")
    tar_days = json.load(open(out / "tar_days.json", encoding="utf-8"))
    manifest_path = out / "manifest.json"
    manifest = json.load(open(manifest_path, encoding="utf-8")) if manifest_path.exists() else {}

    tars = [(Path(t), d) for t, d in tar_days.items() if not d.startswith("ERROR")]
    tars.sort(key=lambda td: (not str(td[0]).startswith("C:"), td[0].name))  # C: 먼저(빠름), 그다음 T:
    if args.tars:
        keys = [k.strip() for k in args.tars.split(",")]
        tars = [(t, d) for t, d in tars if any(k in t.name for k in keys)]
    print(f"[plan] tar {len(tars)}개, 샘플 이미지 {len(sample):,}장, workers={args.workers}", flush=True)

    pool = Pool(processes=args.workers, initializer=_init_worker, initargs=(str(MODEL),))
    t_all = time.time()
    done_imgs = 0
    for tar_path, day in tars:
        name = tar_path.stem
        if name in manifest and manifest[name].get("status") == "done":
            continue
        wanted = sample[sample["day"] == day]
        if wanted.empty:
            manifest[name] = {"status": "done", "day": day, "n": 0, "note": "no sample"}
            json.dump(manifest, open(manifest_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
            continue
        if args.limit:
            wanted = wanted.head(args.limit)
        t0 = time.time()
        idx = build_index(tar_path, out / f"index_{name}.parquet")
        n_found = int(idx["name"].isin(set(wanted["img_key"])).sum())
        rows = []
        for i, row in enumerate(pool.imap_unordered(_infer, read_items(tar_path, idx, wanted), chunksize=4)):
            rows.append(row)
            if (i + 1) % 2000 == 0:
                el = time.time() - t0
                print(f"   [{name}] {i+1}/{n_found}  {el:.0f}s  ({(i+1)/el:.1f} img/s)", flush=True)
        df = pd.DataFrame(rows)
        for c in LM_COLS + W_COLS:
            if c not in df.columns:
                df[c] = np.nan
        df = df[["img_key", "detected", "w", "h", "infer_ms"] + LM_COLS + W_COLS]
        df.to_parquet(out / f"landmarks_{name}.parquet", index=False)
        el = time.time() - t0
        det = float(df["detected"].mean()) if len(df) else 0.0
        manifest[name] = {"status": "done", "day": day, "n": int(len(df)), "n_wanted": int(len(wanted)), "n_found": n_found,
                          "detect_rate": det, "sec": round(el, 1), "img_per_s": round(len(df) / max(el, 1e-6), 1),
                          "mean_infer_ms": float(df["infer_ms"].mean()) if len(df) else None}
        json.dump(manifest, open(manifest_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        done_imgs += len(df)
        print(f"[done] {name} ({day}): {len(df):,}/{len(wanted):,} imgs, detect {det*100:.1f}%, {el:.0f}s, {len(df)/max(el,1e-6):.1f} img/s | 누적 {done_imgs:,} ({(time.time()-t_all)/60:.1f}분)", flush=True)
    pool.close()
    pool.join()
    print(f"[all done] {done_imgs:,} imgs in {(time.time()-t_all)/60:.1f}분")


if __name__ == "__main__":
    main()

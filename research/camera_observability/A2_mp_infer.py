#!/usr/bin/env python
"""A2 추론 — AIHub 바벨 스쿼트 720클립 × 5뷰 × 16프레임 전부에 MediaPipe Pose Landmarker(full)를 돌린다.

왜 새로 돌리나: 기존 실험 A(`research/aihub_fitness/outputs/mp/landmarks_*.parquet`)는 종목당 60클립만 샘플해
바벨 스쿼트가 60클립(조건 라벨 30 대 30)뿐이다. 조건 라벨 AUC·뷰별 상관을 재려면 720클립(16조합 × 42수행자 균형 설계)이 필요하다.

두 변형을 같은 디코드에서 돌린다:
  full : 원본 1920×1080 (실험 A 와 같은 조건 — 60클립 겹침으로 재현성 확인)
  app  : 앱 조건 근사 — 사람 GT 2D 외접 상자(클립·뷰 단위, 16프레임 합집합)를 세로 3:4 로 잘라(사람 높이 ≈ 80 %) 480×640 으로 축소.
         앱은 분석 프레임 480×640(세로 폰)이다. 모델 입력은 어차피 ROI 256×256 이라 차이는 작을 것으로 예상 — 그걸 확인한다.
둘 다 IMAGE 모드(프레임 독립). 앱은 VIDEO 모드(추적+평활)지만 AIHub 16프레임은 여러 반복에 걸친 성긴 표본이라
VIDEO 모드 평활을 흉내 내면 오히려 지연을 과장한다 — 보고서에 한계로 적는다.

입력(읽기 전용): research/aihub_fitness/outputs/{clips,kp2d}.parquet, outputs/mp/tar_days.json, outputs/mp/index_<tar>.parquet(tar 헤더 인덱스)
출력: research/camera_observability/outputs/A2/mp/lm_<tar>_<variant>.parquet
  컬럼: img_key, variant, detected, w, h, crop_x0, crop_y0, crop_s (app: 원본px = crop_x0 + x/crop_s),
        l{i}_{x,y,z,v,p} (i=0..32, x/y 는 그 변형 이미지의 px), w{i}_{x,y,z} (월드 m, MediaPipe 부호 그대로)
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
AIHUB = HERE.parent / "aihub_fitness"
SRC = AIHUB / "outputs"
MODEL = AIHUB / "models" / "pose_landmarker_full.task"
OUT = HERE / "outputs" / "A2" / "mp"
N_LM = 33
LM_COLS = [f"l{i}_{a}" for i in range(N_LM) for a in ("x", "y", "z", "v", "p")]
W_COLS = [f"w{i}_{a}" for i in range(N_LM) for a in ("x", "y", "z")]
APP_W, APP_H = 480, 640
FILL = 0.80  # 사람(머리 여유 포함) 높이 / 프레임 높이

_lmk = None


def _init(model_path: str):
    global _lmk
    from mediapipe.tasks.python import BaseOptions, vision
    opts = vision.PoseLandmarkerOptions(base_options=BaseOptions(model_asset_path=model_path),
                                        running_mode=vision.RunningMode.IMAGE, num_poses=1,
                                        min_pose_detection_confidence=0.5, min_pose_presence_confidence=0.5,
                                        min_tracking_confidence=0.5, output_segmentation_masks=False)
    _lmk = vision.PoseLandmarker.create_from_options(opts)


def _detect(rgb: np.ndarray, row: dict):
    import mediapipe as mp
    res = _lmk.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb)))
    h, w = rgb.shape[:2]
    row["w"], row["h"] = int(w), int(h)
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


def _app_crop(rgb: np.ndarray, crop):
    """crop = (cx, cy, ch) 원본 px. 세로 3:4 창을 이미지 안으로 밀어 넣고(가능하면) 모자라면 검은 여백. → (img480x640, x0, y0, s)"""
    import cv2
    H, W = rgb.shape[:2]
    cx, cy, ch = crop
    ch = float(ch)
    cw = ch * APP_W / APP_H
    x0, y0 = cx - cw / 2, cy - ch / 2
    if cw <= W:
        x0 = min(max(x0, 0.0), W - cw)
    if ch <= H:
        y0 = min(max(y0, 0.0), H - ch)
    s = APP_H / ch
    # 아핀: 원본 → 앱 이미지 (여백은 0)
    M = np.array([[s, 0, -x0 * s], [0, s, -y0 * s]], np.float32)
    out = cv2.warpAffine(rgb, M, (APP_W, APP_H), flags=cv2.INTER_AREA, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    return out, float(x0), float(y0), float(s)


def _work(item):
    import cv2
    img_key, buf, crop, variants = item
    rows = []
    arr = cv2.imdecode(np.frombuffer(buf, np.uint8), cv2.IMREAD_COLOR)
    for v in variants:
        row = {"img_key": img_key, "variant": v, "detected": False, "w": 0, "h": 0,
               "crop_x0": 0.0, "crop_y0": 0.0, "crop_s": 1.0}
        if arr is None:
            rows.append(row)
            continue
        rgb = cv2.cvtColor(arr, cv2.COLOR_BGR2RGB)
        if v == "app":
            if crop is None:
                rows.append(row)
                continue
            rgb, x0, y0, s = _app_crop(rgb, crop)
            row.update(crop_x0=x0, crop_y0=y0, crop_s=s)
        rows.append(_detect(rgb, row))
    return rows


def crops_from_gt(k2: pd.DataFrame) -> dict:
    """(clip_id, view_letter) → (cx, cy, crop_h): GT 2D 24관절의 16프레임 합집합 상자 + 머리 여유."""
    xs = k2[[c for c in k2.columns if c.endswith("_x") and c not in ("crop_x",)]].to_numpy(float)
    ys = k2[[c for c in k2.columns if c.endswith("_y")]].to_numpy(float)
    g = pd.DataFrame({"clip_id": k2.clip_id.values, "view_letter": k2.view_letter.values,
                      "xmin": xs.min(1), "xmax": xs.max(1), "ymin": ys.min(1), "ymax": ys.max(1)})
    b = g.groupby(["clip_id", "view_letter"]).agg(xmin=("xmin", "min"), xmax=("xmax", "max"), ymin=("ymin", "min"), ymax=("ymax", "max"))
    bh = b.ymax - b.ymin
    ymin = b.ymin - 0.08 * bh          # 눈·귀 위 머리 꼭대기
    ymax = b.ymax + 0.02 * bh          # 발 아래
    hh = ymax - ymin
    ww = b.xmax - b.xmin
    ch = np.maximum(hh / FILL, (ww / 0.9) * APP_H / APP_W)
    cx = (b.xmin + b.xmax) / 2
    cy = (ymin + ymax) / 2
    return {k: (float(x), float(y), float(h)) for k, x, y, h in zip(b.index, cx, cy, ch)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=5)
    ap.add_argument("--limit", type=int, default=0, help="tar 당 이미지 수 제한(디버그)")
    ap.add_argument("--variants", default="full,app")
    ap.add_argument("--tars", default="")
    args = ap.parse_args()
    variants = tuple(v.strip() for v in args.variants.split(",") if v.strip())
    OUT.mkdir(parents=True, exist_ok=True)

    clips = pd.read_parquet(SRC / "clips.parquet", columns=["clip_id", "exercise", "day"])
    sq = clips[clips.exercise == "바벨 스쿼트"]
    k2 = pd.read_parquet(SRC / "kp2d.parquet", filters=[("clip_id", "in", sq.clip_id.tolist())])
    k2 = k2[k2.img_key != ""].merge(sq[["clip_id", "day"]], on="clip_id")
    crops = crops_from_gt(k2)
    tar_days = json.load(open(SRC / "mp" / "tar_days.json", encoding="utf-8"))
    days = set(k2.day)
    tars = sorted((Path(t), d) for t, d in tar_days.items() if d in days)
    if args.tars:
        keys = [k.strip() for k in args.tars.split(",")]
        tars = [(t, d) for t, d in tars if any(k in t.name for k in keys)]
    man_p = OUT / "manifest.json"
    man = json.load(open(man_p, encoding="utf-8")) if man_p.exists() else {}
    print(f"[plan] 스쿼트 이미지 {len(k2):,}장, tar {len(tars)}개, 변형 {variants}, workers={args.workers}", flush=True)

    pool = Pool(processes=args.workers, initializer=_init, initargs=(str(MODEL),))
    t_all = time.time()
    for tar_path, day in tars:
        name = tar_path.stem
        if man.get(name, {}).get("status") == "done" and not args.limit:
            continue
        want = k2[k2.day == day][["img_key", "clip_id", "view_letter"]]
        if args.limit:
            want = want.head(args.limit)
        idx = pd.read_parquet(SRC / "mp" / f"index_{name}.parquet")
        m = idx.merge(want, left_on="name", right_on="img_key", how="inner").sort_values("offset")
        t0 = time.time()
        if m.empty:   # 이 날짜의 스쿼트 이미지가 이 tar 에 없음(Day12 등) — 기록만 남긴다
            man[name] = {"status": "done", "day": day, "n_img": 0, "n_want": int(len(want)), "note": "tar 에 해당 이미지 없음"}
            json.dump(man, open(man_p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
            print(f"[skip] {name} ({day}) 원하는 {len(want):,}장 중 tar 에 있는 것 0장", flush=True)
            continue

        def items():
            with open(tar_path, "rb") as f:
                for r in m.itertuples():
                    f.seek(r.offset)
                    yield (r.img_key, f.read(int(r.size)), crops.get((r.clip_id, r.view_letter)), variants)

        rows = []
        for i, rr in enumerate(pool.imap_unordered(_work, items(), chunksize=4)):
            rows.extend(rr)
            if (i + 1) % 2000 == 0:
                el = time.time() - t0
                print(f"   [{name}] {i+1}/{len(m)}  {el:.0f}s ({(i+1)/el:.1f} img/s)", flush=True)
        cols = ["img_key", "variant", "detected", "w", "h", "crop_x0", "crop_y0", "crop_s"] + LM_COLS + W_COLS
        df = pd.DataFrame(rows).reindex(columns=cols)
        for v in variants:
            dv = df[df.variant == v]
            suffix = "_test" if args.limit else ""
            dv.to_parquet(OUT / f"lm_{name}_{v}{suffix}.parquet", index=False)
        el = time.time() - t0
        if not args.limit:
            man[name] = {"status": "done", "day": day, "n_img": int(len(m)), "n_want": int(len(want)), "sec": round(el, 1),
                         "detect": {v: float(df[df.variant == v].detected.mean()) for v in variants}}
            json.dump(man, open(man_p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f"[done] {name} ({day}) {len(m):,}장 {el:.0f}s | 검출 " +
              ", ".join(f"{v}={df[df.variant==v].detected.mean()*100:.1f}%" for v in variants) +
              f" | 누적 {(time.time()-t_all)/60:.1f}분", flush=True)
    pool.close()
    pool.join()


if __name__ == "__main__":
    main()

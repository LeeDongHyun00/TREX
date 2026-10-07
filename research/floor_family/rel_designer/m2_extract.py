# -*- coding: utf-8 -*-
"""M2: MM-Fit 윗몸일으키기 세트(로컬 RGB 5명)를 앱 모델로 100 ms 간격 추론 → 이미지 좌표·가시성 parquet. 앞뒤 6 s 여유(휴식 헛카운트 확인용)."""
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path
import numpy as np, pandas as pd

ROOT = Path(r"C:/Users/hp276/Desktop/trex")
MODEL = ROOT / "app/src/main/assets/posture/pose_landmarker_full.task"
S = Path(__file__).resolve().parent
STEP_MS = 100
MARGIN_S = 6.0


def job(args):
    w, k, start, end, reps = args
    import cv2, mediapipe as mp
    from mediapipe.tasks import python as mp_python
    from mediapipe.tasks.python import vision
    video = str(ROOT / f"data/mm-fit/{w}_rgb.mp4")
    cap = cv2.VideoCapture(video); fps = cap.get(cv2.CAP_PROP_FPS)
    a = max(0, int(start - MARGIN_S * fps)); b = int(end + MARGIN_S * fps)
    warm = max(0, a - int(1.5 * fps))
    cap.set(cv2.CAP_PROP_POS_FRAMES, warm)
    frame = int(cap.get(cv2.CAP_PROP_POS_FRAMES))
    lm = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(MODEL)), running_mode=vision.RunningMode.VIDEO, num_poses=1,
        min_pose_detection_confidence=0.5, min_pose_presence_confidence=0.5, min_tracking_confidence=0.5))
    rows = []; last = None
    while frame <= b:
        ok, bgr = cap.read()
        if not ok: break
        t = int(round(frame * 1000.0 / fps))
        if last is not None and t - last < STEP_MS:
            frame += 1; continue
        last = t
        h, wd = bgr.shape[:2]; sc = 640 / max(h, wd)
        if sc < 1: bgr = cv2.resize(bgr, (int(round(wd * sc)), int(round(h * sc))), interpolation=cv2.INTER_AREA)
        res = lm.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)), t)
        if frame >= a:
            r = dict(w=w, set=k, frame=frame, t_ms=t, in_set=start <= frame <= end, reps=reps, W=bgr.shape[1], H=bgr.shape[0])
            if res.pose_landmarks:
                for i, p in enumerate(res.pose_landmarks[0]):
                    r[f"x{i}"] = p.x; r[f"y{i}"] = p.y; r[f"v{i}"] = p.visibility
            rows.append(r)
        frame += 1
    cap.release(); lm.close()
    return rows


def main():
    jobs = []
    for w in ["w06", "w14", "w18", "w19", "w20"]:
        lab = pd.read_csv(ROOT / f"data/mm-fit/mm-fit/{w}/{w}_labels.csv", header=None, names=["s", "e", "n", "act"])
        for k, r in enumerate(lab[lab.act == "situps"].itertuples()):
            jobs.append((w, k, r.s, r.e, r.n))
    out = []
    with ProcessPoolExecutor(max_workers=5) as ex:
        for rows in ex.map(job, jobs):
            out += rows
    df = pd.DataFrame(out)
    df.to_parquet(S / "m2_situps.parquet", index=False)
    print(df.shape, df.groupby(["w", "set"]).size().to_dict())


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""A3 — 30 fps 전 프레임 MediaPipe 추출 (이미지 좌표 + 월드 좌표) + 같은 영상의 '진짜 앱 주기' 추론.

왜 새로 뽑나
    기존 캡처 두 종류 모두 이 연구에 맞지 않는다.
      data/rehab24-6/captures/*_full.trexcap  30 fps 전 프레임이지만 **월드 좌표만**(이미지 x·y 없음), 원본 1920 해상도,
                                              num_poses=2(두 사람 후보 프레임은 버림) — 앱 설정(num_poses=1, 긴 변 640)과 다르다.
      data/rehab_mp_captures/*.cap,           이미지+월드 좌표, 앱 설정 그대로지만 **300 ms 주기**(9프레임에 1개).
      data/mmfit_mp_captures/*.cap
    그래서 앱과 같은 모델(SHA 고정)·모드(VIDEO, num_poses=1, 신뢰도 0.5)·해상도(긴 변 640, INTER_AREA)·
    mediapipe 0.10.14(앱 tasks-vision 과 같은 판)로 **모든 프레임**을 추론한다.

진짜 주기 추론을 같이 하는 이유
    VIDEO 모드는 추적기(직전 프레임의 ROI)와 랜드마크 평활(one-euro, 시간 간격 의존)을 가진다. 30 fps 결과를 300 ms 로
    솎아낸 값은 "추적기가 사이 프레임을 다 본" 값이라, 앱처럼 300 ms 마다만 넣은 값과 다를 수 있다. 그래서 같은 영상을
    별도 랜드마커 인스턴스에 100/150/200/300/333/450 ms 격자(위상 0)로만 넣어 둔다 — 솎아내기 결과가 앱 현실을 대표하는지
    같은 프레임끼리 비교할 수 있다.

격자
    간격 I ms 의 k 번째 샘플 = 시작 프레임 + floor(k × I × fps / 1000 + 1e-9). 150 ms(4.5프레임)·450 ms(13.5)는 4/5·13/14 가 번갈아
    평균 간격이 정확히 I 가 된다. 333 ms = 10프레임 = 실기기에서 '300 ms' 설정이 30 fps 카메라 지터로 실제 받는 최악 간격.

사용법 (mediapipe 0.10.14 가 있는 venv 로)
    <venv>/python A3_extract30.py --workers 8
출력
    outputs/A3/cap30/<name>.npz  — frames(int32), t_ms(int32), img(N,33,4: x,y,vis,pres), wld(N,33,3), det(bool),
                                   c<I>_frames/_img/_wld/_det (진짜 주기, 위상 0), meta(json 문자열)
    REHAB24-6 은 CC BY-NC 4.0 — 이 파생 캡처는 측정 전용이고 커밋·공개하지 않는다.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import multiprocessing as mp_proc
import sys
import time
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[2]
MODEL = REPO / "app" / "src" / "main" / "assets" / "posture" / "pose_landmarker_full.task"
MODEL_SHA = "4eaa5eb7a98365221087693fcc286334cf0858e2eb6e15b506aa4a7ecdcec4ad"
OUT = Path(__file__).resolve().parent / "outputs" / "A3" / "cap30"
LONG_SIDE = 640
CADENCES_MS = [100, 150, 200, 300, 1000 / 3, 450]  # 1000/3 = 정확히 10프레임(키 c333)
REHAB_SQUAT_VIDEOS = ["PM_008", "PM_022", "PM_029", "PM_038", "PM_043", "PM_105", "PM_113", "PM_118", "PM_126"]
MMFIT_WORKOUTS = ["w06", "w14", "w18", "w19", "w20"]  # 로컬에 RGB 영상이 남아 있는 워크아웃만 (나머지는 추출 후 삭제됨)
MMFIT_PRE_S, MMFIT_POST_S = 6.0, 5.0


def grid(start: int, end: int, interval_ms: float, fps: float, phase: int = 0) -> list[int]:
    stride = interval_ms * fps / 1000.0
    out, k = [], 0
    while True:
        f = start + phase + int(np.floor(k * stride + 1e-9))
        if f > end:
            return out
        out.append(f)
        k += 1


def make_landmarker():
    from mediapipe.tasks import python as mp_python  # noqa: PLC0415
    from mediapipe.tasks.python import vision  # noqa: PLC0415
    return vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(MODEL)),
        running_mode=vision.RunningMode.VIDEO, num_poses=1,
        min_pose_detection_confidence=0.5, min_pose_presence_confidence=0.5, min_tracking_confidence=0.5,
        output_segmentation_masks=False))


def run_job(job: dict) -> dict:
    import cv2  # noqa: PLC0415
    import mediapipe as mp  # noqa: PLC0415

    out_path = Path(job["out"])
    if out_path.is_file():
        return {"name": job["name"], "skipped": True}
    started = time.time()
    cap = cv2.VideoCapture(job["video"])
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    cap.set(cv2.CAP_PROP_POS_FRAMES, job["start"])
    landed = int(cap.get(cv2.CAP_PROP_POS_FRAMES))
    if landed != job["start"]:  # 키프레임에 섰으면 처음부터 읽어 정확한 프레임에 선다
        cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
        for _ in range(job["start"]):
            cap.grab()
    start, end = job["start"], job["end"]
    lm30 = make_landmarker()
    cad = {I: (set(grid(start, end, I, fps)), make_landmarker()) for I in CADENCES_MS}
    rec = {"f30": ([], [], [], [])}
    for I in CADENCES_MS:
        rec[f"c{round(I)}"] = ([], [], [], [])

    def put(key, frame, result):
        fr, im, wl, dt = rec[key]
        fr.append(frame)
        if result.pose_landmarks and result.pose_world_landmarks:
            img = result.pose_landmarks[0]
            wld = result.pose_world_landmarks[0]
            im.append([[p.x, p.y, p.visibility if p.visibility is not None else np.nan,
                        p.presence if p.presence is not None else np.nan] for p in img])
            wl.append([[q.x, q.y, q.z] for q in wld])
            dt.append(True)
        else:
            im.append(np.full((33, 4), np.nan).tolist())
            wl.append(np.full((33, 3), np.nan).tolist())
            dt.append(False)

    h0 = w0 = None
    frame = start
    while frame <= end:
        ok, bgr = cap.read()
        if not ok:
            break
        h, w = bgr.shape[:2]
        h0, w0 = h, w
        s = LONG_SIDE / max(h, w)
        if s < 1.0:
            bgr = cv2.resize(bgr, (int(round(w * s)), int(round(h * s))), interpolation=cv2.INTER_AREA)
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        t_ms = int(round(frame * 1000.0 / fps))
        image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        put("f30", frame, lm30.detect_for_video(image, t_ms))
        for I, (sel, lmk) in cad.items():
            if frame in sel:
                put(f"c{round(I)}", frame, lmk.detect_for_video(image, t_ms))
        frame += 1
    cap.release()
    lm30.close()
    for _, lmk in cad.values():
        lmk.close()

    arrays = {}
    for key, (fr, im, wl, dt) in rec.items():
        pre = "" if key == "f30" else f"{key}_"
        arrays[pre + "frames"] = np.asarray(fr, dtype=np.int32)
        arrays[pre + "img"] = np.asarray(im, dtype=np.float32).reshape(-1, 33, 4)
        arrays[pre + "wld"] = np.asarray(wl, dtype=np.float32).reshape(-1, 33, 3)
        arrays[pre + "det"] = np.asarray(dt, dtype=bool)
    arrays["t_ms"] = np.round(arrays["frames"] * 1000.0 / fps).astype(np.int32)
    meta = {k: v for k, v in job.items() if k not in ("out",)}
    meta.update({"fps": fps, "width": w0, "height": h0, "analysisLongSide": LONG_SIDE, "modelSha256": MODEL_SHA,
                 "mediapipe": mp.__version__, "cadencesMs": CADENCES_MS, "numPoses": 1, "mode": "VIDEO", "delegate": "CPU",
                 "elapsedS": round(time.time() - started, 1)})
    arrays["meta"] = np.asarray(json.dumps(meta, ensure_ascii=False))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_path.with_suffix(".tmp.npz")
    np.savez_compressed(tmp, **arrays)
    tmp.replace(out_path)
    return {"name": job["name"], "frames": int(len(arrays["frames"])), "det": float(arrays["det"].mean()),
            "elapsedS": meta["elapsedS"]}


def rehab_jobs() -> list[dict]:
    import cv2  # noqa: PLC0415
    jobs = []
    for vid in REHAB_SQUAT_VIDEOS:
        for cam, suffix in (("17", "Camera17-30fps"), ("18", "Camera18-30fps-transposed")):
            path = REPO / "data" / "rehab24-6" / "Ex6" / f"{vid}-{suffix}.mp4"
            c = cv2.VideoCapture(str(path))
            n = int(c.get(cv2.CAP_PROP_FRAME_COUNT))
            c.release()
            jobs.append({"name": f"rehab_{vid}_{cam}", "dataset": "rehab", "video_id": vid, "camera": cam,
                         "video": str(path), "start": 0, "end": n - 1, "out": str(OUT / f"rehab_{vid}_{cam}.npz")})
    return jobs


def mmfit_jobs() -> list[dict]:
    import cv2  # noqa: PLC0415
    align = json.loads((REPO / "research" / "external_rep_replay" / "results" / "mmfit_mp" / "alignment.json")
                       .read_text(encoding="utf-8"))["sets"]
    jobs = []
    for w in MMFIT_WORKOUTS:
        path = REPO / "data" / "mm-fit" / f"{w}_rgb.mp4"
        c = cv2.VideoCapture(str(path))
        n = int(c.get(cv2.CAP_PROP_FRAME_COUNT))
        fps = c.get(cv2.CAP_PROP_FPS) or 30.0
        c.release()
        rows = sorted(([int(r[0]), int(r[1]), int(r[2]), r[3].strip()] for r in
                       csv.reader((REPO / "data" / "mm-fit" / "mm-fit" / w / f"{w}_labels.csv").open(newline="")) if r),
                      key=lambda r: r[0])
        for ordinal, (s, e, reps, act) in enumerate(rows):
            if act != "squats":
                continue
            key = f"{w}/{w}_set{ordinal:02d}_squats.cap"
            lag = int(align.get(key, {}).get("usedLagFrames", 0))
            s_v, e_v = s + lag, e + lag  # 영상 프레임 = 라벨 프레임 + 지연(음수 = 영상이 앞섬, 설계 §16.1)
            lo = max(0, s_v - int(round(MMFIT_PRE_S * fps)))
            hi = min(n - 1, e_v + int(round(MMFIT_POST_S * fps)))
            jobs.append({"name": f"mmfit_{w}_set{ordinal:02d}", "dataset": "mmfit", "workout": w, "ordinal": ordinal,
                         "video": str(path), "start": lo, "end": hi, "labelStart": s_v, "labelEnd": e_v,
                         "truthReps": reps, "lagFrames": lag, "out": str(OUT / f"mmfit_{w}_set{ordinal:02d}.npz")})
    return jobs


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--only", nargs="*", default=None)
    args = ap.parse_args()
    digest = hashlib.sha256(MODEL.read_bytes()).hexdigest()
    if digest != MODEL_SHA:
        raise SystemExit(f"model SHA mismatch: {digest}")
    jobs = rehab_jobs() + mmfit_jobs()
    if args.only:
        jobs = [j for j in jobs if any(o in j["name"] for o in args.only)]
    # 긴 작업부터 — 풀의 꼬리를 줄인다
    jobs.sort(key=lambda j: -(j["end"] - j["start"]))
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "jobs.json").write_text(json.dumps(jobs, ensure_ascii=False, indent=1), encoding="utf-8")
    t0 = time.time()
    with mp_proc.Pool(args.workers) as pool:
        for done in pool.imap_unordered(run_job, jobs):
            print(f"[{time.time() - t0:6.0f}s] {done}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

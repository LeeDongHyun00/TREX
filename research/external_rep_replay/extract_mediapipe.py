# -*- coding: utf-8 -*-
"""영상 → MediaPipe 추론 캡처. 앱 분석 스레드가 보는 것과 최대한 같은 입력을 만든다.

앱과 맞춘 것 (PostureAnalyzer · PostureLive · InferencePolicy)
    모델          app/src/main/assets/posture/pose_landmarker_full.task (앱이 번들한 파일, SHA 기록)
    실행 모드     VIDEO, num_poses=1, 검출·존재·추적 신뢰도 0.5, 분할 마스크 끔
    추론 주기     직전 추론 이후 300ms 이상인 첫 프레임만 (30fps 영상이면 300ms 간격, 실기기는 300~333ms).
                  건너뛴 프레임은 랜드마커에 넣지 않는다 — 앱의 추적기도 그 프레임을 못 본다
    해상도        긴 변 640px 로 축소 (앱 분석 스트림 640×480 과 같은 화소 밀도)
    준비 구간     세트 시작 전 1.5초를 추론만 하고 버린다 — 앱도 준비 카운트다운 동안 추론하지만
                  그 프레임은 카운터에 넣지 않는다(preparing 이면 return)

앱과 다른 것 — 결과 해석에 반드시 따라가야 한다
    카메라        휴대폰이 아니라 데이터셋 카메라(MM-Fit 고정 정면, REHAB24-6 같은 자리의 가로·세로 두 대)
    델리게이트    CPU(XNNPACK). 앱은 GPU 우선 — 수치가 미세하게 다를 수 있다
    중력          영상에는 IMU 가 없다. 재생기는 up=화면 세로축(앱의 센서 없는 폴백)을 쓴다
    발열          발열 배수 1.0 (감속 없음) 가정

사용법
    python extract_mediapipe.py mmfit  <mm-fit 라벨 루트> --videos <영상 폴더> --out <출력> [--pre-s 15 --post-s 10] [--workouts w00]
    python extract_mediapipe.py rehab  <REHAB24-6 루트(Segmentation.csv)> --videos <영상 폴더> --out <출력>
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import multiprocessing as mp_proc
import sys
from pathlib import Path

from capture_format import APP_SAMPLE_INTERVAL_MS, frame_line, write_capture

REPO = Path(__file__).resolve().parents[2]
MODEL = REPO / "app" / "src" / "main" / "assets" / "posture" / "pose_landmarker_full.task"
ANALYSIS_LONG_SIDE = 640
WARMUP_MS = 1500
MMFIT_MARGIN_FRAMES = 15

MMFIT_ACTIVITIES = {"squats": "바벨 스쿼트", "lunges": "스텝 포워드 다이나믹 런지", "bicep_curls": "덤벨 컬"}
# REHAB24-6 exercise_id → 앱 신호 종목. 컬은 이 데이터셋에 없다.
REHAB_EXERCISES = {5: "스텝 포워드 다이나믹 런지", 6: "바벨 스쿼트"}


def model_sha() -> str:
    return hashlib.sha256(MODEL.read_bytes()).hexdigest()


def infer_segment(job: dict) -> dict:
    """한 구간을 앱 주기로 추론한다. job: video, fps, start, end(프레임, 끝 포함), capture, meta."""
    import cv2  # noqa: PLC0415 — 작업 프로세스에서만 불러 부모가 TFLite 상태를 갖지 않게 한다
    import mediapipe as mp  # noqa: PLC0415
    from mediapipe.tasks import python as mp_python  # noqa: PLC0415
    from mediapipe.tasks.python import vision  # noqa: PLC0415

    fps = job["fps"]
    warm = max(0, job["start"] - int(round(WARMUP_MS * fps / 1000.0)))
    cap = cv2.VideoCapture(job["video"])
    cap.set(cv2.CAP_PROP_POS_FRAMES, warm)
    # mp4 탐색은 코덱에 따라 요청한 프레임이 아니라 근처 키프레임에 설 수 있다. 실제 위치를 읽어 쓰지 않으면
    # 프레임 번호(=라벨 축)와 시각이 통째로 밀린다.
    landed = int(cap.get(cv2.CAP_PROP_POS_FRAMES))
    landmarker = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(MODEL)),
        running_mode=vision.RunningMode.VIDEO, num_poses=1,
        min_pose_detection_confidence=0.5, min_pose_presence_confidence=0.5, min_tracking_confidence=0.5,
        output_segmentation_masks=False))
    lines: list[str] = []
    outcomes = {"pose": 0, "no_pose": 0, "seekOffset": landed - warm}
    last_infer: int | None = None
    frame = landed
    while frame <= job["end"]:
        ok, bgr = cap.read()
        if not ok:
            break
        t_ms = int(round(frame * 1000.0 / fps))
        if last_infer is not None and t_ms - last_infer < APP_SAMPLE_INTERVAL_MS:
            frame += 1
            continue
        last_infer = t_ms
        h, w = bgr.shape[:2]
        scale = ANALYSIS_LONG_SIDE / max(h, w)
        if scale < 1.0:
            bgr = cv2.resize(bgr, (int(round(w * scale)), int(round(h * scale))), interpolation=cv2.INTER_AREA)
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        result = landmarker.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), t_ms)
        if frame >= job["start"]:
            if result.pose_landmarks and result.pose_world_landmarks:
                img = result.pose_landmarks[0]
                wld = result.pose_world_landmarks[0]
                lms = [(p.x, p.y, p.visibility, p.presence, q.x, q.y, q.z) for p, q in zip(img, wld)]
                lines.append(frame_line(t_ms, lms))
                outcomes["pose"] += 1
            else:
                lines.append(frame_line(t_ms, None))
                outcomes["no_pose"] += 1
        frame += 1
    cap.release()
    landmarker.close()
    write_capture(Path(job["capture"]), job["meta"], lines)
    return {"capture": job["capture"], "outcomes": outcomes, "samples": len(lines)}


def video_info(path: Path) -> tuple[float, int]:
    import cv2  # noqa: PLC0415
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise SystemExit(f"cannot open {path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    cap.release()
    return fps, frames


def mmfit_jobs(root: Path, videos: Path, out: Path, pre_s: float, post_s: float, workouts: list[str] | None,
               video_glob: str) -> tuple[list[dict], list[dict]]:
    """세트마다 [라벨 시작 − pre_s, 라벨 끝 + post_s] 를 추출한다. 앞뒤 구간은 세트 전 준비·세트 뒤 정리 동작의
    헛카운트를 재기 위한 음성 구간이다 — 다만 이웃 세트의 라벨 구간(±0.5s)과는 겹치지 않게 자른다.
    채점은 index 의 label 시각(startMs/endMs)과 capture 시각(captureStartMs/captureEndMs)으로 구간을 나눈다."""
    jobs, sets = [], []
    for label_path in sorted(root.glob("w??/w??_labels.csv")):
        w = label_path.parent.name
        if workouts and w not in workouts:
            continue
        candidates = sorted(videos.glob(video_glob.format(w=w)))
        if not candidates:
            print(f"skip {w}: no video ({video_glob.format(w=w)})", file=sys.stderr)
            continue
        video = candidates[0]
        fps, frame_total = video_info(video)
        rows = [r for r in csv.reader(label_path.open(newline="")) if r]
        rows.sort(key=lambda r: int(r[0]))
        spans = [(int(r[0]), int(r[1])) for r in rows]
        for ordinal, (start, end, reps, activity) in enumerate(rows):
            activity = activity.strip()
            exercise = MMFIT_ACTIVITIES.get(activity)
            if exercise is None:
                continue
            start, end = int(start), int(end)
            lo = start - max(MMFIT_MARGIN_FRAMES, int(round(pre_s * fps)))
            hi = end + max(MMFIT_MARGIN_FRAMES, int(round(post_s * fps)))
            if ordinal > 0:
                lo = max(lo, spans[ordinal - 1][1] + MMFIT_MARGIN_FRAMES + 1)
            if ordinal + 1 < len(spans):
                hi = min(hi, spans[ordinal + 1][0] - MMFIT_MARGIN_FRAMES - 1)
            lo, hi = max(0, lo), min(frame_total - 1, hi)
            name = f"{w}/{w}_set{ordinal:02d}_{activity}.cap"
            jobs.append({"video": str(video), "fps": fps, "start": lo, "end": hi, "capture": str(out / name),
                         "meta": {"source": "mmfit_mediapipe", "workout": w, "activity": activity}})
            ms = lambda f: int(round(f * 1000.0 / fps))
            sets.append({"workout": w, "ordinal": ordinal, "activity": activity, "exercise": exercise,
                         "truthReps": int(reps), "startMs": ms(start), "endMs": ms(end),
                         "captureStartMs": ms(lo), "captureEndMs": ms(hi), "capture": name})
    return jobs, sets


def rehab_jobs(root: Path, videos: Path, out: Path, cameras: list[str]) -> tuple[list[dict], list[dict]]:
    by_video: dict[str, list[dict]] = {}
    with (root / "Segmentation.csv").open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle, delimiter=";"):
            ex = int(row["exercise_id"])
            if ex not in REHAB_EXERCISES:
                continue
            by_video.setdefault(row["video_id"], []).append({
                "exerciseId": ex, "first": int(row["first_frame"]), "last": int(row["last_frame"]),
                "correct": row["correctness"] == "1", "cam17": row.get("cam17_orientation", ""),
            })
    jobs, sets = [], []
    for video_id, reps in sorted(by_video.items()):
        for cam in cameras:
            candidates = sorted(videos.glob(f"**/{video_id}*{cam}*.mp4"))
            if not candidates:
                print(f"skip {video_id} cam{cam}: no video", file=sys.stderr)
                continue
            fps, frames = video_info(candidates[0])
            exercise = REHAB_EXERCISES[reps[0]["exerciseId"]]
            name = f"{video_id}_{cam}/{video_id}_{cam}.cap"
            jobs.append({"video": str(candidates[0]), "fps": fps, "start": 0, "end": frames - 1,
                         "capture": str(out / name), "meta": {"source": "rehab_mediapipe", "video": video_id, "camera": cam}})
            sets.append({"video": video_id, "camera": cam, "exercise": exercise, "truthReps": len(reps),
                         "reps": [[int(round(r["first"] * 1000.0 / fps)), int(round(r["last"] * 1000.0 / fps)), r["correct"]]
                                  for r in sorted(reps, key=lambda r: r["first"])],
                         "cam17Orientation": reps[0]["cam17"], "capture": name})
    return jobs, sets


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dataset", choices=["mmfit", "rehab"])
    parser.add_argument("root", type=Path)
    parser.add_argument("--videos", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--cameras", nargs="*", default=["17", "18"])
    parser.add_argument("--workers", type=int, default=max(1, mp_proc.cpu_count()))
    parser.add_argument("--pre-s", type=float, default=0.5, help="MM-Fit: 세트 라벨 앞 음성 구간(초)")
    parser.add_argument("--post-s", type=float, default=0.5, help="MM-Fit: 세트 라벨 뒤 음성 구간(초)")
    parser.add_argument("--workouts", nargs="*", default=None, help="MM-Fit: 처리할 워크아웃만 (한 개씩 받아 처리할 때)")
    parser.add_argument("--video-glob", default="**/{w}*.mp4", help="MM-Fit: 영상 파일 패턴, {w} = 워크아웃 이름")
    args = parser.parse_args()

    if args.dataset == "mmfit":
        jobs, sets = mmfit_jobs(args.root, args.videos, args.out, args.pre_s, args.post_s, args.workouts, args.video_glob)
    else:
        jobs, sets = rehab_jobs(args.root, args.videos, args.out, args.cameras)
    sha = model_sha()
    with mp_proc.Pool(args.workers) as pool:
        for done in pool.imap_unordered(infer_segment, jobs):
            print(f"  {done['capture']}: {done['samples']} samples {done['outcomes']}", file=sys.stderr, flush=True)
    args.out.mkdir(parents=True, exist_ok=True)
    index_path = args.out / "index.json"
    # 워크아웃을 한 개씩 받아 처리해도 index 가 덮이지 않게, 같은 캡처 이름만 갈아 끼운다.
    previous = json.loads(index_path.read_text(encoding="utf-8")).get("sets", []) if index_path.is_file() else []
    fresh = {s["capture"] for s in sets}
    merged = [s for s in previous if s["capture"] not in fresh] + sets
    index = {"source": f"{args.dataset} video → MediaPipe (app model, VIDEO, app cadence)", "modelSha256": sha,
             "analysisLongSide": ANALYSIS_LONG_SIDE, "cadenceMs": APP_SAMPLE_INTERVAL_MS, "warmupMs": WARMUP_MS,
             "preS": args.pre_s, "postS": args.post_s, "sets": merged}
    index_path.write_text(json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

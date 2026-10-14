# -*- coding: utf-8 -*-
"""FMS 데이터셋(Xing 2022, Sci Data 9:104, figshare CC0)의 ASLR 프레임 열 → MediaPipe 캡처 (바닥 종목 '라잉 레그 레이즈' 재생용).

ASLR(active straight leg raise) = 바닥 매트에 누워 한 다리를 곧게 들었다 내리기. m09 = 왼다리, m10 = 오른다리, 피험자마다 쪽당 3에피소드.
에피소드 하나 = 한 번 들고 내리기(앞뒤로 준비 자세 유지). 컬러 프레임은 30fps 1080p JPEG 열(파일명 …_e<에피소드>_i<순번>_r<원 프레임 번호>.jpg).
조사 근거: results/legraise_datasets_survey.md.

앱과 맞춘 것 (extract_mediapipe.py 와 같다)
    모델·실행 모드  앱 번들 pose_landmarker_full.task, VIDEO, num_poses=1, 신뢰도 0.5, mediapipe==0.10.14(앱 tasks-vision 과 같은 버전)
    해상도          긴 변 640px (1920×1080 → 640×360)
    추론·판정 격자  §96 — 랜드마커는 직전 추론 이후 85 ms 이상인 프레임마다(30fps 에서 100 ms 간격) 보고, 캡처(= 카운터가 보는 프레임)는
                    300 ms 칸(t // 300)마다 첫 추론 프레임만 남긴다. 건너뛴 프레임은 랜드마커에도 넣지 않는다
    바닥 경로       캡처 메타 floor=1·imageWidth·imageHeight — 재생기가 FloorFeatureExtractor(앱 PostureFloor.kt)로 피처를 만든다

앱과 다른 것 — 결과 해석에 따라가야 한다
    카메라          휴대폰이 아니라 Azure Kinect 컬러(측면 낮은 위치 24 cm = 'sideLow', 측면 96 cm = 'Side'). IMU 없음
    연속성          한 사람·한 쪽의 e1→e2→e3 을 이어 붙인 한 열로 추론한다(시각 연속, 에피소드 사이 틈 없음). 랜드마커 추적 상태가
                    에피소드를 넘어 이어지므로 에피소드별 캡처는 이 열을 자른 것이다 — 독립 추론과 첫 몇 프레임이 다를 수 있다
    준비 구간       없음. 열의 첫 프레임부터 캡처에 넣는다(앱은 준비 카운트다운 동안 추론만 한다)
    동작            한 다리 들기. 앱 레그 레이즈(양다리)와 다르다 — 양측 중점 hip_ang 의 스윙이 약 절반이다

사용법
    python fms_captures.py <압축 푼 폴더(s??_m09_e?/ …)> --scores <Experts_score.json> --out <출력> [--camera sideLow] [--workers 10]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import multiprocessing as mp_proc
import re
import sys
from collections import defaultdict
from pathlib import Path

from capture_format import APP_SAMPLE_INTERVAL_MS, frame_line, write_capture

REPO = Path(__file__).resolve().parents[2]
MODEL = REPO / "app" / "src" / "main" / "assets" / "posture" / "pose_landmarker_full.task"
ANALYSIS_LONG_SIDE = 640
INFER_INTERVAL_MS = 85      # PostureLive SESSION_INFER_INTERVAL_MS (§96)
FPS = 30.0                  # Azure Kinect 컬러 30fps (논문 "1920*1080 px @30 fps")
EXERCISE = "라잉 레그 레이즈"
MOVEMENTS = {"m09": "L", "m10": "R"}   # 논문: m09 Left leg up, m10 Right leg up
NAME = re.compile(r"_s(\d\d)_(m\d\d)_\d+_e(\d)_i(\d{4})_r(\d+)\.jpg$")


def model_sha() -> str:
    return hashlib.sha256(MODEL.read_bytes()).hexdigest()


def scan(root: Path, camera: str) -> dict[tuple[str, str], dict[int, list[tuple[int, Path]]]]:
    """(피험자, 동작) → 에피소드 → [(원 프레임 번호, 경로)] (원 프레임 번호 순)."""
    seqs: dict[tuple[str, str], dict[int, list[tuple[int, Path]]]] = defaultdict(lambda: defaultdict(list))
    for path in root.glob(f"s??_m??_e?/{camera}_*.jpg"):
        m = NAME.search(path.name)
        if not m or m.group(2) not in MOVEMENTS:
            continue
        seqs[(f"s{m.group(1)}", m.group(2))][int(m.group(3))].append((int(m.group(5)), path))
    for eps in seqs.values():
        for frames in eps.values():
            frames.sort()
    return seqs


def infer_sequence(job: dict) -> dict:
    """한 사람·한 쪽의 에피소드들을 이어 한 열로 추론한다. 반환: 에피소드별 (시작·끝 시각, 캡처 줄)."""
    import cv2  # noqa: PLC0415 — 작업 프로세스에서만 불러 부모가 TFLite 상태를 갖지 않게 한다
    import mediapipe as mp  # noqa: PLC0415
    from mediapipe.tasks import python as mp_python  # noqa: PLC0415
    from mediapipe.tasks.python import vision  # noqa: PLC0415

    landmarker = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(MODEL)),
        running_mode=vision.RunningMode.VIDEO, num_poses=1,
        min_pose_detection_confidence=0.5, min_pose_presence_confidence=0.5, min_tracking_confidence=0.5,
        output_segmentation_masks=False))
    frame_ms = 1000.0 / FPS
    offset = 0.0
    last_infer: int | None = None
    last_bin: int | None = None
    size = None
    episodes = []
    for ep, frames in job["episodes"]:
        r0 = frames[0][0]
        lines: list[str] = []
        stats = {"inferred": 0, "judged": 0, "pose": 0, "rawGaps": 0}
        prev_r = None
        for r, path in frames:
            if prev_r is not None and r - prev_r != 1:
                stats["rawGaps"] += 1
            prev_r = r
            t_ms = int(round(offset + (r - r0) * frame_ms))
            if last_infer is not None and t_ms - last_infer < INFER_INTERVAL_MS:
                continue
            bgr = cv2.imread(str(path))
            if bgr is None:
                continue
            last_infer = t_ms
            h, w = bgr.shape[:2]
            scale = ANALYSIS_LONG_SIDE / max(h, w)
            if scale < 1.0:
                bgr = cv2.resize(bgr, (int(round(w * scale)), int(round(h * scale))), interpolation=cv2.INTER_AREA)
            size = (bgr.shape[1], bgr.shape[0])
            rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
            result = landmarker.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), t_ms)
            stats["inferred"] += 1
            judge_bin = t_ms // APP_SAMPLE_INTERVAL_MS
            if judge_bin == last_bin:
                continue
            last_bin = judge_bin
            stats["judged"] += 1
            if result.pose_landmarks and result.pose_world_landmarks:
                img, wld = result.pose_landmarks[0], result.pose_world_landmarks[0]
                lines.append(frame_line(t_ms, [(p.x, p.y, p.visibility, p.presence, q.x, q.y, q.z) for p, q in zip(img, wld)]))
                stats["pose"] += 1
            else:
                lines.append(frame_line(t_ms, None))
        start_ms = int(round(offset))
        end_ms = int(round(offset + (frames[-1][0] - r0) * frame_ms))
        episodes.append({"episode": ep, "startMs": start_ms, "endMs": end_ms, "rawFrames": len(frames), "lines": lines, **stats})
        offset += (frames[-1][0] - r0 + 1) * frame_ms   # 다음 에피소드는 바로 다음 프레임 시각부터(틈 없음)
    landmarker.close()
    return {"subject": job["subject"], "movement": job["movement"], "size": size, "episodes": episodes}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("root", type=Path)
    parser.add_argument("--scores", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--camera", default="sideLow")
    parser.add_argument("--workers", type=int, default=max(1, mp_proc.cpu_count() - 2))
    args = parser.parse_args()

    scores = json.loads(args.scores.read_text(encoding="utf-8"))
    seqs = scan(args.root, args.camera)
    if not seqs:
        raise SystemExit(f"{args.root} 에 {args.camera} ASLR(m09/m10) 프레임이 없다")
    jobs = [{"subject": s, "movement": m, "episodes": sorted(eps.items())} for (s, m), eps in sorted(seqs.items())]
    print(f"{len(jobs)} sequences, {sum(len(j['episodes']) for j in jobs)} episodes", file=sys.stderr)
    sets, eps_index = [], []
    with mp_proc.Pool(args.workers) as pool:
        for done in pool.imap_unordered(infer_sequence, jobs):
            s, m = done["subject"], done["movement"]
            w, h = done["size"] or (0, 0)
            meta = {"source": f"fms_{args.camera}_mediapipe", "exercise": EXERCISE, "floor": 1, "imageWidth": w, "imageHeight": h,
                    "subject": s, "movement": m, "movingSide": MOVEMENTS[m]}
            name = f"{s}_{m}/{s}_{m}_set.cap"
            write_capture(args.out / name, meta, [ln for e in done["episodes"] for ln in e["lines"]])
            sets.append({"subject": s, "movement": m, "movingSide": MOVEMENTS[m], "capture": name,
                         "truthReps": len(done["episodes"]),
                         "episodes": [[e["episode"], e["startMs"], e["endMs"]] for e in done["episodes"]]})
            for e in done["episodes"]:
                ename = f"{s}_{m}/{s}_{m}_e{e['episode']}.cap"
                write_capture(args.out / ename, {**meta, "episode": e["episode"]}, e["lines"])
                eps_index.append({"subject": s, "movement": m, "movingSide": MOVEMENTS[m], "episode": e["episode"], "capture": ename,
                                  "truthReps": 1, "startMs": e["startMs"], "endMs": e["endMs"], "rawFrames": e["rawFrames"],
                                  "rawGaps": e["rawGaps"], "inferred": e["inferred"], "judged": e["judged"], "pose": e["pose"],
                                  "scores": scores.get(s, {}).get(m, {}).get(f"e{e['episode']}")})
            print(f"  {s} {m}: " + " ".join(f"e{e['episode']} {e['pose']}/{e['judged']}" for e in done["episodes"]), file=sys.stderr, flush=True)
    index = {"source": f"FMS {args.camera} JPEG → MediaPipe (app model, VIDEO, infer {INFER_INTERVAL_MS} ms / judge {APP_SAMPLE_INTERVAL_MS} ms)",
             "modelSha256": model_sha(), "analysisLongSide": ANALYSIS_LONG_SIDE, "exercise": EXERCISE, "camera": args.camera,
             "sets": sorted(sets, key=lambda x: x["capture"]), "episodes": sorted(eps_index, key=lambda x: x["capture"])}
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

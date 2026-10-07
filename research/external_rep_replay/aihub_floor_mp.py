# -*- coding: utf-8 -*-
"""AIHub 바닥 종목 전 클립 → MediaPipe(앱 모델, IMAGE 모드) → 바닥 재생 캡처 (spec §99 양다리 판별의 모집단 근거).

실험 A(`research/aihub_fitness/mp_infer.py`)는 종목당 20클립만 추론해 뒀다. 판별 게이트의 입장 조건(모집단 정상 반복 기각 ≤ 2 %, 원칙 #7)을
재려면 그보다 많아야 해서, 고른 종목의 **모든 클립 × 카메라 5대**를 같은 함수(mp_infer._infer, tar 를 풀지 않고 오프셋으로 읽기)로 추론한다.

캡처(종목 × 카메라마다 하나): 클립 사이 5 s 틈, 프레임 250 ms(aihub_captures.py 와 같다). 바닥 경로 메타(floor=1, 실제 이미지 크기)를 적고
좌표는 진짜 정규화(x/w, y/h)로 쓴다 — 재생기 바닥 경로(FloorFeatureExtractor)가 픽셀 기하로 되돌린다.

    <venv(mediapipe==0.10.14, pandas)>/python aihub_floor_mp.py --exercise "라잉 레그 레이즈" --aihub <research/aihub_fitness/outputs> --out <폴더> [--workers 8]
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from multiprocessing import Pool
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "aihub_fitness"))
sys.path.insert(0, str(HERE))
import capture_format  # noqa: E402
import mp_infer  # noqa: E402

MODEL = HERE.parents[1] / "app" / "src" / "main" / "assets" / "posture" / "pose_landmarker_full.task"
FRAME_MS = 250
CLIP_GAP_MS = 5_000


def infer(aihub: Path, exercise: str, out: Path, workers: int) -> pd.DataFrame:
    clips = pd.read_parquet(aihub / "clips.parquet", columns=["clip_id", "exercise", "performer", "conditions_json"])
    clips = clips[clips.exercise == exercise]
    k2 = pd.read_parquet(aihub / "kp2d.parquet", columns=["img_key", "clip_id", "frame_idx", "view_letter"])
    k2 = k2[k2.clip_id.isin(set(clips.clip_id))].copy()
    k2["day"] = k2.img_key.str.split("/").str[0]
    tar_days = json.load(open(aihub / "mp" / "tar_days.json", encoding="utf-8"))
    day_tar = {d: Path(t) for t, d in tar_days.items() if not d.startswith("ERROR") and Path(t).exists()}
    cache = out / "landmarks.parquet"
    done = pd.read_parquet(cache) if cache.exists() else None
    todo = k2 if done is None else k2[~k2.img_key.isin(set(done.img_key))]
    missing_days = sorted(set(todo.day) - set(day_tar))
    print(f"[plan] {exercise}: 클립 {len(clips)}, 이미지 {len(k2):,} (남은 {len(todo):,}), tar 없는 날 {missing_days}", flush=True)
    rows = [] if done is None else [done]
    pool = Pool(processes=workers, initializer=mp_infer._init_worker, initargs=(str(MODEL),))
    t0 = time.time()
    for day, want in todo.groupby("day"):
        tar = day_tar.get(day)
        if tar is None:
            continue
        idx = mp_infer.build_index(tar, aihub / "mp" / f"index_{tar.stem}.parquet")
        got = list(pool.imap_unordered(mp_infer._infer, mp_infer.read_items(tar, idx, want), chunksize=4))
        rows.append(pd.DataFrame(got))
        print(f"  {day} ({tar.name}): {len(got)} 장, 누적 {time.time() - t0:.0f}s", flush=True)
        pd.concat(rows, ignore_index=True).to_parquet(cache, index=False)   # 중단해도 이어 하게
    pool.close()
    lm = pd.concat(rows, ignore_index=True) if rows else pd.DataFrame()
    return k2.merge(lm, on="img_key", how="inner").merge(clips, on="clip_id")


def landmarks(row) -> list[tuple] | None:
    if not row["detected"]:
        return None
    w, h = float(row["w"]), float(row["h"])
    return [(row[f"l{i}_x"] / w, row[f"l{i}_y"] / h, row[f"l{i}_v"], row[f"l{i}_p"],
             row[f"w{i}_x"], row[f"w{i}_y"], row[f"w{i}_z"]) for i in range(capture_format.LANDMARKS)]


def export(frames: pd.DataFrame, exercise: str, out: Path) -> None:
    for view, g in frames.groupby("view_letter"):
        order = sorted(g.clip_id.unique(), key=lambda c: (g[g.clip_id == c].performer.iloc[0], c))
        w = int(g[g.detected].w.mode().iloc[0]) if g.detected.any() else 1920
        h = int(g[g.detected].h.mode().iloc[0]) if g.detected.any() else 1080
        lines, meta, t = [], [], 0
        for k, cid in enumerate(order):
            cf = g[g.clip_id == cid].sort_values("frame_idx")
            for _, row in cf.iterrows():
                lines.append(capture_format.frame_line(t + int(row.frame_idx) * FRAME_MS, landmarks(row)))
            meta.append({"i": k, "clip_id": cid, "performer": cf.performer.iloc[0], "frames": int(len(cf)), "t0": t,
                         "conditions": {n: bool(v) for n, v in json.loads(cf.conditions_json.iloc[0])}})
            t += 16 * FRAME_MS + CLIP_GAP_MS
        cap = out / f"{view}.cap"
        capture_format.write_capture(cap, {"source": "aihub-mp-floor", "exercise": exercise, "floor": 1, "view": view,
                                           "imageWidth": w, "imageHeight": h, "frameMs": FRAME_MS}, lines)
        (out / f"{view}.clips.json").write_text(json.dumps({"exercise": exercise, "view": view, "clips": meta}, ensure_ascii=False, indent=1),
                                                encoding="utf-8")
        print(f"{exercise} · 카메라 {view}: 클립 {len(order)}, 검출 {int(g.detected.sum())}/{len(g)} → {cap}", flush=True)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--exercise", required=True)
    ap.add_argument("--aihub", type=Path, required=True, help="research/aihub_fitness/outputs (clips·kp2d·mp/tar_days·tar 인덱스)")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--workers", type=int, default=8)
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    export(infer(a.aihub, a.exercise, a.out, a.workers), a.exercise, a.out)
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())

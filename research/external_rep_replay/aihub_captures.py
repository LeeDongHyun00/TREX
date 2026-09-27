# -*- coding: utf-8 -*-
"""AIHub 피트니스 자세 클립(MediaPipe 결과) → 재생 캡처 — 반복 검사를 AIHub 조건 라벨로 채점하기 위해(계열 채점표, family_scorecard.py).

AIHub 클립 하나 = 반복 하나를 16장 키프레임으로 찍은 것이다. 실험 A 가 카메라 5대(A~E) 이미지에 MediaPipe(앱 모델)를 돌려 둔
`research/aihub_fitness/outputs/mp/landmarks_*.parquet` 를 앱 추론 한 번 = 캡처 한 줄(F)로 옮긴다.

- 종목 × 카메라마다 캡처 하나. 클립은 250 ms 간격 16프레임, 클립 사이 5 s 틈 — 재생기 `--clip-eval` 이 틈으로 클립을 나눠
  클립마다 새 검사기로 한 반복을 판정한다(사람 사이 기준이 섞이지 않게).
- 좌표: 재생기는 캡처에 이미지 크기가 없어 세로 480×640(폭÷높이 0.75)을 가정하고 x × 0.75 로 높이 단위를 만든다(Stance2d·Arm2d·Lunge2d).
  AIHub 는 가로 1920×1080 이라 x 를 x_px ÷ (0.75 × h) 로 적어 같은 등방 좌표가 되게 한다(x 가 1 을 넘을 수 있다 — 자르지 않는다).
- 월드 좌표는 MediaPipe 원값(m, y 아래) 그대로 — 재생기가 부호를 뒤집는다. up 벡터는 없다(카메라 수평 가정, MM-Fit·REHAB 과 같다).
- `clips.json` = 클립 순서(재생기 출력의 i)와 클립 id·수행자·카메라·AIHub 조건 라벨(충족 true / 위반 false).

주의: AIHub 는 스튜디오·연기자·키프레임이다. 여기서 얻는 것은 **조건 라벨이 있는 검출률의 1차 거름**이고, 정상 오탐률의 근거는
연속 영상(MM-Fit·REHAB)과 폰 세트다(원칙 #2 — 근거는 AIHub 에 한정하지 않는다).

사용: python aihub_captures.py --exercise "바벨 컬" [--exercise ...] --out <폴더>
"""
from __future__ import annotations

import argparse
import glob
import json
import sys
from pathlib import Path

import pandas as pd

import capture_format

HERE = Path(__file__).resolve().parent
AIHUB = HERE.parents[0] / "aihub_fitness" / "outputs"
FRAME_MS = 250
CLIP_GAP_MS = 5_000
REPLAY_ASPECT = 0.75          # 재생기가 가정하는 폭÷높이(Stance2d.DEFAULT_ASPECT)

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def load(exercises: list[str]) -> tuple[pd.DataFrame, pd.DataFrame]:
    """(프레임 표: 클립·카메라·프레임 번호 + 랜드마크, 클립 표) — 고른 종목만."""
    clips = pd.read_parquet(AIHUB / "clips.parquet", columns=["clip_id", "exercise", "performer", "conditions_json"])
    clips = clips[clips.exercise.isin(exercises)]
    k2 = pd.read_parquet(AIHUB / "kp2d.parquet", columns=["img_key", "clip_id", "frame_idx", "view_letter"])
    k2 = k2[k2.clip_id.isin(set(clips.clip_id))]
    keys = set(k2.img_key)
    parts = []
    for f in sorted(glob.glob(str(AIHUB / "mp" / "landmarks_*.parquet"))):
        d = pd.read_parquet(f)
        d = d[d.img_key.isin(keys)]
        if len(d):
            parts.append(d)
    if not parts:
        raise SystemExit(f"MediaPipe 결과에 {exercises} 클립이 없다 — research/aihub_fitness/mp_infer.py 먼저")
    lm = pd.concat(parts, ignore_index=True)
    return k2.merge(lm, on="img_key", how="inner"), clips


def landmarks(row) -> list[tuple] | None:
    if not row["detected"]:
        return None
    w, h = float(row["w"]), float(row["h"])
    out = []
    for i in range(capture_format.LANDMARKS):
        out.append((row[f"l{i}_x"] / (REPLAY_ASPECT * h), row[f"l{i}_y"] / h, row[f"l{i}_v"], row[f"l{i}_p"],
                    row[f"w{i}_x"], row[f"w{i}_y"], row[f"w{i}_z"]))
    return out


def export(exercises: list[str], out: Path) -> list[dict]:
    frames, clips = load(exercises)
    info = clips.set_index("clip_id")
    written = []
    for (ex, view), g in frames.merge(clips[["clip_id", "exercise"]], on="clip_id").groupby(["exercise", "view_letter"]):
        order = sorted(g.clip_id.unique(), key=lambda c: (info.at[c, "performer"], c))
        lines, meta_clips, t = [], [], 0
        for k, cid in enumerate(order):
            cf = g[g.clip_id == cid].sort_values("frame_idx")
            for _, row in cf.iterrows():
                lines.append(capture_format.frame_line(t + int(row.frame_idx) * FRAME_MS, landmarks(row)))
            meta_clips.append({"i": k, "clip_id": cid, "performer": info.at[cid, "performer"], "view": view,
                               "frames": int(len(cf)), "conditions": {n: bool(v) for n, v in json.loads(info.at[cid, "conditions_json"])}})
            t += 16 * FRAME_MS + CLIP_GAP_MS
        d = out / ex
        cap = d / f"{view}.cap"
        capture_format.write_capture(cap, {"source": "aihub-mp", "exercise": ex, "view": view, "frameMs": FRAME_MS}, lines)
        (d / f"{view}.clips.json").write_text(json.dumps({"exercise": ex, "view": view, "clips": meta_clips}, ensure_ascii=False, indent=1),
                                               encoding="utf-8")
        written.append({"exercise": ex, "view": view, "capture": str(cap), "clips": len(order)})
        print(f"{ex} · 카메라 {view}: 클립 {len(order)} → {cap}")
    return written


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--exercise", action="append", required=True, help="AIHub 종목 이름(여러 번)")
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    export(a.exercise, a.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

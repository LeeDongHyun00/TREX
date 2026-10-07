# -*- coding: utf-8 -*-
"""바닥 계열(플랭크·크런치·라잉 레그 레이즈) 재생 캡처 — AIHub 바닥 C·E 클립과 MM-Fit 윗몸일으키기(spec §99, docs/FLOOR_FAMILY_DESIGN.md §7.1 b·c·d).

입력(로컬, gitignore — 메인 체크아웃 `data/`·`research/aihub_fitness/outputs/`):
  - AIHub: `data/floor_family/aihub_local_agent/mp_full/landmarks_body_*_CE.parquet`(바닥 3종목 C·E 이미지에 앱과 같은 MediaPipe full 모델,
    `run_mp_floor.py`) + `research/aihub_fitness/outputs/{kp2d,clips}.parquet`(이미지 → 클립·프레임 번호·뷰, 수행자·유형·조건 라벨).
  - MM-Fit: `data/floor_family/rel_designer/m2_situps.parquet`(로컬 RGB 5명 15세트를 앱 모델로 100 ms 간격 추론, 앞뒤 6 s 여유 — `m2_extract.py`).
    정답 횟수·세트 구간은 그 스크립트가 `data/mm-fit/mm-fit/w*/w*_labels.csv` 에서 옮긴 `reps`·`in_set` 그대로.

출력(`data/floor_family/replay/` 아래, capture_format 형식 — 재생기 `replay-jvm` 바닥 경로가 읽는다):
  - `aihub/<종목>/<뷰>.cap` + `<뷰>.clips.json` — 종목 × 뷰마다 캡처 하나. 클립(16 키프레임)은 **600 ms 간격 가정**(AIHub 에 시각 정보가 없다 — 메타 frameMs=600,
    timing=assumed), 클립 사이 5 s 틈(재생기 `--floor-clips` 가 틈으로 나눠 클립마다 새 카운터·시계로 돈다). 좌표는 정규화(x ÷ 1920, y ÷ 1080) 그대로이고
    메타 imageW/imageH 로 앱처럼 px 등방화한다. clips.json = 클립 순서(i)·클립 id·수행자·유형(type_key)·유형 설명·뷰·프레임 수·AIHub 조건 라벨(충족 true / 위반 false).
  - `aihub_up/…` — 같은 클립에 U 줄 (0, 1, 0)(화면 위 = 중력 위 가정, 메타 upAssumed=screen). AIHub 카메라는 서 있는 높이에 수평으로 세워져 있어 롤이 0 이라는 가정 —
    앱처럼 중력이 있는 경로(누운 영역 게이트·플랭크 not_prone)를 돌려 보기 위한 변형이다. 무변형(`aihub/`)은 설계 §4.2 '중력 모름 → 게이트 없이' 경로.
  - `aihub_up_trim/…` — U 줄 + 클립의 앞 2·뒤 4 키프레임을 뺀 것(진입·이탈 제외 — 설계 §3.3 `knee_trim.py` 와 같은 자르기).
  - `mmfit/<w>_s<k>.cap`·`mmfit_up/…` — 세트마다 하나. 100 ms 추출을 앱 판정 격자(300 ms 칸마다 첫 프레임, `t_ms // 300`)로 고르고 첫 칸을 0 ms 로 옮긴다.
    가시성만 있고 presence·월드 좌표는 없다(nan — 재생기는 없는 presence 를 1 로 본다, 바닥 경로는 월드 좌표를 쓰지 않는다). 메타 truthReps·setStartMs·setEndMs(세트 구간).
  - `index.json` — 위 캡처 목록.

사용: python aihub_floor_captures.py [--out <폴더>] [--only aihub|mmfit]
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow.dataset as ds

import capture_format

HERE = Path(__file__).resolve().parent
EXERCISES = ["플랭크", "크런치", "라잉 레그 레이즈"]
VIEWS = ["C", "E"]
AIHUB_FRAME_MS = 600          # 가정 — AIHub 키프레임에는 시각이 없다(설계 §1.2)
CLIP_GAP_MS = 5_000
TRIM = (2, 4)                 # 진입·이탈 제외: 앞 2·뒤 4 키프레임(설계 §3.3)
GRID_MS = capture_format.APP_SAMPLE_INTERVAL_MS   # 앱 판정 격자 300 ms(SESSION_SAMPLE_INTERVAL_MS)
SCREEN_UP = (0.0, 1.0, 0.0)

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def main_checkout() -> Path:
    """git worktree 에서도 로컬 데이터(gitignore)가 있는 메인 체크아웃을 찾는다."""
    try:
        common = subprocess.run(["git", "-C", str(HERE), "rev-parse", "--path-format=absolute", "--git-common-dir"],
                                capture_output=True, text=True, check=True).stdout.strip()
        return Path(common).parent
    except (OSError, subprocess.CalledProcessError):
        return HERE.parents[1]


ROOT = main_checkout()
DATA = ROOT / "data" / "floor_family"
AIHUB_OUT = ROOT / "research" / "aihub_fitness" / "outputs"
DEFAULT_OUT = DATA / "replay"


# ---------------------------------------------------------------- AIHub

def load_aihub() -> tuple[pd.DataFrame, pd.DataFrame]:
    """(프레임 표: 클립·뷰·프레임 번호 + 랜드마크, 클립 표)."""
    clips = pd.read_parquet(AIHUB_OUT / "clips.parquet", columns=["clip_id", "exercise", "performer", "type_key", "description", "conditions_json"])
    clips = clips[clips.exercise.isin(EXERCISES)]
    k = ds.dataset(AIHUB_OUT / "kp2d.parquet").to_table(columns=["clip_id", "frame_idx", "view_letter", "img_key"],
                                                        filter=ds.field("clip_id").isin(list(clips.clip_id))).to_pandas()
    k = k[k.view_letter.isin(VIEWS)]
    files = sorted((DATA / "aihub_local_agent" / "mp_full").glob("landmarks_*.parquet"))
    if not files:
        raise SystemExit(f"MediaPipe 결과가 없다: {DATA / 'aihub_local_agent' / 'mp_full'} — run_mp_floor.py 먼저")
    lm = pd.concat([pd.read_parquet(f) for f in files], ignore_index=True)
    return k.merge(lm, on="img_key", how="inner"), clips


def aihub_landmarks(row: dict) -> list[tuple] | None:
    if not row["detected"]:
        return None
    # mp_full 에는 월드 좌표가 없다(nan) — 바닥 경로는 이미지 좌표·가시성만 쓴다
    w, h = float(row["w"]), float(row["h"])
    return [(row[f"l{i}_x"] / w, row[f"l{i}_y"] / h, row[f"l{i}_v"], row[f"l{i}_p"], None, None, None)
            for i in range(capture_format.LANDMARKS)]


def conditions(text: str) -> dict[str, bool]:
    out = {}
    for name, v in json.loads(text):
        # AIHub 라벨 이름의 띄어쓰기 변형(…충분히올라옴)을 하나로
        out[name.replace("충분히올라옴", "충분히 올라옴")] = bool(v)
    return out


def export_aihub(out: Path) -> list[dict]:
    frames, clips = load_aihub()
    info = clips.set_index("clip_id")
    written = []
    variants = {"aihub": (False, None), "aihub_up": (True, None), "aihub_up_trim": (True, TRIM)}
    for (ex, view), g in frames.merge(clips[["clip_id", "exercise"]], on="clip_id").groupby(["exercise", "view_letter"]):
        order = sorted(g.clip_id.unique(), key=lambda c: (info.at[c, "performer"], c))
        sizes = g[g.detected][["w", "h"]].drop_duplicates()
        if len(sizes) != 1:
            raise SystemExit(f"{ex} {view}: 이미지 크기가 하나가 아니다 {sizes.to_dict('records')}")
        W, H = int(sizes.iloc[0].w), int(sizes.iloc[0].h)
        by_clip = {cid: d.sort_values("frame_idx").to_dict("records") for cid, d in g.groupby("clip_id")}
        for name, (up, trim) in variants.items():
            lines, meta_clips, t = [], [], 0
            for k, cid in enumerate(order):
                rows = by_clip[cid]
                if trim is not None:
                    n = max(int(r["frame_idx"]) for r in rows) + 1
                    rows = [r for r in rows if trim[0] <= int(r["frame_idx"]) < n - trim[1]]
                for r in rows:
                    tt = t + int(r["frame_idx"]) * AIHUB_FRAME_MS
                    lm = aihub_landmarks(r)
                    if up and lm is not None:
                        lines.append(capture_format.up_line(tt, SCREEN_UP))
                    lines.append(capture_format.frame_line(tt, lm))
                meta_clips.append({"i": k, "clip_id": cid, "performer": info.at[cid, "performer"], "type_key": str(info.at[cid, "type_key"]),
                                   "description": str(info.at[cid, "description"]), "view": view, "frames": len(rows),
                                   "detected": int(sum(bool(r["detected"]) for r in rows)),
                                   "conditions": conditions(info.at[cid, "conditions_json"])})
                t += 16 * AIHUB_FRAME_MS + CLIP_GAP_MS
            d = out / name / ex
            cap = d / f"{view}.cap"
            meta = {"source": "aihub-mp-floor", "exercise": ex, "floor": "1", "view": view, "frameMs": AIHUB_FRAME_MS, "timing": "assumed",
                    "imageW": W, "imageH": H, "upAssumed": "screen" if up else "none",
                    "trim": f"{trim[0]},{trim[1]}" if trim else "none"}
            capture_format.write_capture(cap, meta, lines)
            (d / f"{view}.clips.json").write_text(json.dumps({"exercise": ex, "view": view, "variant": name, "clips": meta_clips},
                                                             ensure_ascii=False, indent=1), encoding="utf-8")
            written.append({"set": "aihub", "variant": name, "exercise": ex, "view": view, "capture": str(cap.relative_to(out)),
                            "clipsJson": str((d / f"{view}.clips.json").relative_to(out)), "clips": len(order)})
            print(f"{name} · {ex} · {view}: 클립 {len(order)} → {cap}")
    return written


# ---------------------------------------------------------------- MM-Fit

def mmfit_landmarks(row: dict) -> list[tuple] | None:
    if not np.isfinite(row.get("x0", np.nan)):
        return None
    return [(row[f"x{i}"], row[f"y{i}"], row[f"v{i}"], None, None, None, None) for i in range(capture_format.LANDMARKS)]


def export_mmfit(out: Path) -> list[dict]:
    path = DATA / "rel_designer" / "m2_situps.parquet"
    df = pd.read_parquet(path)
    written = []
    for (w, k), d in df.groupby(["w", "set"]):
        d = d.sort_values("t_ms")
        d = d.assign(bin=d.t_ms // GRID_MS).groupby("bin").head(1)   # 앱 판정 격자 — 칸마다 첫 프레임
        t0 = int(d.t_ms.iloc[0])
        ins = d[d.in_set]
        s0, s1 = int(ins.t_ms.min()) - t0, int(ins.t_ms.max()) - t0
        W, H = int(d.W.iloc[0]), int(d.H.iloc[0])
        recs = d.to_dict("records")
        for name, up in (("mmfit", False), ("mmfit_up", True)):
            lines = []
            for r in recs:
                tt = int(r["t_ms"]) - t0
                lm = mmfit_landmarks(r)
                if up and lm is not None:
                    lines.append(capture_format.up_line(tt, SCREEN_UP))
                lines.append(capture_format.frame_line(tt, lm))
            cap = out / name / f"{w}_s{k}.cap"
            meta = {"source": "mmfit-mp", "exercise": "크런치", "floor": "1", "subject": w, "set": int(k), "truthReps": int(d.reps.iloc[0]),
                    "setStartMs": s0, "setEndMs": s1, "frameMs": GRID_MS, "extractMs": 100, "imageW": W, "imageH": H,
                    "upAssumed": "screen" if up else "none", "movement": "situps"}
            capture_format.write_capture(cap, meta, lines)
            written.append({"set": "mmfit", "variant": name, "subject": w, "setIndex": int(k), "capture": str(cap.relative_to(out)),
                            "truthReps": int(d.reps.iloc[0]), "setStartMs": s0, "setEndMs": s1, "frames": len(recs)})
        print(f"MM-Fit {w} 세트 {k}: 격자 프레임 {len(recs)}, 정답 {int(d.reps.iloc[0])}, 세트 구간 {s0}~{s1} ms")
    return written


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--only", choices=["aihub", "mmfit"])
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    idx_path = a.out / "index.json"
    old = json.loads(idx_path.read_text(encoding="utf-8")) if idx_path.exists() else {"captures": []}
    keep = [c for c in old.get("captures", []) if a.only and c["set"] != a.only]
    new = []
    if a.only in (None, "aihub"):
        new += export_aihub(a.out)
    if a.only in (None, "mmfit"):
        new += export_mmfit(a.out)
    idx_path.write_text(json.dumps({"root": str(a.out), "captures": keep + new}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"index → {idx_path} ({len(keep) + len(new)} 캡처)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

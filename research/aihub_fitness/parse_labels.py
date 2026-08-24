#!/usr/bin/env python
"""AIHub '피트니스 자세 이미지' 라벨 파서 (1번 파서).

입력 구조:
  <label_root>/<카테고리>_Labeling*/<DayXX_yymmdd_F>/<clip>.json      (2D: 16프레임 x 5뷰 x 24관절 + type_info)
  <label_root>/<카테고리>_Labeling*/<DayXX_yymmdd_F>/<clip>-3d.json   (3D: 16프레임 x 24관절, cm 월드좌표)

출력 (--out 디렉터리):
  clips.parquet        1행 = 1클립. 종목/카테고리/수행자/조건 JSON 등 메타
  conditions.parquet   1행 = 클립 x 조건 (long format, value=bool)
  kp3d.parquet         1행 = 클립 x 프레임, 컬럼 = <관절>_{x,y,z} (float32, cm)
  kp2d.parquet         1행 = 클립 x 프레임 x 뷰, 컬럼 = <관절>_{x,y} (int16 px) + active + img_key
  joints.json          관절 순서(24)
  parse_report.json    파싱 통계 (프레임/뷰 분포, 종목별 조건 스키마, 수행자 수, 미매칭 파일 등)

사용:
  python parse_labels.py --label-root "<...>/1.Training/라벨링데이터" --out outputs [--workers 10] [--limit 500]
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from collections import Counter
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

DEFAULT_LABEL_ROOT = r"C:\Users\hp276\Desktop\trex\data\013.피트니스자세\1.Training\라벨링데이터"

# 데이터셋의 24관절 (JSON 등장 순서 기준 고정)
JOINTS = [
    "Nose", "Left Eye", "Right Eye", "Left Ear", "Right Ear",
    "Left Shoulder", "Right Shoulder", "Left Elbow", "Right Elbow",
    "Left Wrist", "Right Wrist", "Left Hip", "Right Hip",
    "Left Knee", "Right Knee", "Left Ankle", "Right Ankle",
    "Neck", "Left Palm", "Right Palm", "Back", "Waist",
    "Left Foot", "Right Foot",
]
N_J = len(JOINTS)


def short(j: str) -> str:
    return j.replace("Left ", "L").replace("Right ", "R").replace(" ", "")


JOINTS_SHORT = [short(j) for j in JOINTS]
KP3D_COLS = [f"{j}_{a}" for j in JOINTS_SHORT for a in "xyz"]
KP2D_COLS = [f"{j}_{a}" for j in JOINTS_SHORT for a in "xy"]

PERF_RE = re.compile(r"-(Z\d+)_")
CODES_RE = re.compile(r"/(\d+)-(\d+)-(\d+)-(\d+)-Z\d+_")
VIEW_LETTER_RE = re.compile(r"/([A-Z])/")


def norm_text(s) -> str:
    return " ".join(str(s).split())


def parse_clip(path2d_str: str):
    """하나의 2D JSON(+짝 3D JSON)을 파싱해 컴팩트한 numpy/dict로 반환."""
    path2d = Path(path2d_str)
    with open(path2d, encoding="utf-8") as f:
        d = json.load(f)
    frames = d.get("frames", []) or []
    ti = d.get("type_info", {}) or {}
    n_frames = len(frames)
    views = sorted({k for fr in frames for k in fr.keys()})
    n_views = len(views)

    kp2d = np.full((n_frames, n_views, N_J, 2), -1, dtype=np.int16)
    active = np.zeros((n_frames, n_views), dtype=bool)
    img_keys = [["" for _ in range(n_views)] for _ in range(n_frames)]
    view_letters = ["" for _ in range(n_views)]
    for fi, fr in enumerate(frames):
        for vi, v in enumerate(views):
            vd = fr.get(v)
            if not vd:
                continue
            pts = vd.get("pts", {}) or {}
            for ji, j in enumerate(JOINTS):
                p = pts.get(j)
                if p is not None:
                    kp2d[fi, vi, ji, 0] = int(p["x"])
                    kp2d[fi, vi, ji, 1] = int(p["y"])
            active[fi, vi] = vd.get("active") == "Yes"
            k = vd.get("img_key", "") or ""
            img_keys[fi][vi] = k
            if not view_letters[vi] and k:
                m = VIEW_LETTER_RE.search(k)
                view_letters[vi] = m.group(1) if m else ""

    path3d = path2d.with_name(path2d.stem + "-3d.json")
    kp3d = None
    if path3d.exists():
        with open(path3d, encoding="utf-8") as f:
            d3 = json.load(f)
        fr3 = d3.get("frames", []) or []
        kp3d = np.full((len(fr3), N_J, 3), np.nan, dtype=np.float32)
        for fi, fr in enumerate(fr3):
            pts = fr.get("pts", {}) or {}
            for ji, j in enumerate(JOINTS):
                p = pts.get(j)
                if p is not None:
                    kp3d[fi, ji] = (p["x"], p["y"], p["z"])

    first_key = next((k for row in img_keys for k in row if k), "")
    m = PERF_RE.search(first_key)
    performer = m.group(1) if m else ""
    codes = ("", "", "")
    m2 = CODES_RE.search(first_key)
    if m2:
        codes = (m2.group(2), m2.group(3), m2.group(4))

    conds = [(norm_text(c.get("condition", "")), bool(c.get("value"))) for c in (ti.get("conditions") or [])]

    meta = dict(
        clip_id=path2d.stem,
        day=path2d.parent.name,
        category_dir=path2d.parent.parent.name,
        type_key=str(d.get("type", ti.get("key", ""))),
        category=norm_text(ti.get("type", "")),
        pose=norm_text(ti.get("pose", "")),
        exercise=norm_text(ti.get("exercise", "")),
        description=str(ti.get("description", "")),
        n_frames=n_frames,
        n_views=n_views,
        view_letters="".join(view_letters),
        has_3d=kp3d is not None,
        n_frames_3d=0 if kp3d is None else int(kp3d.shape[0]),
        performer=performer,
        code_a=codes[0], code_b=codes[1], code_c=codes[2],
        n_conditions=len(conds),
        conditions_json=json.dumps(conds, ensure_ascii=False),
        kp3d_nan_frac=(float(np.isnan(kp3d).mean()) if kp3d is not None and kp3d.size else None),
        kp2d_missing_frac=float((kp2d < 0).mean()) if kp2d.size else None,
        active_frac=float(active.mean()) if active.size else None,
        path2d=str(path2d),
    )
    return meta, conds, kp2d, active, img_keys, view_letters, kp3d


class ParquetChunkWriter:
    """메모리 제한을 위해 row-group 단위로 parquet을 스트리밍 기록."""

    def __init__(self, path: Path):
        self.path = path
        self.writer = None
        self.rows = 0

    def write(self, df: pd.DataFrame):
        if df.empty:
            return
        table = pa.Table.from_pandas(df, preserve_index=False)
        if self.writer is None:
            self.writer = pq.ParquetWriter(str(self.path), table.schema, compression="zstd")
        self.writer.write_table(table)
        self.rows += len(df)

    def close(self):
        if self.writer is not None:
            self.writer.close()


def flush_kp3d(buf, writer: ParquetChunkWriter):
    if not buf:
        return
    ids, arrs = [], []
    for clip_id, kp3d in buf:
        n = kp3d.shape[0]
        ids.extend([(clip_id, i) for i in range(n)])
        arrs.append(kp3d.reshape(n, N_J * 3))
    df = pd.DataFrame(np.concatenate(arrs, axis=0), columns=KP3D_COLS)
    df.insert(0, "frame_idx", np.array([i for _, i in ids], dtype=np.int16))
    df.insert(0, "clip_id", [c for c, _ in ids])
    writer.write(df)
    buf.clear()


def flush_kp2d(buf, writer: ParquetChunkWriter):
    if not buf:
        return
    clip_col, fidx_col, view_col, vl_col, act_col, key_col, arrs = [], [], [], [], [], [], []
    for clip_id, kp2d, active, img_keys, view_letters in buf:
        n_f, n_v = kp2d.shape[0], kp2d.shape[1]
        for fi in range(n_f):
            for vi in range(n_v):
                clip_col.append(clip_id)
                fidx_col.append(fi)
                view_col.append(vi + 1)
                vl_col.append(view_letters[vi])
                act_col.append(bool(active[fi, vi]))
                key_col.append(img_keys[fi][vi])
        arrs.append(kp2d.reshape(n_f * n_v, N_J * 2))
    df = pd.DataFrame(np.concatenate(arrs, axis=0), columns=KP2D_COLS)
    df.insert(0, "img_key", key_col)
    df.insert(0, "active", np.array(act_col, dtype=bool))
    df.insert(0, "view_letter", vl_col)
    df.insert(0, "view", np.array(view_col, dtype=np.int8))
    df.insert(0, "frame_idx", np.array(fidx_col, dtype=np.int16))
    df.insert(0, "clip_id", clip_col)
    writer.write(df)
    buf.clear()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label-root", default=DEFAULT_LABEL_ROOT)
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent / "outputs"))
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    ap.add_argument("--limit", type=int, default=0, help="테스트용: 앞에서 N개 클립만 파싱")
    ap.add_argument("--skip-2d", action="store_true", help="kp2d.parquet 생략(빠른 실험용)")
    ap.add_argument("--chunk", type=int, default=1500, help="parquet row-group당 클립 수")
    args = ap.parse_args()

    root = Path(args.label_root)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    t0 = time.time()
    files2d = sorted(p for p in root.rglob("*.json") if not p.name.endswith("-3d.json"))
    files2d_set = set(files2d)
    files3d_as2d = {p.with_name(p.name[:-len("-3d.json")] + ".json") for p in root.rglob("*-3d.json")}
    unmatched_3d = sorted(str(p) for p in files3d_as2d if p not in files2d_set)
    if args.limit:
        files2d = files2d[: args.limit]
    print(f"[scan] 2D 라벨 {len(files2d)}개, 3D 라벨 {len(files3d_as2d)}개, 2D 짝 없는 3D {len(unmatched_3d)}개 ({time.time()-t0:.1f}s)", flush=True)

    metas, cond_rows = [], []
    kp3d_buf, kp2d_buf = [], []
    w3d = ParquetChunkWriter(out / "kp3d.parquet")
    w2d = None if args.skip_2d else ParquetChunkWriter(out / "kp2d.parquet")
    n_done = 0
    t1 = time.time()
    with ProcessPoolExecutor(max_workers=args.workers) as ex:
        for meta, conds, kp2d, active, img_keys, view_letters, kp3d in ex.map(parse_clip, [str(p) for p in files2d], chunksize=16):
            metas.append(meta)
            for ci, (cname, val) in enumerate(conds):
                cond_rows.append((meta["clip_id"], meta["exercise"], ci, cname, val))
            if kp3d is not None:
                kp3d_buf.append((meta["clip_id"], kp3d))
            if w2d is not None:
                kp2d_buf.append((meta["clip_id"], kp2d, active, img_keys, view_letters))
            n_done += 1
            if len(kp3d_buf) >= args.chunk:
                flush_kp3d(kp3d_buf, w3d)
            if w2d is not None and len(kp2d_buf) >= args.chunk:
                flush_kp2d(kp2d_buf, w2d)
            if n_done % 2000 == 0:
                el = time.time() - t1
                print(f"[parse] {n_done}/{len(files2d)}  {el:.0f}s  ({n_done/max(el,1e-6):.0f} clip/s)", flush=True)
    flush_kp3d(kp3d_buf, w3d)
    w3d.close()
    if w2d is not None:
        flush_kp2d(kp2d_buf, w2d)
        w2d.close()

    clips = pd.DataFrame(metas)
    clips.to_parquet(out / "clips.parquet", index=False)
    conds_df = pd.DataFrame(cond_rows, columns=["clip_id", "exercise", "cond_idx", "condition", "value"])
    conds_df.to_parquet(out / "conditions.parquet", index=False)
    with open(out / "joints.json", "w", encoding="utf-8") as f:
        json.dump({"joints": JOINTS, "joints_short": JOINTS_SHORT, "kp3d_cols": KP3D_COLS, "kp2d_cols": KP2D_COLS}, f, ensure_ascii=False, indent=1)

    # ---- 리포트 ----
    ex_tab = []
    for ex_name, g in clips.groupby("exercise"):
        schemas = Counter()
        for cj in g["conditions_json"]:
            schemas[tuple(c for c, _ in json.loads(cj))] += 1
        main_schema = schemas.most_common(1)[0][0] if schemas else ()
        ex_tab.append(dict(
            exercise=ex_name,
            category=g["category"].mode().iat[0] if len(g) else "",
            n_clips=int(len(g)),
            n_types=int(g["type_key"].nunique()),
            n_performers=int(g["performer"].nunique()),
            n_days=int(g["day"].nunique()),
            conditions=list(main_schema),
            n_schema_variants=len(schemas),
        ))
    ex_tab.sort(key=lambda r: -r["n_clips"])
    cond_balance = (conds_df.groupby(["exercise", "condition"])["value"].agg(["count", "mean"]).reset_index()
                    .rename(columns={"count": "n", "mean": "pos_rate"}))
    report = dict(
        label_root=str(root),
        n_clips=int(len(clips)),
        n_with_3d=int(clips["has_3d"].sum()),
        n_unmatched_3d_files=len(unmatched_3d),
        unmatched_3d_examples=unmatched_3d[:20],
        n_frames_dist={str(k): int(v) for k, v in clips["n_frames"].value_counts().items()},
        n_frames_3d_dist={str(k): int(v) for k, v in clips["n_frames_3d"].value_counts().items()},
        n_views_dist={str(k): int(v) for k, v in clips["n_views"].value_counts().items()},
        view_letters_dist={str(k): int(v) for k, v in clips["view_letters"].value_counts().items()},
        n_exercises=int(clips["exercise"].nunique()),
        n_types=int(clips["type_key"].nunique()),
        n_performers=int(clips["performer"].nunique()),
        performers_top=[(k, int(v)) for k, v in clips["performer"].value_counts().head(80).items()],
        category_dist={str(k): int(v) for k, v in clips["category"].value_counts().items()},
        kp3d_nan_frac_mean=(float(clips["kp3d_nan_frac"].dropna().mean()) if clips["kp3d_nan_frac"].notna().any() else None),
        kp2d_missing_frac_mean=float(clips["kp2d_missing_frac"].dropna().mean()),
        active_frac_mean=float(clips["active_frac"].dropna().mean()),
        exercises=ex_tab,
        condition_balance=cond_balance.to_dict(orient="records"),
        elapsed_sec=round(time.time() - t0, 1),
        rows_kp3d=w3d.rows,
        rows_kp2d=(w2d.rows if w2d else 0),
    )
    with open(out / "parse_report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1, default=str)

    print(f"\n[done] 클립 {len(clips)} | 3D 보유 {report['n_with_3d']} | 종목 {report['n_exercises']} | 타입 {report['n_types']} | 수행자 {report['n_performers']} | {report['elapsed_sec']}s")
    print(f"       프레임 분포 {report['n_frames_dist']} | 뷰 분포 {report['n_views_dist']} | 뷰문자 {report['view_letters_dist']}")
    print(f"       kp3d rows {w3d.rows} | kp2d rows {report['rows_kp2d']} | 출력: {out}")


if __name__ == "__main__":
    main()

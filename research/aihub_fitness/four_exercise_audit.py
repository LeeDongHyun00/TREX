#!/usr/bin/env python
"""네 종목 설계용 로컬 데이터 감사. 원본을 수정하지 않고 보유량과 누락만 기록한다."""
from __future__ import annotations

import argparse
import json
import sys
import zipfile
from collections import Counter
from pathlib import Path

import pandas as pd
import pyarrow.parquet as pq

EXERCISES = ("크로스 런지", "사이드 런지", "스탠딩 니업", "스탠딩 사이드 크런치")


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--data-root", type=Path, required=True)
    ap.add_argument("--derived-root", type=Path, required=True)
    ap.add_argument("--rules", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    clips = pd.read_parquet(args.derived_root / "clips.parquet")
    target_clips = clips[clips.exercise.isin(EXERCISES)]
    wanted_labels = {Path(row.path2d).name: row.exercise for row in target_clips.itertuples()}
    wanted_3d = {Path(name).stem + "-3d.json": ex for name, ex in wanted_labels.items()}
    labels_in_zip, labels3d_in_zip, zip_samples = {}, {}, {}
    body_labels = args.data_root / "013.피트니스자세" / "1.Training" / "라벨링데이터" / "맨몸운동_Labeling_new_220128"
    kp2d = pd.read_parquet(args.derived_root / "kp2d.parquet", columns=["clip_id", "img_key"],
                          filters=[("clip_id", "in", target_clips.clip_id.tolist())])
    clip_ex = dict(zip(target_clips.clip_id, target_clips.exercise))
    wanted_images = {row.img_key: clip_ex[row.clip_id] for row in kp2d.itertuples() if row.img_key}
    images_in_zip, zip_summary = {}, []
    for p in sorted(body_labels.glob("*.zip")):
        count2, count3, count_img = Counter(), Counter(), Counter()
        with zipfile.ZipFile(p) as z:
            for entry in z.infolist():
                name = Path(entry.filename).name
                if name in wanted_labels:
                    ex = wanted_labels[name]
                    labels_in_zip[name] = (str(p), entry.filename)
                    count2[ex] += 1
                    if ex not in zip_samples:
                        zip_samples[ex] = (f"{p}!{entry.filename}", json.loads(z.read(entry)))
                if name in wanted_3d:
                    ex = wanted_3d[name]
                    labels3d_in_zip[name] = (str(p), entry.filename)
                    count3[ex] += 1
                if entry.filename.lower().endswith(".jpg"):
                    key = entry.filename.replace("\\", "/").lstrip("/")
                    if not key.startswith("Day"):
                        key = p.stem + "/" + key
                    if key in wanted_images:
                        images_in_zip[key] = str(p)
                        count_img[wanted_images[key]] += 1
        zip_summary.append({"name": p.name, "bytes": p.stat().st_size,
                            "target_2d": dict(count2), "target_3d": dict(count3), "target_jpg": dict(count_img)})
    cached_images = {}
    for p in sorted((args.derived_root / "mp").glob("landmarks_*.parquet")):
        lm = pd.read_parquet(p, columns=["img_key", "detected"])
        for row in lm.itertuples():
            if row.img_key in wanted_images:
                cached_images[row.img_key] = bool(row.detected)
    mp_path = args.derived_root / "expA_features_mp.parquet"
    mp = pd.read_parquet(mp_path) if mp_path.exists() else None
    rules = json.loads(args.rules.read_text(encoding="utf-8"))["rules"]
    result = {"data_root": str(args.data_root.resolve()),
              "derived_root": str(args.derived_root.resolve()),
              "index_note": "기존 Training 색인. 현재 원본 보유량과 과거 분석된 양을 구분한다.",
              "exercises": {}}
    for ex in EXERCISES:
        group = clips[clips.exercise == ex]
        existing = group[group.path2d.map(lambda s: Path(s).is_file())]
        conditions = {}
        all_ok = 0
        for raw in group.conditions_json:
            cs = json.loads(raw)
            all_ok += bool(cs) and all(v for _, v in cs)
            for name, value in cs:
                item = conditions.setdefault(name, {"true": 0, "false": 0})
                item["true" if value else "false"] += 1
        item = {"indexed_clips": len(group), "indexed_performers": int(group.performer.nunique()),
                "indexed_frames": int(group.n_frames.sum()), "indexed_3d_clips": int(group.has_3d.sum()),
                "all_conditions_true": int(all_ok), "conditions": conditions,
                "current_raw_2d_clips": len(existing),
                "current_raw_3d_clips": int(sum(Path(p).with_name(Path(p).stem + "-3d.json").is_file() for p in existing.path2d)),
                "current_raw_performers": int(existing.performer.nunique()),
                "raw_2d_in_zip": sum(name in labels_in_zip for name, e in wanted_labels.items() if e == ex),
                "raw_3d_in_zip": sum(name in labels3d_in_zip for name, e in wanted_3d.items() if e == ex),
                "indexed_image_keys": sum(e == ex for e in wanted_images.values()),
                "raw_jpg_in_zip": sum(wanted_images[k] == ex for k in images_in_zip),
                "cached_mp_image_rows": sum(wanted_images[k] == ex for k in cached_images),
                "cached_mp_detected_images": sum(wanted_images[k] == ex and v for k, v in cached_images.items()),
                "sample_not_unpacked_path": next((p for p in group.path2d if not Path(p).is_file()), None),
                "rules": [r for r in rules if r.get("exercise") == ex]}
        if not existing.empty:
            sample = Path(existing.iloc[0].path2d)
            raw = json.loads(sample.read_text(encoding="utf-8"))
            frames = raw.get("frames", [])
            item["sample"] = {"path": str(sample), "type_info": raw.get("type_info"),
                              "frame_count": len(frames), "first_frame": frames[0] if frames else None}
        elif ex in zip_samples:
            sample, raw = zip_samples[ex]
            frames = raw.get("frames", [])
            item["sample"] = {"path": sample, "type_info": raw.get("type_info"),
                              "frame_count": len(frames), "first_frame": frames[0] if frames else None}
        current_names = set(Path(p).name for p in existing.path2d) | {n for n, e in wanted_labels.items() if e == ex and n in labels_in_zip}
        item["current_raw_2d_union"] = len(current_names)
        current3 = {Path(p).stem + "-3d.json" for p in existing.path2d
                    if Path(p).with_name(Path(p).stem + "-3d.json").is_file()}
        current3 |= {n for n, e in wanted_3d.items() if e == ex and n in labels3d_in_zip}
        item["current_raw_3d_union"] = len(current3)
        item["current_raw_union_performers"] = int(group[group.path2d.map(lambda p: Path(p).name in current_names)].performer.nunique())
        if mp is not None:
            mg = mp[mp.exercise == ex] if "exercise" in mp.columns else mp[mp.clip_id.isin(group.clip_id)]
            item["cached_mp_rows"] = len(mg)
            item["cached_mp_clips"] = int(mg.clip_id.nunique()) if "clip_id" in mg else None
            item["cached_mp_columns"] = list(mg.columns)
        result["exercises"][ex] = item
    result["derived_schemas"] = {name: str(pq.read_schema(args.derived_root / name))
                                  for name in ("kp3d.parquet", "kp2d.parquet", "expA_features_mp.parquet")
                                  if (args.derived_root / name).exists()}
    if mp is not None:
        ids = clips[(clips.exercise == "스탠딩 니업") & clips.conditions_json.map(
            lambda s: all(v for _, v in json.loads(s)))].clip_id
        normal = mp[mp.clip_id.isin(ids)].copy()
        normal["mean_to_side_range"] = normal["hip_mean__range"] / normal[["hip_L__range", "hip_R__range"]].max(axis=1)
        result["exploratory_niup_mean_damping"] = {
            "note": "개발 표본의 신호 크기 비교. 횟수 정답이나 검출 정확도가 아니다.",
            "clips": int(normal.clip_id.nunique()),
            "by_view": normal.groupby("view_letter")["mean_to_side_range"].agg(["size", "median", "min", "max"]).to_dict("index")}
    archive_root = args.data_root / "013.피트니스자세" / "1.Training" / "원시데이터"
    result["raw_archives"] = [{"name": p.name, "bytes": p.stat().st_size} for p in sorted(archive_root.glob("*.tar"))]
    result["body_zip_files"] = zip_summary
    counts, unique = Counter(), set()
    errors = []
    for p in (args.data_root / "phone").rglob("sets-*.jsonl"):
        for i, line in enumerate(p.read_text(encoding="utf-8-sig").splitlines(), 1):
            if not line.strip():
                continue
            try:
                log = json.loads(line)
            except json.JSONDecodeError:
                errors.append({"path": str(p), "line": i})
                continue
            ex = log.get("exercise")
            if ex not in EXERCISES:
                continue
            key = log.get("set_id") or json.dumps(log, sort_keys=True, ensure_ascii=False)
            if key not in unique:
                unique.add(key)
                counts[ex] += 1
    result["phone_target_sets_deduplicated"] = dict(counts)
    result["phone_json_parse_errors"] = errors
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({ex: {k: v for k, v in item.items() if k in (
        "indexed_clips", "indexed_performers", "current_raw_2d_clips", "current_raw_3d_clips", "current_raw_performers",
        "all_conditions_true", "cached_mp_clips", "cached_mp_rows", "raw_2d_in_zip", "raw_3d_in_zip", "current_raw_3d_union",
        "current_raw_2d_union", "current_raw_union_performers", "raw_jpg_in_zip", "cached_mp_image_rows", "cached_mp_detected_images")}
        for ex, item in result["exercises"].items()}, ensure_ascii=False, indent=2))
    print("phone_target_sets_deduplicated:", dict(counts))
    print("report:", args.out.resolve())


if __name__ == "__main__":
    main()

"""AIHub 라벨 조사·휴대폰 재생 묶음·규칙 채점. 원본/앱 임계값을 변경하지 않는다."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import random
import re
import sys
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
OUT = HERE / "outputs" / "device_replay"
DATA = Path(r"C:\Users\hp276\Desktop\trex\data\013.피트니스자세")
CACHE = Path(r"C:\Users\hp276\Desktop\trex\research\aihub_fitness\outputs\mp")


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=1), encoding="utf-8")


def read_label(item):
    split, path = item
    d = json.loads(path.read_text(encoding="utf-8"))
    frames = d.get("frames", [])
    keys = [v.get("img_key", "") for f in frames[:1] for v in f.values()]
    first = next((k for k in keys if k), "")
    performer = re.search(r"-(Z\d+)_", first)
    info = d.get("type_info", {})
    conditions = {" ".join(c["condition"].split()): c["value"] for c in info.get("conditions", [])}
    return dict(split=split, path=str(path), clip_id=path.stem, exercise=" ".join(info.get("exercise", "").split()),
                performer=performer.group(1) if performer else None, day=first.split("/")[0],
                conditions=conditions, all_good=bool(conditions) and all(v is True for v in conditions.values()),
                frames=len(frames), description=info.get("description", ""), first_key=first)


def inventory():
    jobs = []
    for split in ("1.Training", "2.Validation"):
        jobs.extend((split, p) for p in sorted((DATA / split / "라벨링데이터").rglob("*.json")) if not p.stem.endswith("-3d"))
    rows = []
    with ThreadPoolExecutor(max_workers=8) as pool:
        for i, row in enumerate(pool.map(read_label, jobs)):
            rows.append(row)
            if (i + 1) % 2000 == 0:
                print(f"labels {i+1}/{len(jobs)}", flush=True)
    source = (REPO / "app/src/main/java/com/example/trex_kotlin/PostureLive.kt").read_text(encoding="utf-8")
    block = source.split("val postureExerciseMap:", 1)[1].split("\n)", 1)[0]
    supported = set(re.findall(r'"[^"]+" to "([^"]+)"', block))
    train_people = {r["performer"] for r in rows if r["split"] == "1.Training" and r["performer"]}
    val_people = {r["performer"] for r in rows if r["split"] == "2.Validation" and r["performer"]}
    train_keys = {r["first_key"] for r in rows if r["split"] == "1.Training"}
    table = []
    for ex in sorted({r["exercise"] for r in rows}):
        group = [r for r in rows if r["exercise"] == ex]
        table.append(dict(exercise=ex, supported=ex in supported,
                          training=sum(r["split"] == "1.Training" for r in group),
                          validation=sum(r["split"] == "2.Validation" for r in group),
                          shared_performers_same_exercise=len({r["performer"] for r in group if r["split"] == "1.Training"} & {r["performer"] for r in group if r["split"] == "2.Validation"}),
                          all_good=sum(r["all_good"] for r in group), performers=len({r["performer"] for r in group})))
    audit = dict(clips=len(rows), exercises=table, supported=len(supported),
                 train_performers=len(train_people), validation_performers=len(val_people),
                 shared_performers=sorted(train_people & val_people),
                 shared_first_images=sum(r["first_key"] in train_keys for r in rows if r["split"] == "2.Validation"),
                 frame_counts=dict(Counter(r["frames"] for r in rows)))
    write(OUT / "labels.json", rows)
    write(OUT / "inventory.json", audit)
    lines = ["# AIHub 파일 조사", "", f"클립 {len(rows):,}개 · 종목 {len(table)}개 · 앱 매핑 {len(supported)}개", "",
             f"수행자: Training {len(train_people)}명 / Validation {len(val_people)}명 / 공통 {len(train_people & val_people)}명", "",
             "| 운동 | Training | Validation | 앱 지원 |", "|---|---:|---:|---|"]
    lines += [f"| {r['exercise']} | {r['training']} | {r['validation']} | {'예' if r['supported'] else '아니오'} |" for r in table]
    (HERE / "DEVICE_REPLAY_INVENTORY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(audit, ensure_ascii=False), flush=True)


def pack(per_class=4, split="2.Validation"):
    import pandas as pd
    rows = json.loads((OUT / "labels.json").read_text(encoding="utf-8"))
    floor = json.loads((REPO / "app/src/main/assets/posture/rules_floor_v0.json").read_text(encoding="utf-8"))
    exercises = {r["exercise"] for r in floor["rules"]}
    tar_days = json.loads((CACHE / "tar_days.json").read_text(encoding="utf-8"))
    day_tars = defaultdict(list)
    for tar, day in tar_days.items():
        if Path(tar).exists():
            day_tars[day].append(Path(tar))
    indices = {}
    def locate(key):
        for tar in day_tars[key.split("/")[0]]:
            if str(tar) not in indices:
                p = CACHE / f"index_{tar.stem}.parquet"
                if not p.exists():
                    continue
                df = pd.read_parquet(p)
                indices[str(tar)] = {name: (int(off), int(size)) for name, off, size in df[["name", "offset", "size"]].itertuples(index=False, name=None)}
            if key in indices.get(str(tar), {}):
                return tar, *indices[str(tar)][key]
        return None
    rng = random.Random(20260911)
    sequences, missing, chosen = [], [], []
    used = set()
    archive = OUT / "replay.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as z:
        for ex in sorted(exercises):
            for good in (True, False):
                candidates = [r for r in rows if r["split"] == split and r["exercise"] == ex and r["all_good"] == good]
                candidates = [r for r in candidates if r["frames"] >= 8 and r["first_key"]]
                rng.shuffle(candidates)
                n = 0
                for row in candidates:
                    d = json.loads(Path(row["path"]).read_text(encoding="utf-8"))
                    views = sorted(d["frames"][0])
                    pending = []
                    complete = True
                    for view in views:
                        imgs = []
                        for frame in d["frames"]:
                            key = frame[view]["img_key"]
                            found = locate(key)
                            if found is None:
                                missing.append(key); complete = False; break
                            imgs.append((key, found))
                        if not complete:
                            break
                        pending.append((view, imgs))
                    if not complete:
                        continue
                    for view, imgs in pending:
                        keys = []
                        for key, (tar, offset, size) in imgs:
                            name = "images/" + hashlib.sha256(key.encode()).hexdigest() + ".jpg"
                            if name not in used:
                                with tar.open("rb") as f:
                                    f.seek(offset); buf = f.read(size)
                                if len(buf) != size or not buf.startswith(b"\xff\xd8"):
                                    raise ValueError(f"stale tar index: {tar} {key}")
                                z.writestr(name, buf); used.add(name)
                            keys.append(name)
                        sequences.append(dict(id=row["clip_id"] + "|" + view, clip_id=row["clip_id"], exercise=ex,
                                              performer=row["performer"], conditions=row["conditions"],
                                              view=imgs[0][0].split("/")[2], images=keys, source_keys=[x[0] for x in imgs],
                                              gt=[fr[view].get("pts", {}) for fr in d["frames"]],
                                              gt_active=[fr[view].get("active") for fr in d["frames"]]))
                    chosen.append(row); n += 1
                    if n >= per_class:
                        break
                print(f"pack {ex} {'normal' if good else 'mixed'} {n}/{per_class}", flush=True)
        manifest = dict(schema="trex.aihub.replay/1", timestamp_source="synthetic_600ms_NOT_ground_truth",
                        frame_interval_ms=600, split=split, seed=20260911, sequences=sequences)
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False))
    write(OUT / "manifest.json", manifest)
    write(OUT / "pack_report.json", dict(clips=len(chosen), sequences=len(sequences), images=len(used),
          missing_keys=len(missing), missing_examples=missing[:10], exercises=dict(Counter(r["exercise"] for r in chosen)),
          sample_policy=split + ", all conditions good vs at least one violated, seeded random; not per-rule balanced",
          zip_bytes=archive.stat().st_size))
    print(f"ready {len(chosen)} clips / {len(sequences)} sequences / {len(used)} images", flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser()
    p.add_argument("command", choices=["inventory", "pack"])
    p.add_argument("--split", choices=["1.Training", "2.Validation"], default="2.Validation")
    p.add_argument("--per-class", type=int, default=4)
    args = p.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if args.command == "inventory": inventory()
    else: pack(args.per_class, args.split)

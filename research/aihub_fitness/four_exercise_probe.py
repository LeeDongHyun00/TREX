"""네 종목의 기존 MP 캐시를 앱 공용 Kotlin 피처에 통과시킨다. 반복 정답/폰 정확도 검증은 아니다."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys

import pandas as pd

EXERCISES = ["크로스 런지", "사이드 런지", "스탠딩 니업", "스탠딩 사이드 크런치"]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--derived-root", type=Path, required=True)
    ap.add_argument("--replay", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    clips = pd.read_parquet(args.derived_root / "clips.parquet")
    clips = clips[clips.exercise.isin(EXERCISES)]
    keys = pd.read_parquet(args.derived_root / "kp2d.parquet", columns=["clip_id", "img_key", "view", "frame_idx"],
                           filters=[("clip_id", "in", clips.clip_id.tolist())])
    keys = keys.merge(clips[["clip_id", "exercise", "performer", "conditions_json"]], on="clip_id")
    frames = []
    for file in sorted((args.derived_root / "mp").glob("landmarks_*.parquet")):
        p = pd.read_parquet(file)
        frames.append(keys.merge(p, on="img_key", how="inner"))
    df = pd.concat(frames, ignore_index=True).drop_duplicates("img_key").sort_values(["clip_id", "view", "frame_idx"])
    df = df.reset_index(drop=True)
    capture = args.out / "cached-mp.cap"
    with capture.open("w", encoding="utf-8", newline="\n") as fp:
        for index, r in enumerate(df.to_dict("records")):
            fp.write(f"H\timageWidth={r['w']}\timageHeight={r['h']}\n")
            if not r["detected"]:
                fp.write(f"F\t{index}\t0\n")
                continue
            points = []
            for j in range(33):
                values = [r[f"l{j}_x"] / r["w"], r[f"l{j}_y"] / r["h"], r[f"l{j}_v"], r[f"l{j}_p"],
                          r[f"w{j}_x"], r[f"w{j}_y"], r[f"w{j}_z"]]
                points.append(f"{j}:" + ",".join(str(float(v)) for v in values))
            fp.write(f"F\t{index}\t1\t" + "\t".join(points) + "\n")
    dump = args.out / "features.jsonl"
    subprocess.run([str(args.replay.resolve()), "--dump-features", str(capture.resolve()), str(dump.resolve())], check=True)
    rows = [json.loads(x) for x in dump.read_text(encoding="utf-8").splitlines()]
    assert len(rows) == len(df), (len(rows), len(df))
    f = pd.DataFrame([r["features"] for r in rows])
    result = {"purpose": "cached MP geometric coverage; NOT repetition or phone validation", "frames": len(df), "exercises": {}}
    for ex in EXERCISES:
        idx = df.exercise == ex
        fs, ds = f[idx], df[idx]
        normal = ds.conditions_json.map(lambda s: all(v for _, v in json.loads(s)))
        valid = fs.get("four_visible", pd.Series(index=fs.index, dtype=float)).notna()
        result["exercises"][ex] = {
            "frames": len(ds), "clips": int(ds.clip_id.nunique()), "people": int(ds.performer.nunique()),
            "core_observed": int(valid.sum()), "core_coverage": float(valid.mean()),
            "normal_clips": int(ds.loc[normal, "clip_id"].nunique()),
            "features": {key: {"n": int(fs[key].notna().sum()), "normal_frame_quantiles": {
                str(q): float(v) for q, v in fs.loc[normal, key].dropna().quantile([.05, .5, .95]).items()}}
                for key in ["four_thigh_L", "four_thigh_R", "four_elbow_knee_L", "four_hand_head_L", "four_twist"] if key in fs},
        }
    df[["img_key", "clip_id", "view", "frame_idx", "exercise", "performer"]].to_json(args.out / "index.jsonl", orient="records", lines=True, force_ascii=False)
    (args.out / "coverage.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()

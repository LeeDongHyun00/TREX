"""Pick the camera view that best preserves a driver angle, per exercise and capture day.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
AIHUB_LABEL_STATISTICAL_ANALYSIS (non-commercial educational scope).

Why this exists
---------------
The first bridge measurement assumed view A is the lateral one because it was on the day the
lunges were filmed. It is not a property of the dataset: opening a Day33 frame showed view A as a
raised oblique of the whole studio, and the measurement built on that assumption produced
near-chance results. Camera assignment varies by capture day.

Each labelled frame carries 2D points for all five views plus a multi-view 3D reconstruction, so
the right view can simply be measured: whichever view's 2D projection of the driver angle tracks
the 3D angle most closely is the view a single phone camera should imitate. Cheap, because it
reads labels only.

Usage:
    python tools/select_aihub_measurement_view.py <label_root> [--out PATH]
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ARTIFACT = REPO_ROOT / "docs" / "aihub-measurement-view.v1.json"

CHAINS: dict[str, tuple[str, str, str]] = {
    "KNEE": ("Hip", "Knee", "Ankle"),
    "HIP": ("Shoulder", "Hip", "Knee"),
    "ELBOW": ("Shoulder", "Elbow", "Wrist"),
    "SHOULDER": ("Elbow", "Shoulder", "Hip"),
    "TRUNK": ("Shoulder", "Hip", "Ankle"),
}

VIEW_LETTERS = "ABCDE"

# The chain each surveyed exercise is measured on, from aihub-angle-separability.v1.json.
SURVEYED_CHAIN: dict[str, str] = {
    "스탠딩 니업": "HIP",
    "랫풀 다운": "SHOULDER",
    "굿모닝": "KNEE",
    "딥스": "ELBOW",
    "바벨 컬": "SHOULDER",
    "덤벨 컬": "SHOULDER",
    "스텝 포워드 다이나믹 런지": "KNEE",
    "스텝 백워드 다이나믹 런지": "KNEE",
}

# Below this median disagreement with the 3D reconstruction, a view is worth measuring from.
USABLE_MEDIAN_ERROR_DEGREES = 12.0


def _angle(points: dict[str, Any], joints: tuple[str, str, str], axes: str) -> float | None:
    try:
        p, q, r = (points[j] for j in joints)
    except KeyError:
        return None
    u = [p[k] - q[k] for k in axes]
    v = [r[k] - q[k] for k in axes]
    nu = math.sqrt(sum(t * t for t in u))
    nv = math.sqrt(sum(t * t for t in v))
    if nu < 1e-9 or nv < 1e-9:
        return None
    cosine = sum(a * b for a, b in zip(u, v)) / (nu * nv)
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def build(label_root: Path) -> dict[str, Any]:
    errors: dict[tuple[str, str], dict[int, list[float]]] = defaultdict(lambda: defaultdict(list))

    for path in sorted(label_root.rglob("D*.json")):
        if path.name.endswith("-3d.json"):
            continue
        spatial_path = path.with_name(path.stem + "-3d.json")
        if not spatial_path.exists():
            continue
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
            spatial = json.loads(spatial_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        info = document.get("type_info") or {}
        exercise = unicodedata.normalize("NFC", info.get("exercise", "")).strip()
        chain = SURVEYED_CHAIN.get(exercise)
        frames = document.get("frames") or []
        spatial_frames = spatial.get("frames") or []
        if chain is None or not frames or len(frames) != len(spatial_frames):
            continue
        day = frames[0]["view1"]["img_key"].split("/")[0]
        first, vertex, second = CHAINS[chain]

        for flat, solid in zip(frames, spatial_frames):
            for side in ("Left", "Right"):
                joints = (f"{side} {first}", f"{side} {vertex}", f"{side} {second}")
                truth = _angle(solid.get("pts") or {}, joints, "xyz")
                if truth is None:
                    continue
                for index in range(1, 6):
                    view = flat.get(f"view{index}") or {}
                    projected = _angle(view.get("pts") or {}, joints, "xy")
                    if projected is not None:
                        errors[(exercise, day)][index].append(abs(projected - truth))

    selections: list[dict[str, Any]] = []
    for (exercise, day), per_view in sorted(errors.items()):
        medians = {
            index: statistics.median(values) for index, values in per_view.items() if values
        }
        if not medians:
            continue
        best = min(medians, key=medians.get)
        selections.append(
            {
                "exercise": exercise,
                "chain": SURVEYED_CHAIN[exercise],
                "captureDay": day,
                "bestViewIndex": best,
                "bestViewLetter": VIEW_LETTERS[best - 1],
                "medianErrorDegrees": round(medians[best], 2),
                "perViewMedianErrorDegrees": {
                    VIEW_LETTERS[i - 1]: round(v, 2) for i, v in sorted(medians.items())
                },
                "usable": medians[best] <= USABLE_MEDIAN_ERROR_DEGREES,
                "frames": sum(len(v) for v in per_view.values()) // len(medians),
            }
        )

    by_exercise: dict[str, dict[str, Any]] = {}
    for row in selections:
        entry = by_exercise.setdefault(
            row["exercise"], {"chain": row["chain"], "viewsByDay": {}, "usableDays": 0, "days": 0}
        )
        entry["viewsByDay"][row["captureDay"]] = row["bestViewLetter"]
        entry["days"] += 1
        entry["usableDays"] += 1 if row["usable"] else 0

    return {
        "artifactKind": "TREX_AIHUB_MEASUREMENT_VIEW_SELECTION",
        "artifactVersion": 1,
        "rightsAuthorization": {
            "manifestId": "trex.aihub-research-use-rights.v1",
            "permittedOperation": "AIHUB_LABEL_STATISTICAL_ANALYSIS",
        },
        "method": {
            "criterion": "MEDIAN_ABS_DIFFERENCE_2D_PROJECTED_ANGLE_VS_3D_RECONSTRUCTION",
            "usableMedianErrorDegrees": USABLE_MEDIAN_ERROR_DEGREES,
            "chains": {k: list(v) for k, v in CHAINS.items()},
        },
        "limitations": [
            "View choice is per capture day; it is not a fixed property of the dataset.",
            "A low projection error bounds what a lateral phone camera could see, not what "
            "MediaPipe will actually estimate from it.",
        ],
        "summaryByExercise": by_exercise,
        "selections": selections,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label_root", type=Path)
    parser.add_argument("--out", type=Path, default=DEFAULT_ARTIFACT)
    args = parser.parse_args()

    if not args.label_root.is_dir():
        print(f"not a directory: {args.label_root}", file=sys.stderr)
        return 1

    artifact = build(args.label_root)
    args.out.write_text(
        json.dumps(artifact, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {args.out.name}\n")
    for exercise, entry in sorted(artifact["summaryByExercise"].items()):
        views = sorted(set(entry["viewsByDay"].values()))
        print(
            f"{exercise:22} {entry['chain']:9} days={entry['days']:3} "
            f"usable={entry['usableDays']:3} views={','.join(views)}"
        )
    print()
    for row in artifact["selections"]:
        flag = "ok " if row["usable"] else "POOR"
        per = " ".join(
            f"{k}{v:6.1f}" for k, v in row["perViewMedianErrorDegrees"].items()
        )
        print(
            f"  {flag} {row['exercise']:20} {row['captureDay']:18} -> "
            f"{row['bestViewLetter']} ({row['medianErrorDegrees']:5.1f}deg)   {per}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

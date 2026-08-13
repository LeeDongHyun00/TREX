"""Pick the camera view that best preserves the statistic a threshold is fitted on.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
AIHUB_LABEL_STATISTICAL_ANALYSIS (non-commercial educational scope).

Why this exists
---------------
The first bridge measurement assumed view A is the lateral one because it was on the day the
lunges were filmed. It is not a property of the dataset: opening a Day33 frame showed view A as a
raised oblique of the whole studio, and the measurement built on that assumption produced
near-chance results. Camera assignment varies by capture day.

Why the criterion changed
-------------------------
The first version of this tool averaged per-frame angle error over every frame of every clip and
took a bare argmin. An adversarial review showed that criterion is dominated by frames that carry
no information: 58.9% of standing knee-up frames sit above 160 degrees of driver angle, where all
five views agree and the threshold never lives. 31 of 54 selections were decided by margins under
half a degree, the narrowest by 0.01 degrees, and three standing knee-up days were thereby given
view C -- the view with the *worst* error in the flexed range on every single day.

A threshold is not fitted on frames. It is fitted on one number per clip: the extreme of the
driver angle over that clip. So the error that matters is the error in *that* number, and this
tool now measures exactly it -- the difference between the clip statistic computed from a view's
2D projection and the same statistic computed from the multi-view 3D reconstruction. Frames away
from the extreme drop out on their own, because they never enter the statistic.

Which chain and which extreme each exercise uses is read from the separability survey rather than
restated here, so the view is always chosen for the statistic that will actually be fitted.

Selections also carry their margin over the runner-up with a bootstrap interval. A margin whose
interval includes zero is a tie, and a tie is reported rather than silently resolved by argmin.

Usage:
    python tools/select_aihub_measurement_view.py <label_root> [--out PATH]
"""

from __future__ import annotations

import argparse
import json
import math
import random
import statistics
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SURVEY = REPO_ROOT / "docs" / "aihub-angle-separability.v1.json"
DEFAULT_ARTIFACT = REPO_ROOT / "docs" / "aihub-measurement-view.v1.json"

CHAINS: dict[str, tuple[str, str, str]] = {
    "KNEE": ("Hip", "Knee", "Ankle"),
    "HIP": ("Shoulder", "Hip", "Knee"),
    "ELBOW": ("Shoulder", "Elbow", "Wrist"),
    "SHOULDER": ("Elbow", "Shoulder", "Hip"),
    "TRUNK": ("Shoulder", "Hip", "Ankle"),
}

VIEW_LETTERS = "ABCDE"

# Below this median statistic error a view is worth measuring from at all.
USABLE_MEDIAN_ERROR_DEGREES = 12.0

# A margin whose bootstrap interval reaches this close to zero is not a real preference.
BOOTSTRAP_RESAMPLES = 400
BOOTSTRAP_SEED = 20260812


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


def _clip_statistic(
    frames: list[dict[str, Any]],
    accessor,
    joints_for_side: dict[str, tuple[str, str, str]],
    axes: str,
    extreme: str,
) -> float | None:
    """The one number a threshold is fitted on: the clip's extreme driver angle."""
    per_frame: list[float] = []
    for frame in frames:
        points = accessor(frame)
        if points is None:
            continue
        angles = [
            angle
            for joints in joints_for_side.values()
            if (angle := _angle(points, joints, axes)) is not None
        ]
        if angles:
            per_frame.append(min(angles) if extreme == "min" else max(angles))
    if not per_frame:
        return None
    return min(per_frame) if extreme == "min" else max(per_frame)


def _surveyed_targets(survey_path: Path) -> dict[str, tuple[str, str]]:
    """Exercise -> (chain, extreme), taking each exercise's strongest separable finding."""
    survey = json.loads(survey_path.read_text(encoding="utf-8"))
    best: dict[str, tuple[float, str, str]] = {}
    for finding in survey.get("findings", []):
        if finding.get("verdict") != "SEPARABLE":
            continue
        exercise = unicodedata.normalize("NFC", finding["exercise"]).strip()
        score = float(finding["losoBalancedAccuracy"])
        if exercise not in best or score > best[exercise][0]:
            best[exercise] = (score, finding["chain"], finding["extreme"])
    return {name: (chain, extreme) for name, (_, chain, extreme) in best.items()}


def _bootstrap_margin(
    best: list[float],
    runner_up: list[float],
    rng: random.Random,
) -> tuple[float, float]:
    """Percentile interval for median(runner_up) - median(best), paired by clip."""
    paired = list(zip(best, runner_up))
    if len(paired) < 8:
        return (float("nan"), float("nan"))
    margins: list[float] = []
    size = len(paired)
    for _ in range(BOOTSTRAP_RESAMPLES):
        sample = [paired[rng.randrange(size)] for _ in range(size)]
        margins.append(
            statistics.median(s for _, s in sample) - statistics.median(f for f, _ in sample)
        )
    margins.sort()
    low = margins[int(0.025 * (len(margins) - 1))]
    high = margins[int(0.975 * (len(margins) - 1))]
    return (low, high)


def build(label_root: Path, survey_path: Path) -> dict[str, Any]:
    targets = _surveyed_targets(survey_path)
    # (exercise, day) -> view index -> per-clip |2D statistic - 3D statistic|
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
        target = targets.get(exercise)
        frames = document.get("frames") or []
        spatial_frames = spatial.get("frames") or []
        if target is None or not frames or len(frames) != len(spatial_frames):
            continue
        chain, extreme = target
        first, vertex, second = CHAINS[chain]
        joints = {
            side: (f"{side} {first}", f"{side} {vertex}", f"{side} {second}")
            for side in ("Left", "Right")
        }
        day = frames[0]["view1"]["img_key"].split("/")[0]

        truth = _clip_statistic(
            spatial_frames, lambda f: f.get("pts"), joints, "xyz", extreme
        )
        if truth is None:
            continue
        for index in range(1, 6):
            projected = _clip_statistic(
                frames,
                lambda f, i=index: (f.get(f"view{i}") or {}).get("pts"),
                joints,
                "xy",
                extreme,
            )
            if projected is not None:
                errors[(exercise, day)][index].append(abs(projected - truth))

    rng = random.Random(BOOTSTRAP_SEED)
    selections: list[dict[str, Any]] = []
    for (exercise, day), per_view in sorted(errors.items()):
        medians = {i: statistics.median(v) for i, v in per_view.items() if v}
        if len(medians) < 2:
            continue
        ordered = sorted(medians, key=medians.get)
        best_index, runner_index = ordered[0], ordered[1]
        clips = min(len(per_view[best_index]), len(per_view[runner_index]))
        low, high = _bootstrap_margin(
            per_view[best_index][:clips], per_view[runner_index][:clips], rng
        )
        margin = medians[runner_index] - medians[best_index]
        tied = not (low > 0.0) if not math.isnan(low) else True
        # A tie is reported with every view inside the interval, so a later run can prefer one on
        # other grounds instead of inheriting an argmin that a hundredth of a degree decided.
        tied_views = (
            sorted(
                VIEW_LETTERS[i - 1]
                for i in medians
                if medians[i] - medians[best_index] <= max(margin, 0.0)
            )
            if tied
            else [VIEW_LETTERS[best_index - 1]]
        )
        chain, extreme = targets[exercise]
        selections.append(
            {
                "exercise": exercise,
                "chain": chain,
                "extreme": extreme,
                "captureDay": day,
                "bestViewIndex": best_index,
                "bestViewLetter": VIEW_LETTERS[best_index - 1],
                "medianErrorDegrees": round(medians[best_index], 2),
                "runnerUpViewLetter": VIEW_LETTERS[runner_index - 1],
                "marginDegrees": round(margin, 3),
                "marginBootstrapLowDegrees": None if math.isnan(low) else round(low, 3),
                "marginBootstrapHighDegrees": None if math.isnan(high) else round(high, 3),
                "tied": tied,
                "tiedViewLetters": tied_views,
                "perViewMedianErrorDegrees": {
                    VIEW_LETTERS[i - 1]: round(v, 2) for i, v in sorted(medians.items())
                },
                "clips": clips,
                "usable": medians[best_index] <= USABLE_MEDIAN_ERROR_DEGREES,
            }
        )

    by_exercise: dict[str, dict[str, Any]] = {}
    for row in selections:
        entry = by_exercise.setdefault(
            row["exercise"],
            {"chain": row["chain"], "extreme": row["extreme"], "viewsByDay": {},
             "usableDays": 0, "tiedDays": 0, "days": 0},
        )
        entry["viewsByDay"][row["captureDay"]] = row["bestViewLetter"]
        entry["days"] += 1
        entry["usableDays"] += 1 if row["usable"] else 0
        entry["tiedDays"] += 1 if row["tied"] else 0

    return {
        "artifactKind": "TREX_AIHUB_MEASUREMENT_VIEW_SELECTION",
        "artifactVersion": 2,
        "rightsAuthorization": {
            "manifestId": "trex.aihub-research-use-rights.v1",
            "permittedOperation": "AIHUB_LABEL_STATISTICAL_ANALYSIS",
        },
        "method": {
            "criterion": "MEDIAN_ABS_DIFFERENCE_OF_CLIP_STATISTIC_2D_VIEW_VS_3D_RECONSTRUCTION",
            "statisticSource": "docs/aihub-angle-separability.v1.json strongest SEPARABLE finding",
            "usableMedianErrorDegrees": USABLE_MEDIAN_ERROR_DEGREES,
            "bootstrapResamples": BOOTSTRAP_RESAMPLES,
            "bootstrapSeed": BOOTSTRAP_SEED,
            "chains": {k: list(v) for k, v in CHAINS.items()},
            "supersedes": (
                "v1 averaged per-frame error over all frames and took a bare argmin; frames away "
                "from the clip extreme carry no threshold information and dominated that average"
            ),
        },
        "limitations": [
            "View choice is per capture day; it is not a fixed property of the dataset.",
            "A low projection error bounds what a lateral phone camera could see, not what "
            "MediaPipe will actually estimate from it.",
            "A tie means the label data cannot separate those views for this statistic; it is "
            "not a statement that either is adequate.",
        ],
        "summaryByExercise": by_exercise,
        "selections": selections,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label_root", type=Path)
    parser.add_argument("--survey", type=Path, default=DEFAULT_SURVEY)
    parser.add_argument("--out", type=Path, default=DEFAULT_ARTIFACT)
    args = parser.parse_args()

    if not args.label_root.is_dir():
        print(f"not a directory: {args.label_root}", file=sys.stderr)
        return 1

    artifact = build(args.label_root, args.survey)
    args.out.write_text(
        json.dumps(artifact, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {args.out.name}\n")
    for exercise, entry in sorted(artifact["summaryByExercise"].items()):
        views = sorted(set(entry["viewsByDay"].values()))
        print(
            f"{exercise:22} {entry['chain']:9}{entry['extreme']:4} days={entry['days']:3} "
            f"usable={entry['usableDays']:3} tied={entry['tiedDays']:3} views={','.join(views)}"
        )
    print()
    for row in artifact["selections"]:
        flag = "TIE " if row["tied"] else ("ok  " if row["usable"] else "POOR")
        per = " ".join(f"{k}{v:6.1f}" for k, v in row["perViewMedianErrorDegrees"].items())
        print(
            f"  {flag} {row['exercise']:20} {row['captureDay']:18} -> "
            f"{row['bestViewLetter']} ({row['medianErrorDegrees']:5.1f}deg, "
            f"margin {row['marginDegrees']:+6.2f} "
            f"[{row['marginBootstrapLowDegrees']}, {row['marginBootstrapHighDegrees']}])   {per}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

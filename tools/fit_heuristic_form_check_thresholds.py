"""Fit heuristic form-check depth thresholds from AI Hub Day05 labels.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
AIHUB_LABEL_THRESHOLD_FITTING_FOR_HEURISTIC_BETA (non-commercial educational scope).

Method
------
Each labelled clip carries a Boolean condition vector; for both dynamic lunges the first
condition is "앞다리 무릎 각도 90도". For every clip we take the minimum knee included angle over
its labelled frames and ask which threshold best separates condition-true from condition-false
clips. Generalisation is measured leave-one-subject-out: the threshold is refit on 7 subjects and
scored on the held-out one, so a constant that only works on the subjects that produced it is
visible as a LOSO drop.

Two angle sources are reported because they answer different questions:
  * `world3d` -- the multi-view 3D label. Comparable in kind to MediaPipe world landmarks, so
    this is the source a runtime constant should be taken from.
  * `lateral2d` -- the view-A (side camera) 2D projection. Reported to expose the systematic
    projection offset a single lateral camera introduces.

Limitations recorded in the artifact, not just here: these are AI Hub label angles, not MediaPipe
estimates. The bridge error between them is unquantified, so a fitted constant is a candidate for
the heuristic beta, never a validated release threshold.

Usage:
    python tools/fit_heuristic_form_check_thresholds.py <day05_label_root>
    python tools/fit_heuristic_form_check_thresholds.py <day05_label_root> --check
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent
ARTIFACT_PATH = REPO_ROOT / "docs" / "heuristic-form-check-threshold-fit.v1.json"

# Quarantined in docs/aihub-label-quarantine and the catalog audit: description and truth-vector
# disagree, so these type codes cannot serve as labels.
CONTAMINATED_TYPE_CODES = frozenset({"062", "101", "109"})

# Condition whose truth value the fitted angle is asked to predict.
DEPTH_CONDITION_SUBSTRING = "앞다리"

SUBJECT_PATTERN = re.compile(r"-(Z\d+)_")
THRESHOLD_SEARCH_RANGE = range(40, 181)

SIDES = ("Left", "Right")


def _included_angle(
    first: dict[str, float],
    vertex: dict[str, float],
    second: dict[str, float],
    axes: tuple[str, ...],
) -> float | None:
    """Included angle at `vertex`, in degrees, or None when a ray is degenerate."""
    a = [first[axis] - vertex[axis] for axis in axes]
    b = [second[axis] - vertex[axis] for axis in axes]
    na = math.sqrt(sum(component * component for component in a))
    nb = math.sqrt(sum(component * component for component in b))
    if na < 1e-9 or nb < 1e-9:
        return None
    cosine = sum(x * y for x, y in zip(a, b)) / (na * nb)
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def _minimum_knee_angle(points: dict[str, Any], axes: tuple[str, ...]) -> float | None:
    """Deepest knee bend visible in one frame, over whichever legs are present.

    The AI Hub condition names a specific limb (lead versus trail) but the runtime has no role
    resolver, so the minimum over both legs is used as the deliberately resolver-free proxy. The
    LOSO score is what says whether that proxy holds.
    """
    angles = []
    for side in SIDES:
        try:
            angle = _included_angle(
                points[f"{side} Hip"], points[f"{side} Knee"], points[f"{side} Ankle"], axes
            )
        except KeyError:
            continue
        if angle is not None:
            angles.append(angle)
    return min(angles) if angles else None


def _fit_threshold(rows: Iterable[tuple[float, bool]]) -> int | None:
    """Threshold maximising plain accuracy; `angle <= threshold` predicts condition-true."""
    rows = list(rows)
    positives = [angle for angle, label in rows if label]
    negatives = [angle for angle, label in rows if not label]
    if not positives or not negatives:
        return None
    best_threshold, best_accuracy = None, -1.0
    for threshold in THRESHOLD_SEARCH_RANGE:
        correct = sum(1 for a in positives if a <= threshold)
        correct += sum(1 for a in negatives if a > threshold)
        accuracy = correct / len(rows)
        if accuracy > best_accuracy:
            best_threshold, best_accuracy = threshold, accuracy
    return best_threshold


def _collect(label_root: Path) -> dict[str, list[dict[str, Any]]]:
    clips: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for label_path in sorted(label_root.glob("D05-*.json")):
        if label_path.name.endswith("-3d.json"):
            continue
        document = json.loads(label_path.read_text(encoding="utf-8"))
        frames = document.get("frames") or []
        type_info = document.get("type_info") or {}
        if not frames or document.get("type") in CONTAMINATED_TYPE_CODES:
            continue
        conditions = {
            unicodedata.normalize("NFC", entry["condition"]).strip(): entry["value"]
            for entry in type_info.get("conditions", [])
        }
        depth_keys = [key for key in conditions if DEPTH_CONDITION_SUBSTRING in key]
        if len(depth_keys) != 1:
            continue

        spatial_path = label_path.with_name(label_path.stem + "-3d.json")
        if not spatial_path.exists():
            continue
        spatial = json.loads(spatial_path.read_text(encoding="utf-8"))
        spatial_frames = spatial.get("frames") or []
        if not spatial_frames:
            continue

        world = [
            angle
            for frame in spatial_frames
            if (angle := _minimum_knee_angle(frame["pts"], ("x", "y", "z"))) is not None
        ]
        lateral = [
            angle
            for frame in frames
            if (angle := _minimum_knee_angle(frame["view1"]["pts"], ("x", "y"))) is not None
        ]
        if not world or not lateral:
            continue

        subject = SUBJECT_PATTERN.search(frames[0]["view1"]["img_key"])
        exercise = unicodedata.normalize("NFC", type_info["exercise"]).strip()
        clips[exercise].append(
            {
                "world3d": min(world),
                "lateral2d": min(lateral),
                "label": bool(conditions[depth_keys[0]]),
                "subject": subject.group(1) if subject else "UNKNOWN",
                "condition": depth_keys[0],
            }
        )
    return clips


def _evaluate(rows: list[dict[str, Any]], source: str) -> dict[str, Any] | None:
    samples = [(row[source], row["label"]) for row in rows]
    pooled = _fit_threshold(samples)
    if pooled is None:
        return None

    positives = [a for a, label in samples if label]
    negatives = [a for a, label in samples if not label]
    sensitivity = sum(1 for a in positives if a <= pooled) / len(positives)
    specificity = sum(1 for a in negatives if a > pooled) / len(negatives)

    subjects = sorted({row["subject"] for row in rows})
    fold_thresholds: list[int] = []
    correct = held_out = 0
    for subject in subjects:
        train = [(row[source], row["label"]) for row in rows if row["subject"] != subject]
        test = [(row[source], row["label"]) for row in rows if row["subject"] == subject]
        threshold = _fit_threshold(train)
        if threshold is None or not test:
            continue
        fold_thresholds.append(threshold)
        correct += sum(1 for angle, label in test if (angle <= threshold) == label)
        held_out += len(test)

    return {
        "pooledThresholdDegrees": pooled,
        "pooledAccuracy": round((sum(1 for a in positives if a <= pooled) + sum(
            1 for a in negatives if a > pooled)) / len(samples), 4),
        "sensitivity": round(sensitivity, 4),
        "specificity": round(specificity, 4),
        "losoAccuracy": round(correct / held_out, 4) if held_out else None,
        "losoHeldOutClips": held_out,
        "losoThresholdMinDegrees": min(fold_thresholds) if fold_thresholds else None,
        "losoThresholdMaxDegrees": max(fold_thresholds) if fold_thresholds else None,
        "losoThresholdMedianDegrees": (
            int(statistics.median(fold_thresholds)) if fold_thresholds else None
        ),
    }


def build_artifact(label_root: Path) -> dict[str, Any]:
    clips = _collect(label_root)
    exercises = []
    for exercise in sorted(clips):
        rows = clips[exercise]
        positives = sum(1 for row in rows if row["label"])
        entry: dict[str, Any] = {
            "exercise": exercise,
            "conditionEvaluated": rows[0]["condition"],
            "clipCount": len(rows),
            "conditionTrueCount": positives,
            "conditionFalseCount": len(rows) - positives,
            "subjectCount": len({row["subject"] for row in rows}),
            "sources": {},
        }
        for source in ("world3d", "lateral2d"):
            result = _evaluate(rows, source)
            if result is not None:
                entry["sources"][source] = result
        entry["usable"] = bool(entry["sources"]) and positives not in (0, len(rows))
        exercises.append(entry)

    return {
        "artifactKind": "TREX_HEURISTIC_FORM_CHECK_THRESHOLD_FIT",
        "artifactVersion": 1,
        "rightsAuthorization": {
            "manifestId": "trex.aihub-research-use-rights.v1",
            "permittedOperation": "AIHUB_LABEL_THRESHOLD_FITTING_FOR_HEURISTIC_BETA",
        },
        "method": {
            "statistic": "MINIMUM_KNEE_INCLUDED_ANGLE_OVER_LABELLED_FRAMES",
            "limbSelection": "MINIMUM_OVER_BOTH_LEGS_NO_ROLE_RESOLVER",
            "generalisation": "LEAVE_ONE_SUBJECT_OUT",
            "excludedTypeCodes": sorted(CONTAMINATED_TYPE_CODES),
        },
        "limitations": [
            "Angles come from AI Hub labels, not MediaPipe; the bridge error is unquantified.",
            "Sparse keyframes with no timestamps: no rep-duration or phase constant can be fitted.",
            "Clip-level condition labels, so a threshold predicts clip quality, not per-rep truth.",
            "Barbell squat has no Day05 imagery and cannot be fitted from this source at all.",
            "Fitted values are heuristic-beta candidates and confer no release authority.",
        ],
        "exercises": exercises,
    }


def _render(artifact: dict[str, Any]) -> str:
    return json.dumps(artifact, sort_keys=True, indent=2, ensure_ascii=False) + "\n"


def _summarise(artifact: dict[str, Any]) -> None:
    for entry in artifact["exercises"]:
        verdict = "USABLE" if entry["usable"] else "NOT USABLE"
        print(
            f"{entry['exercise']}  [{verdict}]  clips={entry['clipCount']} "
            f"(true={entry['conditionTrueCount']}/false={entry['conditionFalseCount']}) "
            f"subjects={entry['subjectCount']}"
        )
        print(f"    condition: {entry['conditionEvaluated']}")
        for source, result in entry["sources"].items():
            print(
                f"    {source:10} threshold={result['pooledThresholdDegrees']}deg "
                f"acc={result['pooledAccuracy']:.1%} "
                f"sens={result['sensitivity']:.1%} spec={result['specificity']:.1%} "
                f"LOSO={result['losoAccuracy']:.1%} "
                f"(folds {result['losoThresholdMinDegrees']}-"
                f"{result['losoThresholdMaxDegrees']}deg)"
            )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label_root", type=Path, help="Day05 label directory")
    parser.add_argument("--check", action="store_true", help="verify the stored artifact")
    args = parser.parse_args()

    if not args.label_root.is_dir():
        print(f"not a directory: {args.label_root}", file=sys.stderr)
        return 1

    artifact = build_artifact(args.label_root)
    if not artifact["exercises"]:
        print("no evaluable clips found", file=sys.stderr)
        return 1

    rendered = _render(artifact)
    if args.check:
        if not ARTIFACT_PATH.exists():
            print(f"missing artifact: {ARTIFACT_PATH}", file=sys.stderr)
            return 1
        if ARTIFACT_PATH.read_text(encoding="utf-8") != rendered:
            print("artifact drifted from generator output", file=sys.stderr)
            return 1
        print(f"ok: {ARTIFACT_PATH.name}")
        return 0

    ARTIFACT_PATH.write_text(rendered, encoding="utf-8")
    print(f"wrote {ARTIFACT_PATH.name}\n")
    _summarise(artifact)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

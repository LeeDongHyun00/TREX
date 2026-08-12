"""Survey which AI Hub conditions any joint-angle chain can actually predict.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
AIHUB_LABEL_STATISTICAL_ANALYSIS (non-commercial educational scope).

Why this exists
---------------
Calibrating a form-check threshold means finding a joint angle whose value separates a condition's
true clips from its false ones. Which condition that is had been chosen by hand, one exercise at a
time, and hand-chosen mappings turned out to be both incomplete and wrong: the push-up's
"elbow 90" condition does not separate on elbow angle even in perfect 3D ground truth, while
nothing had ever checked whether some *other* condition on some *other* chain does.

This sweeps every (exercise x condition x chain x extreme) combination the dataset admits and
reports which ones separate. It reads 3D labels only -- no imagery, no MediaPipe -- so it is cheap
enough to run before any expensive measurement, which is the order the earlier work got wrong.

A pass here is necessary, not sufficient: it says the dataset's own ground truth can predict the
condition from that angle. Whether MediaPipe can reproduce that angle from one camera is the
separate bridge question, and which camera view to use is a third.

Usage:
    python tools/survey_aihub_angle_separability.py <label_root> [--out PATH] [--limit N]
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
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CATALOG = REPO_ROOT / "docs" / "aihub-exercise-catalog.json"
DEFAULT_ARTIFACT = REPO_ROOT / "docs" / "aihub-angle-separability.v1.json"

# Quarantined: description and truth-vector disagree.
CONTAMINATED_TYPE_CODES = frozenset({"062", "101", "109"})

# Every three-joint chain worth asking about, including two the runtime does not yet drive:
# SHOULDER (arm elevation) and TRUNK (whole-body straightness), because the point of a survey is
# to find what is measurable rather than to confirm what is already implemented.
CHAINS: dict[str, tuple[str, str, str]] = {
    "KNEE": ("Hip", "Knee", "Ankle"),
    "HIP": ("Shoulder", "Hip", "Knee"),
    "ELBOW": ("Shoulder", "Elbow", "Wrist"),
    "SHOULDER": ("Elbow", "Shoulder", "Hip"),
    "TRUNK": ("Shoulder", "Hip", "Ankle"),
}

# A clip is summarised by how far it travelled in each direction; which one matters depends on
# whether the condition is about flexing or extending, so both are surveyed.
EXTREMES = ("min", "max")

FILENAME = re.compile(r"^D(\d+)-(\d+)-(\d+)-3d\.json$")

# Reporting gates. A combination has to clear all of them to be called separable.
MINIMUM_CLIPS = 40
MINIMUM_MINORITY_FRACTION = 0.15
MINIMUM_SUBJECTS = 4
SEPARABLE_LOSO = 0.75
MARGINAL_LOSO = 0.65


def _included_angle(p: Any, q: Any, r: Any) -> float | None:
    u = [p[k] - q[k] for k in "xyz"]
    v = [r[k] - q[k] for k in "xyz"]
    nu = math.sqrt(sum(t * t for t in u))
    nv = math.sqrt(sum(t * t for t in v))
    if nu < 1e-9 or nv < 1e-9:
        return None
    cosine = sum(a * b for a, b in zip(u, v)) / (nu * nv)
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def _clip_extremes(frames: list[dict[str, Any]]) -> dict[tuple[str, str], float]:
    """Per-chain min and max of the better-articulated side, over the clip's frames."""
    collected: dict[str, list[float]] = defaultdict(list)
    for frame in frames:
        points = frame.get("pts") or {}
        for chain, (first, vertex, second) in CHAINS.items():
            angles = []
            for side in ("Left", "Right"):
                try:
                    angle = _included_angle(
                        points[f"{side} {first}"],
                        points[f"{side} {vertex}"],
                        points[f"{side} {second}"],
                    )
                except KeyError:
                    continue
                if angle is not None:
                    angles.append(angle)
            if angles:
                # Both extremes need the same side-selection rule, so keep both candidates and
                # let the extreme itself pick.
                collected[chain].append(min(angles))
                collected[chain + "|max"].append(max(angles))
    out: dict[tuple[str, str], float] = {}
    for chain in CHAINS:
        if collected.get(chain):
            out[(chain, "min")] = min(collected[chain])
        if collected.get(chain + "|max"):
            out[(chain, "max")] = max(collected[chain + "|max"])
    return out


def _fit(rows: list[tuple[float, bool]]) -> tuple[int, float] | None:
    """Best threshold under `angle <= t predicts true`, plus its accuracy.

    Swept over the sorted values rather than every integer degree: the survey fits once per
    held-out subject per combination, and the quadratic version dominated the whole run.
    """
    total = len(rows)
    positives = sum(1 for _, label in rows if label)
    negatives = total - positives
    if not positives or not negatives:
        return None

    order = sorted(rows, key=lambda r: r[0])
    # Threshold below everything: nothing predicted true, so only the negatives are correct.
    best_threshold = math.floor(order[0][0]) - 1
    best_accuracy = negatives / total

    pos_seen = neg_seen = 0
    index = 0
    while index < total:
        value = order[index][0]
        while index < total and order[index][0] == value:
            if order[index][1]:
                pos_seen += 1
            else:
                neg_seen += 1
            index += 1
        accuracy = (pos_seen + (negatives - neg_seen)) / total
        if accuracy > best_accuracy:
            best_accuracy = accuracy
            best_threshold = round(value)
    return best_threshold, best_accuracy


def _loso(rows: list[tuple[float, bool, str]]) -> tuple[float, float, list[int]] | None:
    """Held-out balanced accuracy, raw accuracy and the per-fold thresholds.

    Balanced accuracy is what decides a verdict. Raw accuracy rewards a classifier that simply
    predicts the majority class, and this dataset produced exactly that: the foot-knee alignment
    condition is ~81% true, so "threshold 180, everything passes" scored 81% on every chain and
    every extreme at once -- an unmistakable signature of a degenerate fit, and one the earlier
    burpee check had already shown in miniature.
    """
    subjects = sorted({s for _, _, s in rows})
    if len(subjects) < MINIMUM_SUBJECTS:
        return None
    true_positive = false_negative = true_negative = false_positive = 0
    folds: list[int] = []
    for subject in subjects:
        train = [(v, label) for v, label, s in rows if s != subject]
        test = [(v, label) for v, label, s in rows if s == subject]
        fitted = _fit(train)
        if fitted is None or not test:
            continue
        folds.append(fitted[0])
        for value, label in test:
            predicted = value <= fitted[0]
            if label and predicted:
                true_positive += 1
            elif label:
                false_negative += 1
            elif predicted:
                false_positive += 1
            else:
                true_negative += 1
    positives = true_positive + false_negative
    negatives = true_negative + false_positive
    if not positives or not negatives:
        return None
    sensitivity = true_positive / positives
    specificity = true_negative / negatives
    raw = (true_positive + true_negative) / (positives + negatives)
    return (sensitivity + specificity) / 2.0, raw, folds


def _direction_of(rows: list[tuple[float, bool, str]], threshold: int) -> str:
    """Whether the condition holds below the threshold (flexion) or above it (extension)."""
    positives = [v for v, label, _ in rows if label]
    negatives = [v for v, label, _ in rows if not label]
    if not positives or not negatives:
        return "UNKNOWN"
    return "FLEXION" if statistics.median(positives) < statistics.median(negatives) else "EXTENSION"


def build(label_root: Path, catalog_path: Path, limit: int | None) -> dict[str, Any]:
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    by_code: dict[str, tuple[str, dict[str, bool]]] = {}
    for exercise in catalog["exercises"]:
        name = unicodedata.normalize("NFC", exercise["name"]).strip()
        for entry in exercise["types"]:
            conditions = {
                unicodedata.normalize("NFC", c["condition"]).strip(): bool(c["value"])
                for c in entry["conditions"]
            }
            by_code[entry["code"]] = (name, conditions)

    # (exercise, condition, chain, extreme) -> [(angle, label, subject)]
    samples: dict[tuple[str, str, str, str], list[tuple[float, bool, str]]] = defaultdict(list)
    scanned = skipped = 0

    for path in sorted(label_root.rglob("D*-3d.json")):
        match = FILENAME.match(path.name)
        if not match:
            continue
        day, folder, code = match.groups()
        if code in CONTAMINATED_TYPE_CODES:
            continue
        entry = by_code.get(code)
        if entry is None:
            skipped += 1
            continue
        exercise, conditions = entry
        try:
            frames = json.loads(path.read_text(encoding="utf-8")).get("frames") or []
        except (OSError, ValueError):
            skipped += 1
            continue
        if not frames:
            continue
        extremes = _clip_extremes(frames)
        if not extremes:
            continue
        # One capture day's folder is one participant; days are separate recruitment sessions, so
        # the pair is the finest subject key the filenames support.
        subject = f"D{day}-{folder}"
        for condition, value in conditions.items():
            for (chain, extreme), angle in extremes.items():
                samples[(exercise, condition, chain, extreme)].append((angle, value, subject))
        scanned += 1
        if limit is not None and scanned >= limit:
            break

    findings: list[dict[str, Any]] = []
    for (exercise, condition, chain, extreme), rows in samples.items():
        if len(rows) < MINIMUM_CLIPS:
            continue
        positives = sum(1 for _, label, _ in rows if label)
        minority = min(positives, len(rows) - positives) / len(rows)
        if minority < MINIMUM_MINORITY_FRACTION:
            continue
        pooled = _fit([(v, label) for v, label, _ in rows])
        if pooled is None:
            continue
        scored = _loso(rows)
        if scored is None:
            continue
        loso, loso_raw, folds = scored
        verdict = (
            "SEPARABLE"
            if loso >= SEPARABLE_LOSO
            else "MARGINAL"
            if loso >= MARGINAL_LOSO
            else "NOT_SEPARABLE"
        )
        findings.append(
            {
                "exercise": exercise,
                "condition": condition,
                "chain": chain,
                "extreme": extreme,
                "clips": len(rows),
                "conditionTrue": positives,
                "subjects": len({s for _, _, s in rows}),
                "pooledThresholdDegrees": pooled[0],
                "pooledAccuracy": round(pooled[1], 4),
                "losoBalancedAccuracy": round(loso, 4),
                "losoRawAccuracy": round(loso_raw, 4),
                "majorityBaseline": round(max(positives, len(rows) - positives) / len(rows), 4),
                "losoThresholdMinDegrees": min(folds) if folds else None,
                "losoThresholdMaxDegrees": max(folds) if folds else None,
                "direction": _direction_of(rows, pooled[0]),
                "verdict": verdict,
            }
        )

    findings.sort(key=lambda f: (-f["losoBalancedAccuracy"], f["exercise"]))
    return {
        "artifactKind": "TREX_AIHUB_ANGLE_SEPARABILITY_SURVEY",
        "artifactVersion": 1,
        "rightsAuthorization": {
            "manifestId": "trex.aihub-research-use-rights.v1",
            "permittedOperation": "AIHUB_LABEL_STATISTICAL_ANALYSIS",
        },
        "method": {
            "source": "AI_HUB_3D_LABELS_ONLY_NO_IMAGERY_NO_MEDIAPIPE",
            "chains": {k: list(v) for k, v in CHAINS.items()},
            "extremes": list(EXTREMES),
            "subjectKey": "CAPTURE_DAY_AND_PARTICIPANT_FOLDER",
            "generalisation": "LEAVE_ONE_SUBJECT_OUT",
            "verdictMetric": "BALANCED_ACCURACY_SENSITIVITY_SPECIFICITY_MEAN",
            "gates": {
                "minimumClips": MINIMUM_CLIPS,
                "minimumMinorityFraction": MINIMUM_MINORITY_FRACTION,
                "minimumSubjects": MINIMUM_SUBJECTS,
                "separableLoso": SEPARABLE_LOSO,
                "marginalLoso": MARGINAL_LOSO,
            },
            "excludedTypeCodes": sorted(CONTAMINATED_TYPE_CODES),
        },
        "limitations": [
            "Ground-truth 3D labels: a pass bounds what is measurable, not what MediaPipe can see.",
            "Clip-level conditions, so a threshold predicts clip quality, not per-repetition truth.",
            "Sparse keyframes: a brief extreme may fall between samples and depress separability.",
            "Conditions are capture-script instructions; adherence varies by exercise.",
            "Studio domain, and one capture day's folder is treated as one participant.",
            "Verdicts use balanced accuracy; raw accuracy is reported only to expose imbalance.",
        ],
        "clipsScanned": scanned,
        "clipsSkipped": skipped,
        "combinationsTested": len(samples),
        "findings": findings,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label_root", type=Path)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--out", type=Path, default=DEFAULT_ARTIFACT)
    parser.add_argument("--limit", type=int, default=None)
    args = parser.parse_args()

    if not args.label_root.is_dir():
        print(f"not a directory: {args.label_root}", file=sys.stderr)
        return 1

    artifact = build(args.label_root, args.catalog, args.limit)
    args.out.write_text(
        json.dumps(artifact, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    print(f"wrote {args.out.name}")
    print(f"clips scanned {artifact['clipsScanned']}, combinations {artifact['combinationsTested']}")
    separable = [f for f in artifact["findings"] if f["verdict"] == "SEPARABLE"]
    marginal = [f for f in artifact["findings"] if f["verdict"] == "MARGINAL"]
    print(f"SEPARABLE {len(separable)}, MARGINAL {len(marginal)}\n")
    for f in separable:
        print(
            f"  {f['losoBalancedAccuracy']:.1%}  {f['exercise']:14} {f['chain']:9}{f['extreme']:4}"
            f" n={f['clips']:5} subj={f['subjects']:3} thr={f['pooledThresholdDegrees']:3}deg"
            f" {f['direction']:9} :: {f['condition']}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

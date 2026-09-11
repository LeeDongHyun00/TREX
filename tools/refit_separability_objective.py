"""Ask whether the separability ceilings are real, or artefacts of how they were fitted.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
AIHUB_LABEL_STATISTICAL_ANALYSIS (non-commercial educational scope). Labels only; reads no imagery.

Why this exists
---------------
Gate 1 decides whether an exercise is worth spending inference on, and thirteen of the app's
eighteen exercises are uncalibrated because of where that gate put them. Two properties of how the
gate was computed are worth questioning before accepting its verdicts as facts about the data:

  * **The fit maximises raw accuracy while the verdict reads balanced accuracy.** On a condition
    that is 77% true, the raw-optimal threshold drifts toward predicting the majority, which is
    precisely what balanced accuracy punishes. Fitting the metric being judged can only help it.
  * **The fold unit is the participant folder within one capture day.** The Z code in the image
    key is a dataset-wide participant id, so a person filmed on several days is currently several
    "subjects", and a held-out fold can be trained on that same person's other days. The rest of
    this repo declares LEAVE_ONE_GLOBAL_Z_SUBJECT_OUT.

The first lifts scores, the second lowers them, and neither effect is knowable without measuring.
This tool recomputes the four combinations over the same labels so the corrected ceiling can be
compared with the published one, and reports which exercises move across the 0.75 gate.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "tools"))

from survey_aihub_angle_separability import (  # noqa: E402
    CONTAMINATED_TYPE_CODES,
    FILENAME,
    MARGINAL_LOSO,
    MINIMUM_CLIPS,
    MINIMUM_MINORITY_FRACTION,
    MINIMUM_SUBJECTS,
    SEPARABLE_LOSO,
    _clip_extremes,
)

SUBJECT_PATTERN = re.compile(r"-(Z\d+)_")


def _confusion(rows: list[tuple[float, bool]], threshold: float) -> tuple[int, int, int, int]:
    tp = fn = tn = fp = 0
    for value, label in rows:
        predicted = value <= threshold
        if label and predicted:
            tp += 1
        elif label:
            fn += 1
        elif predicted:
            fp += 1
        else:
            tn += 1
    return tp, fn, tn, fp


def _fit(rows: list[tuple[float, bool]], objective: str) -> int | None:
    """The threshold maximising the requested objective, scanning every candidate cut."""
    positives = sum(1 for _, label in rows if label)
    negatives = len(rows) - positives
    if not positives or not negatives:
        return None

    order = sorted(rows, key=lambda r: r[0])
    best_threshold = math.floor(order[0][0]) - 1
    tp, fn, tn, fp = _confusion(rows, best_threshold)
    best_score = _score(tp, fn, tn, fp, objective)

    pos_seen = neg_seen = 0
    index = 0
    total = len(rows)
    while index < total:
        value = order[index][0]
        while index < total and order[index][0] == value:
            if order[index][1]:
                pos_seen += 1
            else:
                neg_seen += 1
            index += 1
        tp, fn = pos_seen, positives - pos_seen
        fp, tn = neg_seen, negatives - neg_seen
        score = _score(tp, fn, tn, fp, objective)
        if score > best_score:
            best_score = score
            best_threshold = round(value)
    return best_threshold


def _score(tp: int, fn: int, tn: int, fp: int, objective: str) -> float:
    if objective == "raw":
        total = tp + fn + tn + fp
        return (tp + tn) / total if total else 0.0
    positives, negatives = tp + fn, tn + fp
    if not positives or not negatives:
        return 0.0
    return (tp / positives + tn / negatives) / 2.0


def _loso(rows: list[tuple[float, bool, str]], objective: str) -> float | None:
    """Held-out balanced accuracy — always the verdict metric, whatever the fit optimised."""
    subjects = sorted({s for _, _, s in rows})
    if len(subjects) < MINIMUM_SUBJECTS:
        return None
    tp = fn = tn = fp = 0
    for subject in subjects:
        train = [(v, label) for v, label, s in rows if s != subject]
        test = [(v, label) for v, label, s in rows if s == subject]
        fitted = _fit(train, objective)
        if fitted is None or not test:
            continue
        f_tp, f_fn, f_tn, f_fp = _confusion(test, fitted)
        tp += f_tp
        fn += f_fn
        tn += f_tn
        fp += f_fp
    if not (tp + fn) or not (tn + fp):
        return None
    return (tp / (tp + fn) + tn / (tn + fp)) / 2.0


def build(label_root: Path, catalog_path: Path, only: set[str] | None) -> dict[str, Any]:
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

    # (exercise, condition, chain, extreme) -> [(angle, label, dayFolderSubject, globalSubject)]
    samples: dict[tuple[str, str, str, str], list[tuple[float, bool, str, str]]] = defaultdict(list)
    scanned = 0

    paths = sorted(label_root.rglob("D*-3d.json"))
    print(f"scanning {len(paths)} spatial label files", file=sys.stderr, flush=True)
    for i, path in enumerate(paths):
        if i % 4000 == 0:
            print(f"  {i}/{len(paths)}", file=sys.stderr, flush=True)
        match = FILENAME.match(path.name)
        if not match:
            continue
        day, folder, code = match.groups()
        if code in CONTAMINATED_TYPE_CODES:
            continue
        entry = by_code.get(code)
        if entry is None:
            continue
        exercise, conditions = entry
        if only is not None and exercise not in only:
            continue
        try:
            frames = json.loads(path.read_text(encoding="utf-8")).get("frames") or []
        except (OSError, ValueError):
            continue
        if not frames:
            continue
        extremes = _clip_extremes(frames)
        if not extremes:
            continue

        # The global participant id lives in the 2D file's image key, not in either filename.
        planar = path.with_name(path.name.replace("-3d.json", ".json"))
        global_subject = f"D{day}-{folder}"
        try:
            planar_frames = json.loads(planar.read_text(encoding="utf-8")).get("frames") or []
            if planar_frames:
                found = SUBJECT_PATTERN.search(planar_frames[0]["view1"]["img_key"])
                if found:
                    global_subject = found.group(1)
        except (OSError, ValueError, KeyError):
            pass

        day_subject = f"D{day}-{folder}"
        for condition, value in conditions.items():
            for (chain, extreme), angle in extremes.items():
                samples[(exercise, condition, chain, extreme)].append(
                    (angle, value, day_subject, global_subject)
                )
        scanned += 1

    findings: list[dict[str, Any]] = []
    for (exercise, condition, chain, extreme), rows in samples.items():
        if len(rows) < MINIMUM_CLIPS:
            continue
        positives = sum(1 for _, label, _, _ in rows if label)
        minority = min(positives, len(rows) - positives) / len(rows)
        if minority < MINIMUM_MINORITY_FRACTION:
            continue

        day_rows = [(v, label, s) for v, label, s, _ in rows]
        global_rows = [(v, label, g) for v, label, _, g in rows]
        scores = {
            "publishedRawFitDaySubject": _loso(day_rows, "raw"),
            "balancedFitDaySubject": _loso(day_rows, "balanced"),
            "rawFitGlobalSubject": _loso(global_rows, "raw"),
            "correctedBalancedFitGlobalSubject": _loso(global_rows, "balanced"),
        }
        if scores["correctedBalancedFitGlobalSubject"] is None:
            continue
        findings.append(
            {
                "exercise": exercise,
                "condition": condition,
                "chain": chain,
                "extreme": extreme,
                "clips": len(rows),
                "conditionTrue": positives,
                "daySubjects": len({s for _, _, s, _ in rows}),
                "globalSubjects": len({g for _, _, _, g in rows}),
                **{k: (round(v, 4) if v is not None else None) for k, v in scores.items()},
            }
        )

    findings.sort(key=lambda f: -f["correctedBalancedFitGlobalSubject"])
    return {
        "artifactKind": "TREX_AIHUB_SEPARABILITY_OBJECTIVE_REFIT",
        "artifactVersion": 1,
        "rightsAuthorization": {
            "manifestId": "trex.aihub-research-use-rights.v1",
            "permittedOperation": "AIHUB_LABEL_STATISTICAL_ANALYSIS",
        },
        "question": (
            "Are the published gate-1 ceilings properties of the data, or of fitting raw accuracy "
            "while judging balanced accuracy and splitting one participant across capture days?"
        ),
        "variants": {
            "publishedRawFitDaySubject": "What docs/aihub-angle-separability.v1.json reports.",
            "balancedFitDaySubject": "Fit the metric being judged; keep the published fold unit.",
            "rawFitGlobalSubject": "Keep the published objective; use the global participant.",
            "correctedBalancedFitGlobalSubject": "Both corrections. The honest ceiling.",
        },
        "gates": {"separable": SEPARABLE_LOSO, "marginal": MARGINAL_LOSO},
        "clipsScanned": scanned,
        "findings": findings,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label_root", type=Path)
    parser.add_argument(
        "--catalog",
        type=Path,
        default=REPO_ROOT / "docs" / "aihub-exercise-catalog.json",
    )
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--only", action="append", default=None)
    args = parser.parse_args()

    only = (
        {unicodedata.normalize("NFC", n).strip() for n in args.only} if args.only else None
    )
    artifact = build(args.label_root, args.catalog, only)
    args.out.write_text(
        json.dumps(artifact, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {args.out}")

    best: dict[str, dict[str, Any]] = {}
    for f in artifact["findings"]:
        cur = best.get(f["exercise"])
        if cur is None or f["correctedBalancedFitGlobalSubject"] > cur["correctedBalancedFitGlobalSubject"]:
            best[f["exercise"]] = f
    print(
        f"\n{'exercise':22} {'chain':9}{'ext':4} {'published':>10} {'balFit':>8} "
        f"{'globalZ':>8} {'corrected':>10} {'delta':>7}  gate"
    )
    for name in sorted(best, key=lambda n: -best[n]["correctedBalancedFitGlobalSubject"]):
        f = best[name]
        pub = f["publishedRawFitDaySubject"]
        cor = f["correctedBalancedFitGlobalSubject"]
        delta = f"{cor - pub:+.3f}" if pub is not None else "—"
        gate = "SEPARABLE" if cor >= SEPARABLE_LOSO else (
            "MARGINAL" if cor >= MARGINAL_LOSO else "NOT_SEPARABLE"
        )
        print(
            f"{name:22} {f['chain']:9}{f['extreme']:4} "
            f"{(f'{pub:.4f}' if pub is not None else '—'):>10} "
            f"{f['balancedFitDaySubject']:>8} {f['rawFitGlobalSubject']:>8} "
            f"{cor:>10.4f} {delta:>7}  {gate}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

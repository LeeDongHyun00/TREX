"""Score the shipped repetition counter against MM-Fit's labelled sets and its rest.

Reads the capture indices written by `extract_mmfit_captures.py` and the counts written by the
Kotlin harness `ExternalCaptureCorpusReplay`, and produces the first repetition-counting card this
project has ever had.

What is reported, and why it is not called rep F1
------------------------------------------------
MM-Fit labels a *set*: start frame, end frame, and how many repetitions it contained. It does not
label where each repetition began and ended. A temporal rep F1 -- the roadmap's SL-1 gate, matching
predicted repetitions to true ones within a tolerance -- therefore cannot be computed from it, and
naming one here would be inventing the very boundaries the dataset withholds.

What the labels do support is stated instead, and each is a number the roadmap actually asks for:

  * **Count error per set** -- mean absolute error, the normalised MAE and off-by-one accuracy
    that the repetition-counting literature reports (so these rows can be read beside published
    numbers), and exact-match rate. All of them need only a count.
  * **Count-level F1** -- treating each set's overlap as `min(predicted, true)` true positives,
    `max(0, predicted - true)` false positives and `max(0, true - predicted)` false negatives. It
    is an upper bound on temporal F1, not a substitute: a counter that fires the right number of
    times at the wrong moments scores perfectly here and would fail a boundary-matched test.
  * **False firing, split by what caused it.** A session-wide excess confounds two different
    faults, so they are reported apart. **Rest firing** is what a spec counts over the frames no
    labelled set claims -- standing about, walking between stations, putting a dumbbell down -- and
    is a hysteresis fault with no excuse. **Cross-exercise firing** is what a spec counts while the
    subject performs a different exercise, which the policy already admits the definition gates
    cannot always prevent, and which the app defends against by having the user choose the exercise
    rather than by detecting it. No scripted dataset can produce either number; MM-Fit can, because
    its sessions were never trimmed.

Reading the fidelity column is not optional. MM-Fit's camera is frontal while the engine asks for
lateral placement on all but two of these exercises, and several movements are the unloaded or
differently-postured cousins of the shipped spec. A low score in that column is at least as likely
to be measuring the mismatch as the detector.
"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
from collections import defaultdict
from pathlib import Path
from typing import Any


def load_indices(root: Path) -> list[dict[str, Any]]:
    indices = []
    for path in sorted(root.glob("*/*_index.json")):
        indices.append(json.loads(path.read_text(encoding="utf-8")))
    if not indices:
        raise SystemExit(f"no *_index.json under {root}")
    return indices


def load_counts(
    path: Path, gravity: str = "none"
) -> dict[tuple[str, str, str], dict[str, int]]:
    """Keyed by (workout, capture file, spec name), for one gravity condition.

    The harness replays each posture-bearing exercise twice -- once as a device whose sensor said
    nothing, once as the level camera the recording was actually made with -- and both rows are
    kept in the file. Selecting a condition here rather than merging them keeps the two paths
    separable, which matters because only one of them is what a phone without the sensor does.
    """
    counts: dict[tuple[str, str, str], dict[str, int]] = {}
    with path.open("r", newline="") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            if row.get("gravity", "none") != gravity:
                continue
            key = (row["workout"], row["capture"], row["spec"])
            counts[key] = {
                "frames": int(row["frames"]),
                "reps": int(row["reps"]),
                "uncounted": int(row["uncounted"]),
                "role": row["role"],
            }
    return counts


def summarise(root: Path, counts_path: Path, gravity: str = "none") -> dict[str, Any]:
    indices = load_indices(root)
    counts = load_counts(counts_path, gravity)

    per_set: list[dict[str, Any]] = []
    session_owed: dict[tuple[str, str], int] = defaultdict(int)
    fidelity: dict[str, str] = {}
    notes: dict[str, str] = {}

    for index in indices:
        workout = index["workout"]
        for entry in index["sets"]:
            spec = entry["exercise"]
            if not spec:
                continue
            fidelity[spec] = entry["fidelity"]
            notes[spec] = entry["mappingNote"]
            observed = counts.get((workout, entry["capture"], spec))
            if observed is None:
                continue
            per_set.append(
                {
                    "workout": workout,
                    "activity": entry["activity"],
                    "exercise": spec,
                    "fidelity": entry["fidelity"],
                    "labelled": entry["labelledRepetitions"],
                    "counted": observed["reps"],
                    "uncounted": observed["uncounted"],
                    "frames": entry["frames"],
                }
            )
            session_owed[(workout, spec)] += entry["labelledRepetitions"]

    by_exercise: dict[str, dict[str, Any]] = {}
    for row in per_set:
        bucket = by_exercise.setdefault(
            row["exercise"],
            {
                "exercise": row["exercise"],
                "fidelity": row["fidelity"],
                "mappingNote": notes.get(row["exercise"], ""),
                "sets": 0,
                "labelledRepetitions": 0,
                "countedRepetitions": 0,
                "uncountedAttempts": 0,
                "errors": [],
                "truePositives": 0,
                "falsePositives": 0,
                "falseNegatives": 0,
            },
        )
        bucket["sets"] += 1
        bucket["labelledRepetitions"] += row["labelled"]
        bucket["countedRepetitions"] += row["counted"]
        bucket["uncountedAttempts"] += row["uncounted"]
        bucket["errors"].append(row["counted"] - row["labelled"])
        bucket.setdefault("normalisedErrors", []).append(
            abs(row["counted"] - row["labelled"]) / row["labelled"] if row["labelled"] else 0.0
        )
        bucket["truePositives"] += min(row["counted"], row["labelled"])
        bucket["falsePositives"] += max(0, row["counted"] - row["labelled"])
        bucket["falseNegatives"] += max(0, row["labelled"] - row["counted"])

    for bucket in by_exercise.values():
        errors = bucket.pop("errors")
        normalised = bucket.pop("normalisedErrors")
        bucket["meanAbsoluteError"] = round(
            statistics.fmean(abs(error) for error in errors), 3
        )
        # The literature's "MAE" for repetition counting is this one: the per-video error divided
        # by that video's true count, averaged. Reported under its own name so the two are never
        # mistaken for each other.
        bucket["normalisedMeanAbsoluteError"] = round(statistics.fmean(normalised), 3)
        bucket["meanSignedError"] = round(statistics.fmean(errors), 3)
        bucket["offByOneAccuracy"] = round(
            sum(1 for error in errors if abs(error) <= 1) / len(errors), 3
        )
        bucket["exactMatchRate"] = round(
            sum(1 for error in errors if error == 0) / len(errors), 3
        )
        true_positive = bucket["truePositives"]
        precision_denominator = true_positive + bucket["falsePositives"]
        recall_denominator = true_positive + bucket["falseNegatives"]
        precision = true_positive / precision_denominator if precision_denominator else 0.0
        recall = true_positive / recall_denominator if recall_denominator else 0.0
        bucket["countPrecision"] = round(precision, 3)
        bucket["countRecall"] = round(recall, 3)
        bucket["countF1"] = round(
            2 * precision * recall / (precision + recall) if precision + recall else 0.0, 3
        )

    # Rest firing: the frames no labelled set claims, replayed as each exercise. Nothing here is a
    # repetition of anything, so every count is a false positive with no mapping caveat attached.
    rest: dict[str, dict[str, Any]] = {}
    for index in indices:
        workout = index["workout"]
        fps = index["video"]["fps"] or 30.0
        seen_workout: set[str] = set()
        for (wk, capture, spec), observed in counts.items():
            if wk != workout or observed["role"] != "rest":
                continue
            bucket = rest.setdefault(
                spec,
                {"exercise": spec, "workouts": 0, "gaps": 0, "restFrames": 0, "counted": 0},
            )
            if spec not in seen_workout:
                bucket["workouts"] += 1
                seen_workout.add(spec)
            bucket["gaps"] += 1
            bucket["restFrames"] += observed["frames"]
            bucket["counted"] += observed["reps"]
            bucket["fps"] = fps
    for bucket in rest.values():
        minutes = bucket["restFrames"] / max(bucket.pop("fps", 30.0), 1e-6) / 60.0
        bucket["restMinutes"] = round(minutes, 1)
        bucket["countedPerRestMinute"] = round(bucket["counted"] / minutes, 3) if minutes else None

    # Cross-exercise firing: a labelled set of one activity, replayed as a different exercise. The
    # denominator is that activity's own labelled repetitions, so a value near 1.0 means the spec
    # counted the other movement about as readily as its performer did.
    cross: dict[tuple[str, str], dict[str, Any]] = {}
    for index in indices:
        workout = index["workout"]
        for entry in index["sets"]:
            for (wk, capture, spec), observed in counts.items():
                if wk != workout or capture != entry["capture"]:
                    continue
                if observed["role"] != "cross":
                    continue
                key = (entry["activity"], spec)
                bucket = cross.setdefault(
                    key,
                    {
                        "performedActivity": entry["activity"],
                        "countedAs": spec,
                        "sets": 0,
                        "performedRepetitions": 0,
                        "countedRepetitions": 0,
                        "activityIsMapped": bool(entry["exercise"]),
                    },
                )
                bucket["sets"] += 1
                bucket["performedRepetitions"] += entry["labelledRepetitions"]
                bucket["countedRepetitions"] += observed["reps"]
    for bucket in cross.values():
        performed = bucket["performedRepetitions"]
        bucket["countedPerPerformedRepetition"] = (
            round(bucket["countedRepetitions"] / performed, 3) if performed else None
        )

    # The session total is kept as a cross-check on the two above: it is what a user would see if
    # they left one exercise selected for the whole workout, and it should land near the sum of the
    # exercise's own sets plus its rest and cross firing.
    outside: dict[str, dict[str, Any]] = {}
    for index in indices:
        workout = index["workout"]
        session_capture = f"{workout}_full.trexcap"
        for (wk, capture, spec), observed in counts.items():
            if wk != workout or capture != session_capture:
                continue
            bucket = outside.setdefault(
                spec,
                {
                    "exercise": spec,
                    "workouts": 0,
                    "sessionCounted": 0,
                    "owedBySets": 0,
                    "sessionFrames": 0,
                },
            )
            bucket["workouts"] += 1
            bucket["sessionCounted"] += observed["reps"]
            bucket["owedBySets"] += session_owed.get((workout, spec), 0)
            bucket["sessionFrames"] += observed["frames"]
    for bucket in outside.values():
        bucket["excessOverOwned"] = bucket["sessionCounted"] - bucket["owedBySets"]
        bucket["excessPerWorkout"] = round(
            bucket["excessOverOwned"] / max(bucket["workouts"], 1), 2
        )

    totals_errors = [row["counted"] - row["labelled"] for row in per_set]
    true_positive = sum(min(row["counted"], row["labelled"]) for row in per_set)
    false_positive = sum(max(0, row["counted"] - row["labelled"]) for row in per_set)
    false_negative = sum(max(0, row["labelled"] - row["counted"]) for row in per_set)
    precision = true_positive / (true_positive + false_positive) if per_set else 0.0
    recall = true_positive / (true_positive + false_negative) if per_set else 0.0

    return {
        "artifactKind": "TREX_MMFIT_REP_COUNTING",
        "artifactVersion": 1,
        "question": (
            "How many of the repetitions in a labelled set does the shipped detector count, and "
            "how often does it count something outside the set?"
        ),
        "limitations": [
            "MM-Fit labels sets, not repetitions, so this is count error and not a "
            "boundary-matched rep F1; the roadmap's SL-1 gate still needs per-repetition truth.",
            "The camera is fixed and frontal. The engine asks for lateral placement on every "
            "mapped exercise except the lateral raise, so most rows measure a placement the app "
            "would advise against.",
            "MM-Fit's squats and lunges are bodyweight and its presses and rows are dumbbell; "
            "several shipped specs were written for the barbell or lying variant. See fidelity.",
            "A studio recording: one subject, clear background, consistent lighting. Says nothing "
            "about frame rate, thermal behaviour, or the phone's own capture path.",
            "No device sensor accompanies the video, so every gravity-dependent posture clause "
            "abstains, exactly as it does on a phone without the sensor.",
        ],
        "source": {
            "dataset": "MM-Fit",
            "citation": "Strömbäck, Huang and Radu, IMWUT 2020, doi:10.1145/3432701",
            "licence": "CC BY 4.0",
            "workouts": sorted(index["workout"] for index in indices),
        },
        "model": indices[0]["model"],
        "detectionOutcomes": {
            key: sum(index["detectionOutcomes"].get(key, 0) for index in indices)
            for key in ("single_pose", "no_pose", "ambiguous_multi_person")
        },
        "overall": {
            "sets": len(per_set),
            "labelledRepetitions": sum(row["labelled"] for row in per_set),
            "countedRepetitions": sum(row["counted"] for row in per_set),
            "meanAbsoluteError": round(
                statistics.fmean(abs(error) for error in totals_errors), 3
            )
            if totals_errors
            else None,
            "meanSignedError": round(statistics.fmean(totals_errors), 3)
            if totals_errors
            else None,
            "normalisedMeanAbsoluteError": round(
                statistics.fmean(
                    abs(row["counted"] - row["labelled"]) / row["labelled"]
                    for row in per_set
                    if row["labelled"]
                ),
                3,
            )
            if per_set
            else None,
            "offByOneAccuracy": round(
                sum(1 for error in totals_errors if abs(error) <= 1) / len(totals_errors), 3
            )
            if totals_errors
            else None,
            "exactMatchRate": round(
                sum(1 for error in totals_errors if error == 0) / len(totals_errors), 3
            )
            if totals_errors
            else None,
            "countPrecision": round(precision, 3),
            "countRecall": round(recall, 3),
            "countF1": round(
                2 * precision * recall / (precision + recall) if precision + recall else 0.0, 3
            ),
        },
        "byExercise": sorted(by_exercise.values(), key=lambda row: row["exercise"]),
        "restFiring": sorted(rest.values(), key=lambda row: row["exercise"]),
        "crossExerciseFiring": sorted(
            cross.values(),
            key=lambda row: -(row["countedPerPerformedRepetition"] or 0.0),
        ),
        "sessionTotals": sorted(outside.values(), key=lambda row: row["exercise"]),
        "sets": per_set,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("captures", type=Path, help="directory of wNN/ capture folders")
    parser.add_argument("--counts", type=Path, default=None, help="replay-counts.tsv")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument(
        "--gravity",
        choices=("none", "level-camera"),
        default="none",
        help="which replay condition to score: 'none' is a device whose sensor said nothing, "
        "'level-camera' supplies the direction a fixed studio camera would have reported",
    )
    args = parser.parse_args(argv)

    counts_path = args.counts or (args.captures / "replay-counts.tsv")
    artifact = summarise(args.captures, counts_path, args.gravity)
    artifact["gravityCondition"] = args.gravity

    # Both conditions, side by side, for the exercises that carry a posture clause. The clause is
    # the only thing separating a face-down press from a standing one -- every included angle they
    # make is the same -- so the difference between these two rows is the measured worth of the
    # device sensor, and the sensorless row is the path the app already discloses as fail-open.
    other = "level-camera" if args.gravity == "none" else "none"
    try:
        counterpart = summarise(args.captures, counts_path, other)
    except SystemExit:
        counterpart = None
    if counterpart:
        by_condition = {
            args.gravity: {
                "byExercise": artifact["byExercise"],
                "restFiring": artifact["restFiring"],
                "crossExerciseFiring": artifact["crossExerciseFiring"],
            },
            other: {
                "byExercise": counterpart["byExercise"],
                "restFiring": counterpart["restFiring"],
                "crossExerciseFiring": counterpart["crossExerciseFiring"],
            },
        }
        artifact["gravityComparison"] = {
            "note": (
                "Only exercises with a posture clause appear under 'level-camera'; for every "
                "other exercise the two conditions are the same replay."
            ),
            "conditions": by_condition,
        }
    args.out.write_text(json.dumps(artifact, indent=2, ensure_ascii=False), encoding="utf-8")

    overall = artifact["overall"]
    print(
        f"sets={overall['sets']} labelled={overall['labelledRepetitions']} "
        f"counted={overall['countedRepetitions']} MAE={overall['meanAbsoluteError']} "
        f"OBO={overall['offByOneAccuracy']} countF1={overall['countF1']}"
    )
    for row in artifact["byExercise"]:
        print(
            f"  {row['exercise']:26s} {row['fidelity']:18s} sets={row['sets']:3d} "
            f"MAE={row['meanAbsoluteError']:6.2f} nMAE={row['normalisedMeanAbsoluteError']:.3f} "
            f"OBO={row['offByOneAccuracy']:.2f} "
            f"F1={row['countF1']:.3f} counted={row['countedRepetitions']:4d}/"
            f"{row['labelledRepetitions']:4d}"
        )
    print("  -- firing during rest --")
    for row in artifact["restFiring"]:
        print(
            f"  {row['exercise']:26s} counted={row['counted']:4d} over "
            f"{row['restMinutes']:6.1f} rest-min in {row['gaps']:3d} gaps "
            f"= {row['countedPerRestMinute']:.2f}/min"
        )
    print("  -- top cross-exercise confusions --")
    for row in artifact["crossExerciseFiring"][:10]:
        print(
            f"  {row['performedActivity']:24s} counted as {row['countedAs']:26s} "
            f"{row['countedRepetitions']:4d}/{row['performedRepetitions']:4d} reps "
            f"= {row['countedPerPerformedRepetition']:.2f}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

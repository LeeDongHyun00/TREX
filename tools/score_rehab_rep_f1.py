"""Boundary-matched repetition F1 for the shipped counter, against REHAB24-6.

What this computes that the MM-Fit card could not
-------------------------------------------------
MM-Fit says how many repetitions a set contained. REHAB24-6 says where each one was: first frame
and last frame, for all 1,072 of them. That difference is the whole point of this file. A counter
that fires the right number of times at the wrong moments passes a count comparison and fails here,
which is why the roadmap's SL-1 gate is written in terms of repetition F1 rather than count error.

Matching rule
-------------
The session reports a repetition at the instant its excursion closes -- the frame whose acceptance
made the mark appear, which is also the instant the app speaks the count. That instant lands near
the *end* of the movement, not its middle, so a predicted repetition is credited to a true one when
it falls inside that repetition's interval extended by a tolerance at both ends. The matching is
one-to-one and greedy by time: each true repetition takes at most one prediction and each
prediction is spent once, so two counts inside one repetition leave the second as a false positive
rather than quietly collapsing into a hit.

Two tolerances are reported. The strict one is zero -- the count must land inside the annotated
interval. The loose one is a second, which is the figure the roadmap names and roughly a third of
this corpus's median repetition (3.6 s). Reporting both keeps the tolerance from being the place
the result is decided.

Rights
------
REHAB24-6 is CC BY-NC 4.0: measurement only. Nothing here fits or emits a threshold.
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
    indices = [
        json.loads(path.read_text(encoding="utf-8")) for path in sorted(root.glob("*/*_index.json"))
    ]
    if not indices:
        raise SystemExit(f"no *_index.json under {root}")
    return indices


def load_conditions(path: Path) -> dict[tuple[str, int], dict[str, Any]]:
    """Recording conditions per repetition, read from the corpus's own segmentation file.

    The capture index records what the model saw; this records what the room was doing. They are
    kept apart because the second is annotation the corpus supplies and the first is measurement
    this repository made, and the splits below depend on not confusing the two.
    """
    conditions: dict[tuple[str, int], dict[str, Any]] = {}
    if not path.is_file():
        return conditions
    with path.open("r", newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle, delimiter=";"):
            conditions[(row["video_id"], int(row["repetition_number"]))] = {
                "lightsOn": row["lights_on"] == "1",
                "extraPersonCam17": int(row["extra_person_in_cam17"] or 0),
                "extraPersonCam18": int(row["extra_person_in_cam18"] or 0),
            }
    return conditions


def load_counts(path: Path, gravity: str) -> dict[tuple[str, str], dict[str, Any]]:
    """Keyed by (capture directory name, spec), for one gravity condition."""
    rows: dict[tuple[str, str], dict[str, Any]] = {}
    with path.open("r", newline="") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            if row.get("gravity", "none") != gravity:
                continue
            counted = [int(value) for value in row["countedAtMs"].split(",") if value]
            rows[(row["workout"], row["spec"])] = {
                "reps": int(row["reps"]),
                "uncounted": int(row["uncounted"]),
                "countedAtMs": counted,
                "frames": int(row["frames"]),
            }
    return rows


def match(
    predicted_ms: list[int],
    truths: list[dict[str, Any]],
    tolerance_ms: int,
) -> tuple[int, list[int], list[dict[str, Any]], list[int]]:
    """One-to-one greedy matching in time.

    Returns (hits, unmatched predictions, misses, offsets), where an offset is how far a matched
    prediction sat from the end of the repetition it was credited to. The corpus annotates
    back-to-back intervals -- repetition k ends on the frame before k+1 begins -- so a count that
    consistently lands a little after the annotated end would drift into the next repetition's
    window and be scored there. The offsets say whether that is happening rather than leaving it
    to be assumed either way.
    """
    remaining = sorted(predicted_ms)
    hits = 0
    misses: list[dict[str, Any]] = []
    offsets: list[int] = []
    used: set[int] = set()
    for truth in sorted(truths, key=lambda row: row["firstMs"]):
        low = truth["firstMs"] - tolerance_ms
        high = truth["lastMs"] + tolerance_ms
        taken = None
        for position, value in enumerate(remaining):
            if position in used:
                continue
            if low <= value <= high:
                taken = position
                break
        if taken is None:
            misses.append(truth)
        else:
            used.add(taken)
            hits += 1
            offsets.append(remaining[taken] - truth["lastMs"])
    unmatched = [value for position, value in enumerate(remaining) if position not in used]
    return hits, unmatched, misses, offsets


def score(
    root: Path,
    counts_path: Path,
    segmentation_path: Path,
    gravity: str,
    tolerances_ms: list[int],
    cameras: list[str] | None = None,
) -> dict[str, Any]:
    indices = load_indices(root)
    # One capture root holds both cameras, and both filmed the same repetitions from the same
    # station. Scoring them together would count every true repetition twice and call the result
    # an overall, so a camera is chosen and the other is scored in its own artifact.
    if cameras:
        indices = [index for index in indices if index["video"]["camera"] in cameras]
        if not indices:
            raise SystemExit(f"no captures for camera(s) {cameras} under {root}")
    counts = load_counts(counts_path, gravity)
    conditions = load_conditions(segmentation_path)

    per_video: list[dict[str, Any]] = []
    for index in indices:
        video = index["video"]
        directory = f"{video['id']}_{video['camera']}"
        truths_by_spec: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for repetition in index["repetitions"]:
            if not repetition["exercise"]:
                continue
            condition = conditions.get((video["id"], repetition["repetition"]), {})
            truths_by_spec[repetition["exercise"]].append({**repetition, **condition})

        # The corpus counts bystanders per camera, and the two disagree because the portrait
        # framing sees less of the room. Scoring camera 18 against camera 17's column would
        # attribute the wrong videos to the sole-subject split.
        extra_person_field = f"extraPersonCam{video['camera']}"
        for spec, truths in truths_by_spec.items():
            observed = counts.get((directory, spec))
            if observed is None:
                continue
            row: dict[str, Any] = {
                "video": video["id"],
                "camera": video["camera"],
                # The corpus states which way the subject faced camera 17. Both cameras stand at
                # the same station -- they differ in framing, one landscape and one portrait --
                # so the column is carried under its own name rather than restated as if it had
                # been measured for camera 18 as well.
                "cam17Orientation": truths[0]["cam17Orientation"],
                "personId": truths[0]["personId"],
                "exercise": spec,
                "fidelity": truths[0]["fidelity"],
                "trueRepetitions": len(truths),
                "correctRepetitions": sum(1 for row in truths if row["correct"]),
                # A third of this corpus was filmed with somebody else in shot. The runtime clears
                # the person lock on an ambiguous frame and discards the excursion in flight, by
                # design, so a video like that measures the abstention rather than the counter --
                # which is why it is split out rather than averaged in.
                "extraPersonInShot": truths[0].get(extra_person_field, 0),
                "soleSubject": all(row.get(extra_person_field, 0) == 0 for row in truths),
                "lightsOn": truths[0].get("lightsOn", True),
                "predicted": observed["reps"],
                "uncounted": observed["uncounted"],
            }
            for tolerance in tolerances_ms:
                hits, unmatched, misses, offsets = match(
                    observed["countedAtMs"], truths, tolerance
                )
                row[f"offsets@{tolerance}"] = offsets
                row[f"hits@{tolerance}"] = hits
                row[f"falsePositives@{tolerance}"] = len(unmatched)
                row[f"misses@{tolerance}"] = len(misses)
                # Whether the misses were the repetitions a human called wrong. The engine counts a
                # repetition for its arc, not its quality, so a systematic gap here would mean the
                # arc and the quality are not as separate as the design assumes.
                row[f"missesAmongIncorrect@{tolerance}"] = sum(
                    1 for miss in misses if not miss["correct"]
                )
            per_video.append(row)

    def aggregate(rows: list[dict[str, Any]], tolerance: int) -> dict[str, Any]:
        hits = sum(row[f"hits@{tolerance}"] for row in rows)
        false_positives = sum(row[f"falsePositives@{tolerance}"] for row in rows)
        misses = sum(row[f"misses@{tolerance}"] for row in rows)
        precision = hits / (hits + false_positives) if hits + false_positives else 0.0
        recall = hits / (hits + misses) if hits + misses else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        offsets = [value for row in rows for value in row[f"offsets@{tolerance}"]]
        return {
            "matchedOffsetMedianMs": round(statistics.median(offsets)) if offsets else None,
            "matchedOffsetP05Ms": round(
                statistics.quantiles(offsets, n=20)[0]
            ) if len(offsets) > 20 else None,
            "matchedOffsetP95Ms": round(
                statistics.quantiles(offsets, n=20)[18]
            ) if len(offsets) > 20 else None,
            "trueRepetitions": sum(row["trueRepetitions"] for row in rows),
            "predicted": sum(row["predicted"] for row in rows),
            "hits": hits,
            "falsePositives": false_positives,
            "misses": misses,
            "missesAmongIncorrect": sum(
                row[f"missesAmongIncorrect@{tolerance}"] for row in rows
            ),
            "precision": round(precision, 3),
            "recall": round(recall, 3),
            "repF1": round(f1, 3),
        }

    def group(key: str) -> dict[str, dict[str, Any]]:
        buckets: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for row in per_video:
            buckets[str(row[key])].append(row)
        return {
            name: {
                "videos": len(rows),
                **{
                    f"tolerance{tolerance}ms": aggregate(rows, tolerance)
                    for tolerance in tolerances_ms
                },
            }
            for name, rows in sorted(buckets.items())
        }

    # What the other specs counted while this exercise was performed. Each recording is one
    # movement repeated, so anything a different spec counted here is a count for a movement the
    # user did not select -- the same failure the MM-Fit card measured, now on a corpus that also
    # says where the real repetitions were.
    cross: dict[tuple[str, str], dict[str, Any]] = {}
    for index in indices:
        video = index["video"]
        directory = f"{video['id']}_{video['camera']}"
        performed = {
            repetition["exercise"] for repetition in index["repetitions"] if repetition["exercise"]
        }
        performed_name = ", ".join(sorted(performed)) or "(unmapped)"
        true_count = sum(1 for repetition in index["repetitions"] if repetition["exercise"])
        if not true_count:
            true_count = len(index["repetitions"])
        for (folder, spec), observed in counts.items():
            if folder != directory or spec in performed:
                continue
            bucket = cross.setdefault(
                (performed_name, spec),
                {
                    "performed": performed_name,
                    "countedAs": spec,
                    "videos": 0,
                    "trueRepetitions": 0,
                    "countedRepetitions": 0,
                },
            )
            bucket["videos"] += 1
            bucket["trueRepetitions"] += true_count
            bucket["countedRepetitions"] += observed["reps"]
    for bucket in cross.values():
        bucket["countedPerPerformedRepetition"] = (
            round(bucket["countedRepetitions"] / bucket["trueRepetitions"], 3)
            if bucket["trueRepetitions"]
            else None
        )

    return {
        "artifactKind": "TREX_REHAB_REP_F1",
        "artifactVersion": 1,
        "question": (
            "Does the shipped counter's repetition land where the annotated repetition was, not "
            "merely as often?"
        ),
        "matching": {
            "predictedInstant": "the frame whose acceptance published the repetition mark",
            "rule": "one-to-one greedy in time against [firstFrame, lastFrame] widened by the "
            "tolerance",
            "tolerancesMs": tolerances_ms,
        },
        "gravityCondition": gravity,
        "cameras": sorted({index["video"]["camera"] for index in indices}),
        "limitations": [
            "REHAB24-6 is a rehabilitation corpus: table push-ups rather than floor push-ups, "
            "bodyweight squats and lunges, one arm at a time for the abduction. See fidelity.",
            "CC BY-NC 4.0. Measurement only; no shipped threshold may be fitted from it.",
            "Ten subjects in one room. Says nothing about frame rate, thermal behaviour, or the "
            "phone's own capture path.",
            "The predicted instant is the close of the excursion, so it sits near the end of the "
            "annotated interval by construction; the tolerance is applied to both ends rather "
            "than centred, and the strict row shows what happens with none at all.",
        ],
        "source": {
            "dataset": "REHAB24-6",
            "citation": "Zenodo record 13305826",
            "licence": "CC BY-NC 4.0",
        },
        "overall": {
            f"tolerance{tolerance}ms": aggregate(per_video, tolerance)
            for tolerance in tolerances_ms
        },
        "byExercise": group("exercise"),
        "bySoleSubject": group("soleSubject"),
        "byLightsOn": group("lightsOn"),
        "byCam17Orientation": group("cam17Orientation"),
        "byCamera": group("camera"),
        "crossExerciseFiring": sorted(
            cross.values(),
            key=lambda row: -(row["countedPerPerformedRepetition"] or 0.0),
        ),
        "perVideo": [
            {key: value for key, value in row.items() if not key.startswith("offsets@")}
            for row in per_video
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("captures", type=Path)
    parser.add_argument("--counts", type=Path, default=None)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--segmentation", type=Path, default=None)
    parser.add_argument(
        "--cameras",
        nargs="*",
        default=None,
        help="which camera's captures to score; both filmed the same repetitions, so scoring "
        "them together would double every true count",
    )
    parser.add_argument("--gravity", choices=("none", "level-camera"), default="none")
    # A curve rather than a verdict: zero says how many counts land inside the annotated interval
    # unaided, and the rest show how quickly the rest arrive. If the number only becomes
    # respectable at two seconds, the tolerance is doing the work and the reader can see that.
    parser.add_argument("--tolerances-ms", type=int, nargs="*", default=[0, 250, 500, 1000, 2000])
    args = parser.parse_args(argv)

    counts_path = args.counts or (args.captures / "replay-counts.tsv")
    segmentation_path = args.segmentation or (args.captures.parent / "Segmentation.csv")
    artifact = score(
        args.captures,
        counts_path,
        segmentation_path,
        args.gravity,
        args.tolerances_ms,
        args.cameras,
    )
    args.out.write_text(json.dumps(artifact, indent=2, ensure_ascii=False), encoding="utf-8")

    for tolerance in args.tolerances_ms:
        row = artifact["overall"][f"tolerance{tolerance}ms"]
        print(
            f"tolerance {tolerance:5d} ms: repF1={row['repF1']:.3f} "
            f"P={row['precision']:.3f} R={row['recall']:.3f} "
            f"hits={row['hits']} fp={row['falsePositives']} miss={row['misses']} "
            f"(true={row['trueRepetitions']}) offset med={row['matchedOffsetMedianMs']}ms"
        )
    for name, section in (
        ("exercise", "byExercise"),
        ("camera-17 orientation", "byCam17Orientation"),
        ("sole subject in shot", "bySoleSubject"),
        ("lights on", "byLightsOn"),
    ):
        print(f"  -- by {name} --")
        for key, rows in artifact[section].items():
            headline = rows[f"tolerance{args.tolerances_ms[-1]}ms"]
            print(
                f"  {key:28s} videos={rows['videos']:3d} true={headline['trueRepetitions']:4d} "
                f"repF1={headline['repF1']:.3f} P={headline['precision']:.3f} "
                f"R={headline['recall']:.3f}"
            )
    print("  -- counted while a different movement was performed --")
    for row in artifact["crossExerciseFiring"][:8]:
        print(
            f"  {row['performed']:28s} as {row['countedAs']:28s} "
            f"{row['countedRepetitions']:4d}/{row['trueRepetitions']:4d} = "
            f"{row['countedPerPerformedRepetition']}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

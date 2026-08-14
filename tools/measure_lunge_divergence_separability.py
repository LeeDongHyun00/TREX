"""Ask whether a cross-side divergence clause can tell a lunge from a squat.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
AIHUB_LABEL_STATISTICAL_ANALYSIS (non-commercial educational scope). Labels only; reads no imagery.

Why this exists
---------------
`docs/pose-heuristic-form-check.v1.md` §4.9 rule 5(c) refuses a definition gate for the three
lunges on a closure argument: the engine picks the camera-near leg and never learns whether that
is the front or the rear one, so any clause built from ANDed half-spaces over single-side
coordinates admits a box that a squat sits inside. The rule then names the one escape that
argument does not reach — a *divergence* ``abs(left - right)``, which is symmetric under swapping
the assignment and therefore not a coordinate half-space at all.

This tool measures whether that escape actually separates the two movements, so the refusal that
follows is checkable rather than asserted. It answers three questions on the dataset's own 3D
ground truth:

  1. Sweeping a divergence bound, what fraction of lunge clips would be discarded (false
     rejection) and what fraction of barbell-squat clips would be discarded (the impostor caught)?
     Reported for the HIP chain, which §4.9 rule 5(c) names, and for the KNEE chain, which it
     does not.
  2. Where does the *shallow* lunge population sit — the clips the dataset itself labels as not
     reaching ninety degrees, i.e. beginners, short steps and fatigued repetitions? That is the
     population a clause must not discard, and it is the false-rejection tail.
  3. How far apart are the two ankles during each movement, as a fraction of leg length? A
     lateral camera loses the far leg behind the near one when they are close, so this is a proxy
     for whether the clause can be observed at all — and it runs the wrong way if the movement
     that needs the clause is the one that hides it.

Every number is computed from 3D label coordinates, which is a ceiling: MediaPipe cannot beat
perfect ground truth, so a separation that fails here fails on a phone by more.

Generalisation unit is the global Z participant code parsed from the 2D metadata's ``img_key``,
matching LEAVE_ONE_GLOBAL_Z_SUBJECT_OUT elsewhere in this repository — the same person appears on
several capture days, and treating each day as a separate subject would understate the spread.

Usage
-----
    python tools/measure_lunge_divergence_separability.py \
        --data data/013.피트니스자세/1.Training/라벨링데이터 \
        --out docs/lunge-divergence-separability.v1.json
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import re
import statistics
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

# The four exercises the question is about: the three lunges a clause would have to admit, and
# the squat it would have to exclude.
LUNGES = ("스텝 포워드 다이나믹 런지", "스텝 백워드 다이나믹 런지", "바벨 런지")
IMPOSTOR = "바벨 스쿼트"
EXERCISES = LUNGES + (IMPOSTOR,)

# Chains as the runtime defines them, in AI Hub's joint vocabulary. The vertex is the middle name.
CHAINS = {
    "HIP": ("Shoulder", "Hip", "Knee"),
    "KNEE": ("Hip", "Knee", "Ankle"),
}

SIDES = ("Left", "Right")

# The dataset marks a shallow repetition by failing its own ninety-degree knee condition. Those
# clips are the population a clause must not discard, so they are reported separately.
SHALLOW_CONDITION = re.compile(r"90\s*도")

Z_CODE = re.compile(r"-(Z\d+)_")

# Seeded so the noise scenarios are reproducible; repeats keep the tail estimate stable.
NOISE_SEED = 20260814
NOISE_REPEATS = 5


def included_angle(vertex, first, second) -> Optional[float]:
    """The included angle at ``vertex``, in degrees. None when a segment is degenerate."""
    ax, ay, az = first[0] - vertex[0], first[1] - vertex[1], first[2] - vertex[2]
    bx, by, bz = second[0] - vertex[0], second[1] - vertex[1], second[2] - vertex[2]
    la = math.sqrt(ax * ax + ay * ay + az * az)
    lb = math.sqrt(bx * bx + by * by + bz * bz)
    if la <= 1e-9 or lb <= 1e-9:
        return None
    cosine = (ax * bx + ay * by + az * bz) / (la * lb)
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def point(pts: Dict[str, Dict[str, float]], name: str) -> Optional[Tuple[float, float, float]]:
    node = pts.get(name)
    if not node:
        return None
    try:
        return (float(node["x"]), float(node["y"]), float(node["z"]))
    except (KeyError, TypeError, ValueError):
        return None


def distance(a: Sequence[float], b: Sequence[float]) -> float:
    return math.sqrt(sum((p - q) ** 2 for p, q in zip(a, b)))


def clip_measurements(frames: Iterable[dict]) -> Optional[dict]:
    """Per-clip statistics a divergence clause would read, or None when nothing is measurable.

    The window statistic is the MAXIMUM divergence over the clip's frames, which is the shape a
    "the two legs must differ at some point" clause takes. Taking the maximum is the most
    generous reading available to a lunge, so a separation that fails under it fails under any
    stricter one.
    """
    hip_divergences: List[float] = []
    knee_divergences: List[float] = []
    separations: List[float] = []

    for frame in frames:
        pts = frame.get("pts") or {}
        angles: Dict[str, Dict[str, float]] = {}
        for chain, (first, vertex, second) in CHAINS.items():
            per_side = {}
            for side in SIDES:
                v = point(pts, f"{side} {vertex}")
                f = point(pts, f"{side} {first}")
                s = point(pts, f"{side} {second}")
                if v is None or f is None or s is None:
                    continue
                angle = included_angle(v, f, s)
                if angle is not None:
                    per_side[side] = angle
            angles[chain] = per_side

        if len(angles["HIP"]) == 2:
            hip_divergences.append(abs(angles["HIP"]["Left"] - angles["HIP"]["Right"]))
        if len(angles["KNEE"]) == 2:
            knee_divergences.append(abs(angles["KNEE"]["Left"] - angles["KNEE"]["Right"]))

        # Occlusion proxy: how far apart the feet are, in leg lengths. A lateral camera loses the
        # far leg behind the near one when this is small. Scale-free so body size cancels.
        left_ankle, right_ankle = point(pts, "Left Ankle"), point(pts, "Right Ankle")
        left_hip = point(pts, "Left Hip")
        if left_ankle and right_ankle and left_hip:
            leg = distance(left_hip, left_ankle)
            if leg > 1e-6:
                separations.append(distance(left_ankle, right_ankle) / leg)

    if not hip_divergences or not knee_divergences:
        return None

    def second_highest(values: List[float]) -> float:
        """The runner-up frame.

        A window maximum over sixteen sparse keyframes is exactly the statistic one corrupted
        coordinate inflates, and this dataset demonstrably contains some — an ankle separation of
        nearly three thousand leg lengths turned up in the first pass. Reporting the runner-up
        alongside the maximum shows whether a separation survives dropping each clip's single
        most extreme frame, which is the cheapest available check that the signal is the movement
        rather than the noise.
        """
        ordered = sorted(values, reverse=True)
        return ordered[1] if len(ordered) > 1 else ordered[0]

    return {
        "hipDivergenceMax": max(hip_divergences),
        "kneeDivergenceMax": max(knee_divergences),
        "hipDivergenceSecond": second_highest(hip_divergences),
        "kneeDivergenceSecond": second_highest(knee_divergences),
        "ankleSeparationMax": max(separations) if separations else None,
        "ankleSeparationSecond": second_highest(separations) if separations else None,
        # Kept per frame so measurement error can be applied afterwards. A divergence is
        # abs(left - right), so noise on the two sides enters as a single term on their signed
        # difference: abs(trueDifference + e), with e the combined error of both estimates.
        "hipFrameDifferences": hip_divergences,
        "kneeFrameDifferences": knee_divergences,
    }


def is_shallow(type_info: dict) -> bool:
    """Whether the dataset marked this clip as failing its own ninety-degree knee condition."""
    for condition in type_info.get("conditions") or []:
        name = str(condition.get("condition", ""))
        if SHALLOW_CONDITION.search(name) and condition.get("value") is False:
            return True
    return False


def subject_of(metadata: dict) -> Optional[str]:
    for frame in metadata.get("frames") or []:
        for view in frame.values():
            if not isinstance(view, dict):
                continue
            match = Z_CODE.search(str(view.get("img_key", "")))
            if match:
                return match.group(1)
    return None


def collect(data_root: str) -> List[dict]:
    """Every measurable clip of the four exercises, with its subject and shallow flag."""
    clips: List[dict] = []
    for dirpath, _, filenames in os.walk(data_root):
        for filename in sorted(filenames):
            if not filename.endswith(".json") or filename.endswith("-3d.json"):
                continue
            metadata_path = os.path.join(dirpath, filename)
            try:
                with open(metadata_path, encoding="utf-8") as handle:
                    metadata = json.load(handle)
            except (OSError, ValueError):
                continue
            type_info = metadata.get("type_info") or {}
            exercise = type_info.get("exercise")
            if exercise not in EXERCISES:
                continue
            coordinate_path = metadata_path[: -len(".json")] + "-3d.json"
            if not os.path.exists(coordinate_path):
                continue
            try:
                with open(coordinate_path, encoding="utf-8") as handle:
                    coordinates = json.load(handle)
            except (OSError, ValueError):
                continue
            measured = clip_measurements(coordinates.get("frames") or [])
            if measured is None:
                continue
            measured.update(
                exercise=exercise,
                subject=subject_of(metadata),
                shallow=is_shallow(type_info),
                clip=os.path.relpath(metadata_path, data_root).replace(os.sep, "/"),
            )
            clips.append(measured)
    clips.sort(key=lambda c: c["clip"])
    return clips


def percentile(values: Sequence[float], fraction: float) -> Optional[float]:
    """Nearest-rank percentile. Deterministic and dependency-free."""
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(math.ceil(fraction * len(ordered))) - 1))
    return ordered[index]


def sweep(lunge: Sequence[float], impostor: Sequence[float], bounds: Sequence[float]) -> List[dict]:
    """What each candidate bound would cost and catch.

    The clause reads "the two sides must diverge by at least the bound at some point", so a lunge
    is FALSELY REJECTED when its maximum divergence falls under the bound, and a squat is CAUGHT
    on exactly the same condition. The two move together, which is the whole difficulty.
    """
    rows = []
    for bound in bounds:
        rows.append(
            {
                "boundDegrees": bound,
                "lungeFalseRejectionRate": sum(1 for v in lunge if v < bound) / len(lunge),
                "impostorCaughtRate": sum(1 for v in impostor if v < bound) / len(impostor),
            }
        )
    return rows


def summarise(values: Sequence[float]) -> dict:
    return {
        "count": len(values),
        "median": statistics.median(values) if values else None,
        "p5": percentile(values, 0.05),
        "p10": percentile(values, 0.10),
        "p90": percentile(values, 0.90),
        "p95": percentile(values, 0.95),
        "min": min(values) if values else None,
        "max": max(values) if values else None,
    }


def with_measurement_error(
    clips: Sequence[dict],
    key: str,
    sigma: float,
    repeats: int,
    seed: int,
) -> List[float]:
    """Each clip's window-maximum divergence after applying per-frame measurement error.

    This is the step that decides the question, because the shape of the clause and the shape of
    the error work against each other. The clause reads a window MAXIMUM, and the impostor's true
    divergence is near zero — so its reading becomes the maximum of sixteen draws of abs(error),
    which grows with the window rather than averaging out. A statistic that is generous to the
    lunge is equally generous to the squat, and the squat has more room to gain.

    Independent draws per frame, seeded, so the artifact is reproducible.
    """
    rng = random.Random(seed)
    values: List[float] = []
    for clip in clips:
        differences = clip[key]
        for _ in range(repeats):
            values.append(max(abs(d + rng.gauss(0.0, sigma)) for d in differences))
    return values


def build_artifact(clips: Sequence[dict]) -> dict:
    by_exercise: Dict[str, List[dict]] = {}
    for clip in clips:
        by_exercise.setdefault(clip["exercise"], []).append(clip)

    lunge_clips = [c for c in clips if c["exercise"] in LUNGES]
    impostor_clips = [c for c in clips if c["exercise"] == IMPOSTOR]

    chains = {}
    for chain, key in (
        ("HIP", "hipDivergenceMax"),
        ("KNEE", "kneeDivergenceMax"),
        ("HIP_DROP_ONE", "hipDivergenceSecond"),
        ("KNEE_DROP_ONE", "kneeDivergenceSecond"),
    ):
        lunge_values = [c[key] for c in lunge_clips]
        impostor_values = [c[key] for c in impostor_clips]
        shallow_values = [c[key] for c in lunge_clips if c["shallow"]]
        bounds = [float(b) for b in range(2, 61, 1)]
        rows = sweep(lunge_values, impostor_values, bounds)
        # The most generous bound that keeps false rejection at or under one clip in a hundred.
        affordable = [r for r in rows if r["lungeFalseRejectionRate"] <= 0.01]
        best = max(affordable, key=lambda r: r["impostorCaughtRate"]) if affordable else None
        chains[chain] = {
            "lunge": summarise(lunge_values),
            "shallowLunge": summarise(shallow_values),
            "impostor": summarise(impostor_values),
            "sweep": rows,
            "bestBoundAtOnePercentFalseRejection": best,
        }

    # What per-frame measurement error does to the same clause. The sigma is the COMBINED error
    # of the two sides, since a divergence inherits both: sqrt(near^2 + far^2). The scenarios
    # bracket the only published near/far monocular figures available, which were measured during
    # overground walking and are therefore optimistic for a deep lunge, plus a hypothetical
    # low-error case to show what the clause would need.
    noise = {}
    for chain, key in (("HIP", "hipFrameDifferences"), ("KNEE", "kneeFrameDifferences")):
        scenarios = {}
        for label, sigma in (("perfect", 0.0), ("optimistic_3deg", 3.0), ("walking_gait", 8.0)):
            lunge_values = with_measurement_error(lunge_clips, key, sigma, NOISE_REPEATS, NOISE_SEED)
            impostor_values = with_measurement_error(
                impostor_clips, key, sigma, NOISE_REPEATS, NOISE_SEED + 1
            )
            bounds = [float(b) for b in range(2, 81, 1)]
            rows = sweep(lunge_values, impostor_values, bounds)
            affordable = [r for r in rows if r["lungeFalseRejectionRate"] <= 0.01]
            scenarios[label] = {
                "combinedSigmaDegrees": sigma,
                "lunge": summarise(lunge_values),
                "impostor": summarise(impostor_values),
                "bestBoundAtOnePercentFalseRejection": (
                    max(affordable, key=lambda r: r["impostorCaughtRate"]) if affordable else None
                ),
            }
        noise[chain] = scenarios

    separations = {}
    for exercise, group in sorted(by_exercise.items()):
        values = [c["ankleSeparationSecond"] for c in group if c["ankleSeparationSecond"] is not None]
        separations[exercise] = summarise(values)

    subjects = sorted({c["subject"] for c in clips if c["subject"]})
    return {
        "schemaVersion": 1,
        "artifactKind": "LUNGE_DIVERGENCE_SEPARABILITY",
        "authority": "CATALOG_AND_LABEL_ANALYSIS_ONLY_NOT_RUNTIME_RELEASE",
        "question": (
            "Whether a cross-side divergence clause separates the three lunges from a barbell "
            "squat on 3D ground truth, which is a ceiling for what the app's monocular estimate "
            "could achieve."
        ),
        "generalisationUnit": "LEAVE_ONE_GLOBAL_Z_SUBJECT_OUT",
        "windowStatistic": "MAXIMUM_OVER_CLIP_FRAMES",
        "clipCounts": {
            exercise: {
                "clips": len(group),
                "shallow": sum(1 for c in group if c["shallow"]),
                "subjects": len({c["subject"] for c in group if c["subject"]}),
            }
            for exercise, group in sorted(by_exercise.items())
        },
        "subjectCount": len(subjects),
        "chains": chains,
        "underMeasurementError": noise,
        "ankleSeparationInLegLengths": separations,
        "limitations": [
            "3D label coordinates, not MediaPipe output: this is a ceiling, not a prediction.",
            "Clip-level statistics over sixteen sparse keyframes with no timestamps, so the "
            "window maximum is taken over the whole clip rather than over one repetition.",
            "The ankle separation is a proxy for far-leg occlusion, not a measurement of it; "
            "actual visibility depends on the camera axis, which the labels do not record.",
            "No per-frame measurement error is applied. A divergence is a difference of two "
            "independently estimated angles and inherits both, one of them from the occluded "
            "side, so the real separation is narrower than every figure here.",
        ],
    }


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--data",
        default=os.path.join("data", "013.피트니스자세", "1.Training", "라벨링데이터"),
    )
    parser.add_argument(
        "--out",
        default=os.path.join("docs", "lunge-divergence-separability.v1.json"),
    )
    args = parser.parse_args(argv)

    if not os.path.isdir(args.data):
        parser.error(f"label root not found: {args.data}")

    clips = collect(args.data)
    if not clips:
        parser.error("no measurable clips found")
    artifact = build_artifact(clips)

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(artifact, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")

    for chain, block in sorted(artifact["chains"].items()):
        best = block["bestBoundAtOnePercentFalseRejection"]
        if best is None:
            print(f"{chain}: no bound keeps false rejection at or under 1%")
        else:
            print(
                f"{chain}: bound {best['boundDegrees']:.0f} deg catches "
                f"{best['impostorCaughtRate'] * 100:.1f}% of squats at "
                f"{best['lungeFalseRejectionRate'] * 100:.1f}% false rejection"
            )
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

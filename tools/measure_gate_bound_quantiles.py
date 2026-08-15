"""Measure where each definition-gate bound sits inside its exercise's own distribution.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
AIHUB_LABEL_STATISTICAL_ANALYSIS (non-commercial educational scope). Labels only; reads no imagery.

Why this exists
---------------
`docs/pose-heuristic-form-check.v1.md` §4.9 defines gates — companion-joint clauses that make a
driver arc count as *this* exercise's repetition — and rule 7 requires a bound to sit outside the
exercise's own legitimate distribution, with the margin stated. Until now every bound was placed
by anatomy alone, and the squat's 140-degree hip bound demonstrated what that costs: measured, it
admitted the impostor it existed to exclude. This tool measures the legitimate side of that
placement for every gated companion chain, so a bound is put where the distribution says the
exercise does not go, not where a comment hoped it would not.

What it measures
----------------
For each target exercise the tool replays the 3D label frames through the same excursion state
machine the app runs (rest -> armed at the attempt line -> completed back past the rest line,
counting only excursions whose driver reached the rep line), one side at a time, and inside each
repetition-shaped excursion reads the companion chains' window minimum and maximum. The output is
the per-excursion distribution of those window extremes — quantiles, counts, and a false-rejection
sweep for all four clause shapes (REACH/STAY x AT_LEAST/AT_MOST) over a five-degree grid.

For the two deadlifts the barbell squat is replayed through the *deadlift's* driver constants as
an impostor row: a squat flexes the hip past the deadlift's rep line, so the interesting question
is how many squat excursions a knee bound catches, alongside how many real deadlifts it costs.

What it cannot answer
---------------------
The labels are sparse keyframes (roughly a second apart), so an excursion here holds a handful of
frames where the runtime holds ~60. The runtime's k-satisfying-frames REACH rule therefore cannot
be simulated at its own k; window extremes here are the k=1 reading, which on noise-free ground
truth is the same statistic the sustained rule converges to. Nothing here transfers a bound to
MediaPipe-native readings either — these are 3D ground-truth angles, and rule 7 placement is the
claim, not a calibration. And the impostor the user actually performs (arm swings, standing
bounces) is in no dataset: the legitimate side of the line is measured, the impostor side stays a
model.

Usage
-----
    python tools/measure_gate_bound_quantiles.py \
        --data data/013.피트니스자세/1.Training/라벨링데이터 \
        --out docs/gate-bound-quantiles.v1.json
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

# Chains exactly as the runtime composes them (FormCheckGeometry.kt), in AI Hub's joint
# vocabulary: (first, vertex, second).
CHAINS: Dict[str, Tuple[str, str, str]] = {
    "KNEE": ("Hip", "Knee", "Ankle"),
    "HIP": ("Shoulder", "Hip", "Knee"),
    "ELBOW": ("Shoulder", "Elbow", "Wrist"),
    "SHOULDER": ("Elbow", "Shoulder", "Hip"),
}

SIDES = ("Left", "Right")

# The driver state machine's constants, mirrored from FormCheckExercise. Every target is a
# flexion exercise: armed at or under the attempt line, completed at or over the rest line,
# counted when the driver minimum reached the rep line.
REST_DEGREES = 150.0
ATTEMPT_DEGREES = 140.0


class Target:
    def __init__(
        self,
        exercise: str,
        driver: str,
        rep_degrees: float,
        companions: Sequence[str],
        impostors: Sequence[str] = (),
    ) -> None:
        self.exercise = exercise
        self.driver = driver
        self.rep_degrees = rep_degrees
        self.companions = tuple(companions)
        self.impostors = tuple(impostors)


# Which companion chains each exercise's candidate clause would read. The squat row exists to
# validate the shipped 130 clause against the same statistic it is judged on.
TARGETS: Tuple[Target, ...] = (
    Target("바벨 스쿼트", "KNEE", 110.0, ("HIP",)),
    Target("풀업", "ELBOW", 120.0, ("SHOULDER", "HIP")),
    Target("페이스 풀", "ELBOW", 120.0, ("SHOULDER", "HIP")),
    Target("업라이트로우", "ELBOW", 120.0, ("SHOULDER", "HIP")),
    Target("바벨 로우", "ELBOW", 120.0, ("SHOULDER", "HIP")),
    Target("덤벨 벤트오버 로우", "ELBOW", 120.0, ("SHOULDER", "HIP")),
    Target("행잉 레그 레이즈", "HIP", 130.0, ("SHOULDER", "KNEE")),
    Target("라잉 레그 레이즈", "HIP", 130.0, ("SHOULDER", "KNEE")),
    Target("바벨 데드리프트", "HIP", 125.0, ("KNEE",), impostors=("바벨 스쿼트",)),
    Target("바벨 스티프 데드리프트", "HIP", 130.0, ("KNEE",), impostors=("바벨 스쿼트",)),
)

# A companion window with fewer credible frames than this abstains — the sparse-keyframe analogue
# of the runtime's five-frame observation floor.
MINIMUM_COMPANION_FRAMES = 2

QUANTILES = (0.005, 0.01, 0.025, 0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95, 0.975, 0.99, 0.995)

SWEEP_START = 40.0
SWEEP_STOP = 180.0
SWEEP_STEP = 5.0

Z_CODE = re.compile(r"-(Z\d+)_")


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


def chain_angle(pts: dict, side: str, chain: str) -> Optional[float]:
    first_name, vertex_name, second_name = CHAINS[chain]
    vertex = point(pts, f"{side} {vertex_name}")
    first = point(pts, f"{side} {first_name}")
    second = point(pts, f"{side} {second_name}")
    if vertex is None or first is None or second is None:
        return None
    return included_angle(vertex, first, second)


def reconstruct_excursions(
    frames: Sequence[dict],
    side: str,
    driver: str,
    rep_degrees: float,
    companions: Sequence[str],
) -> List[dict]:
    """Repetition-shaped excursions of one side, with each companion's window extremes.

    The same band the runtime's detector walks: an excursion arms when the driver crosses the
    attempt line going down, completes when it returns past the rest line, and counts as
    repetition-shaped only if its driver minimum reached the rep line. An excursion still armed
    when the clip ends is discarded, exactly as the engine discards one the camera stopped
    watching. Companion windows include the arming frame and everything until — not including —
    the completing frame, matching the engine's accumulate-while-in-excursion ordering.
    """
    excursions: List[dict] = []
    armed = False
    driver_minimum = math.inf
    windows: Dict[str, List[float]] = {}

    for frame in frames:
        pts = frame.get("pts") or {}
        angle = chain_angle(pts, side, driver)
        if angle is None:
            continue
        if not armed:
            if angle <= ATTEMPT_DEGREES:
                armed = True
                driver_minimum = angle
                windows = {chain: [] for chain in companions}
            else:
                continue
        elif angle >= REST_DEGREES:
            if driver_minimum <= rep_degrees:
                record = {"driverMinimum": driver_minimum}
                for chain in companions:
                    values = windows[chain]
                    if len(values) >= MINIMUM_COMPANION_FRAMES:
                        record[chain] = {"windowMinimum": min(values), "windowMaximum": max(values)}
                excursions.append(record)
            armed = False
            driver_minimum = math.inf
            continue
        driver_minimum = min(driver_minimum, angle)
        for chain in companions:
            companion = chain_angle(pts, side, chain)
            if companion is not None:
                windows[chain].append(companion)

    return excursions


def subject_of(metadata: dict) -> Optional[str]:
    for frame in metadata.get("frames") or []:
        for view in frame.values():
            if not isinstance(view, dict):
                continue
            match = Z_CODE.search(str(view.get("img_key", "")))
            if match:
                return match.group(1)
    return None


def collect(data_root: str) -> Dict[str, List[dict]]:
    """Every measurable clip of every named exercise, keyed by exercise name."""
    wanted = {target.exercise for target in TARGETS}
    for target in TARGETS:
        wanted.update(target.impostors)
    clips: Dict[str, List[dict]] = {name: [] for name in wanted}
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
            exercise = (metadata.get("type_info") or {}).get("exercise")
            if exercise not in wanted:
                continue
            coordinate_path = metadata_path[: -len(".json")] + "-3d.json"
            try:
                with open(coordinate_path, encoding="utf-8") as handle:
                    coordinates = json.load(handle)
            except (OSError, ValueError):
                continue
            clips[exercise].append(
                {
                    "frames": coordinates.get("frames") or [],
                    "subject": subject_of(metadata),
                    "clip": os.path.relpath(metadata_path, data_root).replace(os.sep, "/"),
                }
            )
    for name in clips:
        clips[name].sort(key=lambda c: c["clip"])
    return clips


def percentile(values: Sequence[float], fraction: float) -> Optional[float]:
    """Nearest-rank percentile. Deterministic and dependency-free."""
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(math.ceil(fraction * len(ordered))) - 1))
    return ordered[index]


def summarise(values: Sequence[float]) -> Optional[dict]:
    if not values:
        return None
    summary = {
        "count": len(values),
        "minimum": round(min(values), 1),
        "maximum": round(max(values), 1),
    }
    for fraction in QUANTILES:
        summary[f"p{fraction * 100:g}"] = round(percentile(values, fraction), 1)
    return summary


def sweep_shapes(minima: Sequence[float], maxima: Sequence[float]) -> List[dict]:
    """The fraction of excursions each clause shape would reject, per candidate bound.

    The four shapes exhaust §4.9's clause grammar. On the legitimate exercise every rejection is
    a false one, so these columns ARE the false-rejection curves; on an impostor replay they are
    the caught-rates. The runtime's sustained-frames rules move each shape only toward fewer
    rejections on ground truth, so every figure is the conservative bracket.
    """
    rows = []
    bound = SWEEP_START
    while bound <= SWEEP_STOP + 1e-9:
        rows.append(
            {
                "boundDegrees": bound,
                "reachAtLeastOnMaximum": _fraction_below(maxima, bound),
                "stayAtMostOnMaximum": _fraction_above(maxima, bound),
                "reachAtMostOnMinimum": _fraction_above(minima, bound),
                "stayAtLeastOnMinimum": _fraction_below(minima, bound),
            }
        )
        bound += SWEEP_STEP
    return rows


def _fraction_below(values: Sequence[float], bound: float) -> Optional[float]:
    if not values:
        return None
    return round(sum(1 for v in values if v < bound) / len(values), 4)


def _fraction_above(values: Sequence[float], bound: float) -> Optional[float]:
    if not values:
        return None
    return round(sum(1 for v in values if v > bound) / len(values), 4)


def measure_target(target: Target, clips: Dict[str, List[dict]]) -> dict:
    def run(exercise: str) -> dict:
        minima: Dict[str, List[float]] = {chain: [] for chain in target.companions}
        maxima: Dict[str, List[float]] = {chain: [] for chain in target.companions}
        subjects = set()
        contributing = 0
        excursion_count = 0
        for clip in clips.get(exercise, []):
            contributed = False
            for side in SIDES:
                for excursion in reconstruct_excursions(
                    clip["frames"], side, target.driver, target.rep_degrees, target.companions
                ):
                    excursion_count += 1
                    contributed = True
                    for chain in target.companions:
                        window = excursion.get(chain)
                        if window:
                            minima[chain].append(window["windowMinimum"])
                            maxima[chain].append(window["windowMaximum"])
            if contributed:
                contributing += 1
                if clip["subject"]:
                    subjects.add(clip["subject"])
        chains = {}
        for chain in target.companions:
            chains[chain] = {
                "windowMinimum": summarise(minima[chain]),
                "windowMaximum": summarise(maxima[chain]),
                "clauseSweep": sweep_shapes(minima[chain], maxima[chain]),
            }
        return {
            "clipsContributing": contributing,
            "subjects": len(subjects),
            "excursions": excursion_count,
            "chains": chains,
        }

    result = {
        "exercise": target.exercise,
        "driver": target.driver,
        "repDegrees": target.rep_degrees,
        "legitimate": run(target.exercise),
    }
    if target.impostors:
        result["impostorReplays"] = {name: run(name) for name in target.impostors}
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True, help="라벨링데이터 root")
    parser.add_argument("--out", required=True, help="artifact path")
    args = parser.parse_args()

    clips = collect(args.data)
    measurements = [measure_target(target, clips) for target in TARGETS]

    artifact = {
        "artifactKind": "TREX_GATE_BOUND_QUANTILES",
        "artifactVersion": 1,
        "rightsAuthorization": {
            "manifest": "docs/pose-data-rights-manifest.aihub-research.v1.json",
            "operation": "AIHUB_LABEL_STATISTICAL_ANALYSIS",
        },
        "question": (
            "Where does each definition-gated companion chain travel during the exercise's own "
            "repetition-shaped excursions, so a gate bound can sit outside that distribution "
            "with a stated margin (§4.9 rule 7)?"
        ),
        "method": {
            "unit": "per-excursion companion-chain window extreme, one side at a time",
            "excursionMachine": (
                f"armed at {ATTEMPT_DEGREES}, completed at {REST_DEGREES}, counted when the "
                "driver minimum reached the exercise's rep line; armed-at-clip-end discarded"
            ),
            "minimumCompanionFrames": MINIMUM_COMPANION_FRAMES,
            "coordinates": "AI Hub 3D ground-truth labels; no MediaPipe transfer is claimed",
        },
        "limitations": [
            "Sparse keyframes: an excursion holds a handful of frames where the runtime holds "
            "~60, so the runtime's k-satisfying-frames rule is not simulated; window extremes "
            "are the k=1 reading, which ground truth makes equivalent.",
            "The impostor the user performs is in no dataset; only the legitimate side of each "
            "bound is measured, plus the squat replayed through the deadlifts' driver.",
            "Ground-truth angles: MediaPipe-native error is measured elsewhere "
            "(docs/bridge-frame-error.v1.json) and is not folded in here.",
        ],
        "measurements": measurements,
    }

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(artifact, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print(f"wrote {args.out}")

    for measurement in measurements:
        legit = measurement["legitimate"]
        print(
            f"\n{measurement['exercise']}  driver={measurement['driver']}"
            f"  excursions={legit['excursions']}  clips={legit['clipsContributing']}"
            f"  subjects={legit['subjects']}"
        )
        for chain, tables in legit["chains"].items():
            for stat_name in ("windowMinimum", "windowMaximum"):
                table = tables[stat_name]
                if table:
                    print(
                        f"   {chain:9s} {stat_name}: p1={table['p1']} p5={table['p5']} "
                        f"median={table['p50']} p95={table['p95']} p99={table['p99']}"
                    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

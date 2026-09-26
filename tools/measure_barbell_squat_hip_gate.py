"""Ask where the barbell squat's own hip angles sit, relative to the 140-degree definition gate.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
AIHUB_LABEL_STATISTICAL_ANALYSIS (non-commercial educational scope). Labels only; reads no imagery.

Why this exists
---------------
`HeuristicFormCheck.kt` gives BARBELL_SQUAT one definition gate: the HIP chain (shoulder-hip-knee)
on the driver's side must reach a WINDOW_MINIMUM of AT_MOST 140 degrees inside the excursion, or
the repetition is not counted. The limit is declared HEURISTIC_DEFAULT — §4.9 rule 4 says every
gate limit is an uncalibrated default and names them as the next thing to measure.

§4.9 rule 7 states the safety condition for such a limit: it must sit **outside the exercise's own
legitimate distribution**. Inside it, the gate stops being an identity statement ("that arc was not
a squat") and starts reading as a severity scale, which this track forbids.

This tool measures the distribution the rule refers to, on the dataset's 3D ground truth:

  1. Per clip, the bottom-of-rep frame — the frame whose bilateral knee included angle is lowest,
     with the engine's 45-degree cross-side divergence check applied first.
  2. At that frame, the HIP included angle; and separately the minimum HIP angle over the clip and
     over the excursion window, which is what WINDOW_MINIMUM actually reads.
  3. The false-rejection curve: what fraction of real squat clips a gate at 140/145/150/155/160
     would discard.
  4. The same, restricted to clips whose knee minimum crosses the rep line, since only a completed
     excursion reaches the gate at all.
  5. The same, split by the dataset's own condition labels for this exercise.

Every number is computed from 3D label coordinates, which is a ceiling: MediaPipe cannot beat
perfect ground truth, so a distribution that already crowds the gate here crowds it worse on a
phone.

Side handling. The engine measures ONE side (`FormCheckGeometry.sample` picks the better-observed
chain, in a lateral pose the near limb) and the gate reads that same side
(`FormCheckGateSide.DRIVER`). The labels carry no confidence, so which side the engine would have
picked is not recoverable; the artifact therefore brackets it, reporting the shallower side
(``WORSE``, most rejections), the deeper side (``BETTER``), and the mean.

Generalisation unit is the global Z participant code parsed from the 2D metadata's ``img_key``,
matching LEAVE_ONE_GLOBAL_Z_SUBJECT_OUT elsewhere in this repository.

Usage
-----
    python tools/measure_barbell_squat_hip_gate.py \
        --data data/013.피트니스자세/1.Training/라벨링데이터 \
        --out barbell-squat-hip-gate.v1.json
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import statistics
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

EXERCISE = "바벨 스쿼트"

# Chains as the runtime defines them, in AI Hub's joint vocabulary. The vertex is the middle name.
CHAINS = {
    "HIP": ("Shoulder", "Hip", "Knee"),
    "KNEE": ("Hip", "Knee", "Ankle"),
}
SIDES = ("Left", "Right")

# Engine constants, mirrored from HeuristicFormCheck.kt / RepCycleDetector.kt.
BILATERAL_DIVERGENCE_DEGREES = 45.0   # HeuristicFormCheck.BILATERAL_DIVERGENCE_DEGREES
GATE_BOUND_DEGREES = 140.0            # BARBELL_SQUAT definition gate, HIP WINDOW_MINIMUM AT_MOST
ATTEMPT_ANGLE_DEGREES = 140.0         # arms the excursion
TOP_ANGLE_DEGREES = 150.0             # closes the excursion (restAngle)
REP_ANGLE_DEGREES = 110.0             # the rep line: a completed excursion must cross it
REACHED_ANGLE_DEGREES = 105.0         # the "no suggestion needed" extreme

# Swept in both directions. Loosening (above 140) answers §4.9 rule 7 — how far the limit sits
# from the exercise's own distribution. Tightening (below 140) is the direction that would have to
# be taken to exclude a standing knee-bend, and its cost is the false-rejection column.
SWEEP_BOUNDS = (
    160.0, 155.0, 150.0, 145.0, 140.0,
    135.0, 130.0, 125.0, 120.0, 115.0, 110.0, 105.0, 100.0, 95.0, 90.0,
)

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


def frame_angles(frame: dict) -> Dict[str, Dict[str, float]]:
    """{chain: {side: included angle}} for one frame; missing joints simply do not appear."""
    pts = frame.get("pts") or {}
    out: Dict[str, Dict[str, float]] = {}
    for chain, (first, vertex, second) in CHAINS.items():
        per_side: Dict[str, float] = {}
        for side in SIDES:
            v = point(pts, f"{side} {vertex}")
            f = point(pts, f"{side} {first}")
            s = point(pts, f"{side} {second}")
            if v is None or f is None or s is None:
                continue
            angle = included_angle(v, f, s)
            if angle is not None:
                per_side[side] = angle
        out[chain] = per_side
    return out


def clip_measurements(frames: Sequence[dict]) -> Optional[dict]:
    """What the gate would read on this clip, or None when nothing is measurable.

    Bottom-of-rep is located the way the engine's driver behaves: the knee chain, both sides
    required to agree within the 45-degree divergence check, and the deepest such frame taken as
    the bottom. Frames whose two knees disagree past the check are exactly the frames the engine
    would count toward bilateral incoherence, so they are excluded from the search rather than
    allowed to define a "bottom" the engine would never accept.
    """
    per_frame: List[dict] = []
    for frame in frames:
        angles = frame_angles(frame)
        knee, hip = angles["KNEE"], angles["HIP"]
        per_frame.append(
            {
                "kneeLeft": knee.get("Left"),
                "kneeRight": knee.get("Right"),
                "hipLeft": hip.get("Left"),
                "hipRight": hip.get("Right"),
            }
        )

    bilateral = [
        (index, (row["kneeLeft"] + row["kneeRight"]) / 2.0)
        for index, row in enumerate(per_frame)
        if row["kneeLeft"] is not None
        and row["kneeRight"] is not None
        and abs(row["kneeLeft"] - row["kneeRight"]) <= BILATERAL_DIVERGENCE_DEGREES
    ]
    if not bilateral:
        return None

    concurrent = sum(
        1 for row in per_frame if row["kneeLeft"] is not None and row["kneeRight"] is not None
    )
    divergent = concurrent - len(bilateral)

    bottom_index, knee_minimum = min(bilateral, key=lambda pair: pair[1])
    bottom = per_frame[bottom_index]

    def side_pair(left: Optional[float], right: Optional[float]) -> Optional[dict]:
        present = [v for v in (left, right) if v is not None]
        if not present:
            return None
        return {"worse": max(present), "better": min(present), "mean": sum(present) / len(present)}

    # The excursion window: the engine accumulates gate frames only while an excursion is armed,
    # which begins when the driver falls to the attempt line and ends when it returns to the top.
    # With sixteen sparse keyframes and no timestamps the faithful reconstruction is the maximal
    # contiguous run of frames around the bottom whose bilateral knee stays under the top line,
    # kept only when that run actually reaches the attempt line.
    bilateral_by_index = dict(bilateral)
    window = [bottom_index]
    index = bottom_index - 1
    while index >= 0 and bilateral_by_index.get(index, 1e9) < TOP_ANGLE_DEGREES:
        window.append(index)
        index -= 1
    index = bottom_index + 1
    while index < len(per_frame) and bilateral_by_index.get(index, 1e9) < TOP_ANGLE_DEGREES:
        window.append(index)
        index += 1
    armed = any(bilateral_by_index[i] <= ATTEMPT_ANGLE_DEGREES for i in window)

    def hip_minimum(indices: Iterable[int]) -> Optional[dict]:
        lefts = [per_frame[i]["hipLeft"] for i in indices if per_frame[i]["hipLeft"] is not None]
        rights = [per_frame[i]["hipRight"] for i in indices if per_frame[i]["hipRight"] is not None]
        return side_pair(min(lefts) if lefts else None, min(rights) if rights else None)

    return {
        "kneeMinimumBilateral": knee_minimum,
        "kneeMinimumPerSide": side_pair(
            min((r["kneeLeft"] for r in per_frame if r["kneeLeft"] is not None), default=None),
            min((r["kneeRight"] for r in per_frame if r["kneeRight"] is not None), default=None),
        ),
        "hipAtBottom": side_pair(bottom["hipLeft"], bottom["hipRight"]),
        "hipMinimumClip": hip_minimum(range(len(per_frame))),
        "hipMinimumExcursion": hip_minimum(sorted(window)) if armed else None,
        "excursionFrames": len(window) if armed else 0,
        "bilateralConcurrentFrames": concurrent,
        "bilateralDivergentFrames": divergent,
        "frameCount": len(per_frame),
    }


def subject_of(metadata: dict) -> Optional[str]:
    for frame in metadata.get("frames") or []:
        for view in frame.values():
            if not isinstance(view, dict):
                continue
            match = Z_CODE.search(str(view.get("img_key", "")))
            if match:
                return match.group(1)
    return None


def conditions_of(type_info: dict) -> Dict[str, bool]:
    out: Dict[str, bool] = {}
    for condition in type_info.get("conditions") or []:
        name = str(condition.get("condition", ""))
        if name:
            out[name] = bool(condition.get("value"))
    return out


def collect(data_root: str) -> List[dict]:
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
            if type_info.get("exercise") != EXERCISE:
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
            relative = os.path.relpath(metadata_path, data_root).replace(os.sep, "/")
            measured.update(
                subject=subject_of(metadata),
                conditions=conditions_of(type_info),
                day=relative.split("/")[1] if "/" in relative else None,
                clip=relative,
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


def summarise(values: Sequence[float]) -> dict:
    ordered = sorted(values)
    return {
        "count": len(ordered),
        "min": ordered[0] if ordered else None,
        "p1": percentile(ordered, 0.01),
        "p5": percentile(ordered, 0.05),
        "p25": percentile(ordered, 0.25),
        "median": statistics.median(ordered) if ordered else None,
        "p75": percentile(ordered, 0.75),
        "p95": percentile(ordered, 0.95),
        "p99": percentile(ordered, 0.99),
        "max": ordered[-1] if ordered else None,
        "mean": statistics.fmean(ordered) if ordered else None,
    }


def rejection_curve(values: Sequence[float]) -> List[dict]:
    """A gate reading AT_MOST ``bound`` rejects a clip whose window minimum sits above it."""
    rows = []
    for bound in SWEEP_BOUNDS:
        rejected = sum(1 for v in values if v > bound)
        rows.append(
            {
                "boundDegrees": bound,
                "rejectedClips": rejected,
                "falseRejectionRate": rejected / len(values) if values else None,
            }
        )
    return rows


def block(clips: Sequence[dict], key: str) -> dict:
    """Every side-bracket of one window statistic, summarised and swept."""
    out: Dict[str, dict] = {}
    for variant in ("worse", "better", "mean"):
        values = [c[key][variant] for c in clips if c.get(key)]
        out[variant] = {
            "distribution": summarise(values),
            "rejectionCurve": rejection_curve(values),
        }
    return out


def cohort(clips: Sequence[dict]) -> dict:
    return {
        "clips": len(clips),
        "subjects": len({c["subject"] for c in clips if c["subject"]}),
        "days": len({c["day"] for c in clips if c["day"]}),
        "hipAtBottom": block(clips, "hipAtBottom"),
        "hipMinimumClip": block(clips, "hipMinimumClip"),
        "hipMinimumExcursion": block(clips, "hipMinimumExcursion"),
        "kneeMinimumBilateral": summarise([c["kneeMinimumBilateral"] for c in clips]),
    }


def build_artifact(clips: Sequence[dict]) -> dict:
    crossed_rep = [c for c in clips if c["kneeMinimumBilateral"] <= REP_ANGLE_DEGREES]
    crossed_reached = [c for c in clips if c["kneeMinimumBilateral"] <= REACHED_ANGLE_DEGREES]

    condition_names = sorted({name for c in clips for name in c["conditions"]})
    by_condition = {}
    for name in condition_names:
        for value in (True, False):
            group = [c for c in clips if c["conditions"].get(name) is value]
            if group:
                by_condition[f"{name}={'true' if value else 'false'}"] = cohort(group)
    all_true = [c for c in clips if c["conditions"] and all(c["conditions"].values())]
    any_false = [c for c in clips if c["conditions"] and not all(c["conditions"].values())]
    if all_true:
        by_condition["ALL_CONDITIONS_TRUE"] = cohort(all_true)
    if any_false:
        by_condition["ANY_CONDITION_FALSE"] = cohort(any_false)

    return {
        "schemaVersion": 1,
        "artifactKind": "BARBELL_SQUAT_HIP_GATE_DISTRIBUTION",
        "authority": "CATALOG_AND_LABEL_ANALYSIS_ONLY_NOT_RUNTIME_RELEASE",
        "question": (
            "Whether the BARBELL_SQUAT definition gate's 140-degree HIP limit sits outside the "
            "barbell squat's own legitimate distribution, as §4.9 rule 7 requires."
        ),
        "generalisationUnit": "LEAVE_ONE_GLOBAL_Z_SUBJECT_OUT",
        "engineConstants": {
            "gateBoundDegrees": GATE_BOUND_DEGREES,
            "gateChain": "HIP (shoulder-hip-knee), WINDOW_MINIMUM, AT_MOST",
            "attemptAngleDegrees": ATTEMPT_ANGLE_DEGREES,
            "repAngleDegrees": REP_ANGLE_DEGREES,
            "reachedAngleDegrees": REACHED_ANGLE_DEGREES,
            "bilateralDivergenceDegrees": BILATERAL_DIVERGENCE_DEGREES,
        },
        "cohorts": {
            "ALL_CLIPS": cohort(clips),
            "KNEE_CROSSES_REP_LINE_110": cohort(crossed_rep),
            "KNEE_CROSSES_REACHED_LINE_105": cohort(crossed_reached),
        },
        "byCondition": by_condition,
        "captureDays": sorted({c["day"] for c in clips if c["day"]}),
        "limitations": [
            "3D label coordinates, not MediaPipe output: this is a ceiling, not a prediction. The "
            "measured frame error card puts the hip chain's |error| median at 8.3 degrees, so a "
            "real reading scatters around every figure here.",
            "Sixteen sparse keyframes per clip with no timestamps, so the excursion window is "
            "reconstructed geometrically rather than replayed through RepCycleDetector.",
            "The labels carry no confidence, so which side the engine would have measured is not "
            "recoverable; the worse/better/mean bracket stands in for it.",
            "The dataset carries no depth condition for this exercise, so its condition labels "
            "describe head, knee tracking, foot contact and spine — not how deep the squat went.",
        ],
    }


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--data",
        default=os.path.join("data", "013.피트니스자세", "1.Training", "라벨링데이터"),
    )
    parser.add_argument("--out", default="barbell-squat-hip-gate.v1.json")
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

    for name, group in artifact["cohorts"].items():
        stats = group["hipMinimumClip"]["worse"]["distribution"]
        curve = {int(r["boundDegrees"]): r["falseRejectionRate"] for r in
                 group["hipMinimumClip"]["worse"]["rejectionCurve"]}
        print(
            f"{name}: n={group['clips']} subjects={group['subjects']} "
            f"hipMin(clip, worse side) median={stats['median']:.1f} "
            f"p95={stats['p95']:.1f} p99={stats['p99']:.1f} max={stats['max']:.1f} "
            f"reject@140={curve[140] * 100:.2f}%"
        )
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

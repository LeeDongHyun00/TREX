"""Measure the paired excursion-extreme error the reference-distance floors were bootstrapped toward.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
MEDIAPIPE_TO_AIHUB_BRIDGE_ERROR_MEASUREMENT (non-commercial educational scope). Reads the
same-side pairs a bridge run emitted with ``--sides-out``; touches no imagery itself.

Why this exists
---------------
Every threshold in the engine is compared against an EXTREME over an excursion window, never
against a single frame, and the per-frame error card cannot stand in for the extreme's error: the
error is phase-dependent and one-signed — the deepest frame is the most self-occluded and it
fails toward under-flexion — so the extreme's bias runs three to six times the per-frame median.
Policy §4.10 sized its per-chain floors from that fact, but the measurement behind them had two
compromises it named honestly:

  * the per-frame rows carried no frame identity, so excursions were recovered by cutting a
    block-ordered stream apart against per-clip totals — 88.5% of clips reconstructed unambiguously
    and the rest were dropped rather than guessed;
  * both the MediaPipe side and the label side had been collapsed across Left and Right with a
    min/max inside the worker, so every "per-frame" figure already carried one extreme-of-two of
    noise, and a MediaPipe reading could be paired with the label of the OTHER side whenever noise
    ranked the sides differently.

``--sides-out`` removes both: each row is one (clip, frame, side) with the MediaPipe angle and the
label angle of THAT side. This tool takes, per (clip, side), the extreme over the frames that
survived on both sides of the pair, and reports the distribution of

    S = the degrees by which the reported extreme overstates the shortfall
      = extreme(MediaPipe) − extreme(label)   for a "min" chain (flexion)
      = extreme(label) − extreme(MediaPipe)   for a "max" chain (extension)

A claimed reference distance of N is entirely manufactured by the measurement with probability
exactly P(S ≥ N), so a floor is an upper quantile of S and needs no further conditioning.

Which side's S? — the finding that decides the floors
------------------------------------------------------
Same-side pairing exposes what the collapsed pairing hid: the two sides are NOT exchangeable. In
the lateral capture the far side reads systematically worse on every chain (its median S runs
5-8 degrees above the near side's, its p90 10-13 degrees). So "the" extreme error depends on
which side is being reported, and this tool reports S under three selectors:

  * ``perSideEqual``     every (clip, side) counted once — what a two-sided error card would say
  * ``runtimeExact``     per clip, the side the runtime would lock onto: on the first paired
                         frame where both sides are credible, the higher chain confidence wins
                         (Left on a tie), and that side is held for the clip — verbatim
                         FormCheckGeometry.sample plus HeuristicFormCheckSession.selectSample.
                         Needs the ``confidence`` field the sides stream carries.
  * ``runtimeByMeanConfidence``  per clip, the side with the higher MEAN chain confidence over
                         its paired frames — what side-stickiness converges to, kept beside the
                         exact rule because a clip's first frame is an arbitrary instant.
  * ``runtimeLike``      per clip, the side whose MediaPipe extreme is deepest, paired with THAT
                         side's own label — the depth-based analogue used before the confidence
                         field existed; kept so the two can be compared.
  * ``worstSide``        per clip, the larger S of the two — the far side, mostly

Policy §4.10's floors are upper quantiles under the runtime's own selector, ``runtimeExact``.
The per-side-equal figures weight the far side equally and describe a selector the app does not
run; the depth-based ``runtimeLike`` was the stand-in before confidence was carried, and the
artifact keeps it so the gap between "picked by depth" and "picked by confidence" is a number
rather than an assumption.

What it cannot answer
---------------------
A clip holds ~16 labelled frames where the app's repetition window holds 40-60; the previous
measurement showed by within-clip bootstrap that the p90 moves by ≤2° between the two and moves
down, but that transfer is still a bootstrap, not a measurement — this dataset has no 60-frame
paired ground truth at any price. Extension-direction evidence exists only if the emitting run
measured an extension profile; if every profile is flexion, the "max" branch here is exercised by
nothing and the policy's warning about extension stands. IMAGE-mode inference over studio
captures, as always.

Usage
-----
    python tools/summarize_extreme_error.py docs/bridge-sides.full-sweep.v1.json \
        --out docs/bridge-extreme-error.v1.json
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Sequence

# An excursion needs this many paired frames before its extreme is a statistic rather than a
# frame. The bridge tool's clips carry ~16 labelled frames; the runtime abstains under five
# observed frames, and this mirrors that floor.
MINIMUM_PAIRED_FRAMES = 5

QUANTILES = (0.50, 0.75, 0.90, 0.95, 0.99)
EXCEEDANCE_DEGREES = (10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 40.0)


def percentile(values: Sequence[float], fraction: float) -> Optional[float]:
    """Nearest-rank percentile; deterministic and dependency-free."""
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(math.ceil(fraction * len(ordered))) - 1))
    return ordered[index]


def selector_views(rows: Sequence[dict]) -> Dict[str, Dict[str, List[dict]]]:
    """S per chain under each of the three selectors, from clips where both sides survived.

    A clip with only one credible side is left to ``perSideEqual``; the two per-clip selectors
    need both sides to choose between, and 39 of 6,035 clips in the full sweep have only one.
    """
    by_clip: Dict[str, Dict[str, List[dict]]] = defaultdict(dict)
    for row in rows:
        by_clip[row["clip"]].setdefault(row["side"], []).append(row)

    views: Dict[str, Dict[str, List[dict]]] = {
        "perSideEqual": defaultdict(list),
        "runtimeExact": defaultdict(list),
        "runtimeByMeanConfidence": defaultdict(list),
        "runtimeLike": defaultdict(list),
        "worstSide": defaultdict(list),
    }
    for clip, sides in by_clip.items():
        usable = {s: fr for s, fr in sides.items() if len(fr) >= MINIMUM_PAIRED_FRAMES}
        if not usable:
            continue
        head = next(iter(usable.values()))[0]
        chain, extreme = head["chain"], head["extreme"]

        def overstatement(frames: List[dict]) -> float:
            mediapipe = [f["mediapipe"] for f in frames]
            label = [f["aihub"] for f in frames]
            if extreme == "min":
                return min(mediapipe) - min(label)
            return max(label) - max(mediapipe)

        def mediapipe_extreme(frames: List[dict]) -> float:
            values = [f["mediapipe"] for f in frames]
            return min(values) if extreme == "min" else max(values)

        per_side = {s: overstatement(fr) for s, fr in usable.items()}
        base = {
            "clip": clip,
            "exercise": head["exercise"],
            "day": head["day"],
            "subject": head["subject"],
            "extreme": extreme,
        }
        for side, value in per_side.items():
            views["perSideEqual"][chain].append(
                {**base, "side": side, "pairedFrames": len(usable[side]),
                 "overstatementDegrees": round(value, 2)}
            )
        if len(usable) < 2:
            continue
        deepest = (min if extreme == "min" else max)(
            usable, key=lambda s: mediapipe_extreme(usable[s])
        )
        views["runtimeLike"][chain].append(
            {**base, "side": deepest, "pairedFrames": len(usable[deepest]),
             "overstatementDegrees": round(per_side[deepest], 2)}
        )
        worst = max(per_side, key=per_side.get)
        views["worstSide"][chain].append(
            {**base, "side": worst, "pairedFrames": len(usable[worst]),
             "overstatementDegrees": round(per_side[worst], 2)}
        )
        # The two confidence selectors need the field; rows from an older run lack it and the
        # views simply stay empty, which the artifact then reports as such.
        if all("confidence" in f for fr in usable.values() for f in fr):
            locked = runtime_locked_side(usable)
            if locked is not None:
                views["runtimeExact"][chain].append(
                    {**base, "side": locked, "pairedFrames": len(usable[locked]),
                     "overstatementDegrees": round(per_side[locked], 2)}
                )
            by_mean = max(
                usable,
                key=lambda s: (
                    sum(f["confidence"] for f in usable[s]) / len(usable[s]),
                    s == "Left",
                ),
            )
            views["runtimeByMeanConfidence"][chain].append(
                {**base, "side": by_mean, "pairedFrames": len(usable[by_mean]),
                 "overstatementDegrees": round(per_side[by_mean], 2)}
            )
    return views


def runtime_locked_side(usable: Dict[str, List[dict]]) -> Optional[str]:
    """The side FormCheckGeometry.sample would pick on the first frame both sides are credible.

    HeuristicFormCheckSession.selectSample then holds that side while it stays credible, so the
    first joint frame decides the clip. Frames are put in capture order by their image key — the
    key ends in a zero-padded frame index, and emission order is NOT capture order, because the
    bridge tool's workers drain a queue in whatever order they finish. Left wins a tie, as
    `left.chainConfidence >= right.chainConfidence` does in the runtime.
    """
    if "Left" not in usable or "Right" not in usable:
        return next(iter(usable))
    right_by_key = {f["key"]: f for f in usable["Right"]}
    for left in sorted(usable["Left"], key=lambda f: f["key"]):
        right = right_by_key.get(left["key"])
        if right is None:
            continue
        return "Left" if left["confidence"] >= right["confidence"] else "Right"
    # No frame where both sides were credible: the runtime would have locked whichever
    # appeared first, which the clip cannot say. Abstain.
    return None


def excursion_overstatements(rows: Sequence[dict]) -> Dict[str, List[dict]]:
    """Per chain, one record per (clip, side): S and its context.

    Groups the same-side pairs by clip and side, takes each side's extreme over exactly the
    frames that carry both a MediaPipe and a label reading, and reports how far the MediaPipe
    extreme overstates the shortfall. Sides with fewer than MINIMUM_PAIRED_FRAMES paired frames
    abstain, as the runtime does.
    """
    by_clip_side: Dict[tuple, List[dict]] = defaultdict(list)
    for row in rows:
        by_clip_side[(row["clip"], row["side"])].append(row)

    per_chain: Dict[str, List[dict]] = defaultdict(list)
    for (clip, side), frames in by_clip_side.items():
        if len(frames) < MINIMUM_PAIRED_FRAMES:
            continue
        head = frames[0]
        extreme = head["extreme"]
        mediapipe = [f["mediapipe"] for f in frames]
        label = [f["aihub"] for f in frames]
        if extreme == "min":
            overstatement = min(mediapipe) - min(label)
        else:
            overstatement = max(label) - max(mediapipe)
        per_chain[head["chain"]].append(
            {
                "clip": clip,
                "side": side,
                "exercise": head["exercise"],
                "day": head["day"],
                "subject": head["subject"],
                "extreme": extreme,
                "pairedFrames": len(frames),
                "overstatementDegrees": round(overstatement, 2),
            }
        )
    return per_chain


def summarise(records: Sequence[dict]) -> dict:
    values = [r["overstatementDegrees"] for r in records]
    summary = {
        "excursions": len(values),
        "clips": len({r["clip"] for r in records}),
        "subjects": len({r["subject"] for r in records}),
        "captureDays": sorted({r["day"] for r in records}),
        "exercises": sorted({r["exercise"] for r in records}),
        "extremes": sorted({r["extreme"] for r in records}),
        "median": round(statistics.median(values), 2),
    }
    for fraction in QUANTILES:
        summary[f"p{int(fraction * 100)}"] = round(percentile(values, fraction), 2)
    summary["exceedance"] = {
        f"P(S>={int(n)})": round(sum(1 for v in values if v >= n) / len(values), 4)
        for n in EXCEEDANCE_DEGREES
    }
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sides", type=Path, help="the --sides-out artifact of a bridge run")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument(
        "--only",
        action="append",
        default=None,
        help=(
            "restrict to these exercises (repeatable). The reference-distance floors are about "
            "range-of-motion profiles; a stability profile's extreme answers a different question "
            "and pooling the two would report a guard chain's behaviour as a depth chain's."
        ),
    )
    args = parser.parse_args()

    payload = json.loads(args.sides.read_text(encoding="utf-8"))
    rows = payload["rows"]
    if args.only:
        wanted = set(args.only)
        rows = [r for r in rows if r["exercise"] in wanted]
    per_chain = excursion_overstatements(rows)
    views = selector_views(rows)

    per_exercise: Dict[str, List[dict]] = defaultdict(list)
    for records in per_chain.values():
        for record in records:
            per_exercise[record["exercise"]].append(record)

    def per_exercise_of(view: Dict[str, List[dict]]) -> Dict[str, dict]:
        grouped: Dict[str, List[dict]] = defaultdict(list)
        for records in view.values():
            for record in records:
                grouped[record["exercise"]].append(record)
        return {ex: summarise(recs) for ex, recs in sorted(grouped.items())}

    artifact = {
        "artifactKind": "BRIDGE_EXCURSION_EXTREME_ERROR_CARD",
        "schemaVersion": 1,
        "authority": "CATALOG_AND_LABEL_ANALYSIS_ONLY_NOT_RUNTIME_RELEASE",
        "source": args.sides.name,
        "question": (
            "By how many degrees does the excursion extreme MediaPipe reports overstate a "
            "shortfall against the 3D label's extreme over the same frames of the same side — the "
            "quantity policy §4.10's reference-distance floors are upper quantiles of?"
        ),
        "signedConvention": (
            "S = min(MediaPipe) − min(label) for a min chain, max(label) − max(MediaPipe) for a "
            "max chain; positive means the reported extreme sits short of the truth"
        ),
        "unit": "one record per (clip, side) with at least MINIMUM_PAIRED_FRAMES same-side pairs",
        "restrictedTo": sorted(args.only) if args.only else None,
        "minimumPairedFrames": MINIMUM_PAIRED_FRAMES,
        "perChain": {chain: summarise(recs) for chain, recs in sorted(per_chain.items())},
        "perExercise": {ex: summarise(recs) for ex, recs in sorted(per_exercise.items())},
        "selectors": {
            name: {
                "perChain": {chain: summarise(recs) for chain, recs in sorted(view.items())},
                "perExercise": per_exercise_of(view),
            }
            for name, view in views.items()
        },
        "selectorNote": (
            "perChain/perExercise above count every (clip, side) once. The floors in policy "
            "§4.10 are upper quantiles under selectors.runtimeExact — the side the runtime locks "
            "onto, chosen by chain confidence on the first frame both sides are credible and "
            "held thereafter, verbatim FormCheckGeometry.sample + selectSample. runtimeLike is "
            "the depth-based stand-in used before the confidence field existed and is kept for "
            "comparison; perSideEqual weights the far side equally and describes a selector the "
            "app does not run."
        ),
        "limitations": [
            "A clip holds ~16 labelled frames against a 40-60 frame runtime window; the transfer "
            "was previously bounded by within-clip bootstrap (p90 moves <=2 degrees, downward) "
            "and remains a bootstrap, not a measurement.",
            "Extension-direction evidence exists only where the emitting run measured an "
            "extension profile; check perChain[*].extremes before citing a max-chain figure.",
            "IMAGE-mode inference over studio captures; the 3D reconstruction is itself a label.",
        ],
    }
    args.out.write_text(
        json.dumps(artifact, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {args.out}")
    for name, view in artifact["selectors"].items():
        print(f"\n[{name}]")
        for chain, s in view["perChain"].items():
            print(
                f"  {chain:9s} n={s['excursions']:5d} median={s['median']:+6.2f} "
                f"p90={s['p90']:+6.2f} p95={s['p95']:+6.2f} "
                f"P>=15={s['exceedance']['P(S>=15)']:.3f} "
                f"P>=25={s['exceedance']['P(S>=25)']:.3f} "
                f"P>=35={s['exceedance']['P(S>=35)']:.3f}"
            )
    print("\n[perSideEqual, legacy layout]")
    for chain, s in artifact["perChain"].items():
        print(
            f"{chain:9s} excursions={s['excursions']:6d} subjects={s['subjects']:3d} "
            f"median={s['median']:+6.2f} p90={s['p90']:+6.2f} p95={s['p95']:+6.2f} "
            f"P(S>=15)={s['exceedance']['P(S>=15)']:.3f} P(S>=25)={s['exceedance']['P(S>=25)']:.3f} "
            f"P(S>=35)={s['exceedance']['P(S>=35)']:.3f}  extremes={s['extremes']}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

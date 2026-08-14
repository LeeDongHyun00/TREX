"""Turn per-frame bridge pairs into the per-chain error card the policy keeps reaching for.

Authorized by `docs/pose-data-rights-manifest.aihub-research.v1.json` under
MEDIAPIPE_TO_AIHUB_BRIDGE_ERROR_MEASUREMENT (non-commercial educational scope). Reads the
per-frame pairs a bridge run emitted; touches no imagery itself.

Why this exists
---------------
Several policy bounds now lean on a per-frame error figure that has only ever been measured on
the knee chain: the curl gate's margin, the STAY-clause noise rule, the divergence-gate refusal
and the fifteen-degree baseline floor all argue from "the per-frame error of this chain is
unmeasured" (docs/pose-heuristic-form-check.v1.md §4.9 rules 5-6). The bridge runs already
decode every frame those numbers would need — the pairs were simply discarded after the clip
extreme was taken. `measure_bridge_from_archives.py --frames-out` now keeps them, and this tool
distils the card: per chain and per exercise, the signed bias and the absolute error of a single
frame's reading, with the spread that decides whether a bound's margin is real.

The signed convention is MediaPipe minus label, so a positive bias means MediaPipe reads the
joint straighter than the 3D reconstruction — the direction every clip-level bias in the bridge
card has pointed so far.

Usage
-----
    python tools/summarize_bridge_frame_error.py \
        docs/bridge-frames.barbell-lunge-curls.v1.json \
        --out docs/bridge-frame-error.v1.json
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path
from typing import Dict, List, Optional, Sequence


def percentile(values: Sequence[float], fraction: float) -> Optional[float]:
    """Nearest-rank percentile; deterministic and dependency-free."""
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(math.ceil(fraction * len(ordered))) - 1))
    return ordered[index]


def summarise(pairs: Sequence[Dict[str, float]]) -> dict:
    """The error card for one group of per-frame pairs."""
    signed = [p["mediapipe"] - p["aihub"] for p in pairs]
    absolute = [abs(d) for d in signed]
    return {
        "frames": len(pairs),
        "subjects": len({p["subject"] for p in pairs}),
        "captureDays": sorted({p["day"] for p in pairs}),
        "signedBias": {
            "median": round(statistics.median(signed), 2),
            "p5": round(percentile(signed, 0.05), 2),
            "p95": round(percentile(signed, 0.95), 2),
        },
        "absoluteError": {
            "median": round(statistics.median(absolute), 2),
            "p90": round(percentile(absolute, 0.90), 2),
            "p95": round(percentile(absolute, 0.95), 2),
        },
    }


def build_artifact(rows: List[dict], source: str) -> dict:
    by_chain: Dict[str, List[dict]] = {}
    by_exercise: Dict[str, List[dict]] = {}
    for row in rows:
        by_chain.setdefault(row["chain"], []).append(row)
        by_exercise.setdefault(row["exercise"], []).append(row)

    return {
        "schemaVersion": 1,
        "artifactKind": "BRIDGE_PER_FRAME_ERROR_CARD",
        "authority": "CATALOG_AND_LABEL_ANALYSIS_ONLY_NOT_RUNTIME_RELEASE",
        "question": (
            "What a single frame of a chain's MediaPipe reading misses the 3D label by — the "
            "quantity per-frame noise arguments in the policy have so far had to borrow from "
            "the knee chain or from walking-gait literature."
        ),
        "signedConvention": "MEDIAPIPE_MINUS_AIHUB_3D_LABEL",
        "source": source,
        "perChain": {chain: summarise(group) for chain, group in sorted(by_chain.items())},
        "perExercise": {ex: summarise(group) for ex, group in sorted(by_exercise.items())},
        "limitations": [
            "IMAGE-mode inference over studio captures; the runtime is VIDEO mode in the wild.",
            "Frames come only from the capture days and selected views of the exercises the "
            "emitting run measured, so a chain's card reflects those exercises' poses, not the "
            "chain in general.",
            "The 3D reconstruction is itself a label, not truth; where it fails, its failure is "
            "counted here as MediaPipe's.",
        ],
    }


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("frames", type=Path, help="a --frames-out file from a bridge run")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)

    payload = json.loads(args.frames.read_text(encoding="utf-8"))
    rows = payload.get("rows") or []
    if not rows:
        parser.error("no per-frame rows in the input")

    artifact = build_artifact(rows, source=args.frames.name)
    args.out.write_text(
        json.dumps(artifact, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    for chain, card in sorted(artifact["perChain"].items()):
        print(
            f"{chain}: {card['frames']} frames, bias median {card['signedBias']['median']:+.1f} deg, "
            f"|err| median {card['absoluteError']['median']:.1f} p95 {card['absoluteError']['p95']:.1f}"
        )
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

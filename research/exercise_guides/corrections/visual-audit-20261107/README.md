# Full-catalog visual correction — 2026-11-07

All 72 packaged GIFs were decoded and inspected against their existing exercise
variant, setup and steps. The audit retained 59 and corrected 13. Findings for
every exercise are in `../../visual-audit-20261107.json`.

The PNGs here are unpainted native outputs from the built-in image generator.
The four `receipt-*.json` files preserve exact selected/rejected prompts and
review findings. Generated-image paths identify local provenance; selected PNGs
are tracked here so the app assets do not depend on that local output directory.

Corrections address supporting-foot drift in three lunges, step-up proportions,
whole-body elevation in calf raises, dumbbell-bench head support, machine/cable
continuity, I-Y-T bench registration, dead-bug hand clipping, bird-dog leg alignment
and the specified elevated-leg scissor-cross variant. The reviewed muscle roles
and red/orange convention are retained. Small drawing differences between poses
remain; these are two/three-pose illustrations, not continuous motion capture.

Packaging: split each PNG into two equal square panels (three for I-Y-T), resize
each to 640 × 640 with Lanczos, derive one 256-color median-cut palette from the
whole strip, quantize each frame with Floyd–Steinberg, then save a GIF with 900 ms
per frame, loop 0, disposal 2, optimize false. No compositing, joint repositioning
or color repainting was performed after image generation. The source/GIF SHA-256
values and previous revisions are recorded in `../../guides.ko.json`.

Native sources and all final encoded poses were inspected, including 260 px
previews. `python tools/generate_exercise_guides.py --check` verifies registry and
packaged hashes. Device/build evidence is in `docs/EXERCISE_GUIDE_APP_72.md`.

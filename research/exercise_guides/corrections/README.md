# App-integration image correction

`bicycle_crunch.png` is the native output of the built-in image generation edit
tool, 2026-11-07. The original two-panel sheet placed the second figure across the
center boundary. Equal-width GIF packaging therefore left a stray elbow fragment
in frame 0 and clipped the rear elbow in frame 1. Both figures are now isolated
inside their own square panels. Movement variants, red/orange roles, opaque shorts
and grayscale styling remain the same. Native and 260 px frame reviews passed.

The app GIF uses equal square crops resized to 640 × 640, a shared 256-color
palette, two 900 ms frames, disposal 2 and infinite looping. The final hash and
original reviewed hash are stored in `../guides.ko.json` under this exercise's
`asset_sha256` and `app_visual_revision`.

## Final edit prompt

Precise framing correction of this existing TREX educational exercise sprite sheet. Output a wide 2:1 canvas with TWO EXACTLY EQUAL SQUARE PANELS side by side, without any drawn divider, text or labels. Preserve the existing identity, grayscale anatomical rendering, black opaque shorts, white background, camera orientation, two exact exercise poses, and all red/orange muscle regions. CRITICAL FIX: resize and position each entire person independently inside its own square panel with generous white margin (at least 7% panel width on all four sides). No part of either person, fingers, elbows, feet or shadows may cross the vertical center boundary or the outer canvas boundaries. The first panel must contain ONLY the first complete person and no fragment of the second person's elbow. The second panel must show the second person's complete rear elbow/hand; nothing may be clipped at its left edge. LEFT pose: supine bicycle-crunch preparation with shoulders gently raised, BOTH hips and knees at approximately 90 degrees, shins horizontal, hands lightly behind head. RIGHT pose: same supine person rotates the NEAR shoulder/elbow toward the FAR bent knee while the NEAR leg extends, exactly like the reference. The far hand stays behind the head, do not pull the neck. Preserve red rectus abdominis/external oblique and orange visible anterior/middle deltoid and thigh quadriceps regions exactly; calves, feet, forearms and upper-arm bellies remain neutral gray; shorts remain black. Do not add blue or paint clothing. Preserve the anatomical detail and realistic joint anatomy. Only correct each figure's scale/placement to isolate both complete poses in equal square panels.

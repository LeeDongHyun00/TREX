# Guide integration — 72 exercises

Base: PR #65, `c7e16633b73f498bb16feb3a655dca9c3fa6f6a0`.
Local branch: `codex/exercise-guides-72`.

## Content and UI

- All 72 exact catalog names have their reviewed GIF and Korean guide copy.
- Exercise detail opens the guide near the top; preparation/list entry points
  still use the same shared registry and sheet.
- Three tabs: movement instructions, cautions, muscle roles/evidence.
- All 1,355 role entries retain a function, evidence category and numbered links.
  The source dataset preserves the more detailed audit limitations.
- Red/orange legend, gray/deep/occluded caveats and hold/I-Y-T instructions are
  explicit. The color legend is not a claim about EMG magnitude or efficacy.
- Camera support remains the original 16 exercises. The 46 catalog additions
  remain camera-disabled. Training-load weights are unchanged.

## Reproduction

```powershell
python tools/generate_exercise_guides.py --check
python tools/export_ios_resources.py --check
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

The original generated artwork lives in the ignored production output folder.
The app is self-contained: all required GIFs are in its tracked assets directory.
The retained research JSON and generator make registry regeneration independent
of that output folder. The legacy iOS resource JSON is regenerated too.

## Verification status

Unit tests: 928 passed, zero failures/errors/skips. `assembleDebug`,
`assembleDebugAndroidTest` and `lintDebug` passed; lint reported 0 errors,
50 warnings and 4 hints. Generator, resource export and the 52-file shared-core
synchronization checks passed. The final APK contains all 72 expected asset hashes.

The isolated Android API 36.1 emulator passed all three `ExerciseGuideCatalogTest`
tests in 274.401 seconds:

1. All 72 GIFs decode at 640 × 640; all 145 frames differ within their exercise
   and their native duration matches the shared GIF timing parser.
2. I-Y-T changes visibly on screen, including the three illustrated poses.
3. All 72 production sheets expose setup, movement, breathing, image instructions,
   cautions, stabilization roles and the final source link through actual scroll
   and tab actions. No user workout records or preferences are changed by these tests.

Representative screenshots were inspected for squat, one-arm dumbbell row, I-Y-T,
plank, bicycle crunch and Russian twist. This caught a source-sheet framing issue:
the old bicycle sheet crossed the panel boundary. The built-in image generator
corrected framing while preserving the two poses and muscle roles. See
`research/exercise_guides/corrections/README.md` for the prompt and provenance.
The corrected native/260 px artwork and both rendered app frames were inspected.
All 145 frames passed a fresh native decode test after the final APK rebuild
(5.43 seconds); unchanged screen/copy behavior is covered by the full earlier run.
An edge scan flagged three other assets, which were inspected: machine/frame
outlines or small edge contact, with no neighboring figure fragment identified.

Manual real-app navigation passed: onboarding → guest mode → workout tab →
catalog → I-Y-T detail → guide, and the abs filter → bicycle-crunch detail →
corrected guide. The catalog still shows 16 camera exercises and 56 record-only
exercises. Temporary emulator data is removed after verification.

Evidence: `outputs/exercise-guides-72/roles-v3/emulator-guide-tests.log`,
`roles-v3/emulator-verification/guide-verification/`, and
`outputs/exercise-guides-72/app-integration/` (final build/decode logs, screenshots,
APK/hash summary and correction receipt). These are local verification artifacts.

## Connected phone — explicit user hold

Galaxy Note10+ SM-N976N: the initial app update and test APK installed successfully
without uninstalling the existing app. All 72 GIFs decoded on the phone. A separate
concurrent installation then killed the instrumentation process (`installPackageLI`)
and replaced the app. The initial screen check also revealed that the test needed
native-view accessibility lookup instead of Compose-only semantics; this was fixed
and passed on the emulator.

The user explicitly asked to wait until the other task is finished. No further
phone installation or interaction was performed after that answer. The final
bicycle correction is therefore **not installed or screen-verified on the phone**.
Resume from the final APK when the device is released; preserve existing data and
coordinate the latest build from the other task before replacing its installation.
The test package `com.example.trex_kotlin.test` remains pending that handoff.

NOT RUN: final full phone screen/playback verification while the user hold is
active; native iOS compilation/testing on Windows; clinical certification.

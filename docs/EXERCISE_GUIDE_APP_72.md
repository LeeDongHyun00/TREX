# Guide integration — 72 exercises

Original content baseline: PR #65, `c7e16633b73f498bb16feb3a655dca9c3fa6f6a0`.
Integrated onto PR head `e5bdf126d4f8a231ef00398052bac29434936acc` before publication.
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

After integrating the current PR: app unit tests 929 passed; shared-core JVM tests
559 passed. App tests had zero failures/errors/skips. `assembleDebug`,
`assembleDebugAndroidTest` and `lintDebug` passed; lint reported 0 errors,
51 warnings and 4 hints. Generator, resource export and the 52-file shared-core
synchronization checks passed. The final APK contains all 72 expected asset hashes.

Before the PR-head integration, the isolated Android API 36.1 emulator passed all three `ExerciseGuideCatalogTest`
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

## PR-head integration

The latest PR's first-position muscle atlas, bodyweight record behavior and
training-load evidence panel are retained. The guide entry follows the muscle
card, whose role text now uses the same reviewed dataset as the guide. Red/orange
swatches remain; their labels describe visual emphasis rather than dynamic roles.
The porting specification records this addition as section 135.

Integration checks found two pre-existing inconsistencies in the PR head:
`RestorationUiTest` still referenced the removed `HomeNutrition`, and the generated
shared `MuscleLoad` copy lagged behind Android. The test now exercises the current
`HomeGlanceStrip`, and the shared copy is regenerated from the Android source.
A fresh API 36.1 emulator run on the integrated APK passed all four selected tests
in 183.312 seconds: all 72 GIFs/145 frames, visible I-Y-T animation, all 72 guide
sheets through the last source, and the updated large-text home/onboarding test.
The final red/orange swatches, bicycle artwork and source-tab screenshots were
inspected. Evidence is retained locally under
`outputs/exercise-guides-72/app-integration/pr65-verification/` and
`pr65-emulator-tests.log`. No phone interaction occurred.

The final APK contains 72/72 reviewed GIF hashes and has SHA-256
`9d13ad59c4f4ee489caf66cd5a6abe65b64c8127714ab62d7a218c2d209f5cba`.

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

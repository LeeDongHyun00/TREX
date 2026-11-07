# Guide integration — 72 exercises

Original content baseline: PR #65, `c7e16633b73f498bb16feb3a655dca9c3fa6f6a0`.
Integrated onto PR head `e5bdf126d4f8a231ef00398052bac29434936acc` before publication.
Local branch: `codex/exercise-guides-72`.

## Content and UI

- All 72 exact catalog names have their reviewed GIF and Korean guide copy.
- Exercise detail opens the guide near the top; preparation/list entry points
  still use the same shared registry and sheet.
- Two tabs: movement instructions and cautions. The image-reading section,
  muscle/evidence tab and small explanation below the introductory sentence
  were removed at the user's request on 2026-11-07.
- All 1,355 role entries retain a function, evidence category and numbered links.
  The source dataset preserves the more detailed audit limitations.
- Red/orange swatches and hold/I-Y-T movement instructions remain. The source
  dataset retains muscle evidence and limitations for review; the guide sheet
  no longer renders the extra explanations or evidence panel.
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

## Earlier verification history

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
An initial edge scan flagged three other assets. The initial review accepted them,
but later production-phone review exposed a bird-dog neighboring-foot fragment.
That false acceptance is corrected in the phone follow-up below; the other two
flagged assets were re-opened and showed machine/frame outlines, not a neighboring
figure fragment.

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

The pre-phone APK contained 72/72 reviewed GIF hashes and had SHA-256
`9d13ad59c4f4ee489caf66cd5a6abe65b64c8127714ab62d7a218c2d209f5cba`.

## Connected phone — resumed 2026-11-07

The user released the previous device hold. PR head `527b9504` was fast-forwarded
locally, including the MET calculation changes. The latest build passed 934 unit
tests, `assembleDebug`, `assembleDebugAndroidTest` and lint (0 errors, 51 warnings,
4 hints). The Galaxy Note10+ SM-N976N runs Android 12 / API 31. Installation used
`adb install -r`, with no uninstall or data clearing. A private, Git-ignored local
backup was taken first; no phone records or tokens were uploaded.

The initial physical-device run passed all three guide tests in 133.988 seconds:
72 GIFs / 145 distinct decoded poses, three visibly different I-Y-T frames, and
all 72 sheets through setup, movement, breathing, visual instructions, cautions,
stabilization roles and the final source. Before production-app navigation,
all 14 existing preferences/database/no_backup files matched the pre-install
backup byte for byte.

Production-app review then caught a visual error that the structural tests cannot
prove absent: bird-dog frame 0 included the extended foot from the neighboring
source panel. The native image generator corrected figure placement while
preserving the poses, camera angle and reviewed muscle overlays. Both complete
640 px frames and the 260 px preview were inspected. See
`research/exercise_guides/corrections/BIRD_DOG.md` for the exact prompt and native
output. Bird-dog was added to the physical screenshot samples. The other earlier
edge flags (lat pulldown and incline push-up) were re-opened and verified.

The corrected app APK SHA-256 is
`e6d7a1d0a486eb602721bee426dd65d3c49767d2fe828178f069ebb3e20a5b1c`.
The installed APK was confirmed to match this hash after an ADB connection drop
during installation. All 72 packaged GIF hashes match the final source dataset.

The corrected APK passed the complete phone suite again: **3/3 tests, 118.403
seconds**, 72/72 sheets and 145 decoded poses. The actual bird-dog guide and its
last source were visually inspected on the phone; the stray foot is absent.
Local evidence: `outputs/exercise-guides-72/app-integration/phone-corrected-tests.log`,
`phone-corrected-verification/` and `phone-validation.json`. The production app was
opened after testing. The private backup remains local and ignored by Git.

NOT RUN: native iOS compilation/testing on Windows; clinical certification.

## Full visual audit and simpler guide — 2026-11-07

The user requested removal of image-reading explanations, the muscle/evidence
tab and the small text below the exercise introduction. The sheet now has only
instructions and cautions. Red/orange swatches and the main introductory sentence
remain; evidence data is still retained in the research registry. Old saved tab
index 2 is clamped to the remaining cautions tab.

All 72 actual packaged GIFs were decoded and inspected at native frame size
against their existing variant/setup/steps. Findings and final asset hashes are
recorded in `research/exercise_guides/visual-audit-20261107.json`: 59 retained,
13 regenerated. Earlier decode/UI passes did not prove anatomical or mechanical
continuity; this audit specifically examines those visual errors.

Corrected exercises: lunge, barbell lunge, side lunge, step-up, calf raise, lat
pulldown, dumbbell bench press, machine chest press, face pull, I-Y-T, dead bug,
bird dog and scissor cross. Issues included moving support points, inconsistent
body/platform scale, floating head support, changing machine linkages/plate
counts, panel contamination and disagreement with the specified leg elevation.
Selected native outputs and exact generation receipts are committed in
`research/exercise_guides/corrections/visual-audit-20261107/`. All final encoded
poses were re-opened at 260 px; red/orange visibility and complete limbs were
checked. Minor drawing drift remains; these are pose illustrations.

Final build: 934 unit tests, zero failures/errors/skips; app/test APK assembly and
lint passed (0 errors, 51 warnings, 4 hints). Generator, iOS-resource and shared-core
sync checks passed. The APK contains all 72 reviewed GIF hashes. APK SHA-256:
`d245766fe533a5a1617d16c1373cfb2a8817b90472b9e13f5e2fd4b7cd654975`.
The connected Galaxy Note10+ was updated with `install -r`; installed APK hash
matches and all 14 existing state files matched the fresh private backup.

The complete phone suite passed **3/3 tests in 86.075 seconds**: 72 GIFs and
145 decoded poses, visible I-Y-T playback, and all 72 production sheets through
setup/movement/breathing/cautions with assertions that the removed explanations
and muscle/evidence tab are absent. All 13 corrected guides have fresh physical
screenshots; each was visually inspected. Existing state files still matched
after testing. The test package was removed and the production app was opened.
The `*-sources.png` files in the pulled folder are historical files from the
previous run; only current `*-guide.png`, `*-cautions.png`, playback and 72-item
result files support this run. See `phone-tests.log` and `phone-validation.json`
in the local evidence folder below.

Local verification artifacts and deliberate retained helpers are grouped in
`outputs/exercise-guides-72/visual-audit-20261107/`: native/260 px review sheets,
packaging script, build/test logs and phone screenshots. Private phone backups
remain in Git-ignored `android_data_backup_guides_*` directories. They are not
included in the PR. No new application server or background service was created.

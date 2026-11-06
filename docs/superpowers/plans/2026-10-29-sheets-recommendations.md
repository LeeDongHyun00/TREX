# Shared sheets and replacement recommendations

**Goal:** All app-owned bottom sheets support drag dismissal. Complex sheets open expanded; only the photo entry menu starts partially and can expand. The replacement recommendation tab contains source-labelled weather and fatigue lists only.

**Design:** Replace the custom iOS `SheetDrag` implementation with one Material3 `SheetState` in `SheetHost`. Use expanded opening by default, an explicit partial-start option for the photo entry menu, the default drag handle, and existing dismissal callbacks. Migrate existing Material sheets to the same host; preserve inner scrolling and number wheels. Material owns IME/navigation insets, so callers must not apply duplicate IME padding. Test partial start, upward expansion, repeated drag settling, nested scrolling, and downward dismissal in a synthetic activity without writing user records.

**Diagnosis:** The previous iOS-only implementation owns offset through both draggable and nested-scroll callbacks. `release` launches animations without cancelling earlier settles, and starts a return spring even after dismissal. Concurrent writers can fight over the offset. This is a code-supported cause candidate, not an observed iOS device reproduction. Remove this competing implementation rather than tuning springs.

**Recommendations:** Load weather via the same `WeatherServices`, selected/GPS location resolver, and repository cache as Home, without requesting new location permission. Cancel work on close and refresh after resume. Build source groups from catalog-mapped names, remove current exercise and duplicates within each group; allow an exercise in both source groups. Fatigue uses the current seven-day snapshot and profile equipment. Empty/error groups are omitted, and no explanatory text appears in recommendation rows. Selecting an item still edits the existing draft and only saves on confirmation.

- [x] Add device regression tests and observe the wheel conflict failure (value stayed 10). The initial generic-sheet baseline attempts were blocked by test visibility, not treated as behavioral evidence.
- [x] Unify bottom-sheet gesture/inset ownership and enable partial expansion.
- [x] Add source-group policy tests and connect weather/fatigue to the picker.
- [x] Run Android unit/device tests, core/iOS source checks, and review.
- [x] Document evidence and native iOS verification limits.

## Implementation and review

- Nine direct Material sheet sites now call `SheetHost`; its existing five callers also inherit the same behavior (three are legacy helpers currently bypassed by the router). Active flows: exercise editor/add/replacement, exercise guide, theme, voice, profile edit, nutrition goals, manual food, photo food, quick settings and performed-count adjustment.
- Complex sheets start expanded. The photo entry menu opts into a partial anchor; camera/analysis/results expand the existing state without recreating it. Short sheets use their content height. The standard handle remains available for expansion/dismissal. Confirmation dialogs and OS gallery/permission interfaces are outside this app-owned bottom-sheet scope.
- Removed the iOS-only offset/animation implementation and both platform flags. Removed duplicate caller IME padding. `ManualSheet` now puts search immediately after meal selection and results before nutrition totals; no existing nutrition or portion data was removed.
- Ruling: isolate number-wheel gestures while pressed or snapping/flinging, with per-owner locks and disposal cleanup. The reviewer found that Material pre-scroll consumes upward wheel drags from the partial anchor and downward boundary overscroll can dismiss the editor. Device regression reproduced unchanged wheel value before the guard; the guarded test passed. Handle/body dragging stays enabled after the wheel releases.
- One read-only review plus a focused re-review found no additional actionable defect. No unrelated engine/recommendation coefficients changed.

## Verification

- Android unit tests: 778 passed; common JVM core: 525 passed.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest`: passed.
- Connected SM-N976N: five instrumentation tests passed (partial start/expand/dismiss without reappearance; wheel directions and upper bound in partial/expanded states; source headings/name selection). The cancellation-path run also passed. The follow-up tests for default-expanded opening and compact-to-detail expansion also passed. Final device result: OK (5 tests), 8.446 seconds.
- `tools/sync_ios_core.py --check`: 51 sources match; `tools/export_ios_resources.py --check`: passed. Changed screen files and number wheel are already in `ios-ui/android-sources.txt` and compile from the same source on iOS.
- `:app:lintDebug`: failed only on the pre-existing literal BOM in `food/FoodDetector.kt:142` (`ByteOrderMark`); 31 warnings and 3 hints. No new lint error in the changed UI files. This separate food-track issue was not modified.
- NOT RUN: native iOS compilation and iPhone gesture/keyboard verification (Windows host, no Xcode/iPhone). The old competing animation paths are established in code; the reported iOS oscillation was not reproduced on an iPhone here.
- Actual photo entry screen was inspected after unlock: initial sheet top 1140px on a 2280px screen, camera/gallery actions in the lower portion; upward handle drag moved the top to 162px. Screenshots are under `outputs/sheet-ui/`. Test activity uses keep-screen-on/show-when-locked only for its own synthetic screen; it does not change device security settings or write user records.

## Final opening policy

The user clarified that compact opening is for sparse action menus, not editors or information-rich sheets. `SheetHost` now defaults to expanded opening (`startPartiallyExpanded = false`). Only `PhotoFoodSheet` opts in; `expandContent` advances its existing SheetState when leaving Pick. Workout edit/add/replacement, weather, manual food, profile, goals and guides therefore open expanded. Both starting policies retain downward dismissal and the numeric-wheel guard. No fixed percentage was imposed on content-sized short sheets.

Final device follow-up: default-expanded manual food top=162px on a 2280px screen. Keyboard opens with search (y597..660) and completion button (y1131..1194) visible. No food was added, edited, or cleared. Updated APK installed successfully; temporary instrumentation package removed. Evidence: `outputs/sheet-ui/verification.json` and four inspected screenshots.

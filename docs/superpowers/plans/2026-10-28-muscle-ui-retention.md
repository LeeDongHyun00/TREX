# Muscle UI and record retention implementation plan

**Goal:** Render both muscle maps, retain workout records for the same seven calendar days on Android/iOS, keep the camera toggle at the top right, and remove the muscle-page footer.

**Design:** Keep the shared HTML viewer and size its stage from the viewport in pixels on load/resize. Keep the pure load engine platform-independent; pass a calendar cutoff from the shared app layer to snapshots and both persistence implementations. Preserve future-dated records on clock rollback but exclude them from scoring. Expired records are removed on foreground refresh and date rollover; repeated cleanup is idempotent. Empty history must render a neutral body. License attribution moves to Settings.

**Paths:** `muscle_load/viewer.js`, `MuscleLoad.kt`, `RecordRetention.kt`, `TrexAppState.kt`, Android `MuscleLoadStore`, iOS `FileMuscleLoadDatabase`, shared `PostureLive` and `SettingsScreen`.

**Failure paths:** A zero-size initial viewport must recover after resize. Storage failure must keep the existing explicit error state while filtering expired scores independently. No routine/profile/baseline removal. The user explicitly excluded diagnostic logs and self-labels from deletion.

- [x] Add and run failing seven-day retention regression tests.
- [x] Fix viewport sizing, toggle placement, footer, and attribution location.
- [x] Align both stores and snapshot refresh with the calendar cutoff.
- [x] Synchronize iOS core; build and test Android/common JVM; verify fatigue map on device and both modes in headless Chrome.
- [x] Review diff and record validation/limitations in the handoff and porting spec.

Validation: Android 776 tests, core JVM 525 tests; APK build passed. Headless Chrome passes both modes, zero-root-height recovery, three sizes, colors and session isolation. Device home-to-fatigue screenshot confirms visible bodies. The separate full workout-completion flow and active camera toggle still require manual device checks. iOS source/resource checks pass; native iOS compilation is unavailable on Windows. Full iOS static project script could not run because Python `yaml` is unavailable.

Review: independent read-only review found stale UI on a storage-cleanup failure. Removed the early return so the existing snapshot identity guard applies while expired rows are filtered even if storage cleanup fails.

Final device update: installed APK SHA-256 matches `86d7c75d25ede176c8d45a79d600d57de8040071f447235d143310e4080dfa77`. Read-only SQLite inspection after cleanup reports integrity `ok`, zero retained sets and zero dose rows. The temporary database copy was removed. Evidence screenshots/aggregate results remain in `outputs/muscle-ui-fix/`.

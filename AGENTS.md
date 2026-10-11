# AGENTS.md

Pawparazzi: Android app that watches the back camera with MediaPipe EfficientDet-Lite0, plays a sound for each newly
appearing cat or dog and saves a full-resolution photo to `Pictures/Pawparazzi/`. It runs for days on a dedicated phone
(Android 13), including with the screen off.

## Commands

```
./gradlew spotlessApply          # format (ktfmt, kotlinlang style) — run before committing
./gradlew spotlessCheck          # what the pre-push hook and CI run
./gradlew testDebugUnitTest      # JVM unit tests; the only tests CI runs
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest   # instrumented; needs the real device
```

- Enable the hook once per clone: `git config core.hooksPath .githooks`.
- The detector model is fetched by the `downloadModel` Gradle task into `camera/src/main/assets/` (git-ignored).
- All Kotlin and Java warnings are errors. Fix warnings; do not suppress them.
- JDK 25, Kotlin DSL, version catalog in `gradle/libs.versions.toml` (Renovate keeps versions current).

## Modules and dependency rules

```
:app             Activity, Compose UI, ViewModels, DetectionService, Hilt wiring, manifest
:core:detection  Pure Kotlin/JVM: DetectionFilter, Tracker, ActionGate, FrameThrottler, FrameWatchdog,
                 ThermalPolicy, LandscapeRotationFilter, FpsRangeSelector, models, Clock. NO Android deps.
:core:settings   DataStore-backed SettingsRepository, AppSettings, SettingsValidator (clamping)
:camera          CameraX controller, MediaPipe wrapper, result mapping, CaptureQueue
:actions         SoundPlayer (SoundPool), PhotoStore (MediaStore + size cap), PhotoNaming
```

Base package `io.gropp.pawparazzi`; modules use sub-packages (`...core.detection`, `...camera`, ...).

## Architecture invariants (easy to break, expensive to debug)

- **Keep `:core:detection` pure JVM.** All trigger/tracking logic must be testable without Robolectric.
- **Inject `Clock`** (`fun interface Clock { fun nowMs(): Long }`) for anything time-dependent. Production uses
  `SystemClock.elapsedRealtime()`, never `uptimeMillis()`.
- **Tracker and ActionGate use the frame's timestamp**, not the time the async result arrives.
- **`detectAsync` timestamps must be strictly increasing** (`maxOf(now, last + 1)`), otherwise it throws.
- **Always `imageProxy.close()`**, including for throttled frames and on exceptions.
- Analyzer and result listener catch and log all exceptions; a bad frame must never crash the process.
- Detector options are fixed for its lifetime (`scoreThreshold = 0.10`, `maxResults = 20`, allowlist cat/dog, CPU).
  User `scoreThreshold`/`maxResults` are applied afterwards by `DetectionFilter`. Never rebuild the detector for a
  settings change.
- Detection runs on an **upright bitmap**; all box coordinates are normalized `[0,1]` in upright-image space.
- All CameraX use cases share 4:3 and the same `targetRotation`. Only `ROTATION_90`/`ROTATION_270` are accepted
  (the lock screen and launcher flip to portrait); a rotation change resets tracker and gate.
- Tracking is label-agnostic (IoU, then aspect-corrected center-distance fallback). Cooldown is per track. Tracks hold
  no trigger state; the gate owns it.
- Settings are applied **live** while running; no restart. Clamping lives in `SettingsValidator`.
- Camera is owned by `DetectionService` (foreground service, `START_NOT_STICKY`, partial wake lock). The Activity and
  service share state through `@Singleton` Hilt objects (`DetectionStateRepository`, `PreviewSurfaceHolder`), not a
  Binder.
- No `FLAG_KEEP_SCREEN_ON`; turning the screen off must not stop detection.
- Out of scope: re-identification, auto-start after reboot, kiosk mode, networking/sync, night vision, front camera.

## Testing

- **Prefer JVM tests**: JUnit 4 + Truth, `kotlinx-coroutines-test`, Turbine, `FakeClock`. CI runs only
  `testDebugUnitTest`, so anything that must catch regressions has to be a JVM test.
- **Extract, then test**: move logic out of Android classes into plain Kotlin behind small seams (interfaces or
  function parameters) rather than reaching for Robolectric. Existing examples: `DetectionPipeline`,
  `DetectionCoordinator`, `NotificationText`, `DetectionResultMapper`, `SoundChecker`.
- Every test asserts something observable; no "doesn't crash" tests.
- New or changed core logic gets tests alongside it.
- Instrumented tests live in `:actions` and `:app`.
- Extractions must not change app behavior.

## Code style

- Format with Spotless/ktfmt; never hand-format against it.
- Avoid comments. Write self-documenting code (clear names, small functions); rationale belongs in the commit message.
- Match the surrounding code's idioms, naming and structure.

## Git

- Never run `git push`; tell the user when a branch is ready.
- Keep each commit a working build with formatted code. Existing history uses short imperative subjects, some with
  a leading emoji.

## Device-only verification

Camera, thermal, screen-off and vendor-ROM behavior cannot be verified on the JVM. Mention clearly when a change
needs a check on a hardware phone instead of claiming it works.

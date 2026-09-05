# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**This code is still in heavy development.** Anything can be changed; backwards compatibility is
not a concern. Never suggest in comments that something has changed or how the code worked
previously — treat the current code as the only known version.

## Toolchain note

Gradle 8.14.3 does not run on JDK 25+. If the default `java` on PATH resolves to something newer,
set `JAVA_HOME` to a JDK 17 install before running Gradle (the wrapper picks it up automatically).

## Commands

```bash
# Build debug APK
./gradlew :composeApp:assembleDebug

# Build + install on a connected Android device
./gradlew :composeApp:installDebug

# Run unit tests — this is the real test gate on non-Mac hosts; :composeApp:allTests
# additionally aggregates iOS targets that can't run/build without a Mac.
./gradlew :composeApp:testDebugUnitTest

# Regenerate the bundled star/deep-sky catalogs from source (fetches from GitHub;
# only the .bin output is committed, so the app's own build stays offline-reproducible).
# First run (or after pulling a change to tools/package.json) needs `npm install` in tools/ --
# the Milky Way step depends on d3-geo for correct spherical polygon containment.
(cd tools && npm install)
node tools/build-catalogs.mjs
```

## On-Device Debugging (adb)

- **UI element bounds**: `adb shell uiautomator dump //sdcard/ui.xml` then
  `adb pull //sdcard/ui.xml ./ui.xml` (the `//` prefix avoids Git Bash MSYS path-mangling of
  `/sdcard/...`).
- **Screenshot**: `adb exec-out screencap -p > screen.png`.
- **Relaunch app**: `adb shell monkey -p com.astrocompass -c android.intent.category.LAUNCHER 1`.

## Architecture

All application code lives in `composeApp/src/`, a single Gradle module with three source sets:

- `commonMain` — all logic and UI; runs on Android and (eventually) iOS
- `androidMain` — thin Android wiring: `MainActivity`, `AndroidOrientationSensor`,
  `AndroidLocationProvider`, `AndroidMagneticDeclinationProvider`, `AndroidCameraCapture`
- `iosMain` — stub sensor/location implementations, `MainViewController`

### `commonMain` package layout

| Package | Responsibility |
|---|---|
| `astro/` | `Angle`, `Vector3`, `Quaternion`, `Matrix3` — minimal linear algebra, no `java.*`, no platform deps |
| `astro/time/` | `AstroTime` (Julian Day, GMST/LST); `DeviceClock` (`currentEpochMillis()` expect/actual — the one platform-touching piece in `astro/`) |
| `astro/coords/` | `EquatorialCoordinates`, `HorizontalCoordinates`, `CoordinateTransforms` (equatorial <-> horizontal <-> ENU unit vector), `Precession`, `Refraction`, `Ecliptic` |
| `astro/ephemeris/` | `SunEphemeris`, `MoonEphemeris`, `PlanetEphemeris` (JPL Keplerian elements), `SolarSystemEphemeris` facade |
| `astro/io/` | `BinaryReader` — little-endian reader for the catalog blobs (no `java.nio` in `commonMain`) |
| `catalog/` | `SkyObject` sealed interface (`StarObject`, `DeepSkyObject`, `SolarSystemObject`), `CatalogFormat` (binary decode), `CatalogRepository`, `CatalogSearch` |
| `alignment/` | `AlignmentPoint`, `AlignmentModel`, `AlignmentSolver` (yaw-only + Davenport q-method via `JacobiEigenSolver`), `AlignmentStore`, `AlignmentType` (which instrument the user aligned with), `PlateSolveAlignment`, `CompassAlignment` (magnetometer-only `sensorToSky`, no points behind it) |
| `sensors/` | `OrientationSensor` interface, `SensorCapabilities`/`defaultSource()` (auto-selection logic), `FakeOrientationSensor` |
| `location/` | `ObserverLocation`, `LocationProvider` interface, `LocationResolver` (manual-override-wins-over-GPS), `MagneticDeclinationProvider` interface |
| `guiding/` | `TelescopeAxis`, `AbsoluteReference` (+ `AlignmentAbsoluteReference`, `CompassAbsoluteReference`, `AutoPlateSolveRefiner`, `PrioritizedAbsoluteReference`), `StillnessTracker`, `PointingService` (sensor + reference fusion), `GuidanceCalculator`, `CurrentPosition` (target alt/az right now) |
| `settings/` | `AppPreferences` — multiplatform-settings-backed, hand-rolled reactive (`MutableStateFlow` seeded from storage + setter that persists) |
| `ui/screens/` | `MapScreen` (home), `SearchScreen`, `GuidanceScreen`, `AlignmentScreen` (a thin wizard host; the steps live in `ui/screens/alignment/`) + `AlignmentSession` (its hoisted run and step state), `SettingsScreen` |
| `ui/screens/alignment/` | `AlignmentTypeStep`, `StarAlignmentStep`, `CameraCalibrationSteps` — one full screen per wizard branch |
| `ui/components/` | `AppBottomBar` (+ `AppMenuActions`), `ArrowIndicator`, `DeltaBar`, `SkyMap` (pannable/zoomable alt-az chart) |
| `ui/skymap/` | `SkyMapViewport` (pan/zoom state), `SkyMapScene` (projection/culling/hit-test), `SkyMapDirectionCache` (per-tick catalog + constellation-line ENU snapshot) -- `SkyMap`'s non-Composable backing logic, kept separately unit-testable |
| `ui/theme/` | `GuiderTheme`, `AppTheme` (Light/Dark/Night) |

`AppContainer.kt` (top-level `com.astrocompass`) owns every long-lived service — sensor, location,
catalog, preferences, alignment — built once by the platform entry point and injected into
`GuiderApp`. Navigation is state-based in `App.kt` (booleans/nullable vars), no nav library.

### The alignment wizard

`AlignmentScreen` is a linear wizard whose first step asks **which instrument this setup aligns
with** (`AlignmentType`), because a phone with a usable camera never needs star syncs at all — one
plate solve recovers a complete 3-DOF fit. The two branches share nothing past that fork:

- `SENSORS_ONLY` → `StarAlignmentStep`, the 2–3 star sync flow below.
- `PLATE_SOLVE` → `CameraCalibrationSteps` (mirror or no mirror → mount the phone → point the
  telescope → center the crosshair → done). **No sky reference is established in the wizard**:
  pointing falls back to the compass until guiding's own `AutoPlateSolveRefiner` lands its first
  solve, seeded off that compass inside `attemptPlateSolve`'s ±15° search.

**The wizard sets `TelescopeAxis` itself, as each branch's mounting question is answered** — mirror
→ `TOP_EDGE`, no mirror → `BACK_FACE`, sensors-only → `TOP_EDGE` (a clamp along the tube is the only
way that branch is mounted). It is applied at answer time rather than at the wizard's last step,
unlike every other value the wizard collects: the axis is read live by `PointingService` and
`AutoPlateSolveRefiner`, and each `AlignmentPoint` is built from it as it's captured, so a value
saved only at the end would leave every step before it working off the previous setup's geometry.
The Settings entry remains, as the manual override.

The chosen type and the completion time are persisted in `AppPreferences`
(`alignmentType`/`alignmentCompletedAtEpochMillis`), **not** on `AlignmentModel`: a plate-solve
setup replaces its model every few seconds, so a field on the model would be rewritten by the very
mechanism that reads it. The first step reports them back ("Last aligned 2 h ago · Phone sensors").

The step itself lives in `AlignmentSession` alongside the run, since the menu's Settings entry tears
the screen down. `AlignmentStep.previous` is the whole of back navigation — the toolbar's Back
button and `App.kt`'s `BackHandler` both walk it, so the two cannot disagree, and leaving from the
first step exits the screen.

`AlignmentSolver.solve()` fits `sensorToSky: Quaternion` — the rotation from the orientation
sensor's own reference frame to true sky (ENU) — from 2 or 3 star syncs via Davenport's q-method,
chosen over Kabsch/SVD because it cannot return a reflection. There is no from-scratch 1-star
path; `StarAlignmentStep` walks the user through syncs one star at a time (pick a star, point the
telescope at it, confirm), never syncing on the tap that picks the star. The last confirm solves
immediately and shows the RMS with an OK that saves and closes — there is no separate "compute"
step, and the solve is a `remember`-derived value rather than an effect writing state.

That step is a sky map with every stage of the flow overlaid on it — picking, confirming, the
failure notice, the finished-fit card — rather than a `when` that swaps the map out. Picking a star
recenters the viewport on it and drops `SkyMap`'s `onSelect` to null, so the map stays pannable as
an overview while only the pending overlay's own buttons can change or commit the pick.

**`AlignmentSession.mode` (a `GuidingMode`) picks which instrument the run aligns — never both.**
`PHONE` fits the phone's `AlignmentModel` as above and never touches the mount. `TELESCOPE` instead
drives OnStep's own stateful alignment and captures no sensor reading at all: with the phone in the
user's hand rather than on the telescope, a sensor direction taken at confirm time describes
nothing. That sequence is `:A<n>#` to arm (a deliberate "Start" step, because it re-homes the mount,
discards its model and forces tracking on, and the protocol has no cancel), then one `:CM#` per
confirmed star, then `:AW#` to persist. `:CM#` is used rather than `:A+#` because it passes
`sync = true` in OnStep's `alignAddStar`, taking the point from the `:Sr`/`:Sd` target just set
instead of whatever a prior `:MS#` slew left behind — so a confirm without a GOTO in front of it is
still correct. **This branch is currently unreachable**: `mode` is a wizard-local choice and nothing
calls `switchTo(TELESCOPE)` today (there is no app-wide guiding-mode selector any more — see
"Design & UI Conventions" below); a dedicated telescope alignment wizard is planned separately to
pick it, rather than reviving this path through the phone wizard's own Star Sync step.

`:A<n>#` runs `home.reset()`, which declares wherever the mount is standing to be home — start it
off-home and the mount's reported position jumps to home's coordinates (az 0 / alt 0 on an alt-az)
and every later point is built on that fiction. The Start card therefore reads `:GU#`'s `H` flag
(`Lx200Codec.parseAtHome`) and offers `:hC#` "Send home" beside Start, polling that flag while it
is showing so a slew clears the warning on its own. Restart brings the same Start card back rather
than re-arming immediately, so a second attempt goes through the same check — and
`mountAlignmentActive` stays true until a re-arm actually lands, since the protocol has no cancel
and the mount stays armed regardless of what the app does.

A "Controls" toggle in the same toolbar raises `TelescopeControlPad` — a hold-to-move hand
controller (`:Mn#`/`:Ms#`/`:Me#`/`:Mw#`, released with the matching per-axis `:Qn#`…, never the
blanket `:Q#`) with a `MoveRatePreset` selector in the middle of the cross. That rate (`:RG#`…
`:RS#`) is OnStep's *manual-move* rate and has nothing to do with `SlewRatePreset`, the GOTO
speed. The arrows are bordered `Box`es, not Material buttons — a button's own click/indication
pointer input competes with `detectTapGestures` for the down event, and hold-to-move needs the
press, not the click. Release joins the press's coroutine before sending its stop, so a quick tap
cannot land as stop-then-start. Because release is what stops the mount, the pad's `onDispose` fires a blanket stop through
`AppContainer.stopAllTelescopeMotion`, which runs on the container's own scope — a composable being
torn down mid-press cannot stop the mount from its own dying scope.

The run lives in `AlignmentSession`, owned by `GuiderApp`, not remembered inside the screen: the
menu's Settings entry tears it down (`showSettings` is matched ahead of `showAlignment`),
and an armed mount sequence that the app forgot would offer "Start" again — re-homing a mount two
stars into a good run. For the same reason the two modes keep entirely separate progress (star
counts included) and switching between them discards neither — a future caller flipping `mode`
mid-run (a dropped Bluetooth link, say) must not silently wipe the memory of an armed mount along
with it.

**Guiding offers no manual alignment correction at all** — no "Platesolve" button, no yaw
re-sync. A camera setup corrects itself in the background, and a sensors-only one is re-aligned by
running the wizard again from the menu; a one-tap remedy in between was a third path to maintain
that neither case needs. `AlignmentSource.RE_SYNC` survives only because `AlignmentStore` persists
the name verbatim and an unknown value fails the whole model's decode.

**Invariant**: each `AlignmentPoint.skyDirection` is computed *at that point's own capture time*,
never recomputed later at model-solve time — the sky moves ~15"/s, so doing otherwise bakes sky
rotation into the fit. See the doc comment on `AlignmentPoint` before touching this.

### Pointing fusion

`PointingService` combines the continuous relative sensor stream with exactly one
`AbsoluteReference`. Three implementations exist, combined as
`Prioritized(Freshest(auto, alignment), compass)`:

- `AutoPlateSolveRefiner` — live plate solves, running only while `GuidanceScreen` is up under
  `AlignmentType.PLATE_SOLVE`. See below.
- `AlignmentAbsoluteReference` — the stored star-alignment model. Established once per wizard run,
  plus the background refiner's first success (persisted for a warm start).
- `CompassAbsoluteReference` — the magnetometer fallback, so a user with no alignment gets a
  roughly-right arrow instead of a "Not aligned" wall. Self-refreshing on every sensor reading.

**The two fits are ranked by age, the compass by priority.** `FreshestAbsoluteReference` picks the
more recently established of the first two, because neither is categorically better and a fixed
priority gets one of the two directions wrong: a stale background solve would shadow an alignment
the user just finished, and the reverse ordering would let an hours-old star fit shadow a solve from
ten seconds ago. The compass cannot
join that comparison — it re-establishes itself on every sensor reading, so it would always be the
freshest thing there — and stays a strict `PrioritizedAbsoluteReference` fallback.

`AbsoluteReferenceState.origin` is what screens key off to tell a real fit from the compass —
`uncertaintyDegrees` cannot, since a star fit with a poor residual is still a star fit. Compass
mode is the *only* thing that warrants telling the user the whole solution is provisional, and
`GuidanceScreen` shows a "Rough — compass only" banner when it is active and nothing at all
otherwise. `AutoPlateSolveRefiner` reports `STAR_ALIGNMENT` rather than a third origin value: a
plate solve *is* a real 3-DOF fit, and which kind of fit produced a reference is deliberately not a
distinction the UI draws.

### Background plate solving

Under `AlignmentType.PLATE_SOLVE`, `AutoPlateSolveRefiner` photographs the sky and re-solves it
whenever the telescope holds still, re-anchoring the sensor stream without telling the user —
pointing stays smooth off the sensors between solves and silently re-truths itself on each one.

- **Stillness** (`StillnessTracker`, 1° over 2 s) is measured against an *anchor*, not the previous
  reading, so a slow drift can't accumulate into a false "still". It **polls** the sensor rather
  than collecting it: `StateFlow` conflates and drops equal values, so a perfectly still phone can
  stop emitting entirely. Elapsed time is wall-clock — `DeviceOrientation.timestampMillis` carries
  the platform sensor event's own monotonic clock, sharing neither base nor unit with epoch time.
- **The loop runs on the container's scope**, started/stopped by a `DisposableEffect` in
  `GuidanceScreen` (`setAutoPlateSolveActive`) — never a `LaunchedEffect` owning the coroutine, or
  a recomposition could cancel an in-flight camera capture.
- **Nothing is persisted per solve.** Only the first success writes through `saveAlignment`, for a
  warm start next launch; every later one lives in the flow alone. Deactivating keeps the last
  published value — the fit is still true after leaving guidance.
- **Nobody reviews these solves**, so `MAX_ACCEPTED_CORRECTION_DEGREES` (50°) rejects the
  implausible ones — a false `PlateSolver` match geometrically self-consistent enough to pass its
  own inlier check, but against the wrong patch of sky. **This bound is provably insensitive to
  `CameraMounting`, never a signal for a wrong preset there**: `PlateSolveAttempt.correctionDegrees`
  is built with `cameraToDevice.conjugate()` and re-applies `cameraToDevice` against the very same
  capture's orientation reading, so the two cancel out algebraically regardless of which preset is
  active — confirmed both by that derivation and by testing all four `CameraMounting` presets
  against one real photo, which returned the same correction (within noise) every time. What it
  actually measures is a pure vision-vs-IMU comparison, so a large value is just as often an honest
  compass error as a false match: a magnetometer near a telescope's own steel and motors can
  legitimately be off by 20-30°+, especially on the very first solve before anything else has
  corrected it, and 15° (once shared with the search radius) was rejecting exactly those. The bound
  is now sized instead from the search geometry — the search radius (15°) plus roughly half a
  phone's rear-lens field of view (commonly 30-40°) is the real ceiling on how far a *genuine* solve
  can legitimately land from the seed.
- **Cadence is a gap, not a period**: `SOLVE_INTERVAL_MILLIS` starts after the previous solve
  finishes, so a real cycle is that plus the hold, the exposure, opening the camera, and the solve
  itself — expect a refresh every ~10-20 s on a still scope, not every 5. This loop is
  `attemptPlateSolve`'s only caller, and it is serial by construction.
- **Every failure has a reason, not just a null.** `attemptPlateSolve` returns `PlateSolveOutcome`
  (`Success`/`Failure`), never a bare nullable `PlateSolveAttempt?` — `PlateSolver.solve` itself
  returns `PlateSolverOutcome` for the same reason, carrying `PlateSolveDiagnostics` (detection/
  candidate/matched-star counts) on *both* branches so a caller can tell "2 stars detected" from
  "17 detected, 0 matched" from "solved, but the correction was rejected as implausible" — three
  failures that used to look identical (`null`) but point at completely different fixes (exposure,
  camera intrinsics/mounting, and a false match respectively). `AutoPlateSolveRefiner.status`
  (`PlateSolveStatus`: IDLE/SOLVING/SUCCEEDED/FAILED) and `.lastOutcome` exist purely so the
  Guidance app bar's status dot (`PlateSolveStatusIndicator`) has something to show — tapping it
  surfaces the last outcome's own detail text. Neither StateFlow feeds back into `current` or
  `AbsoluteReference`; they're read-only diagnostics. The dot itself is gated in `App.kt` on
  `alignmentType == AlignmentType.PLATE_SOLVE` (passed as `null` otherwise), since the refiner's
  loop never runs under `SENSORS_ONLY` and would otherwise show a permanently uninformative grey.
- **`TelescopeBoresight` (the crosshair-drag calibration step) actually feeds guidance now**, via
  `AppContainer.effectiveTelescopeDirection: StateFlow<Vector3>` — `TelescopeAxis.deviceVector`'s
  coarse top-edge/back-face choice, refined to the exact calibrated pixel converted to a device-
  frame ray (via `CameraIntrinsics.pixelToDirection` + `CameraMounting.cameraToDevice`) once a
  plate-solve setup has captured at least one frame. `PointingService`, `AutoPlateSolveRefiner`'s
  stillness check, and `attemptPlateSolve`'s own correction-degrees check all take this instead of
  `TelescopeAxis` directly, so the live guidance display and the background solver can't disagree
  about which direction is "the telescope." The conversion is computed **locally inside
  `attemptPlateSolve`, not read back through `effectiveTelescopeDirection.value` afterward** —
  writing the backing `MutableStateFlow` doesn't synchronously update a `combine().stateIn()`
  downstream of it, so a read from a different dispatcher (the `withContext(Dispatchers.Default)`
  around the solve itself) could otherwise race and see the *previous* capture's direction. The
  cached value is never cleared once set: real camera intrinsics are a hardware constant for a
  given selected camera, so a stale value from an earlier capture is still correct.
- **`TelescopeAxis` only has two cases**, `TOP_EDGE` and `BACK_FACE` — every other edge/face was
  reachable only through the Settings manual override and never produced by any app flow (the
  wizard only ever sets these two; see "The alignment wizard" above), so they were pure unexercised
  surface. Removed rather than kept "just in case": nothing else in this app mounts a phone
  side-on or screen-first down a tube.

### Guidance math

`GuidanceCalculator` is pure (two ENU unit vectors in, a `Guidance` out) and works entirely in
alt-az terms — never projected onto the phone's screen plane. The arrow angle is
`atan2(crossTrackDelta, altitudeDelta)`: literally the vector sum of the two delta bars shown
next to it. This was a deliberate deviation from an earlier screen-projection design once it
became clear the screen's in-plane axes can coincide with `TelescopeAxis` depending on mounting,
which breaks a screen-projected arrow geometrically. Azimuth is always shown as cross-track
(`Δaz · cos(altitude)`), never the raw azimuth difference — it converges near the zenith.

## Key Conventions

- **`astro/` stays platform-free**: no `java.*`, no Android/iOS imports, so every formula in it
  is a plain JVM unit test. The one exception is `DeviceClock` (`expect`/`actual`), which is
  intentionally isolated in its own file rather than mixed into `AstroTime`.
- **Platform services are plain interfaces, not `expect`/`actual` classes** — `OrientationSensor`
  and `LocationProvider` are common interfaces; `AndroidOrientationSensor`/`AndroidLocationProvider`
  (need a `Context` constructor param) and the iOS stubs implement them directly.
  `MainActivity`/`MainViewController` construct the platform instance and inject it.
- **The app selects the pointing sensor itself, by capability** (`SensorCapabilities.defaultSource()`)
  — this is never a user-facing setting on the main path. A manual override exists only in
  Settings → Advanced, applied once at `AndroidOrientationSensor` construction (takes effect on
  next app restart, not live).
- **Binary catalog format**: `tools/build-catalogs.mjs`'s `BinaryWriter` and
  `astro/io/BinaryReader` + `catalog/CatalogFormat` must stay in lockstep field-for-field, across
  all three blobs (`stars.bin`, `dso.bin`, `constellations.bin`). `NaN` is the "no magnitude"
  sentinel for deep-sky objects (`Float.isNaN()`), not a separate flag byte. `SkyObjectType`'s enum
  order is load-bearing — encoded as a single byte, append-only. Constellation lines are stored as
  raw RA/Dec vertices (not references into `stars.bin`), since their d3-celestial source has no
  concept of a Hipparcos-numbered endpoint.
- **Test ground truth**: astro/ tests assert against independently-citable anchors (JD/GMST at
  J2000, well-known orbital facts like Mercury/Venus max elongation, Polaris altitude ≈
  latitude) or self-consistent invariants (round-trips, synthetic-rotation recovery in
  `AlignmentSolverTest`) — never against a recomputation using the same formula being tested.

## Design & UI Conventions

Stock **Material 3** — prefer default component styling over custom looks.

- **Theme**: `GuiderTheme` supports System / Light / Dark / Night (`AppTheme`). Night is the one
  deliberate exception to theme-driven colors (hardcoded red-on-black, preserves dark adaptation
  at the eyepiece) — everywhere else, colors come from `MaterialTheme.colorScheme`.
- **Action buttons** (Save / Sync / etc.) are wrap-content, centered — `Row(Modifier.fillMaxWidth(),
  horizontalArrangement = Arrangement.Center)`.
- **One bottom-bar shape everywhere** (`AppBottomBar`): the hamburger menu and a divider, both
  constant, then the screen's own context actions, with `ToolbarExitButton` right-aligning itself
  behind its own divider. Actions belonging to the *app* rather than the screen (Align, Night
  wizard, Settings) live in the menu; a toolbar only shows what applies where the user is. The menu
  badges itself when the phone still needs aligning, since burying that state behind a closed menu
  would otherwise lose the at-a-glance cue.
- **Map controls are map controls** — `MapFollowZoomControls` carries follow, zoom in/out and the
  object filter, overlaid on every screen that shows a `SkyMap`. What the map draws is a property
  of the map, not of the screen around it.
- **Search opens over the screen that launched it and returns to it** — `App.kt`'s `when` matches
  `showSearch` ahead of guidance and the wizard, and `onSelectResult` only marks the target and
  closes Search, so the `when` falls through to whatever was underneath.
- **Guiding is phone-only, permanently — there is no app-wide guiding-mode selector.**
  `GuidanceScreen`/`MapScreen` always drive their arrow off `AppContainer.pointingService`
  (the phone's own sensors). A connected mount is a parallel, independent capability instead: its
  own reported position is shown as its own "Telescope" map marker
  (`AppContainer.telescopeSkyDirection`), and `GuidanceScreen`'s "Telescope" toolbar button opens
  `TelescopeSheet` — a connect prompt when nothing is connected, or GOTO/Abort, GOTO speed, and
  tracking controls when something is — never a mode switch that changes what drives guidance.
  `AppBottomBar`'s Telescope menu entry (`SHOW_TELESCOPE_ENTRIES`) is separate again: connecting to
  a mount and running its own alignment is useful on its own even while guiding stays phone-driven.
- **Location is a hard prerequisite** — Search, Guidance, and Alignment all gate on
  `LocationResolver.resolved` being non-null rather than rendering altitudes from a silent default.

## Versions

| Component | Version |
|---|---|
| Kotlin | 2.3.21 |
| Compose Multiplatform | 1.10.3 |
| AGP | 8.11.2 |
| kotlinx-coroutines | 1.10.1 |
| kotlinx-serialization | 1.9.0 |
| multiplatform-settings | 1.2.0 |
| Android minSdk | 24 |
| Android compileSdk | 36 |

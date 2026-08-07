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
# only the .bin output is committed, so the app's own build stays offline-reproducible)
node tools/build-catalogs.mjs
```

## On-Device Debugging (adb)

- **UI element bounds**: `adb shell uiautomator dump //sdcard/ui.xml` then
  `adb pull //sdcard/ui.xml ./ui.xml` (the `//` prefix avoids Git Bash MSYS path-mangling of
  `/sdcard/...`).
- **Screenshot**: `adb exec-out screencap -p > screen.png`.
- **Relaunch app**: `adb shell monkey -p com.astroguider -c android.intent.category.LAUNCHER 1`.

## Architecture

All application code lives in `composeApp/src/`, a single Gradle module with three source sets:

- `commonMain` — all logic and UI; runs on Android and (eventually) iOS
- `androidMain` — thin Android wiring: `MainActivity`, `AndroidOrientationSensor`,
  `AndroidLocationProvider`
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
| `alignment/` | `AlignmentPoint`, `AlignmentModel`, `AlignmentSolver` (yaw-only + Davenport q-method via `JacobiEigenSolver`), `AlignmentStore` |
| `sensors/` | `OrientationSensor` interface, `SensorCapabilities`/`defaultSource()` (auto-selection logic), `FakeOrientationSensor` |
| `location/` | `ObserverLocation`, `LocationProvider` interface, `LocationResolver` (manual-override-wins-over-GPS) |
| `guiding/` | `TelescopeAxis`, `AbsoluteReference`, `PointingService` (sensor + alignment fusion), `GuidanceCalculator`, `CurrentPosition` (target alt/az right now) |
| `settings/` | `AppPreferences` — multiplatform-settings-backed, hand-rolled reactive (`MutableStateFlow` seeded from storage + setter that persists), same pattern as lightnet-mobile's `DemoSettings` |
| `ui/screens/` | `SearchScreen`, `GuidanceScreen`, `AlignmentScreen`, `SettingsScreen` |
| `ui/components/` | `ArrowIndicator`, `DeltaBar` |
| `ui/theme/` | `GuiderTheme`, `AppTheme` (Light/Dark/Night) |

`AppContainer.kt` (top-level `com.astroguider`) owns every long-lived service — sensor, location,
catalog, preferences, alignment — built once by the platform entry point and injected into
`GuiderApp`. Navigation is state-based in `App.kt` (booleans/nullable vars), no nav library —
same convention as lightnet-mobile.

### The alignment model

`AlignmentSolver.solve()` fits `sensorToSky: Quaternion` — the rotation from the orientation
sensor's own reference frame to true sky (ENU) — from 2 or 3 star syncs via Davenport's q-method,
chosen over Kabsch/SVD because it cannot return a reflection. There is no from-scratch 1-star
path; `AlignmentScreen` walks the user through syncs one star at a time (pick a star, point the
telescope at it, confirm), never syncing on the tap that picks the star. `AlignmentSolver.resync()`
is the separate one-tap drift remedy behind the Guidance screen's "Sync on this object": it
corrects only yaw on top of an existing 2-3 star model rather than replacing it, composing the
same yaw-only math onto `existingModel.sensorToSky` instead of solving from scratch -- replacing
the model outright would discard the mounting correction the original 2-3 star fit absorbed.

**Invariant**: each `AlignmentPoint.skyDirection` is computed *at that point's own capture time*,
never recomputed later at model-solve time — the sky moves ~15"/s, so doing otherwise bakes sky
rotation into the fit. See the doc comment on `AlignmentPoint` before touching this.

### Pointing fusion

`PointingService` combines the continuous relative sensor stream with one `AbsoluteReference`
(the alignment model). This is a deliberate seam: a future camera plate-solve would be a second,
self-refreshing `AbsoluteReference` implementation plugged in here, not a rewrite of the pointing
path. `AlignmentPoint.source` similarly exists so a camera-derived sync is a first-class input
later, even though only `MANUAL_SYNC`/`RE_SYNC` exist today.

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
  `MainActivity`/`MainViewController` construct the platform instance and inject it, same as
  lightnet-mobile's `ServiceDiscovery`.
- **The app selects the pointing sensor itself, by capability** (`SensorCapabilities.defaultSource()`)
  — this is never a user-facing setting on the main path. A manual override exists only in
  Settings → Advanced, applied once at `AndroidOrientationSensor` construction (takes effect on
  next app restart, not live).
- **Binary catalog format**: `tools/build-catalogs.mjs`'s `BinaryWriter` and
  `astro/io/BinaryReader` + `catalog/CatalogFormat` must stay in lockstep field-for-field. `NaN`
  is the "no magnitude" sentinel for deep-sky objects (`Float.isNaN()`), not a separate flag byte.
  `SkyObjectType`'s enum order is load-bearing — encoded as a single byte, append-only.
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
  horizontalArrangement = Arrangement.Center)`, same convention as lightnet-mobile.
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

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Breadcrumb is a single-module Android app (Kotlin + Jetpack Compose) that records GPS tracks in the
background, automatically starting/stopping based on the user's detected activity. See `README.md`
for the user-facing overview.

## Build & run

```bash
./gradlew :app:assembleDebug   # build APK
./gradlew :app:installDebug    # build + install on a connected device/emulator
```

The build runs on **JDK 21 automatically**, whatever your system default JDK is: Gradle's daemon JVM
is pinned to Java 21 via `gradle/gradle-daemon-jvm.properties` (auto-provisioned through the foojay
resolver if no JDK 21 is installed). No `JAVA_HOME` override is needed, and a too-new system JDK
won't break the build.

Debug installs as `io.github.valeronm.breadcrumb.debug` (release: `io.github.valeronm.breadcrumb`).
Launch / verify on a connected device:

```bash
adb shell am start -n io.github.valeronm.breadcrumb.debug/io.github.valeronm.breadcrumb.ui.MainActivity
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png   # screenshot to inspect UI
```

Unit tests live in `app/src/test` and cover the pure domain/data logic (ActivityGate,
TrackController, ActivityInterpreter, KeepRule, TrackQuality, GpxExporter) — run them with
`./gradlew :app:testDebugUnitTest`, and note that `assembleDebug` does **not** compile them, so
run the tests after touching anything they cover. There are no instrumented/UI tests; behaviour
above the domain layer is verified by building and driving the app on a device/emulator (activity
recognition needs real movement or an emulator route).

The Gradle and AGP versions are pinned and coupled — if you upgrade one, move the other to a
compatible pair, not one alone.

## Architecture

The app is one foreground service driven by Activity Recognition, with a Compose UI that observes it.
The pieces below only make sense together — read them as a unit.

**Recording pipeline** (`location/`):
- `LocationRecordingService` is the core. It's a started **foreground service** (type `location`)
  that holds the `FusedLocationProviderClient`, owns the current `Track`, and is the single source of
  truth for recording. A `@Volatile companion instance` (plus `activeTrackId`, `isRunning`) lets other
  components talk to the live service **directly within the process** — this deliberately avoids
  Android 12+ background-FGS-start restrictions (we never re-start the service from a broadcast).
- `ActivityRecognitionManager` registers Activity Transition updates (and a one-shot activity
  *snapshot* on arming). Results arrive at `ActivityTransitionReceiver`, which forwards the detected
  `ActivityType` to `LocationRecordingService.instance` (it does not start the service).
- Lifecycle: arming (`ACTION_START`) puts the service in a **paused** state (no track, GPS off) and
  fires the snapshot; recording only begins on a *moving* activity. Each continuous activity is one
  `Track`; switching activity closes one and opens the next; STILL pauses. `START_STICKY` + the
  persisted armed flag resume after process death; `BootReceiver` resumes after reboot.

**State bridge:** `location/TrackingStatus` is a process-wide `MutableStateFlow` the service writes
and the UI collects — this is how live recording state reaches Compose without binding to the service.

**Settings** (`data/Settings`, SharedPreferences): the armed flag plus *global* sampling (min
time/distance between points) and keep-track thresholds (min duration/length). Sampling is read
by the service when each track's GPS request starts; thresholds are read by the repository when a track
finishes. `ActivityType` therefore only carries a label + a `recording` boolean — sampling cadence is
**not** per-activity anymore.

**Data** (`data/`): Room behind `TrackRepository`. The repository's `meetsKeepThresholds` decides
whether a finished track is kept or deleted; this runs
both on normal finish and via `finalizeDangling`, which also cleans up tracks left open by a crash
(it skips `LocationRecordingService.activeTrackId`). `GpxExporter` builds GPX for share intents
(`FileProvider`) or bulk-writes to a user-picked folder (Storage Access Framework).

**UI** (`ui/`): `MainActivity.MainScreen` hosts a bottom-nav (Record / Tracks) Scaffold with a
full-screen **overlay** on top (sealed `Overlay` = `TrackDetail` | `Settings`),
both animated by one shared `PredictiveBackHandler` (Android predictive back — scale/shift previewing
the tabs underneath). The track map is `MapLibreTrackMap` (MapLibre GL Native) on a **Protomaps dark
vector basemap**: the track is a `line-gradient` coloured per point by the selected metric, start/end
and noisy-fix markers sit on a symbol layer, and switching the colour metric recolours in place
without moving the camera. It's edge-aware (declines gestures in the back-gesture edge strips so
edge-swipe-back wins) and lifecycle-bound to the composition.

## Conventions & constraints

- **Activity recognition needs Google Play Services**, so this is intentionally not a FOSS/F-Droid
  build. A continuous foreground service + persistent notification is mandatory for background location
  — there is no "invisible" mode.
- Background location requires the user to grant **"Allow all the time"**, which on Android 11+ is only
  grantable from the app's system settings page (the permission UI deep-links there).
- `applicationId` is permanent once published; the `${applicationId}.fileprovider` authority and
  notification/manifest pieces derive from it, so don't hardcode the package elsewhere.
- All data is local; the only network use is map data — Protomaps vector tiles (hosted API) plus the
  glyphs/sprite from `protomaps.github.io`. There is no server sync (a possible future feature — the
  Settings page is where server URL/key fields would go).
- **The Protomaps hosted-API key is not committed.** It lives in `local.properties` as
  `protomapsApiKey=…` (gitignored), surfaced as `BuildConfig.PROTOMAPS_API_KEY`, and injected into the
  bundled style at load time (`{PROTOMAPS_KEY}` placeholder in `assets/protomaps-dark.json`). A fresh
  checkout needs that line added or the basemap won't load.
- **The dark basemap style is a bundled asset** (`assets/protomaps-dark.json`) — the official
  `protomaps-themes-base` dark flavour with its source repointed at the hosted API. To refresh it,
  re-fetch the flavour JSON and re-point the `protomaps` source, keeping the `{PROTOMAPS_KEY}` placeholder.
- **Frame the map with `moveCamera`, not `easeCamera`** — the track view should open already fitted,
  with no zoom-in animation. Framing runs once per map instance (guarded by a `BooleanArray`) so
  switching the colour metric recolours without re-centring; the live preview refreshes the source
  geometry on point-list growth but re-frames only when the current position leaves the central 80%
  of the viewport, so a user pan/zoom survives. See `MapLibreTrackMap`.

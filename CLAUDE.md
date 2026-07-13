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

Unit tests live in `app/src/test` and cover the pure logic in `domain/` plus data-layer pieces
(TrackQuality, GpxExporter/GpxParser) — run them with
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
  fires the snapshot; recording only begins on a *moving* activity. Each continuous stretch of
  movement is one `Track`: activities in the same `TrackGroup` (walking ⇄ running)
  share a track, a cross-group switch closes one and opens the next, and STILL pauses with a
  **resume window** — a same-group return before the deadline stitches back into the open track,
  and the window lapsing finalizes it. `START_STICKY` + the persisted armed flag resume after
  process death; `BootReceiver` resumes after reboot.

**State bridge:** `location/TrackingStatus` is a process-wide `MutableStateFlow` the service writes
and the UI collects — this is how live recording state reaches Compose without binding to the service.

**Domain logic** (`domain/`): pure, unit-tested Kotlin with no Android dependencies — the service
and UI stay thin by delegating here. `TrackController` (track lifecycle state machine — owns the
pause/resume window), `ActivityGate` (signal filter) / `ActivityInterpreter` (transition
interpretation), `ReadingClock` (event-time gating of activity readings), `NoFixGuard` (give up when
GPS can't get a fix), `KeepRule`, `TrackMerge` (merge short same-activity stays), `StayDeriver` +
`PlaceClusterer` + `PlaceResolver` (timeline stays and named places), `DwellDetector` (in-track stop
detection — currently a read-only track-detail overlay; splitting tracks at stops is designed but
not built), `RecordCard`. New behaviour
belongs here first, with a test, before wiring into the service or UI.

**Settings** (`data/Settings`, SharedPreferences): the armed flag plus *global* sampling (min
time/distance between points), point-quality gates (accuracy gate, require-GNSS cross-check), the
auto-pause resume window, the GPS give-up timeout, the fused-provider toggle, and keep-track
thresholds (min duration/length/extent). Sampling is read by the service when each track's GPS
request starts; thresholds are read by the repository when a track finishes. `ActivityType`
therefore only carries a label, a `recording` boolean, and a `TrackGroup` — sampling cadence is
**not** per-activity anymore.

**Data** (`data/`): Room behind `TrackRepository`. The repository's `meetsKeepThresholds` decides
whether a finished track is kept or soft-deleted as *discarded* — discarded (and user-deleted)
tracks are reviewable and restorable from Settings → Recently deleted, auto-purged after 14 days,
and deliberately block GPX re-import; the check runs
both on normal finish and via `finalizeDangling`, which also cleans up tracks left open by a crash
(it skips `LocationRecordingService.activeTrackId`). `GpxExporter` (`data/export/`) builds GPX for
share intents (`FileProvider`) or bulk-writes to a user-picked folder (Storage Access Framework);
`GpxParser` imports GPX files shared/opened into the app. `PlaceRepository` backs the Places tab.

**Backfills** (one-time Kotlin data migrations): when a new rule needs to reprocess *existing*
rows and a Room SQL migration can't express the logic, add a repository pass and run it from
`App.onCreate`'s IO coroutine behind a `Settings` done-flag:
`if (!Settings.isXDone(...)) { repository.x(); Settings.setXDone(...) }`. It runs there (not in
any screen) because the background service can keep the process alive for weeks without the UI
opening. Make the pass idempotent — a crash between the work and the flag write means it re-runs.
Delete the pass, its flag, and any DAO queries only it used once the installed base has run it;
the pre-DB-v5 ignore-reason backfill and the drive-start leading-stray repair followed this
pattern and were dropped 2026-07-13 (see git history for a template).

**UI** (`ui/`): `MainActivity.MainScreen` hosts a bottom-nav (Record / Timeline / Places) Scaffold
with full-screen **overlay** layers on top: sealed `Overlay` (`TrackDetail` | `Settings`) plus
stacked layers for place detail, the Settings sub-pages (sampling, point quality, auto-pause, GPS
search, track filtering, Recently deleted, Logs), and discarded-track detail — each
animated by a `PredictiveBackHandler` (scale/shift previewing the layer underneath, back returning
one layer at a time). The track map is `MapLibreTrackMap` (MapLibre GL Native) on a **Protomaps dark
vector basemap**: the track is a `line-gradient` coloured per point by the selected metric, start/end
and noisy-fix markers sit on a symbol layer, and switching the colour metric recolours in place
without moving the camera. The map renders in texture mode (a SurfaceView would ignore Compose
clipping and bleed over rounded card corners), sits inside padded cards (so it never reaches the
back-gesture edge strips), and is lifecycle-bound to the composition.

## Releases

When preparing a Play release (version bump, building the bundle, or writing the "What's new"
text), follow `docs/release-notes-guide.md` — it defines the audience rules for release notes,
how to derive them from commits since the last *uploaded* build, and the versioning scheme
(git-derived `versionName`, manual `versionCode`, never upload a `-dirty` build).

Every build uploaded to Play is marked with a lightweight tag `v1.0-vc<N>` (N = versionCode) on
the commit it was built from — so "commits since the last uploaded build" is just
`git log v1.0-vc<N>..`. GitHub Actions automates the pipeline (`.github/workflows/`):
`tests.yml` runs the unit tests on every push/PR; `release.yml` fires on pushing a `v1.0-vc<N>`
tag — it fails unless N matches `versionCode` in `app/build.gradle.kts`, builds the signed
bundle (upload keystore + Protomaps key come from repo secrets), and attaches the `.aab` to a
GitHub Release. Release flow: commit the `versionCode` bump → tag it `v1.0-vc<N>` → push the
tag → download the `.aab` from the GitHub Release and upload it to the Play Console manually.
`versionCode`'s source of truth is `app/build.gradle.kts`; the tag only cross-checks it.

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

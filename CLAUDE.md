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
Launch / verify on an emulator (on a physical phone these are the user's to run — see "Testing on
the device"):

```bash
adb shell am start -n io.github.valeronm.breadcrumb.debug/io.github.valeronm.breadcrumb.ui.MainActivity
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png   # screenshot to inspect UI
```

The Gradle and AGP versions are pinned and coupled — if you upgrade one, move the other to a
compatible pair, not one alone.

Code style is enforced by **ktlint** (`./gradlew :app:ktlintCheck`, auto-fix with
`:app:ktlintFormat`; CI runs the check). The config lives in `.editorconfig`: `intellij_idea`
code style to match the hand formatting, line length unenforced, and a few layout-preference
rules disabled deliberately (argument wrapping, one-line signatures, UPPER_CASE fixture vals,
column-aligned fixture comments). The disables are choices, not oversights — re-enabling one
is a style decision to raise with the user, not a cleanup.

Code smells are checked by **detekt** (`./gradlew :app:detekt`; CI runs it): default rules
minus the style-preference ones (`config/detekt/detekt.yml` — magic numbers, size thresholds
and composable naming/params are off, for the same reasons as the ktlint disables), with
`app/detekt-baseline.xml` grandfathering the findings that predate adoption — only new
findings fail. After fixing a baselined finding, regenerate with `:app:detektBaseline`;
a refactor that moves baselined code can resurface its entry as new, which is intended.

## Unit tests

Unit tests live in `app/src/test` and cover the pure logic in `domain/` plus data-layer pieces
(TrackQuality, TrackStats, GpxExporter/GpxParser, BackupExporter/BackupImporter) — run them with
`./gradlew :app:testDebugUnitTest`, and note that `assembleDebug` does **not** compile them, so
run the tests after touching anything they cover. **Room runs in these host tests via Robolectric**
(in-memory DB, `TestDb` fixture), so the repository's DB rules and the schema migrations are
covered without a device — see `TrackRepositoryTest`, `Migration10To11Test`, and
`TimelineInvalidationTest`. Robolectric emulates up to SDK 36 while the app targets 37, so its
tests are pinned in `app/src/test/resources/robolectric.properties`; raise it when Robolectric
catches up. **Robolectric's native runtime doesn't support Linux aarch64**, so on an arm64 dev box
every Room-backed test fails with an architecture assertion, whatever the change — that's the
environment, not a regression, and CI is where those tests actually run.

A domain rule must be tested through the params that **ship**. `EdgeStayDetectorTest` runs
`EdgeStayDetector.BRIEF_STOP` (and `VEHICLE` where the activity floor is the point) rather than the
`Params()` constructor defaults, which no production path uses: a suite pinning the defaults passes
green through any change to the numbers the recorder actually runs. There are still no instrumented/UI tests: behavior above the data layer is verified by
building and driving the app on a device/emulator (activity recognition needs real movement or an
emulator route).

## Testing on the device

Hands-on testing is the human's job, not Claude's. The workflow for a change that needs device
verification:

1. Verify what you can without the device: build it, run the unit tests.
2. Install the build on the connected phone (`./gradlew :app:installDebug`).
3. Hand off with a short test plan: where to navigate, what the change should look like, and what
   would indicate a regression — pointing at concrete tracks/places found in the device data beats
   generic instructions. Mining the data for such cases is encouraged:
   read-only adb — logcat, pulling a copy of the app's DB (`adb exec-out run-as
   io.github.valeronm.breadcrumb.debug cat databases/tracks.db`) — is fine. Screenshots are not:
   `screencap` grabs whatever is currently on the phone's screen, which can expose personal info
   from other apps — take one only when the user asks for it.

Don't launch or drive the app on a physical phone yourself (`am start`, `input tap`/swipe): you
have no way of knowing whether the user is using the phone at that moment or what for — injected
launches and taps land on top of whatever that is and can break it. On an emulator, driving the
app is fair game.

## Architecture

The app is one foreground service driven by Activity Recognition, with a Compose UI that observes it.
The pieces below only make sense together — read them as a unit.

**Recording pipeline** (`location/`):
- `LocationRecordingService` is the core. It's a started **foreground service** (type `location`)
  that requests raw platform GPS, owns the current `Track`, and is the single source of
  truth for recording. A `@Volatile companion instance` (plus `activeTrackId`, `isRunning`) lets other
  components talk to the live service **directly within the process** — this deliberately avoids
  Android 12+ background-FGS-start restrictions. A broadcast hands work to the live instance; it
  never starts one, with a single exception: the watchdog's self-heal below, which is legal only
  because an alarm carries a temporary power-allowlist window.
- `ActivityRecognitionManager` registers Activity Transition updates (and a one-shot activity
  *snapshot* on arming). Results arrive at `ActivityTransitionReceiver`, which forwards the detected
  `ActivityType` to `LocationRecordingService.instance` (it does not start the service).
- `WatchdogReceiver` fires on an alarm every 15 min while armed, and does four things the coroutine
  timers can't be trusted with in Doze: re-*requests* the transition registration (see the deafness
  bullet under Conventions — a request, never a restart), stamps a heartbeat, closes a pause whose
  resume window lapsed while the wake was frozen, and restarts the service if the armed flag is set
  but the service is dead.
- **A receiver holds its broadcast open (`goAsync`) until the service has applied the reading.**
  Returning from `onReceive` releases the broadcast's wakelock, and Doze then freezes the apply
  coroutine: the reading is logged on time but applied minutes later, which puts a walking tail on a
  drive track and stitches through a real stop. Both receivers do this; don't "simplify" it away.
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
detection — a read-only track-detail overlay, *and* stage 1 of the edge-stay rule below, so its
params are not free to move; splitting tracks at stops is designed but
not built), `EdgeStayDetector` (the recorder's overrun at a track's edges, where Activity
Recognition lagged the real stop) + `EdgeStayIgnore` (what that verdict does to the points),
`RecordCard`, `StaleReadingOracle` (spot a registration that has gone deaf) +
`DeafnessWarning` (decide when to tell the user about it). New behavior
belongs here first, with a test, before wiring into the service or UI. The shared vocabulary lives
here too: `ActivityType`/`TrackGroup`, `IgnoreReason`, and the `DistanceFn` seam (production
implementation `data/AndroidDistance`; the GMS `DetectedActivity` mapping is
`location/DetectedActivities`). One deliberate impurity: domain functions take the Room entities
(`TrackPoint`, `TrackSummary`, `Place`) directly rather than a mapped domain model — the point walk
runs over millions of rows, and a per-row mapping allocation buys nothing but layering purity. The
`db` package must not import `domain` back (that would make the two one unit); entities carry no
domain defaults for the same reason.

**Settings** (`data/Settings`, SharedPreferences): the armed flag plus *global* sampling (min
time/distance between points), point-quality gates (accuracy gate, require-GNSS cross-check), the
auto-pause resume window, the GPS give-up timeout, and keep-track
thresholds (min duration/length/extent). It also holds two pieces of recorder bookkeeping that
aren't user settings at all: the liveness heartbeat timestamp, and the `EdgeStayDetector` rule
version last swept — the latter is what makes `App.onCreate` re-derive the whole history.
Sampling is read by the service when each track's GPS
request starts; thresholds are read by the repository when a track finishes. `ActivityType`
therefore only carries a label, a `recording` boolean, and a `TrackGroup` — sampling cadence is
**not** per-activity anymore.

**Data** (`data/`): Room behind `TrackRepository`. The repository's `keepVerdict` (rule in
`KeepRule`) decides whether a finished track is kept, soft-deleted as *discarded*, or — with 2 or
fewer points in total, good and ignored counted together, so truly nothing to review — hard-deleted
outright. Discarded (and user-deleted)
tracks are reviewable and restorable from Settings → Recently deleted, auto-purged after 14 days,
and deliberately block GPX re-import; the check runs
both on normal finish and via `finalizeDangling`, which also cleans up tracks left open by a crash
(it skips `LocationRecordingService.activeTrackId`). `GpxExporter` (`data/export/`) builds GPX for
share intents (`FileProvider`) or bulk-writes to a user-picked folder (Storage Access Framework);
`GpxParser` imports GPX files shared/opened into the app. `BackupExporter`/`BackupImporter`
(`data/export/`) are the full backup: one gzipped JSON file with every kept track's points
(ignored ones and quality metadata included), places and liveness events — written from Settings,
streamed both ways (one track's points in memory at a time), point rows as arrays keyed by a
`pointFields` header so future exports stay restorable. Restore is offered only on the Timeline's
empty state, deliberately: with existing tracks it would have to merge. The format also feeds the
planned web companion viewer. `PlaceRepository` backs the Places tab.

**An ignored point is one that isn't part of the path — for either of two reasons.** The recorder's
bad-fix rule (`TrackQuality`: accuracy, jump, no-GNSS) rejects fixes it doesn't trust; `EdgeStayIgnore`
flags the good fixes recorded past the stop at a track's edges (`IgnoreReason.EDGE_STAY`), applied
automatically when a track is finished, imported, merged or restored, and the track's `startedAt`/
`endedAt` pulled in to the boundary fix with it. Both drop out of distance, endpoints, the drawn
line and GPX export while keeping their rows, so the operation destroys nothing and is undone by
clearing a flag. Two invariants hold it together: detection runs on the points with the edge flags
*cleared* (never on its own output, or the track walks backwards one sweep at a time), and only
flags outside the first/last good fix may be withdrawn (a merge puts the earlier track's overrun
mid-track, where no edge rule will re-derive it). `TrackRepository.sweepEdgeStays` re-derives the
whole history whenever `EdgeStayDetector.RULE_VERSION` outruns the version last swept — standing
infrastructure, not one of the one-shot backfills below, so bumping that version is part of
changing the rule.

**The track row carries its points' aggregates, and the recorder must never write it.** Distance,
point/ignored counts and the first/last good coordinates live as columns on `tracks`, written only
by `TrackRepository.refreshStats` (from `TrackStats`, the one point walk — the recorder accumulates
through the same code, so live and stored totals can't drift) when a track is *finished, merged,
imported or repaired*. This is a performance invariant, not a convenience: Room invalidates per
table, so an observed query that reads `track_points` — or a per-fix write to `tracks` — is re-run
on **every GPS fix**, scanning the whole point history once a second for a result that can't have
changed (open tracks have no `endedAt` and are in none of these queries). The observed queries must
therefore read `tracks` only, and the hot path writes nothing but the point rows; an open track's
aggregates are meaningless by design, and finishing it (including `finalizeDangling` after a crash)
recomputes them. `TimelineInvalidationTest` fails if either half is broken. The UI collects with
`collectAsStateWithLifecycle` for the same reason — a backgrounded Compose tree keeps collecting
otherwise, and the process outlives the UI by weeks.

**Backfills** (one-time Kotlin data migrations): when a new rule needs to reprocess *existing*
rows and a Room SQL migration can't express the logic, add a repository pass and run it from
`App.onCreate`'s IO coroutine behind a `Settings` done-flag:
`if (!Settings.isXDone(...)) { repository.x(); Settings.setXDone(...) }`. It runs there (not in
any screen) because the background service can keep the process alive for weeks without the UI
opening. Make the pass idempotent — a crash between the work and the flag write means it re-runs.
Delete the pass, its flag, and any DAO queries only it used once the installed base has run it;
the pre-DB-v5 ignore-reason backfill, the drive-start leading-stray repair (both dropped
2026-07-13) and the point-starved-track purge (dropped 2026-07-17) followed this pattern —
see git history for a template. **No backfill is live right now**: `App.onCreate` runs the
discarded-track retention purge and the versioned edge-stay sweep, neither of which is one.

**UI** (`ui/`): `MainActivity.MainScreen` hosts a bottom-nav (Record / Timeline / Places) Scaffold
with full-screen **overlay** layers on top: sealed `Overlay` (`TrackDetail` | `Settings`) plus
stacked layers for place detail, the Settings sub-pages (sampling, point quality, auto-pause, GPS
search, track filtering, Recently deleted, Logs), and discarded-track detail — each
animated by a `PredictiveBackHandler` (scale/shift previewing the layer underneath, back returning
one layer at a time). The Compose code is split one file per screen, all in the `ui` package:
`MainActivity.kt` keeps only the activity, navigation and overlay machinery; the screens live in
`RecordScreen`/`TimelineScreen`/`PlacesScreens`/`TrackDetailScreen`/`SettingsScreens`/
`DiscardedScreens`, with shared widgets and formatters in `Components.kt` and the color-ramp/
legend code in `TrackColoring.kt` (cross-file symbols are `internal`, not `private`). The track
map is `MapLibreTrackMap` (MapLibre GL Native) on a **Protomaps
vector basemap** (dark or light flavor following the app theme): the track is a `line-gradient`
colored per point by the selected metric (ramp luminance also theme-dependent), start/end
and noisy-fix markers sit on a symbol layer, and switching the color metric recolors in place
without moving the camera. Two more layers ride on the same map — the detected in-track stops as
place-style capture circles *under* the line, and the recorder's overrun grayed off the track's
ends, read back from the stored flags rather than re-detected. The map renders in texture mode (a SurfaceView would ignore Compose
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
`tests.yml` runs ktlint, detekt, Android Lint and the unit tests on every push/PR; `release.yml` fires on pushing a `v1.0-vc<N>`
tag — it fails unless N matches `versionCode` in `app/build.gradle.kts`, builds the signed
bundle (upload keystore + Protomaps key come from repo secrets), and attaches the `.aab` to a
GitHub Release. Release flow: commit the `versionCode` bump → tag it `v1.0-vc<N>` → push the
tag → append the "What's new" text (written per `docs/release-notes-guide.md`) to the GitHub
Release body via `gh release edit`, under the generated provenance line → download the `.aab`
from the GitHub Release and upload it to the Play Console manually, reusing the same "What's
new" text there. `versionCode`'s source of truth is `app/build.gradle.kts`; the tag only
cross-checks it.

## Conventions & constraints

- **Never put the author's own trip data in the repo.** Most of the tuning here rests on field
  evidence, and citing it is right — but comments, KDoc, commit messages and `docs/` must carry
  what the data *showed*, never which trip showed it: no recording dates or times, no place names
  or real coordinates, no per-trip distances, durations, point counts or quality figures. "A parked
  phone can report phantom Doppler up to 3.5 m/s" — not "a 2026-07-04 arrival, 7 m from the track's
  final position". History-wide aggregates are fine while they name no date or place ("an end stay
  on ~30% of tracks, median ~72 s"), and so are generated test fixtures, whose coordinates are
  invented. The repository is the one artifact that leaves the machine; the recorded history stays
  on it. Fixtures sit at a **neutral origin** and should stay there: the domain tests build meter
  offsets off latitude 1.0, the data-layer ones off latitude 1.0 / longitude −2.0. Real coordinates
  in a fixture leak a region even when no trip is named.
- **Activity recognition needs Google Play Services**, so this is intentionally not a FOSS/F-Droid
  build. A continuous foreground service + persistent notification is mandatory for background location
  — there is no "invisible" mode.
- **A GMS transition registration can go silently deaf** — it keeps reporting success, answering
  snapshots and replaying on re-registration while never delivering live. Re-registration has been
  field-disproven as a cure (a registration on a request code GMS had never seen came up dead while a
  second install recovered on a reused one); the state sits in Play Services and only a device reboot
  cleared it. So the app **detects and reports rather than repairs**: `StaleReadingOracle` spots it,
  `DeafnessWarning` decides when to say so, and the user is told to reboot. Don't build anything that
  assumes restarting the registration fixes this — that ground has been covered. This is *not* a rule
  against re-registering: the watchdog re-*requests* updates every tick, and the replay that provokes
  is exactly what feeds the oracle. The distinction is load-bearing — a request refreshes a healthy
  registration in place, while `restart()` tears it down and rebuilds it on a fresh token, and only
  arming and a proven-deaf verdict do that.
- The `alerts` notification channel is the second channel, separate from the ongoing tracking one:
  transient, `IMPORTANCE_DEFAULT`, used only for the deafness warning (id 1002). The "persistent
  notification" rules above are about the foreground service's channel, not this one.
- Background location requires the user to grant **"Allow all the time"**, which on Android 11+ is only
  grantable from the app's system settings page (the permission UI deep-links there).
- `applicationId` is permanent once published; the `${applicationId}.fileprovider` authority and
  notification/manifest pieces derive from it, so don't hardcode the package elsewhere.
- All data is local; the only network use is map data — Protomaps vector tiles (hosted API) plus the
  glyphs/sprite from `protomaps.github.io`. There is no server sync (a possible future feature — the
  Settings page is where server URL/key fields would go).
- **The Protomaps hosted-API key is not committed.** It lives in `local.properties` as
  `protomapsApiKey=…` (gitignored), surfaced as `BuildConfig.PROTOMAPS_API_KEY`, and injected into the
  bundled style at load time (`{PROTOMAPS_KEY}` placeholder in `assets/protomaps-{dark,light}.json`).
  A fresh checkout needs that line added or the basemap won't load.
- **The basemap styles are bundled assets** (`assets/protomaps-dark.json` / `protomaps-light.json`,
  picked by theme) — the official flavors as served by the hosted API's style endpoint
  (`https://api.protomaps.com/styles/v5/{dark,light}/en.json?key=…`), verbatim except the API key
  in the tiles URL replaced with the `{PROTOMAPS_KEY}` placeholder. To refresh, re-fetch from that
  endpoint and re-apply the placeholder swap.
- **Frame the map with `moveCamera`, not `easeCamera`** — the track view should open already fitted,
  with no zoom-in animation. Framing runs once per map instance (guarded by a `BooleanArray`) so
  switching the color metric recolors without re-centring; the live preview refreshes the source
  geometry on point-list growth but re-frames only when the current position leaves the central 80%
  of the viewport, so a user pan/zoom survives. See `MapLibreTrackMap`.

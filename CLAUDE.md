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
`:app:ktlintFormat`) and code smells by **detekt** (`./gradlew :app:detekt`); CI runs both. Each
config says which rules it turns off and why — `.editorconfig` and `config/detekt/detekt.yml`. What
neither can say: **the disables are choices, not oversights**, so re-enabling one is a style
decision to raise with the user, not a cleanup. After fixing a finding held by
`app/detekt-baseline.xml`, regenerate with `:app:detektBaseline`; a refactor that moves baselined
code can resurface its entry as new, which is intended.

## Unit tests

Unit tests live in `app/src/test` and cover the pure logic in `domain/` plus data-layer pieces
(TrackQuality, TrackStats, GpxExporter/GpxParser, BackupExporter/BackupImporter) and the view
model's flow gating (`PlaceDerivationGateTest`, which drives the real `pinnedRows` rather than a
copy of it, and needs no database — so unlike the Room-backed tests below it runs on any dev box) — run them with
`./gradlew :app:testDebugUnitTest`, and note that `assembleDebug` does **not** compile them, so
run the tests after touching anything they cover. **Room runs in these host tests via Robolectric**
(in-memory DB, `TestDb` fixture), so the repository's DB rules and the schema migrations are
covered without a device — see `TrackRepositoryTest`, `Migration10To11Test`, and
`TimelineInvalidationTest`. Robolectric emulates up to SDK 36 while the app targets 37, so its
tests are pinned in `app/src/test/resources/robolectric.properties`; raise it when Robolectric
catches up. **Robolectric's native runtime ships no Linux aarch64 build**, so on an arm64 dev box
every Room-backed test fails with an architecture assertion, whatever the change — that's the
environment, not a regression. Run them with `-PqemuJdk`, which forks the test worker into an
x86_64 JVM under qemu (see "Running the Room tests on arm64"); without it, CI is where they run.

A domain rule must be tested through the params that **ship**. `EdgeStayDetectorTest` runs
`EdgeStayDetector.BRIEF_STOP` (and `VEHICLE` where the activity floor is the point) rather than the
`Params()` constructor defaults, which no production path uses: a suite pinning the defaults passes
green through any change to the numbers the recorder actually runs.

The recorder's own two loops are covered off the device by `FixIngestTest` and `ActivityIngestTest`
(both in `src/test/…/location/`, both plain JVM — the cores carry no Android). The activity one
asserts on the returned `Effect` list, so a case reads as the sequence the recorder should perform:
what a stop schedules, whether a return stitches or splits, that a late-drained reading is timed by
its own event but stamps its tracks at the wall clock, and that the deafness restart stays inside its
five-minute floor while the detection behind it does not. There are no instrumented/UI tests:
behavior above these two cores is verified by building and driving the app on a device/emulator
(arming and the notification wording need only the app running; activity recognition needs real
movement or an emulator route).

### Running the Room tests on arm64

`nativeruntime-dist-compat` carries `native/linux/x86_64/`, `native/mac/{aarch64,x86_64}/` and
`native/windows/x86_64/` — no Linux arm64, and none of the published versions has one, so waiting
for a Robolectric bump is not the fix. Once per machine:

```bash
sudo apt install qemu-user libc6-amd64-cross libstdc++6-amd64-cross libgcc-s1-amd64-cross
./gradlew :app:provisionQemuTestJdk      # ~200 MB, pinned version + SHA-256
./gradlew :app:testDebugUnitTest -PqemuJdk
```

`provisionQemuTestJdk` downloads a pinned Temurin build into `$GRADLE_USER_HOME/jdks/`, so nothing
is tied to one machine's layout, and rewrites its `bin/java` as a wrapper exec'ing
`qemu-x86_64 -L <sysroot> bin/java.real`. Overrides: `-PqemuBin`, `-PqemuSysroot`, and
`-PqemuJdk=/path` for an entirely separate JVM. The build file carries the constraints that shaped
it — why the `-L` prefix is baked into the wrapper, why the wrapper replaces `bin/java` in place,
why provisioning can't be a task dependency, and why none of it is a Gradle toolchain — beside the
code each one explains.

Iterating on one Room test wants a filter, since the flag applies to the whole task:
`-PqemuJdk --tests "*TrackRepositoryTest"`. The tax lands only where the work is real — the pure
tests cost a second or two more, while the Robolectric classes — the only ones that need the
emulation at all — account for essentially all of the added minute. Emulation is not a second
opinion on CI, though: it runs the same x86_64 code CI runs, so a
green local run means the tests pass, not that they'd pass on some third platform. It buys no
coverage either — JaCoCo doesn't see through Robolectric's sandbox classloader, so those classes
read 0% however they exit.

## Testing on the device

Hands-on testing is the human's job, not Claude's. The workflow for a change that needs device
verification:

1. Verify what you can without the device: build it, run the unit tests.
2. Install the build on the connected phone (`./gradlew :app:installDebug`).
3. Hand off with a short test plan: where to navigate, what the change should look like, and what
   would indicate a regression — pointing at concrete tracks/places found in the device data beats
   generic instructions. Mining the data for such cases is encouraged:
   read-only adb — logcat, pulling a copy of the app's DB — is fine. **Pull all three files**, not
   just `tracks.db`: Room runs in WAL mode, so a recent commit can still be sitting in
   `tracks.db-wal` and a lone main file reads as though it never happened — which bites exactly when
   investigating something that just occurred.

   ```bash
   for f in tracks.db tracks.db-wal tracks.db-shm; do
     adb exec-out run-as io.github.valeronm.breadcrumb.debug cat databases/$f > $f
   done
   ```

   Open `tracks.db` from the directory holding all three and SQLite replays the log on open. A track
   still missing its `endedAt` after that is genuinely dangling (a process death mid-recording, which
   an install causes), not merely uncheckpointed. Screenshots are a different matter:
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
- **The two paths that decide anything have no Android in them, and the service performs what they
  decide.** `FixIngest` is the fix path; `ActivityIngest` is the activity path (readings in, a list
  of `Effect`s out), and the no-fix give-up guard's consultations run there too (`onGnssTick` /
  `onResumeSignal`). Between them they own everything that persists across a track, while the
  service keeps the platform resources and the open track's id. The rules each have their own
  suite; these two are where the *loops that sequence them* live, so a fixture can drive them with
  no phone. `NoFixGuard` is shared by both paths and the service. The invariants binding core to
  dispatcher — every effect run and run in order, `EnsureGps` asking for a state rather than
  commanding a transition, `ArmResumeSignals` not folded into `StopGps` — are on `Effect` and its
  members.
- **The service's platform surface is split by concern**, so each piece fits on a screen and the
  service reads as wiring: `RecorderNotifications` (the shade), `WatchdogAlarm` (the 15-minute
  wake), `ResumeSignals`, `GnssWatch`. These buy readability, not testability: they wrap final
  platform classes and are host-untestable. `startLocationUpdates` and `stopLocationUpdates`
  deliberately stay in the service; each says why.
- `ActivityRecognitionManager` registers Activity Transition updates (and a one-shot activity
  *snapshot* on arming). Results arrive at `ActivityTransitionReceiver`, which forwards the detected
  `ActivityType` to `LocationRecordingService.instance` (it does not start the service).
- `WatchdogReceiver` fires on an alarm every 15 min while armed, and does five things the coroutine
  timers can't be trusted with in Doze: re-*requests* the transition registration (a request, never
  a restart), stamps a heartbeat, closes a pause whose resume window lapsed while the wake was
  frozen, revisits a held reading, and restarts the service if the armed flag is set but the
  service is dead.
- **Both receivers hold their broadcast open (`goAsync`) until the service has applied the
  reading** — each receiver's KDoc says what breaks otherwise. Don't "simplify" it away.
- Lifecycle: arming (`ACTION_START`) puts the service in a **paused** state (no track, GPS off) and
  fires the snapshot; recording only begins on a *moving* activity. Each continuous stretch of
  movement is one `Track`; how a reading continues, splits, pauses or finalizes it is
  `TrackController`'s state machine. `START_STICKY` + the persisted armed flag resume after process
  death; `BootReceiver` resumes after reboot and app update.

**Activity Recognition describes the user's body, not the journey**, and pause, split and the jump
ceiling all read it as the journey. `MovementConfirmer` is the recorder's second witness. **`Unknown`
is both the fallback and the off switch** — every consultation defines an `Unknown` case identical to
the pre-witness behaviour, so the setting branches at exactly one place
(`ActivityIngest.motionVerdict`); a consultation that can't be expressed as a
`Motion.Unknown`-defaulted parameter is a design smell, not a workaround. Each consulting rule's
suite keeps its pre-witness half unedited above a divider, which is what pins the off state.
**Exactly four consultations exist**, and that there are four and only four is the thing no one file
says: the gate parks a contradicted STILL, the jump ceiling rises to fit measured ground speed, a
`Moving` verdict vetoes the no-fix give-up, and every path that turns GPS off re-evaluates the parked
slot on the way down. Promotion rides the `GnssStatus` callback, with the 15-minute watchdog alarm as
the guaranteed revisit. Default off pending field data.

**State bridge:** `location/TrackingStatus` is a process-wide `MutableStateFlow` the service writes
and the UI collects — this is how live recording state reaches Compose without binding to the service.

**Domain logic** (`domain/`): pure, unit-tested Kotlin with no Android dependencies — the service
and UI stay thin by delegating here. `TrackController` (track lifecycle state machine — owns the
pause/resume window), `ActivityGate` (signal filter) / `ActivityInterpreter` (transition
interpretation), `ReadingClock` (event-time gating of activity readings), `NoFixGuard` (give up when
GPS can't get a fix), `KeepRule`, `TrackMerge` (merge short same-activity stays), `StayDeriver` +
`PlaceClusterer` + `PlaceResolver` (timeline stays and named places), `DwellDetector` (in-track stop
detection — a read-only track-detail overlay, and stage 1 of the edge-stay rule below, which passes
its own tuning; splitting tracks at a *detected* stop is designed but not
built — the user's own cut ships), `EdgeStayDetector` (the recorder's overrun at a track's edges, where Activity
Recognition lagged the real stop) + `EdgeStayIgnore` (what that verdict does to the points),
`RecordCard`, `StaleReadingOracle` (spot a registration that has gone deaf) +
`DeafnessWarning` (decide when to tell the user about it), `MovementConfirmer` (the recording
pipeline's second witness — see below), `PlaceCategorySuggester` (guess a place's category from
what the user called it — see below), `RoutePlaces` (the named places at a track's two ends). New
behavior belongs here first, with a test, before wiring into the service or UI. The shared
vocabulary lives here too: `ActivityType`/`TrackGroup`, `IgnoreReason`, `PlaceCategory`, and the
`DistanceFn` seam (the GMS `DetectedActivity` mapping is `location/DetectedActivities`). One deliberate impurity: domain functions take the Room entities
(`TrackPoint`, `TrackSummary`, `Place`) directly rather than a mapped domain model — the point walk
runs over millions of rows, and a per-row mapping allocation buys nothing but layering purity. The
`db` package must not import `domain` back (that would make the two one unit); entities carry no
domain defaults for the same reason.

**Settings** (`data/Settings`, SharedPreferences): the armed flag plus *global* sampling (min
time/distance between points), point-quality gates (accuracy gate, require-GNSS cross-check), the
auto-pause resume window and its motion cross-check toggle, the GPS give-up timeout, and keep-track
thresholds (min duration/length/extent). It also holds recorder bookkeeping that isn't a user
setting at all: the liveness heartbeat, and the two sweep rule versions (edge stays, track stats)
that are what make `App.onCreate` re-derive the whole history. Sampling is read by the service when
each track's GPS request starts; keep thresholds by the repository when a track finishes.
`ActivityType` therefore only carries a label, a `recording` boolean, and a `TrackGroup`; sampling
cadence is global.

**Data** (`data/`): Room behind `TrackRepository`. A finished track is kept, soft-deleted as
*discarded*, or hard-deleted outright (`keepVerdict`, rule in `KeepRule`). Discarded and
user-deleted tracks are reviewable and restorable from Settings → Recently deleted, auto-purged
after 14 days; the same check runs on normal finish and via `finalizeDangling`, which cleans up
tracks left open by a crash. **A merge is undone by restoring its originals from Recently deleted;
a split by `unsplitTracks`** — which is why `mergeTracks` copies its points while `splitTrack`
reassigns them. `GpxExporter` (`data/export/`) builds GPX for share intents (`FileProvider`) or
bulk-writes to a user-picked folder (Storage Access Framework); `GpxParser` imports GPX files
shared/opened into the app, and `importTracks` refuses a file whose period an existing track
already covers. `BackupExporter`/`BackupImporter` (`data/export/`) are the full backup — one
gzipped JSON file with every kept track's points, places and liveness events, streamed both ways.
Restore is offered only on the Timeline's empty state. The format also feeds the
web companion viewer in `web/` (see its own README) — a change to it is a change to that viewer's
input, and the viewer draws off-path fixes by the same conventions this app does *and derives the
same timeline* (a port of `StayDeriver`/`PlaceClusterer` in `web/js/stays.js`, tested case for case
against `StayDeriverTest`), so a rule that moves here moves there. `PlaceRepository` backs the
Places tab.

**A place row holds what the user said about a spot, and only that** — its name, its capture radius,
and its `category` (`PlaceCategory.code`, null = untagged). Everything else about a place is derived
on read. **A category and a name feed nothing on the way to a stay**, and the plumbing is built to
say so: the derivation is gated on `PlaceClusterer.seedsOf` — a place's pin and its reach — rather
than on the rows (`pinnedRows` in `TrackListViewModel`, pinned by `PlaceDerivationGateTest`), and a
fresher reading of the rows is **matched by id onto the list the clustering was built from**, never
substituted for it, because `PlaceResolver` resolves positionally.

The vocabulary is a closed set of permanent codes stored raw and mapped in the domain
(`Place.placeCategory`, following the `activityType` / `IgnoreReason.code` precedent), with untagged
a first-class state rather than an `Other`; three categories stay out of the timeline's per-day
totals (`dayCategoryTotals`). Every entry owes a glyph (`ui/CategoryIcons`, where an `ImageVector`
can live and the domain package can't) and a `PlaceCategoryGroup` — the coarse five the **colour
coding** reads. `ui/Components.kt` holds both categorical palettes and the rule separating them by
surface; the web viewer instead colours per activity throughout, its map drawing overlapping *lines*
with no glyph to tell them apart.

**Naming a place and categorizing it are two steps, because the second is read off the first.**
`PlaceCategorySuggester` learns from the places the user has already tagged and offers an untagged
place's likely categories as one-tap chips on its detail screen, beside the row that opens the full
picker; `TrackListViewModel.categorySuggester` retrains it off `placeRows` rather than the
derivation.

**An ignored point is one that isn't part of the path — for either of two reasons**: the recorder's
bad-fix rule, and `EdgeStayIgnore`'s `IgnoreReason.EDGE_STAY` for good fixes recorded past the stop
at a track's edges (`IgnoreReason` holds the distinction). The second is applied automatically
wherever a track's points change — finished, imported, merged, split, restored, or retyped across
the foot/vehicle line — with the track's `startedAt`/`endedAt` pulled in to the boundary fix,
and `TrackRepository.sweepEdgeStays` re-derives the whole history when
`EdgeStayDetector.RULE_VERSION` moves.

**The track row carries its points' aggregates, and the recorder must never write it.** Distance,
point/ignored counts and the first/last good coordinates are columns on `tracks`, written only by
`TrackRepository.refreshStats` and re-walked history-wide by `TrackRepository.sweepStats` when
`TrackStats.RULE_VERSION` moves. This is a performance invariant spanning three files, not a
convenience: the observed queries (`TrackDao`) must read `tracks` only, and the recorder's hot path
must write nothing but point rows — `TimelineInvalidationTest` fails if either half is broken, and
each of the three says why. The UI collects with `collectAsStateWithLifecycle` for the same reason —
a backgrounded Compose tree keeps collecting otherwise, and the process outlives the UI by weeks.

**A track's writer is declared, not measured.** `source` (`TrackOrigin.code`) is set by whoever
inserts the row — the recorder in `startTrack`, the GPX import, a backup restore, and merge/split
handing it on to the rows they create. `TrackOrigin.inferFrom` reconstructs it only where no
declaration exists: the v15 migration's SQL fill, and a backup file carrying no `source` key. Two
codes and no third — `TrackMerge.plan` refuses a merge across writers rather than inventing a
"mixed" one. Besides that refusal it is read by `availableColorModes` (`ui/TrackColoring`), which
drops the colour metrics an import can't carry.

**Backfills** (one-time Kotlin data migrations): when a new rule needs to reprocess *existing*
rows and a Room SQL migration can't express the logic, add a repository pass and run it from
`App.onCreate`'s IO coroutine behind a `Settings` done-flag:
`if (!Settings.isXDone(...)) { repository.x(); Settings.setXDone(...) }`. It runs there (not in
any screen) because the background service can keep the process alive for weeks without the UI
opening. Make the pass idempotent — a crash between the work and the flag write means it re-runs.
Delete the pass, its flag, and any DAO queries only it used once the installed base has run it;
`git log --grep=backfill` holds worked examples to copy from (the ignore-reason backfill, the
drive-start leading-stray repair, the point-starved-track purge). **No backfill is live right
now**: `App.onCreate` runs the discarded-track retention purge and the two versioned sweeps (edge
stays, then track stats), and `sweepEdgeStays` says why a sweep is not one.

**UI** (`ui/`): `MainActivity.MainScreen` hosts a bottom-nav (Record / Timeline / Places) Scaffold
with full-screen **overlay** layers on top: sealed `Overlay` (`TrackDetail` | `Settings`) plus
stacked layers for place detail, the Settings sub-pages (sampling, point quality, auto-pause, GPS
search, track filtering, privacy, Recently deleted, Logs), and discarded-track detail — each
animated by a `PredictiveBackHandler` (scale/shift previewing the layer underneath, back returning
one layer at a time). The Compose code is split one file per screen, all in the `ui` package:
`MainActivity.kt` keeps only the activity, navigation and overlay machinery; the screens live in
`RecordScreen`/`TimelineScreen`/`PlacesScreens`/`TrackDetailScreen`/`SettingsScreens`/
`DiscardedScreens`, with shared widgets and formatters in `Components.kt` and the color-ramp/
legend code in `TrackColoring.kt` (cross-file symbols are `internal`, not `private`), and the
duration ladder in `DurationFormat.kt`. **No top-level `val` in these files may reach the Android
framework eagerly** — Kotlin compiles them into one class initializer per file, so a single eager
`android.graphics` call makes *every* pure function in that file unloadable from a plain JVM test,
dragging a sandbox and an emulated JVM behind an arithmetic assertion. `untaggedPinColor` is `by
lazy` for exactly this reason. That is the rule; which file a formatter sits in is then free. The track
map is `MapLibreTrackMap` (MapLibre GL Native) on a **Protomaps
vector basemap** (dark or light flavor following the app theme): **the track is one line feature per
run of same-colored fixes**, colored by the selected metric (ramp luminance also theme-dependent),
start/end and noisy-fix markers sit on a symbol layer, and switching the color metric rebuilds the
line's source without moving the camera. Deliberately not a `line-gradient`: the banded ramp, the
source's simplification tolerance and the layer's round caps are one mechanism serving that, and
none survives being tidied alone — `trackLineFeature` says why. Three more layers ride on the same
map — the detected in-track stops as place-style capture circles *under* the line, the recorder's
overrun grayed off the track's ends, read back from the stored flags rather than re-detected, and
the **named places at the path's two ends** (`RoutePlaces`), each a labeled pin over the capture
area that claimed that end. The first two are **deliberately unlabelled** — their meaning is in
where they sit and how they're drawn. A panel of times over the route says less than the shapes do
and covers the map saying it, so labelling them is a decision to take, not an oversight to fix.

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
- **A GMS transition registration can go silently deaf, and re-registration is not a cure** — the
  app **detects and reports rather than repairs**: `StaleReadingOracle` spots it, `DeafnessWarning`
  decides when to say so, and the user is told to reboot. Don't build anything that assumes
  restarting the registration fixes this; `ActivityRecognitionManager.restart` carries the field
  evidence. It is *not* a rule against re-registering — the watchdog re-*requests* every tick, and
  that replay is what feeds the oracle; the request/restart line is drawn at the watchdog tick.
- The `alerts` channel (`RecorderNotifications`) is a second, transient channel. The "persistent
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
- **Frame the map with `moveCamera`, not `easeCamera`** — a map opens already fitted, never
  animating in, and framing runs once per map instance. `fitCamera` in `MapLibreTrackMap` has the
  live preview's re-frame rule.
- **Never resize a `MapView` — give each screen its own.** A resized texture map stretches its last
  frame (a pin becomes an oval) and nothing fixes it from inside a shared map; two screens that want
  the map at different sizes are two layers with a map each, and the fresh camera is the point
  rather than the price. `PlaceEditScreen`'s KDoc has the argument, including the rejected
  alternatives.
- **`PlaceEditScreen` owns everything the user says about a place** — name, capture radius and pin,
  written as one row; the detail screen reads and does not edit, bar the category chips. A detected
  stop has no row, so it carries no edit action, only "Create place" — and following that create
  needs both halves: `TrackListViewModel.savePlace(…, onCreated)` hands back the inserted id, and
  `MainScreen` re-keys the open screen onto it through `PlaceResolver.keyOf`. The screens carry the
  rest.
- **Marker symbol layers draw in source order**, set once on the shared `markerSymbolLayer` base,
  which says why.

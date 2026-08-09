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
five-minute floor while the detection behind it does not.

There are no *instrumented* tests, but the timeline's rows are composed and read back off the
semantics tree by `TimelineRowTest` (Robolectric, so it runs in the normal unit-test pass and needs
`-PqemuJdk` on arm64 like the Room ones). It answers the one question every other suite has to take
on trust — does *this* state put *that* resource on screen — which is where a string that is missing,
misnamed or wired to the wrong row fails and nowhere else. Two rules make it worth having:
**expectations are read from the resource table, never spelled in the test** (a literal `"Stayed"`
would pin English and rot exactly as a hand-written fake does), and **a `values-pt` expectation is
only obtainable through `translated()`, which asserts the two languages differ for that key** — a
missing translation otherwise falls back to English, the row renders English, and the case passes.
That guard is a function rather than a convention precisely so the next case cannot skip it.

**Know what it does not cover.** It samples — a few rows, some of their cases, a scattering of
Portuguese keys. Totality
over the string table is `ResourceHygieneTest`'s job and belongs there — it is plain JVM, costs a
file read, and holds for every string anyone adds (it now checks that every translatable English key
reaches every language that ships, which is the failure a rendering test *cannot* catch, since a row
composed against a missing key renders the English fallback quite happily). Prefer adding a total
rule there over adding cases here. Enum→resource mappings need neither: they are exhaustive `when`s,
so the compiler already refuses an unmapped entry.

The third move, and usually the best one, is to **take the decision out of the row**: a rule stated as
a value (`ui/StayBounds.kt` — which clock times a stay row states, and whether a duration may sit
beside them) is total over its own cases on a plain JVM, where composed it was reachable only by
building a row in exactly the right zone. What the row test then covers is the rendering, which is
all it was ever the right tool for.

Two prices, both real: composing a row means it is `internal` rather than `private` (a small thing —
this file already defaults to `internal` for cross-file symbols — but do not widen a row without
covering it), and the harness costs ~24 s on an arm64 dev box, of which ~18 s is a one-time toll that
further Compose classes will not pay again. `debugImplementation("ui-test-manifest")` also puts an
empty activity in the **debug APK**; `app/src/debug/AndroidManifest.xml` closes it, since the library
ships it exported and this debug build records around the clock.

Everything above that — screens, the map, the recorder's live behaviour — is still verified by
building and driving the app on a device/emulator (arming and the notification wording need only the
app running; activity recognition needs real movement or an emulator route).

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
ceiling all read it as the journey. `MovementConfirmer` is the recorder's second witness, and it is
**always on** — the recorder's one job is recording movements, and a mislabelled STILL aboard a
moving carrier costs the trip, which is the verdict that retired the setting that used to gate it.
**`Unknown` is the fallback** — every consultation defines an `Unknown` case identical to the
pre-witness behaviour, which is what runs whenever the ground can't answer; a consultation that
can't be expressed as a `Motion.Unknown`-defaulted parameter is a design smell, not a workaround.
Each consulting rule's suite keeps its pre-witness half unedited above a divider, which is what pins
that fallback. **Exactly five consultations exist**, and that there are five and only five is the
thing no one file says: the gate parks a contradicted STILL, the jump ceiling rises to fit measured
ground speed, a `Moving` verdict vetoes the no-fix give-up, every path that turns GPS off
re-evaluates the parked slot on the way down, and a standstill the witness proved pauses a
**signal-opened** track (`ArrivalWatch` — a track opened by a departure trigger has no reporter
whose stop will ever end it, while a reading-opened track is never paused this way: its reporter
lags, but delivers). Promotion rides the `GnssStatus` callback, with the
15-minute watchdog alarm as the guaranteed revisit.

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
pipeline's second witness — see below), `DepartureWatch` + `ArrivalWatch` (the two edges of a
journey Play Services never reported — noticing the leaving from coarse positions, and the
standstill that ends a track the departure side opened), `PlaceCategorySuggester` (guess a place's category from
what the user called it — see below), `RoutePlaces` (the named places at a track's two ends),
`TravelDeriver` + `TravelNaming` (journeys away from home — see below) and `CityAtlas` (the offline
gazetteer they are named from). New
behavior belongs here first, with a test, before wiring into the service or UI. The shared
vocabulary lives here too: `ActivityType`/`TrackGroup`, `IgnoreReason`, `PlaceCategory`, and the
`DistanceFn` seam (the GMS `DetectedActivity` mapping is `location/DetectedActivities`). One deliberate impurity: domain functions take the Room entities
(`TrackPoint`, `TrackSummary`, `Place`) directly rather than a mapped domain model — the point walk
runs over millions of rows, and a per-row mapping allocation buys nothing but layering purity. The
`db` package must not import `domain` back (that would make the two one unit); entities carry no
domain defaults for the same reason.

**A journey is a run of nights, and every rule about one is a rule about placing a night**
(`TravelDeriver`). A night is *away* when the cluster holding it is not one home's — cluster
identity, never a distance, so there is no radius to sit just inside or outside and a hotel five
minutes down the road is as much a journey as one abroad. Home is the **set** of `HOME`-tagged
places (their pins seed the clustering, so each one's capture radius draws its own boundary), and
with nothing tagged the cluster holding the most nights stands in. Nights are sampled at 03:00 on
**solar** time derived from longitude — no zone was ever recorded and today's device zone slices a
past trip abroad at the wrong hours, while an offset off by up to ~1.5 h cannot change which bed
someone was in. A night at home splits a run in two; a night nothing can place (a gap whose two
sides disagree) is carried through a run but can neither open nor close one. **Days are nights + 1**
and a journey's figures come only from its own days: the bounds are the days `daysCovered` marks,
trimmed by the home stays either side but never stretched by them, because the night someone flies
the interval covering their last night at home is a *gap* and the last stay at home can be a day
earlier.

**What a journey is called is decided apart from what it is** (`TravelNaming` + the naming pass in
`TrackListViewModel`): places ranked by time spent, anything under two hours dropped, at most three
named. Time spent means stays **plus tracks that begin and end in the same place** — a day walking a
city is mostly movement, and by stays alone that city scores less than the car park. The floor is
absolute rather than a share of the journey, because a share halves when one place resolves to two
names and both then vanish. Names come from the gazetteer, not from the user's own labels — the
exception is a `HOME`/`FRIENDS_FAMILY` place, where visiting the person *is* the destination — since
a label sits on a parking spot the recorder happened to stop at, and on-foot recording will scatter
that one label into many small clusters. `PlaceCategory.visited` marks the stops that are the road
rather than a place on it (fuel, service areas); parking deliberately still counts, being the only
evidence a car-borne history has of being in a city.

**Settings** (`data/Settings`, SharedPreferences): the armed flag plus *global* sampling (min
time/distance between points), point-quality gates (accuracy gate, require-GNSS cross-check), the
auto-pause resume window, the GPS give-up timeout, and keep-track
thresholds (min duration/length/extent). It also holds recorder bookkeeping that isn't a user
setting at all: the liveness heartbeat, which permissions this install has ever put a dialog up for,
and the two sweep rule versions (edge stays, track stats)
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
Restore is offered only on the Timeline's empty state, and that
screen is where it reports its progress. With tracks present a restore would have to merge with
them, so the offer disappears as soon as the first track exists. The format also feeds the
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
can live and the domain package can't) and a `PlaceCategoryGroup` — the coarse grouping the **colour
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
inserts the row — the recorder in `startTrack`, the GPX import, the add-trip form
(`insertManualTrack`, a `manual` track: two typed endpoints and nothing between), a backup restore,
and merge/split handing it on to the rows they create. `TrackOrigin.inferFrom` reconstructs it only
where no declaration exists: the v15 migration's SQL fill, and a backup file carrying no `source`
key (a source-less manual track reads as imported there — a path, not a measurement, which is the
honest half of the truth). **No "mixed" writer exists** — `TrackMerge.plan` refuses a merge across
writers rather than inventing one, which also keeps typed endpoints from being absorbed into
measured fixes. Besides that refusal it is read by `availableColorModes` (`ui/TrackColoring`),
which drops the colour metrics an import or a manual entry can't carry. Manual tracks bypass the
keep thresholds like imports do — `KeepRule`'s two-point purge floor would otherwise delete every
one on arrival — and their two points are stamped exactly at the row's bounds so the edge-stay
boundary fix and the stats sweep have nothing to rewrite.

**A manual track is also the only one that can be rewritten** (`updateManualTrack`, reached by the
track detail's pencil, which on any other track opens the type dialog instead): the same two pins and
two times go back onto the row that produced them, replacing its fixes outright — which is exactly
why `source` gates it, every other track's points being a measurement or a file's. Two things follow
from rewriting in place: the row is excluded from its own overlap check (`NO_TRACK` where an insert
excludes nothing), since the fixes it would collide with are the ones being replaced; and the write
is otherwise an insert, `finalizeImportedTrack` included, because the aggregates and the overrun
verdict are functions of the points and these are new points. An edit is deliberately **not**
undoable — every value is in the form, unlike a merge or a delete, and the undo snackbar's host
cannot reach the layer a commit lands back on.

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

**UI** (`ui/`): `MainActivity.MainScreen` hosts a bottom-nav (Record / Timeline / Places / Insights) Scaffold
with full-screen **overlay** layers on top: sealed `Overlay` (`TrackDetail` | `Settings`) plus
stacked layers for place detail, the Settings sub-pages (sampling, point filter, auto-pause, GPS
search, track filtering, app lock, online services, Recently deleted, Logs), discarded-track detail,
and the add-trip
form (`AddTripScreen`, opened from the Timeline tab's top-bar "+" or from a gap row) — each
animated by a `PredictiveBackHandler` (scale/shift previewing the layer underneath, back returning
one layer at a time). **What that form opens holding is a `TripDraft`**, which is also the state
saying it is open: a gap row hands over the ends *it* speaks for and no others — the same two
questions its card is drawn from — so the far side of an absence cut at midnight never arrives as
this row's fact. A gap is cut **once**, at the midnight opening the day it ended (`slicePerDay`), so
a row holds both ends or exactly one, and never neither. Each end carries an instant rather than
a wall clock, because the zone follows from resolving the pin (which the form does after opening)
and because a bound rounded to the pickers' minute would overlap the track it was taken from. Its
position is the **recording's own** — `StayDeriver.Gap` carries the two fixes whose disagreement made
it a gap, and an end timed at a neighbouring track's bound is placed where that track was at that
instant, not at the pin of the place holding it, which can sit a street away and would leave the
entered leg jumping off the path it fills. The trip type is deliberately *not* defaulted: it is the
one thing neither end implies.

**Every permission this app asks for hangs off one intention — turning recording on** (`ui/Setup.kt`,
which holds the whole setup flow and, deliberately, no screen). Nothing is requested at launch and
there is no onboarding page:
the Record tab's toggle is the request, and flipping it on runs `SetupLadder` — each unmet
requirement asked at the moment the one before it was granted. **The run ends the moment a step
comes back unmet**, because a refusal is an answer and asking the next thing on top of it is how a
permission flow turns into nagging; the run arms the recorder only if it got through everything.
This is the platform's own "ask in context" guidance applied to an app whose one in-context moment
is arming, there being no later feature to wander into.

The ladder has **two signals and must not confuse them** — a dialog's result, which fires once for
exactly what was asked, and a resume, which fires for reasons that have nothing to do with a run.
`SetupStep.answersOnResume` is which one an ask answers on — the battery exemption and a blocked
step, everything else going through the permission system, which reports back through the launcher
whatever it put on screen. Named for the answer rather than for leaving the app because the two
differ: from Android 11 the all-the-time request *does* leave and still answers through the launcher.
The ladder captures that (with the step's progress) *when the ask goes out* rather than re-deriving
it from the answer. A resume while one of the app's own prompts is up is not an answer either, and
MainScreen holds it back on `prompt` being non-null — one condition covering every such dialog, so a
third cannot be added without it.

**A step is a reason, not a dialog** — and the reader's reason, not the platform's. Location
"all the time" is **one** step, because in system settings it is one switch with two positions, and
a reader shown two rows for it is shown the same control twice; Android merely refuses the second
question until the first is answered, so the row states the whole bargain up front and names the
upgrade alone once that is what is left (`SetupStep.bodyRes`). That is also why the ladder ends on
an ask that **moved nothing** rather than one that left a step unmet — `SetupState.progressOf`
counts location's two halves, so the first grant carries the run into the second.

`SetupState` is read off the platform every time, so a permission revoked long after setup is unmet
again with nothing to invalidate. **Every step is required and `complete` is the whole condition for
arming** — there is no narrower "enough to record" beside it. Two of them could technically be
refused and still leave a recording running (the foreground service starts without
`POST_NOTIFICATIONS`, Android only hiding the notification; the battery exemption is not checked up
front), and they are required anyway: a recorder that arms and then dies in the background hours
later, or one running with nothing on screen to say so, is a failure the reader meets long after the
moment they could have understood it. One list, one meaning of ready, and a toggle that either works
or names what is missing — which is also what keeps the card from having to sort requirements into
two kinds.

Three things the platform makes fiddly, each handled once. **The all-the-time disclosure**
(`BackgroundLocationDisclosure`) is said before *every* request for it, from the ladder and the card
alike, so it cannot go missing by the route taken; being the last thing on screen before the
platform's own asking takes over, it is also where the reader is told which option to pick there.
**A permission refused twice can no longer be asked for**, which `Settings.askedPermissions` is what
detects — `shouldShowRequestPermissionRationale` answers false both for that and for a permission
never asked, and only the recorded ask separates them. **Asking for a blocked step *is* opening
settings** — one definition (`grantSetupStep`) both surfaces reach, since a card that offers "Open
settings" while the toggle fires a request Android stopped taking leaves one control silently doing
nothing. `SetupStep.answersOnResume` follows from the same fact, a blocked step answering on the
resume from settings rather than through a dialog.

**No reader is handed to Android without being told why**, and that is what the two paths differ
over — not the ask, only whether the reason is already on screen. The card carries every step's
words above its own button, so its blocked button goes straight through; a run has no card up, so
`askFromLadder` puts those same words in `BlockedStepDialog` first. Same for the all-time
disclosure, which a run must show for want of a row and shows on the card path too because the
policy attaches it to the request rather than to the surface. A jump into system settings with
nothing said is one the reader arrives from not knowing which switch was wanted.

And **the all-the-time half is requested, never navigated to**. Asking for
`ACCESS_BACKGROUND_LOCATION` on its own, once plain location is granted, makes the permission
controller open its per-app location page itself from Android 11 on — the screen holding "Allow all
the time", which is nearer the switch than anywhere an app may send someone. Do not replace this
with a settings deep link: `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` lands two levels above it,
and the permission controller's own entry points (`MANAGE_APP_PERMISSION`,
`MANAGE_APP_PERMISSIONS`) *resolve* from a normal app and then refuse to start, wanting the
signature permission `GRANT_RUNTIME_PERMISSIONS` — they resolve and then deny, so a resolve check
reads as success and the failure shows up only as a `SecurityException` at launch.
`openAppSettings` is for the twice-refused case alone.

**What is still owed is shown in exactly one place**, `SetupCard` in the Record tab's content slot —
a row per *unmet* requirement, carrying its reason and its own button. There is deliberately no page
behind it: a summary card that opens a screen listing the same requirements is the same list twice,
and the slot is large enough for the detail. It sits **below the live map** in the tab's `when`,
because a recording drawing itself outranks anything still owed and what can be owed while one runs
(the exemption, notifications) stops nothing.

**The Record tab keeps one shape through all of it** — the toggle at the top, the keep-screen-on row
at the bottom, one card between them — so the screen a reader learns on the first launch is the
screen they keep. The toggle stays **live** whatever is missing, since turning it on is what puts
the requests up. The card yields to a live recording, which can only outlive setup by a requirement
being revoked mid-track, and a track being drawn outranks the notice about it. Nothing else about
setup exists: no page, no first-run flow, and so no flag recording that one was seen.

The Compose code is split one file per screen, all in the `ui` package:
`MainActivity.kt` keeps only the activity, navigation and overlay machinery; the screens live in
`RecordScreen`/`TimelineScreen`/`PlacesScreens`/`InsightsScreens`/`TrackDetailScreen`/
`SettingsScreens`/
`DiscardedScreens`, with shared widgets and formatters in `Components.kt`, the recorder's setup
model and its card in `Setup.kt` (no screen of its own — see above), and the color-ramp/
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
GitHub Release. Release flow: commit the `versionCode` bump → push it to `main` → tag it
`v1.0-vc<N>` → push the tag (the tag alone would build, but a commit no branch contains is not a
release; when later commits must stay local, `git push origin <bump-sha>:main` pushes the bump on
its own) → append the "What's new" text (written per `docs/release-notes-guide.md`) to the GitHub
Release body via `gh release edit`, under the generated provenance line → download the `.aab`
from the GitHub Release and upload it to the Play Console manually, reusing the same "What's
new" text there. `versionCode`'s source of truth is `app/build.gradle.kts`; the tag only
cross-checks it.

## Conventions & constraints

- **No recorded history reaches the repo, and nothing in it refers to whoever recorded it.** Most of
  the tuning here rests on field
  evidence, and citing it is right — but comments, KDoc, commit messages and `docs/` must carry
  what the data *showed*, never which trip showed it: no recording dates or times, no place names
  or real coordinates, no per-trip distances, durations, point counts or quality figures. "A parked
  phone can report phantom Doppler up to 3.5 m/s" — not "an arrival 7 m from the track's final
  position on such-and-such a date". The person behind the history is never a subject either: no
  "the author", no "my phone", no note explaining what a choice does or doesn't reveal about anyone
  — a sentence like that is itself the disclosure, and it is the one a stranger reads first.
  History-wide aggregates are fine while they name no date or place ("an end stay
  on ~30% of tracks, median ~72 s"), and so are generated test fixtures, whose coordinates are
  invented. The repository is the one artifact that leaves the machine; the recorded history stays
  on it. Fixtures sit at a **neutral origin** and should stay there: the domain tests build meter
  offsets off latitude 1.0, the data-layer ones off latitude 1.0 / longitude −2.0. Real coordinates
  in a fixture leak a region even when no trip is named.
- **Don't count a set that can grow** — in comments, KDoc, `docs/` or this file. "The three codes",
  "the two rows below": a number in prose is a claim nothing checks, and it rots the first time an
  entry is added or removed. Either describe the set without counting it, or name every member — so
  a change cannot leave the sentence silently wrong.
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
- **User-facing text is a resource; the layer that decides *what* to say holds no words.** Strings
  live in `res/values/strings_<screen>.xml`, one file per `ui/` screen (Android merges them all, so
  names must be unique across the set and the prefixes are what keep them apart). `domain/` and
  `util/` hold no language at all: an enum carries a code and the UI maps it to a `@StringRes`
  (`ui/CategoryLabels`, `ui/RecorderWords`), and a rule that needs a *sentence* takes a vocabulary
  interface the host implements — `RecorderVocabulary`, `UnitSymbols`, `DurationSymbols`. That seam
  is what keeps those suites on a plain JVM, and it is why they are interfaces rather than
  formatters: the rounding, the duration ladder's rungs and the recorder's phrasing are this app's
  decisions, and a measure/date formatter would re-decide them.
- **Each recurring concept has one word per language, recorded in `docs/glossary/`** — `en.md` is the
  canonical file and every other language answers it (trip = *viagem*, place = *local*, positioning
  = *localizar*, …). Write new strings from it, review string changes against it, and record a new
  term or exception in it rather than deciding silently; its README carries the structure, the
  grouping and how a language is added.
- **Never assemble a sentence from parts.** No `"$verb $noun N of M"`, no `if (n == 1) "visit" else
  "visits"`, no lowercasing a noun to slot it mid-sentence — word order, agreement and case are the
  language's, not the caller's. One whole phrase per case, and `<plurals>` for anything counted.
  A line built around a value no format string can carry — a clock time drawn with its own zone-shift
  span — is still one whole phrase: `annotatedStringResource` (`ui/Components.kt`) splices styled
  arguments into a resource's placeholders, so the time goes where the *translation* puts it. A word
  that appears both alone and inside a sentence is **authored twice**, not transformed: an
  `ActivityType` carries `labelRes` and `inlineLabelRes` (`ui/RecorderWords.kt`), because lower-casing
  the label to slot it mid-sentence would decide a noun-capitalizing language's orthography for it.
  `ResourceHygieneTest` pins three rules the compiler can't, reading the XML rather than the resource
  table: **no resource carries edge whitespace** (one that needs it is a fragment, and a fragment
  freezes word order — the quoting that used to preserve the space preserved the freeze with it),
  every translation uses its original's placeholders, and `locales_config.xml` lists exactly the
  `values-*` folders that exist (a language missing from it never reaches the system's per-app
  language picker).
- **Dates and measures follow the locale, units follow the country.** `localizedDateFormat` resolves
  a pattern from a skeleton, and `standaloneCase` capitalizes a date that heads a section — Romance
  languages lowercase months and weekdays, and the platform capitalizes them in that position.
  Because that reaches `android.text.format.DateFormat`, **nothing a plain-JVM test can call may
  format a date**: `groupTimelineByDay` returns dates and the screen renders them for exactly this
  reason. `Measures` pairs the unit system with its symbols because the two come from different
  halves of one locale — the country picks metric vs imperial, the language picks "km" vs "км".
- **A clock is the exception: its hour cycle is a device setting, not a locale's preference.**
  `ReaderClock` pairs the locale (field order, separators) with `DateFormat.is24HourFormat` (12 or
  24), which is the same source Material's time picker reads — a skeleton asking for `j` would answer
  the locale's *preferred* cycle and contradict a reader who set their phone the other way. It is the
  app's only clock format, so it is also the only place `H` or `h` is chosen. Two consequences worth
  knowing: **changing 12/24 is not a configuration change**, so `rememberReaderClock` observes
  `Settings.System.TIME_12_24` rather than trusting recomposition, and a caller inside
  `buildAnnotatedString` takes the clock as a parameter for the same reason it takes the shift colour
  — nothing composable may be read in that scope.
- **Logs are never localized.** `DebugLog` text, its tags, and the operation names handed to it
  (`runExclusiveOp`'s `logLabel`) stay English whatever the device language. Settings → Logs showing
  them in-app does not make them interface text: a log line sits beside untranslated platform
  exception messages, and a fault that greps differently on two phones is worth less than one that
  reads awkwardly on one. Notification and screen text beside a log call is the opposite — that is a
  user surface and translates.
- `applicationId` is permanent once published; the `${applicationId}.fileprovider` authority and
  notification/manifest pieces derive from it, so don't hardcode the package elsewhere.
- All data is local; the network carries map data — Protomaps vector tiles (hosted API) plus the
  glyphs/sprite from `protomaps.github.io` — and one deliberate exception: the add-trip form's
  **online place search** (`data/OnlinePlaceSearch`, photon.komoot.io, OpenStreetMap data), which
  sends the typed query and — where the form has a pin to bias by — that pin's coordinate, treats
  every failure as "no results", and is switchable off on the Online services settings page. **The
  coordinate is a pin the user placed, never wherever the map happens to be looking**: the form's
  own place list sorts by the map centre because re-ordering rows the device already holds discloses
  nothing, and that is the whole reason the two use different anchors. The ODbL credit in Settings
  and at the results is a licence requirement, like the GeoNames one. There is no server sync (a possible future feature — the
  Online services settings page is where server URL/key fields would go).
- **The Protomaps hosted-API key is not committed.** It lives in `local.properties` as
  `protomapsApiKey=…` (gitignored), surfaced as `BuildConfig.PROTOMAPS_API_KEY`, and injected into the
  bundled style at load time (`{PROTOMAPS_KEY}` placeholder in `assets/protomaps-{dark,light}.json`).
  A fresh checkout needs that line added or the basemap won't load.
- **The gazetteer is a bundled asset** (`assets/cities.bin`, ~4 MB): GeoNames `cities1000` — every
  populated place of 1,000+ **and every administrative seat whatever its size**, which is why that
  file rather than the smaller `cities5000`, a historic village being exactly what a journey gets
  named after. Packed by `tools/pack_cities.py` (its docstring is the format spec) and checked in, so
  a fresh checkout and CI need no network; regenerate only to take a newer dump. **CC BY 4.0 — the
  credit in Settings is a licence requirement, not decoration.** Feature codes decide nothing about
  what a place *is*: Paris's arrondissements are plain `PPL`, same as any town, so districts are
  separated from towns by the naming heuristic in `CityAtlas` (a window plus a population dominance
  factor) and not by the data. The rows carry an IANA zone, read in two places: the timeline's
  per-cluster clocks (`TrackListViewModel` resolves each cluster's centroid to a city and shows
  stay/track times on that zone's clock) and the add-trip form, which interprets each end's typed
  departure/arrival time in the zone of the city its pin resolves to.
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

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
(TrackQuality, TrackStats, GpxExporter/GpxParser, BackupExporter/BackupImporter) and the view
model's flow gating (`PlaceDerivationGateTest`, which drives the real `pinnedRows` rather than a
copy of it, and needs no database — so unlike the Room-backed tests below it runs on any dev box) — run them with
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

**Activity Recognition describes the user's body, not the journey**, and three recorder decisions
read it as though it described the journey: when to pause, where tracks split, and which jump
ceiling judges a fix. Aboard anything that carries the phone the body genuinely is still while the
ground moves at vehicle speed, so a crossing is paused away, fragmented, and has its fixes rejected
as teleports. `MovementConfirmer` is the second witness: a trailing window of accepted fixes
answering only *is the ground moving?* (`Motion.Moving` / `Stopped` / `Unknown`). **`Unknown` is
both the fallback and the off switch** — every consultation must define an `Unknown` case identical
to the pre-witness behaviour, so the setting branches at exactly one place (the service's
`motionVerdict`) and nothing downstream knows a switch exists. That invariant is what the untouched
pre-change suites for `ActivityGate`, `TrackQuality` and `NoFixGuard` pin; a consultation that
can't be expressed as a `Motion.Unknown`-defaulted parameter is a design smell, not a workaround.
Four consultations exist: the gate **parks** a STILL the ground contradicts rather than dropping it
(the AR stream is edge-triggered — a discarded stop is never re-announced), the jump ceiling rises
to fit measured ground speed (clamped to the most permissive activity ceiling, since a lone teleport
is fed to the confirmer like any other fix), a `Moving` verdict vetoes the no-fix give-up, and every
path that turns GPS off re-evaluates the parked slot on the way down. Promotion rides the
`GnssStatus` callback, with the 15-minute watchdog alarm as the guaranteed revisit — there is
deliberately no parked-too-long cap, because a fresh verdict distinguishes a stale hold from a
crossing still under way and a deadline cannot. **The feed contract is where the circularity
hides:** the confirmer gets every fix that cleared the *label-independent* gates and only those, so
`JUMP`-flagged fixes are included — they were rejected by the very label the witness exists to
second-guess. Default off pending field data.

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
`DeafnessWarning` (decide when to tell the user about it), `MovementConfirmer` (the recording
pipeline's second witness — see below), `PlaceCategorySuggester` (guess a place's category from
what the user called it — see below). New behavior
belongs here first, with a test, before wiring into the service or UI. The shared vocabulary lives
here too: `ActivityType`/`TrackGroup`, `IgnoreReason`, `PlaceCategory`, and the `DistanceFn` seam
(production implementation `data/AndroidDistance`; the GMS `DetectedActivity` mapping is
`location/DetectedActivities`). One deliberate impurity: domain functions take the Room entities
(`TrackPoint`, `TrackSummary`, `Place`) directly rather than a mapped domain model — the point walk
runs over millions of rows, and a per-row mapping allocation buys nothing but layering purity. The
`db` package must not import `domain` back (that would make the two one unit); entities carry no
domain defaults for the same reason.

**Settings** (`data/Settings`, SharedPreferences): the armed flag plus *global* sampling (min
time/distance between points), point-quality gates (accuracy gate, require-GNSS cross-check), the
auto-pause resume window and its motion cross-check toggle, the GPS give-up timeout, and keep-track
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
tracks are reviewable and restorable from Settings → Recently deleted, auto-purged after 14 days;
the keep check runs
both on normal finish and via `finalizeDangling`, which also cleans up tracks left open by a crash
(it skips `LocationRecordingService.activeTrackId`). **Merge copies its points and split reassigns
them, and the asymmetry is deliberate**: `mergeTracks` turns two rows into one, so the originals are
the only record of the pair and stay in Recently deleted (restorable, which is how a merge is undone);
`splitTrack` keeps the original row *as the first half* and rehomes the later fixes onto one new
track, because nothing needs preserving — every fix survives on one row or the other, and a copied
original would leave a third row covering a period two live tracks already hold, restorable from a
screen that offers a restore for everything on it. Don't unify them. `GpxExporter` (`data/export/`) builds GPX for
share intents (`FileProvider`) or bulk-writes to a user-picked folder (Storage Access Framework);
`GpxParser` imports GPX files shared/opened into the app. **An import is refused whenever the
period is already taken**: `importTracks` skips a file's track as a *duplicate* when a track holds
fixes at both exact ends of its span, and as *overlapping* when the spans merely intersect (a
second path over one period double-counts its stats and hands stay derivation parallel journeys).
Both checks read the points, not the track row's bounds — the row's move when the overrun comes off
its edges — and both ignore soft-deleted rows, so a span covered only by Recently deleted imports. `BackupExporter`/`BackupImporter`
(`data/export/`) are the full backup: one gzipped JSON file with every kept track's points
(ignored ones and quality metadata included), places with their categories and liveness events — written from Settings,
streamed both ways (one track's points in memory at a time), point rows as arrays keyed by a
`pointFields` header so future exports stay restorable. Restore is offered only on the Timeline's
empty state, deliberately: with existing tracks it would have to merge. The format also feeds the
web companion viewer in `web/` (see its own README) — a change to it is a change to that viewer's
input, and the viewer draws off-path fixes by the same conventions this app does *and derives the
same timeline* (a port of `StayDeriver`/`PlaceClusterer` in `web/js/stays.js`, tested case for case
against `StayDeriverTest`), so a rule that moves here moves there. `PlaceRepository` backs the
Places tab.

**A place row holds what the user said about a spot, and only that** — its name, its capture radius,
and its `category` (`PlaceCategory.code`, null = untagged). Everything else about a place is derived
on read. **A category and a name feed nothing on the way to a stay**, and the plumbing is built to
say so: clustering reads a place's pin and its reach and nothing else — `PlaceClusterer.seedsOf` is
that projection, and `Seed` is a **value**, so the derivation is gated by comparing seed lists
(`pinnedRows` in `TrackListViewModel`, pinned by `PlaceDerivationGateTest`) rather than the rows.
Gating on the row re-clusters the whole history whenever a place is renamed or tagged — for fields it
never reads — and the user watches it happen, because everything a place's screen shows waits behind
that walk. Two traps sit in this gate. **`Seed` must keep value equality**: as a plain class it
compared by identity, so a `distinctUntilChanged` over freshly built seeds differed every time and
silently gated nothing. And a fresher reading of the rows may only be **matched by id onto the list
the clustering was built from**, never substituted for it — `PlaceResolver` resolves a cluster to a
place *positionally* (`seedIndex`), so pairing a cached derivation with a list a delete has reindexed
labels stays with the wrong places until the re-clustering lands.
Three rules hold the vocabulary
together. The **codes are permanent** — they reach the DB column *and* the backup format the web
viewer reads — while the `label` beside each is display text, free to reword. The column keeps the
**raw string, mapped in the domain** (`Place.placeCategory`, following the `activityType` /
`IgnoreReason.code` precedent), so a code this build doesn't know reads as untagged but survives a
backup round trip instead of being erased. And **untagged is a first-class state, not one more
category**: there is deliberately no `Other`, because it would collect precisely the places worth
finding again while saying nothing about them. Three categories carry `inTimeTotals = false` and so
stay out of the timeline's per-day totals (`dayCategoryTotals`) — `HOME`, which as the baseline every
day returns to would dwarf the line it shares, plus `PARKING` and `GAS_STATION`, which are transient:
somewhere passed through on the way to the thing, with no purpose of their own for a total to report. The set is closed and not user-extensible — per-category
totals only compare over a vocabulary that doesn't drift, and every entry owes a glyph (`ui/CategoryIcons`,
where an `ImageVector` can live and the domain package can't) plus a `PlaceCategoryGroup`, the coarse
five (home & people / errands / routine / away / transient) that **color coding** reads: a categorized
place wears its *group's* color, never its own, so a list reads as a pattern instead of a legend to
memorize, and untagged stays neutral (`placeDiscTint`). It is a derived categorical palette (M3 has no
categorical roles): fixed saturation and lightness, only the hue rotates, so no group outweighs
another. **The two categorical palettes are separated by surface, not by tone**: `activityColor`'s hue
per activity belongs to the Record tab, which holds no places at all, while everywhere places share
the screen (the Timeline and what it opens) travel takes one neutral (`travelColor`) so the coding is
the places' — an activity hue there competes while saying nothing the row's glyph doesn't (car, boots,
bike are distinct shapes), and a day's shape is in where the user stopped. `CATEGORY_SAT` sitting below
`ACTIVITY_SAT` is only the fallback should a screen ever show both. The web viewer colors per activity
throughout: its map draws overlapping *lines*, with no glyph to tell them apart. `TrackColoring`'s
per-activity ramps are untouched and unrelated — they encode speed, not identity. Those glyphs were chosen as a *set*:
no vehicle silhouette (a timeline row already spends those on the track's activity) and one building
only (Home has it, so Work is a briefcase).

**Naming a place and categorizing it are two steps, because the second is read off the first.**
`PlaceCategorySuggester` learns from the places the user has already tagged — naive Bayes over word
tokens and character 3/4-grams — and offers an untagged place's likely categories as one-tap chips on
its detail screen, beside the row that opens the full picker. Training on *one person's* names is what
makes a model this small work: place names are proper nouns, and a general vocabulary would have to
know every shop brand on earth, where one user's names rhyme with each other. Names arrive through
`PlaceSearch.fold`, so an accent is invisible here exactly as it is to search — two normalizations
would be the app disagreeing with itself about what counts as the same place. **Silence is a supported
answer**: a name built of unrecognized features scores every category at its prior, so the suggester
gates on the share of features it knows at all and says nothing below the floor — which also subsumes
the cold start, an untrained model recognizing nothing on a fresh install without a threshold of its
own. The margin decides *how many* chips, not whether to show any: a low margin means the answer is
probably among the top few, which is the case chips exist for. Retraining is recounting, so it rebuilds
whenever the places table changes and there is no model to persist, version or migrate. Two things the
UI owes it: chips show only while a place is untagged (a tagged place has an answer, and re-suggesting
against it invites tapping the model's opinion over the user's), and a tap is held optimistically until
the stored row agrees — the suggester reloads faster than the derivation, so an untagged place held
against a model that has just been trained on the tag being written is a chip for the category the
user has already chosen.

**An ignored point is one that isn't part of the path — for either of two reasons.** The recorder's
bad-fix rule (`TrackQuality`: accuracy, jump, no-GNSS) rejects fixes it doesn't trust; `EdgeStayIgnore`
flags the good fixes recorded past the stop at a track's edges (`IgnoreReason.EDGE_STAY`), applied
automatically when a track is finished, imported, merged, split, restored, or retyped across the
foot/vehicle line (the activity picks the detector's tuning — `EdgeStayDetector.paramsFor` — so a
retype that changes it re-derives the track), and the track's `startedAt`/
`endedAt` pulled in to the boundary fix with it. Both drop out of distance, endpoints, the drawn
line and GPX export while keeping their rows, so the operation destroys nothing and is undone by
clearing a flag. Two invariants hold it together: detection runs on the points with the edge flags
*cleared* (never on its own output, or the track walks backwards one sweep at a time), and a flag
survives only where the rule still re-derives it — every flag is reconsidered wherever it sits, so
the overrun a merge buries mid-track goes back to the path rather than sitting off it on a verdict
nothing will re-examine. `TrackRepository.sweepEdgeStays` re-derives the
whole history whenever `EdgeStayDetector.RULE_VERSION` outruns the version last swept — standing
infrastructure, not one of the one-shot backfills below, so bumping that version is part of
changing the rule.

**The track row carries its points' aggregates, and the recorder must never write it.** Distance,
point/ignored counts and the first/last good coordinates live as columns on `tracks`, written only
by `TrackRepository.refreshStats` (from `TrackStats`, the one point walk — the recorder accumulates
through the same code, so live and stored totals can't drift) when a track is *finished, merged,
split, imported, retyped, or has its overrun re-derived*. Those are the only events that change a track's
points — so when the *walk* changes instead, `TrackRepository.sweepStats` re-walks the history,
driven by `TrackStats.RULE_VERSION` exactly as the edge-stay sweep is driven by its detector's.
Bumping that version is part of changing what the walk counts (currently: the leg spanning a
segment break is travel — nothing teleports, so ground between two trusted fixes was covered
whether or not the recorder was watching). This is a performance invariant, not a
convenience: Room invalidates per
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
discarded-track retention purge and the two versioned sweeps (edge stays, then track stats), none
of which is one — a sweep is standing infrastructure that re-runs whenever its rule's version
moves, not a pass to delete.

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
vector basemap** (dark or light flavor following the app theme): **the track is one line feature per
run of same-colored fixes**, colored by the selected metric (ramp luminance also theme-dependent),
start/end and noisy-fix markers sit on a symbol layer, and switching the color metric rebuilds the
line's source without moving the camera. Deliberately not a `line-gradient`, which positions color
along the line's *length* where every other reading of the metric — the graph beside it above all —
is positioned along its *time*, so a slow stretch shrinks to nothing on the map while filling the
graph. The banded ramp, the source's simplification tolerance and the layer's round caps are one
mechanism serving that and none survives being tidied alone; `trackLineFeature` says why.
Two more layers ride on the same map — the detected in-track stops as
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
- **Never resize a `MapView` — give each screen its own.** In texture mode it scales its
  last-rendered frame into the new box until it has one of its own, so a marker visibly stretches;
  on a place map the pin becomes an oval. There is no fix from inside a shared map: hiding what is
  drawn is too late (the frame being scaled was rendered *before* the hide), animating the height
  makes it every frame instead of one, and sequencing against `OnDidFinishRenderingFrame` spares
  the markers but not the basemap and costs two frames of latency. Two screens that want the map at
  different sizes are two layers with a map each. A fresh map means a fresh camera, and that is the
  point rather than the price: an editor should open framed on what it edits. **The place detail
  screen carries no map** — one small enough to sit above the visits is framed on the capture circle,
  which cannot say where a place is, and repeats the category glyph already on the screen; both
  questions belong elsewhere (`PlaceEditScreen` for the area, the maps-app action for where), so it
  is the visits that get the room.
- **Marker symbol layers draw in source order** (`symbolZOrder(SYMBOL_Z_ORDER_SOURCE)`, set once on
  the shared `markerSymbolLayer` base). The default stacks point symbols by screen position, so the
  feature a collection deliberately appends last — a place's pin after its dots, a track's start/end
  after the rejected fixes around them — is otherwise covered by whatever happens to sit lower.

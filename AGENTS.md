# AGENTS.md

Breadcrumb is a single-module Android app (Kotlin, Jetpack Compose) that keeps a day-by-day timeline
of where you have been — trips, stays and named places — recorded in the background with no start
button. `README.md` is the user-facing overview.

This file holds what no single source file carries: rules that span files, environment gotchas, and
the decisions behind them. Where one file's KDoc already carries the reasoning, the rule is stated
here once with that file named. Codex reads only the first 32 KiB of this file, so it stays under
that size.

## Working in this repo

### Builds

- The Gradle daemon's JDK is pinned (`gradle/gradle-daemon-jvm.properties`), so setting
  `JAVA_HOME` never fixes a build.
- Gradle and AGP are pinned as a compatible pair: move both or neither.
- **`installPerf` is the build to put on a phone**: debug without `debuggable`, whose slowdown reads
  as a regression that isn't there, installed over the debug app with the history intact. Install
  debug only for the `run-as` database pull and the Compose tooling; `installDemo` is the screenshot
  build. `app/build.gradle.kts` carries each build type's rationale.

### Lint

ktlint and detekt both run in CI, and each config states which rules it turns off and why
(`.editorconfig`, `config/detekt/detekt.yml`). **Re-enabling a disabled rule is a style decision to
raise with the user**, not a cleanup. A refactor that moves code `app/detekt-baseline.xml` holds can
resurface its entry as new, which is intended.

**The build's "ReportingExtension.file(String) has been deprecated" warning stands deliberately**:
the detekt pin in `build.gradle.kts` states why and when it moves.

### Testing on the device

Hands-on testing is the user's job. For a change that needs a device:

1. Verify what you can without it: build, run the unit tests.
2. Install `installPerf` on the connected phone.
3. Hand off a short test plan: where to go, what the change should look like, and what would show a
   regression. Concrete tracks and places found in the device data beat generic instructions, and
   read-only adb — logcat, a copy of the database — is fine for finding them.

**Pull all three database files**: Room runs in WAL mode, so a recent commit can still sit in
`tracks.db-wal`, and a lone main file reads as though it never happened. A pull needs `installDebug`
first.

```bash
for f in tracks.db tracks.db-wal tracks.db-shm; do
  adb exec-out run-as io.github.valeronm.breadcrumb.debug cat databases/$f > $f
done
```

Opened from the directory holding all three, `tracks.db` replays the log. A track still missing its
`endedAt` after that is genuinely dangling: a process death mid-recording, which an install causes.

**Don't launch or drive the app on a physical phone** (`am start`, `input tap` or swipe): there is
no knowing whether the user is using the phone at that moment, and injected input lands on top of
whatever that is. Take a screenshot only when the user asks for one, since `screencap` captures
whatever is on screen, other apps included. On an emulator, driving the app is fair game.

## Tests

Unit tests run on the host only; there are no instrumented tests. `assembleDebug` does not compile
them, so run `:app:testDebugUnitTest` after touching anything they cover. Room runs in them through
Robolectric (an in-memory database, the `TestDb` fixture), so the repository's database rules are
covered without a device. Screens, the map and the recorder's live behavior are verified on a device
or an emulator: arming and the notification need only the app running, and activity recognition
needs real movement or an emulator route.

What each harness can answer, and how to ask it:

- **A domain rule is tested through the params that ship**, such as `EdgeStayDetector.BRIEF_STOP`,
  never the `Params()` defaults no production path uses: a suite pinning those passes green through
  any change to the numbers the recorder runs.
- **The recorder's loops are tested off the device.** `FixIngestTest` and `ActivityIngestTest` drive
  the two cores on a plain JVM and assert on what each returns: the rows and verdict a batch of
  fixes produced (`Ingested`), and the `Effect` list a reading produced, which reads as the sequence
  the recorder should perform.
- **Every suite that writes the stored derivation's rows asserts through `DerivedConsistency`**,
  whose KDoc states which of its two strengths a case is owed. A rebuild must always restore the
  exact one, which keeps the weaker one from becoming a place to hide a bug.
- **A backup suite chooses which side of the export's grid its values sit on**: on it to assert
  equality across the round trip, off it to ask what the rounding does. `BackupImporterTest` and
  `BackupRestoreTest` show each.
- **Robolectric's CLDR is not the device's ICU**, so a locale *rendering* read out of a test run can
  be green in CI and wrong on every phone: one skeleton through one formatter has given a different
  day-period spelling under each. The harness answers whether the pattern reaches the formatter, not
  what a language writes. For day periods, month names, separators and decimal marks, take the answer
  from a device screenshot and say which source a stated format came from; in a suite, assert the
  shape (has a letter, has two digits), never a literal lifted from a Robolectric run.
- **`TimelineRowTest` reads composed rows back off the semantics tree**, the one place a string that
  is missing, misnamed or wired to the wrong row fails; its KDoc carries the rules a case follows.
  It samples while `ResourceHygieneTest` is total over the string table, so prefer a total rule
  there. Enum-to-resource mappings need neither: they are exhaustive `when`s.
- **Better still, take the decision out of the row.** A rule stated as a value (`ui/StayBounds.kt`)
  is total over its cases on a plain JVM, leaving the row test only the rendering. A row composed in
  a test is `internal` rather than `private`; don't widen one without covering it.

### Database schema

**v18 is the schema floor**: an older database fails to open rather than migrating, and the KDoc on
`AppDatabase.MIGRATIONS` states why, including why no destructive fallback belongs in the builder.
**Where the floor sits is a fact about the installs, not a preference**, since a floor above an
install's schema version bricks it on the next update. Before raising it, find the commit each
install came from and read the `version` declared there: `adb shell dumpsys package <id> | grep
versionName` for an uploaded build, whose release tag is `v` plus that name, and the recorder's
arming line (logcat or Settings → Logs), which stamps the commit, for a build that never shipped.

Room exports the schema to `app/schemas/`, one JSON per version, committed so a schema change
reviews as a diff; never prune them. A migration test owes two things.
**Shape**, that the chain reaches what Room builds from the entities, is asserted on the last
migration's test and moves there as the chain grows. **Carriage**, that the rows came across, is what
Room checks nothing about, and is why every rebuild-and-copy migration owes a test. The *before*
schema is frozen hand-written SQL with no entity or DAO imports. `MigrationTestHelper` over the
exported JSON is the direction for a start version that has one (it would want
`androidx.room:room-testing`); nothing below v19 was exported, which is what `MigrationDb` is for.

### Running the Room tests on arm64

Robolectric's native runtime has no Linux arm64 build in any published version, so on an arm64
machine every Room-backed test fails with an architecture assertion whatever the change.
`-PqemuJdk` forks the test worker into an x86_64 JVM under qemu:

```bash
./gradlew :app:provisionQemuTestJdk          # once per machine; the build names any missing package
./gradlew :app:testDebugUnitTest -PqemuJdk   # --tests "*TrackRepositoryTest" iterates on one class
```

Without it, CI is where those tests run. `app/build.gradle.kts` carries the constraints that shaped
the setup beside the code each one explains. It is no second opinion on CI either: it runs the
same x86_64 code CI runs, so a green local run means the tests pass, not that they would pass on
some third platform.

## Architecture

### Recorder

- **`LocationRecordingService` is the single source of truth for recording**, a foreground service
  of type `location`. A broadcast hands work to the live instance in the process and never starts
  the service, except where the platform allows a background start: the boot and update re-arm
  (`BootReceiver`) and the watchdog's self-heal (`WatchdogReceiver`).
- **Decisions are made in two cores with no Android in them**: `FixIngest` for fixes, returning
  the rows to store and the verdict (`Ingested`), and `ActivityIngest` for readings, returning the
  `Effect`s the service performs. New recorder behavior goes into a core with a case in its suite.
  `PackageBoundariesTest` checks what the cores may depend on. The invariants binding a core to its
  dispatcher — every effect run, in order; `EnsureGps` asking for a state rather than commanding a
  transition — are stated on `Effect`.
- The service's platform surface is split into a class per concern (`RecorderNotifications`,
  `WatchdogAlarm`, …) so the service reads as wiring. They wrap final platform classes, so the
  split buys readability, not testability.
- **A receiver holds its broadcast open (`goAsync`) until the service has applied what it carried**;
  each receiver's KDoc states what breaks otherwise.
- **A stop closes the track; there is no paused state anywhere**, because a trip nothing shows
  reads as a trip the app missed. Whether returning movement continues it is asked of the *stored
  history* (`StitchRule`, through `TrackRepository.openOrStitch`), never of anything the recorder
  held.
- Live state reaches Compose through `TrackingStatus`, a process-wide `StateFlow`, never by binding
  to the service.
- **A transition registration can go silently deaf, and re-registering does not cure it**
  (`ActivityRecognitionManager.restart`; the remedy is a reboot, and `StaleReadingOracle` detects
  it). That is no rule against re-registering: the watchdog re-*requests* the registration every
  tick, and that replay is what the detection reads.

### Movement witness

- **Activity Recognition describes the user's body, not the journey**, so `MovementConfirmer` reads
  the ground's movement off the position stream as a second witness. It is always on: a STILL
  mislabeled aboard a moving carrier costs the whole trip.
- **Every consultation takes the verdict as a `Motion.Unknown`-defaulted parameter, and its
  `Unknown` case is the behavior from before the witness.** A consultation that can't be expressed
  that way is a design smell, not a workaround. `ActivityGate`'s corroboration hold is the
  deliberate exception: while GPS runs, a STILL the ground cannot vouch for is held rather than
  applied. Each consulting rule's suite keeps its pre-witness cases unedited above a divider, which
  pins the fallback. No list of consultations is kept; they are wherever a `Motion` is read.
- **A track opened by a departure trigger has no reporter whose stop will end it**, so a standstill
  the witness proved (`ArrivalWatch`) or the no-fix give-up closes it. A track a reading has named
  is closed by neither, since its reporter lags but delivers.
- **When a reading-opened track gives up on GPS, the departure triggers take the ground**, and
  leaving the spot resumes GPS on that track. That is what lets a foot track give up quickly when no
  fix arrives at all (`NoFixGuard`): a walk that starts under a roof comes back when the phone
  leaves the building.
- **The witness names movement, never what is moving.** A track it proves carried gets `UNKNOWN`,
  shown as "Moving". There is no carrier classifier and none is planned: telling a ferry from a train
  from a bus needs map geometry the app does not carry, and speed alone cannot tell a bus from a
  car, so a specific label would be a guess that nothing downstream could tell from a measurement.

### Domain layer

`domain/` depends only on the Kotlin and Java standard libraries and the Room rows, and is
unit-tested. New behavior goes there first, with a test, before it is wired into the service or the
UI. **Domain functions take the Room entities directly** rather than a mapped model: the point walk
runs over millions of rows, and a per-row mapping allocation buys nothing but layering. **`db`
depends on nothing else in the app**, since every reader of a row takes whatever `db` imports with
it: a `db` that imports `domain` makes the two one unit, and entities carry no domain defaults for
the same reason. `PackageBoundariesTest` checks the `domain` and `db` boundaries on the compiled
classes. A value persisted as a code — an activity, an ignore reason, a place category, a writer —
is a closed set of permanent codes stored raw and mapped in the domain.

### Stored derivation

- **The timeline's stays are stored, not derived on read** (`DerivationStore`). The derivation runs
  where a track changes, so showing a day is a query, and a reader outside the app's own screens can
  ask what a day held without walking the history.
- **Two passes write those rows and must agree**: `rebuild` over the whole history and
  `StayLedger.reknit` over the rows one change reaches. They share one rule
  (`StayDeriver.verdictBetween`, `Agreement`), and `StayLedger` states where they may diverge.
- **Every path that changes tracks owes the derivation a call, and a new path has to decide
  which**: `reknit` with the ids it touched, inside its own transaction, for a change that moves a
  known stretch; `reconcile(stale = true)` for one that is historical or out of order, such as an
  import or a restore; `reconcile` for a change to the seeds.
- **`DerivationStore.read` is the only way to read the derivation**: it appends the trailing stay,
  which cannot be stored.
- **The derivation carries no record of the app's own attention**, since that describes the app
  rather than where anyone was. The rule is wider: **no surface renders epistemic state the user
  cannot act on.** A stay whose middle went unattested looks identical to one observed throughout,
  with no "estimated" and no confidence tint, and a surface exposing a derived fact states what is
  known and stays silent on how well it is known. Reintroducing an app-facts surface is a design
  decision to raise, not a gap to fill.
- **A screen does not wait for the derivation**: `TrackListViewModel` dresses every reading with a
  place write (`withPlace`) until the lists the screens read carry it.

### Places

- **A place row holds what was said about a spot, and everything else about a place is derived on
  read** (`Place` in `db/Entities.kt`).
- **A category feeds nothing on the way to a stay, and a name only by being there**
  (`PlaceClusterer.seedOf`).
- **A track's end can be stated to a place** (`startPlaceId`, `endPlaceId`), and then joins that
  place's cluster whatever its distance from the pin. A statement is evidence the capture radii
  cannot weigh, so only a writer holding such evidence sets one: the Google import and the user.
- **The category vocabulary is closed** (`PlaceCategory`): every entry owes a glyph (`ui/Glyphs`)
  and a group, which the color coding reads (`ui/Palette.kt`).
- **`PlaceEditScreen` owns everything the user says about a place**: name, capture radius and pin,
  written as one row. The detail screen reads and does not edit, bar the category chips.

### Tracks

- **An ignored point is one that isn't part of the path** (`IgnoreReason`). Every writer of a
  track's points enters through `TrackSettle.settle`, and `HistorySweeps` re-settles the history
  when the rule's version moves.
- **A track's clock is its first and last good fix**, never the transition that opened or closed
  the row (`TrackBounds`).
- **The track row carries its points' aggregates, and a recorded fix never writes it**, so a fix
  never re-runs the observed queries, which read `tracks` only; `TimelineInvalidationTest` fails if
  either half breaks. The UI collects with `collectAsStateWithLifecycle` for the same reason: a
  backgrounded Compose tree keeps collecting otherwise.
- **A track's writer is declared by whoever inserts the row, not measured** (`TrackOrigin`), and no
  "mixed" writer exists: a merge across writers is refused.
- **An edit to a manual track (`updateManualTrack`) is deliberately not undoable**: every value is
  in the form, unlike a merge or a delete.
- **A trip is what the reader sees, and it can be several tracks** (`Trip`, `TripChains`): tracks
  joined end to start and of one type are one timeline row, one page and one swipe to delete. They
  stay separate tracks because no merge crosses writers, and a different type keeps them apart, the
  user's word that it was another trip.
- Splitting a track at a detected stop is designed but not built; the user's own cut ships.

### Journeys

**A journey is a run of nights away from home, and every rule about one is a rule about placing a
night** (`TravelDeriver`). **What a journey is called is decided apart from what it is**
(`TravelNaming`). Each KDoc carries its rules.

### History operations

- **A backup holds every kept track's fixes and the places on a grid** (`BackupExporter` states
  which and why), so a restore returns the fixes rounded. A column added to `track_points` lowers
  the export's read budget (`BackupExporter.FIXES_PER_READ`), and `Entities.kt`, where the column
  would be added, does not mention it.
- **Operations on the whole history exclude each other** — restore, the imports, clear, the exports,
  and a restore from Recently deleted — and each starts by claiming the one slot in
  `ImportExportController`. An export beside a clear would write a file missing what the clear took.
- **The Google Timeline import keeps Google's grouping of visits into places** by stating each trip
  end to its visit's place. The user confirmed or corrected that grouping in Google, and a path
  sampled once a minute cannot recover it. Every place row the import writes is one it created.
  A place an earlier import made is found again by Google's id; beyond those, only a home or a work
  is matched to a place the history already holds, since a category is the only other evidence on
  both sides that two pins are one place (`GoogleTimelinePlaces`).
- **The backup format also feeds the web viewer** in `site/src/viewer/`, which ports `StayDeriver`
  and `PlaceClusterer` to `js/stays.js` and is tested case for case against `StayDeriverTest`: a
  rule that moves here moves there.

### Backfills

When a new rule must reprocess existing rows and a Room migration cannot express it, add a
repository pass and run it from `App.onCreate`'s IO coroutine behind a `Settings` done-flag. Make
the pass idempotent, since a crash between the work and the flag write
re-runs it. Once the installed base has run it, delete the pass, its flag, and any DAO query only it
used; `git log --grep=backfill` holds worked examples. **No backfill is live now.** The versioned
sweeps (`HistorySweeps`) are not backfills: they run again whenever their rule's version moves, and
`App.onCreate` states why their order before the derivation is load-bearing.

### UI

- **A permission is asked at the intention that needs it**, never at launch: turning recording on
  runs the setup ladder, and a map's My location button asks for plain location. The two share one
  record of what was asked (`Settings.askedPermissions`), so they cannot disagree about whether
  Android will still ask. `ui/Setup.kt` holds the whole flow and the rules it follows. What setup
  still owes shows only on the Record tab's card. Every step is required, and `complete` is the
  whole condition for arming.
- **The all-the-time location half is requested, never navigated to**: asking for it makes Android
  open the page holding "Allow all the time" itself; `util/Permissions.kt` states why each
  alternative fails.
- **No top-level `val` in a `ui/` file may reach the Android framework eagerly.** Kotlin compiles a
  file's top-level declarations into one class initializer, so a single eager `android.graphics`
  call makes every pure function in that file unloadable from a plain JVM test (`untaggedPinColor`
  is `by lazy` for this reason).
- A screen gets a file of its own, named for its composable, and cross-file symbols are `internal`
  rather than `private`.
- **A write launches in `TrackListViewModel`'s `scope`, not `viewModelScope`**: its handler logs a
  failure and tells the reader, where an exception on `viewModelScope` ends the process. A write an
  undo awaits goes through `launchUndoable`, not `async`.

### Maps

- **The shared map owns each map's frame and corners** (`MapLibreStyledMap`): a screen hands it its
  fit, when to refit and what the fit covers, and its corner content as slots, since overlays placed
  by hand in a map's corners have nothing keeping them apart. The live preview follows the
  recording instead (`MapLibreTrackMap`).
- **Never resize a `MapView`; give each screen its own** (`PlaceEditScreen`'s KDoc has the
  argument).
- **A track is drawn as one line feature per run of same-colored fixes**, deliberately not as a
  `line-gradient`: the banded ramp, the source's simplification tolerance and the round caps are one
  mechanism, and none survives being tidied alone (`trackLineFeature`).
- **The stops, the overrun and the end connector are deliberately unlabeled.** Their meaning is in
  where they sit and how they are drawn, and a panel of times over the route says less while covering
  the map. Labeling them is a decision to take, not an oversight to fix.
- **The basemap styles are bundled** (`assets/protomaps-dark.json`, `protomaps-light.json`): the
  hosted API's official flavors verbatim, with the tile URL's key replaced by `{PROTOMAPS_KEY}`. To
  refresh one, re-fetch `https://api.protomaps.com/styles/v5/{dark,light}/en.json?key=…` and
  re-apply the placeholder.

## Product and copy

**The recorded history is the product's first purpose and GPS tracking its second**, so any copy —
store listing, release notes, a post — leads with the timeline, the stays and the places: when and
where you have been, which places you stop at, how often you return. The tracks are valuable in
their own right, and they are also how the history is collected; automatic recording is how that
happens without a start button, which is not the proposition. The code leads with the recording
pipeline, so copy written from the source drifts into pitching a tracker whose selling point is
starting by itself. The README has the order right, so take it from there.

### Site

`site/` is the app's website at `breadcrumb.place`, an Astro project on GitHub Pages; the companion
viewer is a page of it, with its own README in `site/src/viewer/`. **The viewer's Protomaps key is
readable by anyone who opens the page**, so it is a key of its own (`pages.yml`). Every package in
`site/package.json` is a development dependency, because nothing in `node_modules`
reaches a visitor, and advisory alerts are filtered by that scope. The custom domain is a Pages
setting rather than a `CNAME` file, since the Actions deploy carries none.

**The site is written for a person deciding whether to put the app on their phone**: an Android
user, not a developer, who asks where location data goes before installing, because the app's
proposition selects for exactly that reader. They come for four things: what the app is, what it
looks like, what happens to their data, and how to get it. Every sentence answers one of those or
goes. An existing user returning for the viewer or the privacy page, and Google's review checking
the privacy-policy URL, read the same pages and need nothing more. Developers go to the repository,
which the site links and then stops; nothing on it names a build type, a script, a tool or a
mechanism, and nothing explains how the site or its screenshots were made. It is informational, not
a pitch. The privacy page is the policy Play's listing points at, so its URL is fixed.

**The copy is plain help-page prose, because that reader scans for answers rather than reads.** The
reader is *you* and the actor is *the app* or *your phone*. A sentence opens on its subject, carries
one fact, and stops; two facts joined by a semicolon are two sentences, and a merged pair is what
the copy has read as machine-written on. No inverted clauses ("Trips a short stop split can be
merged"), no appositions or balanced pairs, and no derivation's point of view: "when your stays
keep landing in the same spot, the app makes it a place", not "stays that keep landing in the same
spot form a place". The code's words stay in the code — *capture radius*, *fix*, *filter* — and the
reader's stand in for them. Copy written from the source or the README carries their habits, so
rewrite it rather than trim it.

**The privacy page and the landing page weigh correctness differently.** The privacy page is a
contract: every claim on it must hold against the code, and a qualifier that keeps a sentence true
("unless your Android backup holds a copy") stays however it reads. The landing page must be
correct too, but a fact is stated only as exactly as the reader needs it, and never as mechanics:
"the year behind it" over "the twelve months before it", a watch for leaving where you stopped over
a geofence and a probe. When accuracy and simplicity pull apart on the landing page, simplicity wins
as long as the sentence is not false.

**The privacy page describes the released build, not `main`.** The site deploys on every push, so a
page edited beside a feature is live while every installed app still behaves the old way, and the
feature can change again before it ships. A change to what the app collects, logs or sends is
written into the page in the release's version-bump commit.

### Releases

A Play release — the version bump, building the bundle, the "What's new" text — follows
`docs/release-notes-guide.md`, which also states which part of the version to bump. After the Release
workflow has attached the `.aab` to its GitHub Release, append the "What's new" text to the release
body with `gh release edit`, under the generated provenance line, then download the `.aab` and
upload it to the Play Console by hand with the same text. **A build's identity is three facts kept
apart** (`util/BuildIdentity`): no variant suffix on the `versionName`, and no sha anywhere a reader
sees.

## Conventions

- **No recorded history reaches the repo, and nothing in it refers to whoever recorded it.** Most of
  the tuning here rests on field evidence, and citing it is right, but comments, KDoc, commit
  messages and `docs/` carry what the data *showed*, never which trip showed it: no recording dates
  or times, no place names or real coordinates, no per-trip distances, durations, point counts or
  quality figures. "A parked phone can report phantom Doppler up to 3.5 m/s", not "an arrival 7 m
  from the track's final position on such-and-such a date". The person behind the history is never
  a subject either: no "the author", no "my phone", no note explaining what a choice does or doesn't
  reveal about anyone. A sentence like that is itself the disclosure, and it is the one a stranger
  reads first. History-wide aggregates are fine while they name no date or place ("an end stay on
  ~30% of tracks, median ~72 s"), and so are generated test fixtures, whose coordinates are
  invented. Fixtures sit at a neutral origin, since real coordinates leak a region even when no
  trip is named. The repository is the one artifact that leaves the machine; the recorded history
  stays on it.
- **Don't count a set that can grow** in comments, KDoc, `docs/` or this file. "The three codes",
  "the two rows below": a number in prose is a claim nothing checks, and it rots the first time an
  entry is added or removed. Either describe the set without counting it, or name every member.
- **User-facing text is a resource, and the layer that decides what to say holds no words.**
  Strings live in `res/values/strings_<screen>.xml`; Android merges the files, so a name must be
  unique across them. `domain/` and `util/` hold no language at all: an enum carries a code the UI
  maps to a `@StringRes`, and a rule that needs a sentence takes a vocabulary interface the host
  implements (`RecorderVocabulary`, `UnitSymbols`, `DurationSymbols`), which keeps those suites on a
  plain JVM.
- **Each recurring concept has one word per language**, recorded in `docs/glossary/` with `en.md`
  canonical. Write new strings from it, review string changes against it, and record a new term or
  exception there rather than deciding silently.
- **Never assemble a sentence from parts.** No `"$verb $noun N of M"`, no `if (n == 1) "visit" else
  "visits"`, no lowercasing a noun to slot it mid-sentence: word order, agreement and case are the
  language's, not the caller's. One whole phrase per case, and `<plurals>` for anything counted. A
  value no format string can carry still goes where the translation puts it
  (`annotatedStringResource`), and a word used both alone and inside a sentence is authored twice
  (`labelRes`, `inlineLabelRes`). `ResourceHygieneTest` pins the rules the compiler can't.
- **Dates and measures follow the locale; units follow the country.** Nothing a plain-JVM test can
  call may format a date, since formatting reaches `android.text.format.DateFormat`
  (`ui/LocaleWords.kt`). The hour cycle is the exception, a device setting rather than a locale's
  preference: `ReaderClock` is the app's only clock format.
- **Logs are never localized.** `DebugLog` text, its tags, and the operation names handed to it stay
  English whatever the device language. Settings → Logs showing them in the app does not make them
  interface text: a log line sits beside untranslated platform exception messages, and a fault that
  greps differently on two phones is worth less than one that reads awkwardly on one. Notification
  and screen text beside a log call is the opposite: a user surface, translated.
- **The network carries map data and two deliberate exceptions**, each behind its own switch in
  Settings → Privacy: the online place search and the online road routing. Everything else is
  local, and there is no server sync. **The coordinate a search sends is a pin —
  one the user placed, or the pin of the place being named — never wherever the map happens to be
  looking**; sorting rows the device already holds by the map's center discloses nothing, which is
  why the add-trip form's own place list may. Routing sends a trip's two points, and only when
  asked. The ODbL, GeoNames and routing credits are license requirements, and the routing's data
  credit sits in every map's corner rather than behind MapLibre's attribution button, because OSM's
  guideline wants it read without a tap (`MapCredit`).
- **The Protomaps key is not committed.** A fresh checkout needs `protomapsApiKey=…` in the
  gitignored `local.properties`, or the basemap won't load.
- **The city atlas and its per-language name tables are bundled**, packed in one run by
  `tools/pack_cities.py`, whose docstring holds the format and the choices behind it. The packer
  drops the rows the data marks as parts of a town (`PPLX`); a district under an ordinary
  settlement code is told from a town by `CityAtlas`'s naming heuristic.
- **Activity recognition needs Google Play Services**, so this is deliberately not a FOSS or F-Droid
  build.
- **`applicationId` is permanent once published.** The FileProvider authority derives from it, and
  code reads the package from `packageName` rather than a literal.

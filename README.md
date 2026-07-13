# Breadcrumb

An Android app that **automatically records GPS tracks in the background** based on your detected
activity — walking, running, cycling, or driving. Arm it once and forget it:
it records while you move and pauses while you're still. Everything stays **on your device** — no
account, no server — with a dark vector map, a day-by-day timeline of your trips and stays, named
places, and GPX import/export.

## Features

- **Activity-aware recording** — uses on-device activity recognition to start a track when you
  start moving, switch tracks when your activity changes (e.g. walking → driving), and pause when
  you're stationary. A brief stop stitches back into the same track instead of splitting it (the
  resume window is configurable). Recognised modes: walking, running, cycling, and driving; a
  recorded track can be manually reclassified as taxi (passenger) afterwards.
- **Truly autonomous** — flip *Auto recording* on once; it keeps working with the screen off or
  the app closed, survives reboots, and resumes after the system kills it.
- **Battery-conscious** — GPS only runs while you're actually moving, and a no-fix guard drops GPS
  during false "moving" detections that never get a fix. Uses the raw GPS provider by default
  (fused/network positioning is an optional toggle for indoor recording).
- **Timeline** — the Timeline tab reads like a diary: trips and the stays between them, grouped by
  day (Today / Yesterday / date).
- **Places** — recurring stays cluster into places you can name (home, work, the gym). A dedicated
  Places tab shows them all on a map and as a sortable list, each with visit stats and an
  adjustable capture radius.
- **Rich track map** — tap a track to see its route on a dark vector map, coloured per point by
  speed, elevation, GPS accuracy, or satellite count, with start/end markers, noisy-fix markers,
  a metric chart, and distance / duration / average-speed stats.
- **GPX import & export** — import `.gpx` files (via the picker, a share target, or opening a
  `.gpx` file); export a single track from its page, a whole day from the day header, or everything
  to a folder of `.gpx` files.
- **Configurable** — tune sampling (min time / distance between points), point-quality gates, and
  the minimum duration / length required to keep a track.
- **Material You** — follows your system light/dark theme and accent color, edge-to-edge, with
  Android predictive back.

## How it works

- **Activity Recognition Transition API** (Google Play Services) detects when you start/stop
  walking, running, cycling, driving, or going still. A one-shot snapshot on arming starts
  recording immediately if you're already moving.
- A **foreground service** (`LocationRecordingService`) keeps recording while the app is in the
  background. A persistent notification is mandatory on modern Android — there is no truly
  invisible always-on location option. The service checks location permission before starting, so
  a revoked permission falls back to the in-app prompt instead of failing.
- **GPS sampling** uses the platform `GPS_PROVIDER` by default (Play Services' fused provider is
  selectable in settings for indoor/network positioning). GPS runs only while moving.
- Each continuous stretch of movement becomes one **Track** of **TrackPoints**, stored in
  **Room** — related activities (walking ⇄ running) share a track, and a stop shorter than the
  resume window stitches back into it instead of splitting. Tracks
  that fail the configured keep-thresholds (e.g. too few points) are discarded automatically,
  including any left dangling by a crash.
- **Stays and places** are derived from where consecutive tracks begin and end, plus a liveness
  log that distinguishes real stays from gaps where the app wasn't recording. Named places persist
  and label the timeline.
- The map is **MapLibre GL Native** on a bundled **Protomaps dark vector basemap**; the track is a
  colour-gradient line recoloured in place when you switch the metric.
- **GpxExporter / GpxParser** write and read GPX; exports share via `FileProvider` or bulk-write to
  a folder you pick via the Storage Access Framework.

## Permissions

On first launch the app asks for:

1. **Location** (precise) and **Physical activity**.
2. **Background location** — must be set to *"Allow all the time"*. On Android 11+ this is only
   grantable from the app's system settings page, so the button opens it there.
3. **Notifications** (Android 13+) for the ongoing tracking notification.
4. **Ignore battery optimizations** (prompted when armed) — recommended so the OS doesn't kill
   background recording.

## Build

The build runs on **JDK 21 automatically**, whatever your system default JDK is: Gradle's daemon
JVM is pinned to Java 21 via `gradle/gradle-daemon-jvm.properties` (auto-provisioned if no JDK 21
is installed). No `JAVA_HOME` override is needed.

```bash
./gradlew :app:assembleDebug   # build the debug APK
./gradlew :app:installDebug    # build + install on a connected device / emulator
```

The debug APK lands in `app/build/outputs/apk/debug/` and installs as
`io.github.valeronm.breadcrumb.debug` (alongside a release install, with a distinct
blueprint-grid launcher icon).

A fresh checkout needs a **Protomaps hosted-API key** for the basemap to load: add
`protomapsApiKey=…` to `local.properties` (gitignored). Without it the map tiles won't render.

Unit tests cover the pure domain/data logic:

```bash
./gradlew :app:testDebugUnitTest
```

## Tech stack

Kotlin · Jetpack Compose (Material 3) · Room · Play Services Location (raw GPS + Fused + Activity
Recognition) · MapLibre GL Native on a Protomaps vector basemap · AGP 9.2.1 / Gradle 9.6.1 ·
single-module.

## Testing activity switching

Activity recognition needs **real movement** (or a route played through an emulator's extended
controls → Location → Routes) to fire transitions. While stationary the app shows
*"Paused — waiting for movement"*; start walking or driving and a track begins automatically. You
can also import a `.gpx` file to populate tracks, places, and the map without moving.

## Notes & limitations

- **Privacy:** all data is local. Nothing is uploaded; there's no analytics or account. The only
  network use is fetching Protomaps map tiles (and glyphs/sprites) for the in-app map.
- **Play Services dependency:** activity recognition relies on Google Play Services, so this isn't
  a fully FOSS / F-Droid-friendly build.
- **Single device / single user:** no multi-device sync (server upload to e.g. Dawarich/OwnTracks
  is a possible future addition).

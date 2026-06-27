# Breadcrumb

An Android app that **automatically records GPS tracks in the background** based on your detected
activity — walking, running, cycling, or driving. Arm it once and forget it: it records while
you move and pauses while you're still. Everything stays **on your device** — no account, no
server — with an OpenStreetMap view and GPX export.

## Features

- **Activity-aware recording** — uses on-device activity recognition to start a track when you
  start moving, switch tracks when your activity changes, and pause when you're stationary.
- **Truly autonomous** — flip *Auto recording* on once; it keeps working with the screen off or
  the app closed, survives reboots, and resumes after the system kills it.
- **Battery-conscious** — GPS only runs while you're actually moving; sampling cadence is
  configurable.
- **Map view** — tap a track to see its route on an OpenStreetMap map, with start/end markers and
  distance / duration / average-speed / point-count stats.
- **Organised tracks** — recorded tracks grouped by day (Today / Yesterday / date).
- **GPX export** — share a single track from its page, a whole day from the day header, or export
  everything to a folder of `.gpx` files.
- **Configurable** — tune sampling (min time / distance between points) and the minimum
  points / duration / length required to keep a track.
- **Material You** — follows your system theme and accent color, edge-to-edge, with predictive
  back.

## How it works

- **Activity Recognition Transition API** (Google Play Services) detects when you start/stop
  walking, running, cycling, driving, or going still. A one-shot snapshot on arming starts
  recording immediately if you're already moving.
- A **foreground service** (`LocationRecordingService`) keeps recording while the app is in the
  background. A persistent notification is mandatory on modern Android — there is no truly
  invisible always-on location option.
- **FusedLocationProvider** samples GPS while moving and stops while stationary.
- Each continuous activity becomes one **Track** of **TrackPoints**, stored in **Room**. Tracks
  that fail the configured keep-thresholds (e.g. too few points) are discarded automatically,
  including any left dangling by a crash.
- **GpxExporter** writes tracks to GPX and shares them via `FileProvider`, or writes them into a
  folder you pick via the Storage Access Framework.

## Permissions

On first launch the app asks for:

1. **Location** (precise) and **Physical activity**.
2. **Background location** — must be set to *"Allow all the time"*. On Android 11+ this is only
   grantable from the app's system settings page, so the button opens it there.
3. **Notifications** (Android 13+) for the ongoing tracking notification.
4. **Ignore battery optimizations** (prompted when armed) — recommended so the OS doesn't kill
   background recording.

## Build

Requires the Android SDK (platform 34, build-tools 34+) and a JDK in the **17–21** range
(very new JDKs may be too new for the pinned Gradle/AGP).

### Android Studio

Open the project folder and press **Run**. Studio uses its bundled JDK automatically.

### Terminal

```bash
# point JAVA_HOME at a JDK 17-21 if your default `java` is newer
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleDebug

# install on a connected device / running emulator:
JAVA_HOME=/path/to/jdk-21 ./gradlew installDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Tech stack

Kotlin · Jetpack Compose (Material 3) · Room · Play Services Location (Fused Location + Activity
Recognition) · osmdroid (OpenStreetMap) · AGP 8.7.3 / Gradle 8.9 · single-module.

## Testing activity switching

Activity recognition needs **real movement** (or a route played through an emulator's extended
controls → Location → Routes) to fire transitions. While stationary the app shows
*"Paused — waiting for movement"*; start walking or driving and a track begins automatically.

## Notes & limitations

- **Privacy:** all data is local. Nothing is uploaded; there's no analytics or network use beyond
  fetching map tiles for the in-app map.
- **Play Services dependency:** activity recognition relies on Google Play Services, so this isn't
  a fully FOSS / F-Droid-friendly build.
- **Single device / single user:** no multi-device sync (server upload to e.g. Dawarich/OwnTracks
  is a possible future addition).

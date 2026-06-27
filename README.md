# Activity GPS Tracker

A minimal Android app that records GPS tracks in the background, automatically starting and
stopping based on your detected physical activity (walking, running, cycling, driving). Tracks
are stored locally and can be exported as GPX.

## How it works

- **Activity Recognition Transition API** (Google Play Services) detects when you start/stop
  walking, running, cycling, driving, or going still.
- A **foreground service** (`LocationRecordingService`) keeps recording while the screen is off
  or the app is closed. A persistent notification is mandatory on modern Android — there is no
  truly invisible background option.
- **FusedLocationProvider** samples GPS, with a cadence tuned per activity (dense while driving,
  sparser while walking, paused while stationary).
- Each continuous activity becomes one **Track** of **TrackPoints**, stored in **Room**.
- **GpxExporter** writes a track to a `.gpx` file and shares it via `FileProvider`.

## Permissions

On first launch the app asks for:
1. **Location** (precise) and **Physical activity**.
2. **Background location** — must be set to *"Allow all the time"*. On Android 11+ this is only
   grantable from the app's system settings page, so the button opens it there.
3. **Notifications** (Android 13+) for the ongoing tracking notification.

## Build

Requires the Android SDK (platform 34, build-tools 34+) and JDK 17–21.

### Android Studio
Open the project folder and press Run. Studio uses its bundled JDK automatically.

### Terminal
Java 25 is too new for this Gradle/AGP; point the build at JDK 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug
# install on a connected device / running emulator:
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Stack

Kotlin · Jetpack Compose (Material 3) · Room · Play Services Location · AGP 8.7.3 / Gradle 8.9.

## Testing the activity switching

The activity recognition needs real movement (or a route played through an emulator's extended
controls → Location → Routes) to fire transitions. While stationary the app shows
"Paused — waiting for movement"; start walking/driving and a new track begins automatically.

# Breadcrumb Privacy Policy

_Last updated: 18 August 2026_

Breadcrumb ("the app") records GPS tracks of your movements so you can view
them later. It is designed around a simple principle: **your data stays on
your device.**

## Data the app collects and stores

- **Location.** The app records your device's location (including in the
  background, when you have enabled automatic recording and granted the
  "Allow all the time" location permission). Location history is stored in a
  local database **on your device only**. It is never uploaded, synced, or
  transmitted to the developer or to any server.
- **Detected activity.** The app uses Google Play Services activity
  recognition (walking, cycling, driving, …) to decide when to start and stop
  recording. Detected activity is processed on the device.

## Data that leaves your device

- **Map tiles.** When you view a track on a map, the app downloads map data
  (tiles, fonts, icons) from Protomaps (a hosted map-tile service). Like any
  web request, this reveals your IP address and the map areas you view to that
  service. Map requests are not tied to any account or identifier, and the app
  sends no location history with them. See the
  [Protomaps legal page](https://protomaps.com/legal).
- **Online place search** (optional). When you search for a place by name
  (today, in the add-trip form), the app sends the text you type to Photon
  (photon.komoot.io, a search service over OpenStreetMap data). Once the trip
  being entered has a start or end point, that one coordinate is sent too, to
  rank nearby results first — always a coordinate that is already part of the
  trip you are entering, never your live location or wherever the map is
  looking. As with map requests, no account or identifier is attached, and no
  other location history is sent. Online search is on by default and can be
  turned off in Settings → Online services; that page lists everything in the
  app that uses the network beyond the map.
- **Nothing else.** The app has no accounts, no analytics, no ads, no
  crash-reporting SDKs, and no server of its own. Naming your journeys and
  resolving time zones use a database of cities and towns bundled inside the
  app (GeoNames), not an online lookup.

## Sharing and export

Your history leaves your device only when **you** explicitly export or share
it — individual tracks as GPX, or a full backup file holding every kept trip
and your places. Where that data goes is then under your control.

## Data retention and deletion

All data lives in the app's private storage on your device. You can delete
individual trips in the app; deleted trips stay restorable in
Settings → Recently deleted for 14 days (still only on your device), then
are removed for good. Uninstalling the app deletes everything. There is
nothing to delete on any server, because nothing is stored on one.

## Permissions summary

- **Location (all the time)** — to record tracks, including in the background.
- **Physical activity** — to start/stop recording automatically.
- **Notifications** — the persistent notification Android requires for
  background location recording.

## App lock

The optional app lock uses your device's own screen lock through the system
prompt. The app never sees, stores, or transmits biometric data — the
device answers yes or no.

## Children

The app is not directed at children and collects no personal data beyond the
locally stored location history described above.

## Changes

If the app ever gains features that transmit data (for example, an optional
server sync), this policy will be updated first, and the change will be
called out in the release notes.

## Contact

Questions: open an issue at
[github.com/valeronm/breadcrumb](https://github.com/valeronm/breadcrumb/issues).

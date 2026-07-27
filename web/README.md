# Breadcrumb Viewer

A static, fully client-side companion viewer for [Breadcrumb](../README.md) backups: drop the
`breadcrumb-*.json.gz` file the app exports (Settings → Back up everything) and browse your
whole track history on a big map. Nothing is uploaded anywhere — parsing, storage (IndexedDB)
and rendering all happen in the browser; the only network use is the basemap.

## Running

No build step. Any static file server works:

```bash
cp config.example.js config.js   # add your Protomaps API key
python3 -m http.server -d web 8000
# open http://localhost:8000
```

(A server is required — module workers don't run from `file://`.)

## How it works

- `js/backup-parse.js` — incremental parser for the backup format (format v1): streams tracks
  one at a time so a multi-hundred-MB export never needs a whole-file `JSON.parse`.
- `js/import-worker.js` — off-thread import: gunzip (`DecompressionStream`) → parse → typed
  arrays per track (time/lonlat/ignore-reason/flags/speed/…) + a Douglas-Peucker-simplified
  overview geometry → IndexedDB. Import happens once per file; reopening the page is instant.
  Bumping `DB_VERSION` in `js/db.js` drops the stores, so the backup has to be dropped in again —
  they are a cache of the file, not a second copy of the history.
- `js/stays.js` — the app's stay derivation (`StayDeriver` + `PlaceClusterer` +
  `PlaceResolver.resolveClusters`), ported: where the user was *between* tracks, derived from the
  two things the backup already carries — every kept track's endpoints, and the liveness log. Two
  neighboring endpoints at the same place (same endpoint cluster, or within the agreement radius, or
  sharing a nearest named-place pin) make a **stay**; endpoints that disagree mean movement the
  recorder missed and make a **gap**. A stay is *observed* only where the liveness log attests the
  app was alive and armed throughout — an outage, a disarm, or history from before the log existed
  leaves it *inferred*. Derivation is "as of" the export's own timestamp, so the last stay is open
  as of the backup rather than growing every time the page is reloaded.
- `js/geo.js` — the distance seam that runs on, and its coordinate-box prefilter. WGS84 ellipsoidal
  (the same Vincenty inverse `Location.distanceBetween` runs on the phone) rather than a sphere
  approximation, so a borderline pair of endpoints can't cluster one way here and another there.
- `js/map.js` — MapLibre GL JS on the Protomaps basemap (same provider as the app): all tracks
  as simplified lines colored by activity, click or pick from the timeline for the full-resolution
  track. Selecting a track mutes the rest of the history to gray, along with every place the trip
  didn't start or end at. Selecting a stay instead frames its place — the capture circle and the
  endpoints the cluster captured — and a gap frames both of its sides at once, which is the picture
  that explains it: most gaps are one place clustered as two. Clicking a place marker frames it the
  same way.

  **The places drawn are the derived clusters**, the app's places map rather than the export's list
  of named pins: labeled markers for named places, small dots for the unnamed clusters the history
  keeps returning to. Two sidebar toggles, both remembered across visits and both the app's own
  rules: "Show places" drops the layer entirely, and "Rare stops" — off by default — adds the
  clusters below the notable-visit floor (fewer than 3 visits). A name doesn't exempt a place from
  being rare: one named on the strength of a single visit is exactly the clutter the toggle clears.
  A cluster nothing ever stayed at is never drawn either way — it exists only so a gap's side has
  something to point at.

  **Off-path fixes follow the app's conventions**, because the same fix reading two ways in two
  places is worse than either reading. An ignored point is off the path for one of two unrelated
  reasons, and they are drawn differently: fixes the recorder *rejected* (accuracy, speed jump, no
  satellite fix) are markers in the app's own legend colors, while the recorder's *overrun* — good
  fixes of a phone that had already arrived — is a grayed leg hanging off the path, anchored to the
  fix either side so it meets the line. A corner legend names whichever of those a selected track
  actually has. The path itself is one polyline: a segment break records that the recorder stopped
  watching, not that the phone stopped moving, and the app draws through it on the map for the same
  reason its distance counts that ground.

  The sidebar is the app's **timeline**, not a track list: tracks interleaved with the derived
  stays and gaps, newest first, each interval sliced at midnight so every row falls inside one day.

## Testing

```bash
node web/test/draw-test.mjs                                  # hand-built cases, no file needed
node web/test/stays-test.mjs                                 # ditto — the derivation's decision table
node web/test/parse-test.mjs <breadcrumb-export.json.gz>
node web/test/convert-test.mjs <breadcrumb-export.json.gz>
```

`stays-test.mjs` mirrors the app's own `StayDeriverTest` case for case, on the same flat-earth
distance stub — the two suites are how the viewer and the app are held to one answer about what
counts as the same place.

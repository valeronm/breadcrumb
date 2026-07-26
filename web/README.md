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
- `js/map.js` — MapLibre GL JS on the Protomaps basemap (same provider as the app): all tracks
  as simplified lines colored by activity, click or pick from the list for the full-resolution
  track. Selecting a track mutes the rest of the history to gray, along with every place the trip
  didn't start or end at; the sidebar's "Show places" toggle drops the place pins entirely
  (remembered across visits).

  **Off-path fixes follow the app's conventions**, because the same fix reading two ways in two
  places is worse than either reading. An ignored point is off the path for one of two unrelated
  reasons, and they are drawn differently: fixes the recorder *rejected* (accuracy, speed jump, no
  satellite fix) are markers in the app's own legend colors, while the recorder's *overrun* — good
  fixes of a phone that had already arrived — is a grayed leg hanging off the path, anchored to the
  fix either side so it meets the line. A corner legend names whichever of those a selected track
  actually has. The path itself is one polyline: a segment break records that the recorder stopped
  watching, not that the phone stopped moving, and the app draws through it on the map for the same
  reason its distance counts that ground.

## Testing

```bash
node web/test/draw-test.mjs                                  # hand-built cases, no file needed
node web/test/parse-test.mjs <breadcrumb-export.json.gz>
node web/test/convert-test.mjs <breadcrumb-export.json.gz>
```

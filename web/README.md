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
  arrays per track (time/lonlat/flags/speed/…) + a Douglas-Peucker-simplified overview
  geometry → IndexedDB. Import happens once per file; reopening the page is instant.
- `js/map.js` — MapLibre GL JS on the Protomaps basemap (same provider as the app): all tracks
  as simplified lines colored by activity, click or pick from the list for the full-resolution
  track with auto-pause segment gaps and ignored fixes marked.

## Testing

```bash
node web/test/parse-test.mjs <breadcrumb-export.json.gz>
```

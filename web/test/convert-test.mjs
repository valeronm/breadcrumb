// Parses a real export and converts every track, checking the typed-array shapes against the
// source data. Run: node web/test/convert-test.mjs <export.json.gz>
import assert from "node:assert/strict";
import { BackupParser } from "../js/backup-parse.js";
import { indexFields, convertTrack, FLAG_IGNORED, FLAG_SEGMENT_START } from "../js/convert.js";
import { loadExportText, feed } from "./helpers.mjs";

const text = loadExportText(process.argv, "node convert-test.mjs");

const t0 = performance.now();
let fields = null;
let tracks = 0;
let points = 0;
let overviewPoints = 0;
let ignored = 0;
let segments = 0;
const parser = new BackupParser({
  onHeader: (h) => { fields = indexFields(h.pointFields); },
  onTrack: (track) => {
    const { row, geometry, extras } = convertTrack(track, fields);
    const n = track.points.length;
    assert.equal(geometry.count, n);
    assert.equal(new Float64Array(geometry.lonlat).length, n * 2);
    const flags = new Uint8Array(geometry.flags);
    const time = new Float64Array(extras.time);
    let trackIgnored = 0;
    for (let i = 0; i < n; i++) {
      const src = track.points[i];
      assert.equal(time[i], src[fields.timestamp]);
      if (flags[i] & FLAG_IGNORED) trackIgnored++;
      if (flags[i] & FLAG_SEGMENT_START) segments++;
    }
    assert.equal(trackIgnored, track.ignoredCount, `track ${track.id} ignored count`);
    if (row.bbox) {
      const [minLon, minLat, maxLon, maxLat] = row.bbox;
      assert.ok(minLon <= maxLon && minLat <= maxLat);
    }
    const overview = new Float64Array(row.overview);
    assert.ok(overview.length / 2 <= n - trackIgnored || overview.length === 0);
    overviewPoints += overview.length / 2;
    ignored += trackIgnored;
    tracks++;
    points += n;
  },
});
feed(parser, text, 1 << 20);
const ms = performance.now() - t0;

// A file whose pointFields lack a mandatory field must be rejected, not imported as NaN tracks.
assert.throws(() => indexFields(["timestamp", "lon"]), /missing point field "lat"/);

console.log(`converted ${tracks} tracks, ${points} points (${ignored} ignored, ${segments} segment starts) in ${ms.toFixed(0)} ms`);
console.log(`overview: ${overviewPoints} points (${(100 * overviewPoints / (points - ignored)).toFixed(1)}% of good)`);
console.log("all convert tests passed");

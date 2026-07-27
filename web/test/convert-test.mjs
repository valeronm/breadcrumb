// Parses a real export and converts every track, checking the typed-array shapes against the
// source data. Run: node web/test/convert-test.mjs <export.json.gz>
import assert from "node:assert/strict";
import { BackupParser } from "../js/backup-parse.js";
import {
  indexFields, convertTrack, FLAG_SEGMENT_START, REASON_NONE, REASON_EDGE_STAY,
} from "../js/convert.js";
import { loadExportText, feed } from "./helpers.mjs";

const text = loadExportText(process.argv, "node convert-test.mjs");

const t0 = performance.now();
let fields = null;
let tracks = 0;
let points = 0;
let overviewPoints = 0;
let ignored = 0;
let edgeStays = 0;
let segments = 0;
const parser = new BackupParser({
  onHeader: (h) => { fields = indexFields(h.pointFields); },
  onTrack: (track) => {
    const { row, geometry, extras } = convertTrack(track, fields);
    const n = track.points.length;
    assert.equal(geometry.count, n);
    assert.equal(new Float64Array(geometry.lonlat).length, n * 2);
    const flags = new Uint8Array(geometry.flags);
    const reasons = new Uint8Array(geometry.reasons);
    const time = new Float64Array(extras.time);
    let trackIgnored = 0;
    for (let i = 0; i < n; i++) {
      const src = track.points[i];
      assert.equal(time[i], src[fields.timestamp]);
      // The reason byte is the only record of whether a point is on the path, so it has to agree
      // with the ignoredCount the app wrote — and an edge stay must never read as a rejection.
      if (reasons[i] !== REASON_NONE) trackIgnored++;
      if (reasons[i] === REASON_EDGE_STAY) edgeStays++;
      if (flags[i] & FLAG_SEGMENT_START) segments++;
    }
    assert.equal(trackIgnored, track.ignoredCount, `track ${track.id} ignored count`);
    // The endpoints the stay derivation runs on are the app's own columns, carried through as they
    // stand — including a null, which derives as an unknown endpoint rather than as a guess.
    assert.equal(row.startLat, track.startLat ?? null);
    assert.equal(row.startLon, track.startLon ?? null);
    assert.equal(row.endLat, track.endLat ?? null);
    assert.equal(row.endLon, track.endLon ?? null);
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

console.log(
  `converted ${tracks} tracks, ${points} points ` +
  `(${ignored} ignored, of which ${edgeStays} overrun; ${segments} segment starts) ` +
  `in ${ms.toFixed(0)} ms`,
);
console.log(`overview: ${overviewPoints} points (${(100 * overviewPoints / (points - ignored)).toFixed(1)}% of good)`);
console.log("all convert tests passed");

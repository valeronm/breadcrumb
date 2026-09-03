// How a selected track's points become drawable geometry: the path as one polyline per stretch the
// recorder watched, the recorder's overrun as grayed legs anchored to the path, rejected fixes as
// markers. Needs no export file — the cases are hand-built, at a neutral origin.
// Run: node site/public/viewer/test/draw-test.mjs
import assert from "node:assert/strict";
import { splitForDrawing } from "../js/map.js";
import {
  FLAG_SEGMENT_START,
  REASON_NONE, REASON_ACCURACY, REASON_JUMP, REASON_NO_GNSS, REASON_EDGE_STAY,
} from "../js/convert.js";

// Points as [lat, reason] at a fixed longitude — latitude alone identifies a fix in these cases.
// A third element marks the fix as resuming a new segment.
function split(points) {
  const lonlat = new Float64Array(points.length * 2);
  const reasons = new Uint8Array(points.length);
  const flags = new Uint8Array(points.length);
  points.forEach(([lat, reason, breaks], i) => {
    lonlat[i * 2] = -2;
    lonlat[i * 2 + 1] = lat;
    reasons[i] = reason;
    flags[i] = breaks ? FLAG_SEGMENT_START : 0;
  });
  return splitForDrawing(lonlat, reasons, flags, points.length);
}

const BREAK = true;

const lats = (coords) => coords.map(([, lat]) => lat);

// --- the whole shape at once -------------------------------------------------------------------
{
  const { paths, rejected, overruns } = split([
    [1, REASON_EDGE_STAY], // lead overrun: recording started before the walk did
    [2, REASON_EDGE_STAY],
    [3, REASON_NONE],
    [4, REASON_JUMP], // a teleport mid-path
    [5, REASON_NONE],
    [6, REASON_NO_GNSS],
    [7, REASON_EDGE_STAY], // tail overrun
    [8, REASON_EDGE_STAY],
  ]);

  assert.deepEqual(paths.map(lats), [[3, 5]], "the path is the good fixes, in one unbroken line");
  assert.equal(overruns.length, 2);
  assert.deepEqual(lats(overruns[0]), [1, 2, 3], "the lead run closes onto the first good fix");
  assert.deepEqual(lats(overruns[1]), [5, 7, 8], "the tail run hangs off the last good fix");
  assert.deepEqual(rejected.map((f) => f.properties.reason), [REASON_JUMP, REASON_NO_GNSS]);
  assert.deepEqual(rejected.map((f) => f.geometry.coordinates[1]), [4, 6]);
}

// --- a break cuts the path where the recorder stopped watching ---------------------------------
{
  const { paths } = split([
    [1, REASON_NONE],
    [2, REASON_NONE],
    [50, REASON_NONE, BREAK], // recording resumed here, 48 units away
    [51, REASON_NONE],
  ]);

  assert.deepEqual(paths.map(lats), [[1, 2], [50, 51]], "two lines, and no leg across the gap");
}

// --- the flag is read off the fix, not carried ------------------------------------------------
{
  // A break on a fix nothing draws is convert()'s to move onto the good fix that resumed (see
  // segments-test.mjs); by the time geometry is stored there are no strays left to honor, so this
  // reads the flag plainly and a leftover one on a rejected fix cuts nothing.
  const { paths } = split([
    [1, REASON_NONE],
    [2, REASON_NONE],
    [50, REASON_JUMP, BREAK],
    [51, REASON_NONE],
  ]);

  assert.deepEqual(paths.map(lats), [[1, 2, 51]], "one line — no normalized break to cut at");
}

// --- a break beside an overrun leaves the grayed leg unanchored --------------------------------
{
  // Anchoring the run onto the resuming fix would draw the very leg the path refused to.
  const { paths, overruns } = split([
    [1, REASON_NONE],
    [2, REASON_EDGE_STAY],
    [50, REASON_NONE, BREAK],
    [51, REASON_NONE],
  ]);

  assert.deepEqual(paths.map(lats), [[1], [50, 51]]);
  assert.deepEqual(lats(overruns[0]), [1, 2], "anchored behind, open ahead");
}

// --- an overrun buried mid-track is drawn, not dropped -----------------------------------------
{
  // What a merge leaves behind: the earlier track's tail, now with path on both sides. The app's
  // own overlay drops these; drawing them is the point of doing this here.
  const { paths, overruns } = split([
    [1, REASON_NONE],
    [2, REASON_EDGE_STAY],
    [3, REASON_EDGE_STAY],
    [4, REASON_NONE],
  ]);

  assert.deepEqual(paths.map(lats), [[1, 4]]);
  assert.equal(overruns.length, 1);
  assert.deepEqual(lats(overruns[0]), [1, 2, 3, 4], "connected to the path on both sides");
}

// --- a rejected fix inside a stay doesn't end it -----------------------------------------------
{
  const { overruns, rejected } = split([
    [1, REASON_NONE],
    [2, REASON_EDGE_STAY],
    [99, REASON_ACCURACY], // a weak fix while the phone sat parked
    [3, REASON_EDGE_STAY],
  ]);

  assert.equal(overruns.length, 1, "the phone was parked throughout — one run, not two");
  assert.deepEqual(lats(overruns[0]), [1, 2, 3]);
  assert.equal(rejected.length, 1);
}

// --- a track with no path at all ---------------------------------------------------------------
{
  const { paths, overruns } = split([[1, REASON_EDGE_STAY], [2, REASON_EDGE_STAY]]);

  assert.deepEqual(paths, [], "nothing to draw as a path");
  assert.deepEqual(lats(overruns[0]), [1, 2], "and the run has no good fix to anchor to");
}

console.log("all draw tests passed");

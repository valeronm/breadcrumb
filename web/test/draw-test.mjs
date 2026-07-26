// How a selected track's points become drawable geometry: one path polyline, the recorder's overrun
// as grayed legs anchored to the path, rejected fixes as markers. Needs no export file — the cases
// are hand-built, at a neutral origin. Run: node web/test/draw-test.mjs
import assert from "node:assert/strict";
import { splitForDrawing } from "../js/map.js";
import {
  REASON_NONE, REASON_ACCURACY, REASON_JUMP, REASON_NO_GNSS, REASON_EDGE_STAY,
} from "../js/convert.js";

// Points as [lat, reason] at a fixed longitude — latitude alone identifies a fix in these cases.
function split(points) {
  const lonlat = new Float64Array(points.length * 2);
  const reasons = new Uint8Array(points.length);
  points.forEach(([lat, reason], i) => {
    lonlat[i * 2] = -2;
    lonlat[i * 2 + 1] = lat;
    reasons[i] = reason;
  });
  return splitForDrawing(lonlat, reasons, points.length);
}

const lats = (coords) => coords.map(([, lat]) => lat);

// --- the whole shape at once -------------------------------------------------------------------
{
  const { path, rejected, overruns } = split([
    [1, REASON_EDGE_STAY], // lead overrun: recording started before the walk did
    [2, REASON_EDGE_STAY],
    [3, REASON_NONE],
    [4, REASON_JUMP], // a teleport mid-path
    [5, REASON_NONE],
    [6, REASON_NO_GNSS],
    [7, REASON_EDGE_STAY], // tail overrun
    [8, REASON_EDGE_STAY],
  ]);

  assert.deepEqual(lats(path), [3, 5], "the path is the good fixes, in one line");
  assert.equal(overruns.length, 2);
  assert.deepEqual(lats(overruns[0]), [1, 2, 3], "the lead run closes onto the first good fix");
  assert.deepEqual(lats(overruns[1]), [5, 7, 8], "the tail run hangs off the last good fix");
  assert.deepEqual(rejected.map((f) => f.properties.reason), [REASON_JUMP, REASON_NO_GNSS]);
  assert.deepEqual(rejected.map((f) => f.geometry.coordinates[1]), [4, 6]);
}

// --- a break stranded on a rejected fix does not split the line --------------------------------
{
  // The app's map draws one polyline whatever the breaks say, and its distance counts the ground
  // either side of one. A viewer that split here would disagree with both.
  const { path } = split([
    [1, REASON_NONE],
    [2, REASON_NONE],
    [50, REASON_JUMP], // the resume fix, rejected — this is where a segment break lands
    [51, REASON_NONE],
  ]);

  assert.deepEqual(lats(path), [1, 2, 51], "one line, straight across the unwatched gap");
}

// --- an overrun buried mid-track is drawn, not dropped -----------------------------------------
{
  // What a merge leaves behind: the earlier track's tail, now with path on both sides. The app's
  // own overlay drops these; drawing them is the point of doing this here.
  const { path, overruns } = split([
    [1, REASON_NONE],
    [2, REASON_EDGE_STAY],
    [3, REASON_EDGE_STAY],
    [4, REASON_NONE],
  ]);

  assert.deepEqual(lats(path), [1, 4]);
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
  const { path, overruns } = split([[1, REASON_EDGE_STAY], [2, REASON_EDGE_STAY]]);

  assert.deepEqual(path, [], "nothing to draw as a path");
  assert.deepEqual(lats(overruns[0]), [1, 2], "and the run has no good fix to anchor to");
}

console.log("all draw tests passed");

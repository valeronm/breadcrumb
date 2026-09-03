// What convert() does with segment breaks on the way into storage: normalizes the flag onto the good
// fix that resumed recording, and cuts the overview geometry there so the all-tracks map spans a gap
// no more than the full-resolution one does. Needs no export file — the cases are hand-built, at a
// neutral origin. Run: node site/public/viewer/test/segments-test.mjs
import assert from "node:assert/strict";
import { convertTrack, indexFields, FLAG_SEGMENT_START } from "../js/convert.js";

const FIELDS = indexFields(["timestamp", "lat", "lon", "ignored", "ignoreReason", "segmentStart"]);

// Points as [lat, ignoredReason, breaks] — longitude is fixed, so latitude alone names a fix.
// Fixes are spaced far enough apart to survive the overview's simplification.
function convert(points) {
  return convertTrack({
    id: 1,
    points: points.map(([lat, reason, breaks], i) => [
      i * 1000, lat, -2, reason ? 1 : 0, reason ?? null, breaks ? 1 : 0,
    ]),
  }, FIELDS);
}

const BREAK = 1;
const lat = (n) => 1 + n * 0.01;
const flagsOf = ({ geometry }) => Array.from(new Uint8Array(geometry.flags));
const overviewLats = ({ row }) =>
  row.overview.map((b) => Array.from(new Float64Array(b)).filter((_, i) => i % 2 === 1));

// --- a break on a good fix stays where it is ---------------------------------------------------
{
  const result = convert([[lat(0)], [lat(1)], [lat(2), null, BREAK], [lat(3)]]);

  assert.deepEqual(flagsOf(result), [0, 0, FLAG_SEGMENT_START, 0]);
  assert.deepEqual(overviewLats(result), [[lat(0), lat(1)], [lat(2), lat(3)]], "cut in two");
}

// --- a break stranded on a rejected fix moves to the fix that resumed --------------------------
{
  // The fix that resumes recording is exactly the cold-start stray the app's jump rule rejects, so
  // the flag routinely arrives on a row nothing draws. Left there, every reader would either carry
  // it itself or silently draw through the gap.
  const result = convert([[lat(0)], [lat(1)], [lat(2), "jump", BREAK], [lat(3)]]);

  assert.deepEqual(flagsOf(result), [0, 0, 0, FLAG_SEGMENT_START], "moved off the stray");
  assert.deepEqual(overviewLats(result), [[lat(0), lat(1)]], "the resumed stretch is one fix — no line");
}

// --- a run of rejected fixes carries one break, not several ------------------------------------
{
  const result = convert([
    [lat(0)], [lat(1), "accuracy", BREAK], [lat(2), "jump", BREAK], [lat(3)], [lat(4)],
  ]);

  assert.deepEqual(flagsOf(result), [0, 0, 0, FLAG_SEGMENT_START, 0]);
  assert.deepEqual(overviewLats(result), [[lat(3), lat(4)]], "the lone fix before the break draws nothing");
}

// --- a break on the very first good fix opens nothing behind it --------------------------------
{
  // There is no stretch before it to cut away from, so the track is one line, not an empty one
  // followed by the real one.
  const result = convert([[lat(0), null, BREAK], [lat(1)], [lat(2)]]);
  const [only, ...rest] = overviewLats(result);

  assert.deepEqual(flagsOf(result), [FLAG_SEGMENT_START, 0, 0]);
  assert.deepEqual(rest, [], "one stretch, not an empty one before it");
  // Vertices between the ends are the simplifier's business — a straight walk keeps only its ends.
  assert.deepEqual([only.at(0), only.at(-1)], [lat(0), lat(2)]);
}

// --- the bbox spans the whole track, breaks and all --------------------------------------------
{
  // A break moves no fix: it says the recorder looked away, not that the ground between belongs to
  // some other track. Framing the map on one segment would put the rest of the trip off-screen.
  const { row } = convert([[lat(0)], [lat(1), null, BREAK], [lat(9)]]);

  assert.deepEqual(row.bbox, [-2, lat(0), -2, lat(9)]);
}

// --- a track with no good fix has neither overview nor bbox ------------------------------------
{
  const { row } = convert([[lat(0), "accuracy"], [lat(1), "accuracy", BREAK]]);

  assert.deepEqual(row.overview, [], "nothing to draw");
  assert.equal(row.bbox, null);
}

console.log("all segment tests passed");

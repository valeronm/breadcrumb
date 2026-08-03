// greatCircleArc — the port of the app's GreatCircle, pinned case for case against
// GreatCircleTest so the two draw a manual track's leg the same way. Needs no export file —
// the cases are hand-built. Run: node web/test/arc-test.mjs
import assert from "node:assert/strict";
import { greatCircleArc } from "../js/geo.js";

// The arc keeps its ends exact and bows along the great circle: a quarter of the equator stays
// on it, and its midpoint is the halfway meridian.
{
  const arc = greatCircleArc(0, 0, 0, 90);
  assert.deepEqual(arc[0], [0, 0]);
  assert.deepEqual(arc[arc.length - 1], [90, 0]);
  assert.ok(arc.length > 10);
  for (const [, lat] of arc) assert.ok(Math.abs(lat) < 1e-9);
  const midLon = arc[Math.floor(arc.length / 2)][0];
  assert.ok(Math.abs(midLon - 45) < 2, `midpoint at ${midLon}`);
}

// A long east-west arc bows toward the pole — the reason the function exists.
{
  const arc = greatCircleArc(45, -120, 45, 30);
  const apex = Math.max(...arc.map(([, lat]) => lat));
  assert.ok(apex > 60, `apex ${apex} should sit well north of the endpoints`);
}

// An antimeridian crossing unwraps rather than zigzagging: no seam jump between samples, and the
// eastbound walk ends past 180 on the unwrapped copy of the destination.
{
  const arc = greatCircleArc(10, 170, 10, -170);
  for (let i = 1; i < arc.length; i++) {
    assert.ok(Math.abs(arc[i][0] - arc[i - 1][0]) < 180);
  }
  assert.deepEqual(arc[arc.length - 1], [190, 10]);
}

// A short hop is just its two ends.
assert.equal(greatCircleArc(1, -2, 1.001, -2).length, 2);

// An antipodal pair draws no invented arc — every direction is a shortest path.
assert.equal(greatCircleArc(10, 20, -10, -160).length, 2);

console.log("arc-test: ok");

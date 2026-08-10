// The stay derivation, case for case against the app's own suite — the two must agree about what
// counts as the same place, what a gap is, and what the liveness log can attest. Needs no export
// file. Run: node web/test/stays-test.mjs
//
// Distance is stubbed flat-earth, scaled so 0.001° ≈ 100 m, exactly as the app's domain tests do:
// fixtures place endpoints by *meters east of an origin* and every assertion reasons in meters and
// minutes. The origin is neutral (latitude 1.0) and must stay that way.
import assert from "node:assert/strict";
import {
  deriveStays, slicePerDay, interleave, resolveClusters, reportableDurationMs, clusterEndpoints,
  mapVisiblePlaces, derivationInstant,
} from "../js/stays.js";
import { metersBetween } from "../js/geo.js";
import { stayMeta, gapMeta, formatTime, formatDurationMs, categoryLabel } from "../js/format.js";

const MIN = 60_000;
const DAY = 24 * 60 * MIN;
const NOW = 1000 * MIN;

const flatDistance = (aLat, aLon, bLat, bLon) =>
  Math.max(Math.abs(aLat - bLat), Math.abs(aLon - bLon)) * 100_000;

/** The endpoint [meters] east of the origin. */
const at = (meters) => ({ lat: 1.0, lon: 1.0 + meters / 100_000 });
const home = at(0);
const nearHome = { lat: 1.0005, lon: 1.0 }; // 50 m away — agrees
const office = { lat: 2.0, lon: 2.0 };

/** A named-place pin at venue scale (the default place radius is 150 m; venues get widened). */
const pin = (meters, radiusM = 350) => ({ anchor: at(meters), radiusM });

const track = (trackId, startedAt, endedAt, start = home, end = home) =>
  ({ trackId, startedAt, endedAt, start, end });

const derive = (tracks, { liveness = [{ type: "ARMED", at: 0 }], now = NOW, placePins = [] } = {}) =>
  deriveStays({ tracks, liveness, nowMs: now, distance: flatDistance, placePins });

const intervalsOf = (...args) => derive(...args).intervals;

/** Two tracks whose gap is [120, 240) min, both ending/starting near `home`. */
const homePair = (end = home, start = nearHome) => [
  track(1, 60 * MIN, 120 * MIN, home, end),
  track(2, 240 * MIN, 300 * MIN, start, home),
];

/** The intervals between tracks — the tail stay after the last one always derives too, and is not
 *  what most of these cases are about. */
const betweenTracks = (tracks, options) => intervalsOf(tracks, options).filter((i) => i.end != null);
const stays = (intervals) => intervals.filter((i) => i.kind === "stay");
const gaps = (intervals) => intervals.filter((i) => i.kind === "gap");

// --- the decision table --------------------------------------------------------------------------
{
  const stay = stays(betweenTracks(homePair()))[0];
  assert.equal(stay.start, 120 * MIN);
  assert.equal(stay.end, 240 * MIN);
  assert.equal(stay.provenance, "OBSERVED", "agreeing endpoints with full liveness are observed");
  assert.equal(stay.afterTrackId, 1);
}

{
  const withOutage = betweenTracks(homePair(), {
    liveness: [{ type: "ARMED", at: 0 }, { type: "OUTAGE", at: 150 * MIN, until: 180 * MIN }],
  });
  assert.equal(stays(withOutage)[0].provenance, "INFERRED", "an outage inside the gap downgrades it");
}

{
  const twoOutages = betweenTracks(homePair(), {
    liveness: [
      { type: "ARMED", at: 0 },
      { type: "OUTAGE", at: 130 * MIN, until: 140 * MIN },
      { type: "OUTAGE", at: 200 * MIN, until: 210 * MIN },
    ],
  });
  assert.equal(stays(twoOutages).length, 1, "two outages in one gap still yield a single stay");
  assert.equal(stays(twoOutages)[0].provenance, "INFERRED");
}

{
  const rearmed = betweenTracks(homePair(), {
    liveness: [
      { type: "ARMED", at: 0 },
      { type: "DISARMED", at: 150 * MIN },
      { type: "ARMED", at: 200 * MIN },
    ],
  });
  assert.equal(stays(rearmed)[0].provenance, "INFERRED", "a disarm-rearm inside the gap is inferred");
}

{
  // First evidence arrives after the gap — history from before the liveness log existed.
  const old = betweenTracks(homePair(), { liveness: [{ type: "ARMED", at: 500 * MIN }] });
  assert.equal(stays(old)[0].provenance, "INFERRED");
  assert.equal(stays(betweenTracks(homePair(), { liveness: [] }))[0].provenance, "INFERRED");
}

{
  const gap = gaps(betweenTracks(homePair(home, office)))[0];
  assert.equal(gap.reason, "MOVED_UNRECORDED", "disagreeing endpoints are a gap whatever the liveness");
  assert.equal(gap.start, 120 * MIN);
  assert.equal(gap.end, 240 * MIN);
  assert.equal(gap.afterTrackId, 1, "a gap names the track it follows, like a stay");
}

{
  const { intervals, clusters } = derive(homePair(home, office));
  const gap = gaps(intervals)[0];
  assert.notEqual(gap.fromClusterId, gap.toClusterId, "the sides sit in different clusters");
  assert.ok(clusters[gap.fromClusterId].members.includes(home));
  assert.ok(clusters[gap.toClusterId].members.includes(office));
}

{
  // 0.001° = exactly 100 m = the agreement radius; the rule is ≤.
  const exact = betweenTracks(homePair(home, { lat: 1.001, lon: 1.0 }));
  assert.equal(stays(exact).length, 1, "endpoints exactly at the agreement radius still agree");
  // 120 m apart — over the raw radius, but both inside the 150 m cluster around the anchor.
  assert.equal(stays(betweenTracks(homePair(home, at(120)))).length, 1);
}

{
  // Anchors form at 0 m and 170 m (>150 m apart). The first track ends at 130 m (first cluster),
  // the second starts at 170 m (second cluster): different clusters, but only 40 m apart.
  const straddling = betweenTracks([
    track(1, 60 * MIN, 120 * MIN, at(0), at(130)),
    track(2, 240 * MIN, 300 * MIN, at(170), at(300)),
  ]);
  assert.equal(stays(straddling).length, 1, "nearby endpoints straddling two clusters still agree");
}

{
  // 300 m apart — beyond both the raw radius and any shared 150 m cluster — but both nearest to
  // the same pin within 350 m (a mall-sized venue).
  const venue = betweenTracks(homePair(home, at(300)), { placePins: [pin(150)] });
  assert.equal(stays(venue).length, 1, "endpoints sharing a nearest pin agree at venue scale");
  assert.equal(
    gaps(betweenTracks(homePair(home, at(300)), { placePins: [pin(0), pin(300)] }))[0].reason,
    "MOVED_UNRECORDED",
    "endpoints nearest to different pins stay a gap",
  );
  assert.equal(
    gaps(betweenTracks(homePair(home, at(600)), { placePins: [pin(0)] }))[0].reason,
    "MOVED_UNRECORDED",
    "a pin near only one endpoint does not force agreement",
  );
  assert.equal(
    gaps(betweenTracks(homePair(home, at(300)), { placePins: [pin(150, 100)] }))[0].reason,
    "MOVED_UNRECORDED",
    "agreement honors each pin's own radius",
  );
}

{
  const { intervals, clusters } = derive(homePair(home, at(300)), { placePins: [pin(150)] });
  const stay = stays(intervals).find((s) => s.end === 240 * MIN);
  assert.equal(clusters[stay.clusterId].seedIndex, 0, "a pinned venue's stay indexes its pin's cluster");
}

{
  const unknown = gaps(betweenTracks(homePair(null)))[0];
  assert.equal(unknown.reason, "UNKNOWN_ENDPOINT", "a missing endpoint is an unknown-endpoint gap");
  assert.equal(unknown.fromClusterId, null);
  assert.notEqual(unknown.toClusterId, null, "the known side is still carried");
}

{
  const short = [track(1, 60 * MIN, 120 * MIN), track(2, 121 * MIN, 300 * MIN, nearHome)];
  assert.equal(stays(betweenTracks(short)).length, 1, "a short gap emits a stay: there is no minimum");
}

{
  const backwards = [track(1, 60 * MIN, 240 * MIN), track(2, 120 * MIN, 300 * MIN)];
  assert.equal(betweenTracks(backwards).length, 0, "a clock stepping backwards emits nothing");
  assert.equal(intervalsOf([]).length, 0, "an empty history derives nothing");
  const single = intervalsOf([track(1, 60 * MIN, 120 * MIN)]);
  assert.equal(single.length, 1, "a single track yields only its tail stay");
  assert.equal(single[0].start, 120 * MIN);
}

// --- zero-length gaps (trim seams) -----------------------------------------------------------------
{
  const seamPair = (start = home) => [
    track(1, 60 * MIN, 120 * MIN, home, home),
    track(2, 120 * MIN, 130 * MIN, start, home),
  ];
  const seam = stays(betweenTracks(seamPair()))[0];
  assert.equal(seam.start, 120 * MIN, "a same-place zero gap is a zero-length stay — the trim seam");
  assert.equal(seam.end, 120 * MIN);
  assert.equal(betweenTracks(seamPair(office)).length, 0, "a zero gap at different places emits nothing");
  const overlapping = [track(1, 60 * MIN, 120 * MIN), track(2, 119 * MIN, 130 * MIN)];
  assert.equal(betweenTracks(overlapping).length, 0, "a negative gap still emits nothing");
}

// --- the tail stay ----------------------------------------------------------------------------------
{
  const tail = stays(intervalsOf([track(1, 60 * MIN, 120 * MIN)]))[0];
  assert.equal(tail.end, null, "after the last track the stay is open-ended");
  assert.equal(tail.provenance, "OBSERVED");
}

{
  // Which cluster the tail is filed under — the only thing it says about where it was. Two clusters
  // in the history, because with one endpoint every id is 0 and the claim would hold regardless.
  const derivation = derive([
    track(1, 60 * MIN, 120 * MIN, home, home),
    track(2, 240 * MIN, 300 * MIN, home, office),
  ]);
  const tail = stays(derivation.intervals).at(-1);
  assert.deepEqual(
    derivation.clusters[tail.clusterId].anchor, office,
    "the tail stay belongs to the cluster its track ended in",
  );
}

{
  const disarmed = stays(intervalsOf([track(1, 60 * MIN, 120 * MIN)], {
    liveness: [{ type: "ARMED", at: 0 }, { type: "DISARMED", at: 200 * MIN }],
  }))[0];
  assert.equal(disarmed.end, 200 * MIN, "a tail disarm closes the open stay at the disarm");
  assert.equal(disarmed.provenance, "OBSERVED");
  const brief = stays(intervalsOf([track(1, 60 * MIN, 120 * MIN)], {
    liveness: [{ type: "ARMED", at: 0 }, { type: "DISARMED", at: 121 * MIN }],
  }))[0];
  assert.equal(brief.end, 121 * MIN, "even a short one is bounded there");
}

{
  const outaged = stays(intervalsOf([track(1, 60 * MIN, 120 * MIN)], {
    liveness: [{ type: "ARMED", at: 0 }, { type: "OUTAGE", at: 150 * MIN, until: 160 * MIN }],
  }))[0];
  assert.equal(outaged.end, null);
  assert.equal(outaged.provenance, "INFERRED", "an outage in the tail makes the open stay inferred");
}

assert.equal(
  intervalsOf([track(1, 60 * MIN, NOW + MIN)]).length, 0,
  "a track ending after the derivation's instant emits no tail stay",
);

// --- reportable duration -------------------------------------------------------------------------
{
  const stay = (start, end) => ({ start, end });
  // The stop is real — it still derives, still counts as a visit — but its length lives in the
  // untrimmed tail of the track before it, so the bounds are not worth printing.
  assert.equal(reportableDurationMs(stay(100 * MIN, 100 * MIN + 3_000), 300 * MIN), null);
  assert.equal(reportableDurationMs(stay(100 * MIN, 101 * MIN), 300 * MIN), MIN);
  assert.equal(reportableDurationMs(stay(100 * MIN, null), 100 * MIN + 30_000), null);
  assert.equal(reportableDurationMs(stay(100 * MIN, null), 102 * MIN), 2 * MIN);
}

// --- slicePerDay ------------------------------------------------------------------------------------
{
  // One object, so the cut and the day headings cannot answer to different zones.
  const utcClock = {
    startOfDay: (ms) => Math.floor(ms / DAY) * DAY,
    nextMidnight: (ms) => Math.floor(ms / DAY) * DAY + DAY,
  };
  const stay = { kind: "stay", start: 20 * 60 * MIN, end: DAY + 9 * 60 * MIN, provenance: "OBSERVED" };
  const slices = slicePerDay([stay], 2 * DAY, utcClock);
  assert.equal(slices.length, 2, "a midnight-spanning stay splits per day");
  assert.deepEqual([slices[0].start, slices[0].end], [20 * 60 * MIN, DAY]);
  assert.deepEqual([slices[1].start, slices[1].end], [DAY, DAY + 9 * 60 * MIN]);
  // The stamp at the cut: which bounds are the stay's own, so no reader works it out from a clock.
  assert.deepEqual(slices.map((s) => s.holdsStart), [true, false]);
  assert.deepEqual(slices.map((s) => s.holdsEnd), [false, true]);
  assert.ok(slices.every((s) => s.provenance === "OBSERVED"), "slices keep what they are");

  const open = slicePerDay([{ ...stay, end: null }], DAY + 9 * 60 * MIN, utcClock);
  assert.equal(open[0].end, DAY);
  assert.equal(open[1].end, null, "an open-ended stay keeps its null end on the final slice only");

  const sliceGap = (gap, now) => slicePerDay([gap], now, utcClock);

  const gap = { kind: "gap", start: 10 * 60 * MIN, end: 11 * 60 * MIN };
  assert.deepEqual(sliceGap(gap, 2 * DAY), [{ ...gap, holdsStart: true, holdsEnd: true }],
    "an absence inside one day is one row speaking for both its ends");

  // A stay is cut per day because each day it covers has something true to report; an absence has
  // that only at its two ends, so it is cut once and the days in between are folded into the
  // departure half — a row for one could say nothing but that nothing is known.
  const spanning = { kind: "gap", start: 20 * 60 * MIN, end: 3 * DAY + 3 * 60 * MIN };
  assert.deepEqual(sliceGap(spanning, 4 * DAY), [
    { ...spanning, end: 3 * DAY, holdsStart: true, holdsEnd: false },
    { ...spanning, start: 3 * DAY, holdsStart: false, holdsEnd: true },
  ], "however many days it spans, it is two rows: when recording stopped, and when it resumed");
}

// --- interleave -------------------------------------------------------------------------------------
{
  const summary = (id, startedAt) => ({ id, startedAt, endedAt: startedAt + 10 * MIN });
  const descending = [summary(2, 240 * MIN), summary(1, 60 * MIN)];
  const stay = { kind: "stay", start: 120 * MIN, end: 240 * MIN };
  const items = interleave(descending, [stay]);
  assert.deepEqual(
    items.map((i) => (i.kind === "track" ? i.track.startedAt : i.start)),
    [240 * MIN, 120 * MIN, 60 * MIN],
    "tracks and intervals merge newest-first",
  );
  assert.equal(items[1].kind, "stay");

  const ongoing = interleave([summary(1, 60 * MIN)], [{ kind: "stay", start: 60 * MIN, end: null }]);
  assert.deepEqual(ongoing.map((i) => i.kind), ["stay", "track"], "on a tie an open interval is newer");

  // The seam ties with the departing track's start; being closed, it renders between the pair.
  const seam = interleave(
    [summary(2, 120 * MIN), summary(1, 60 * MIN)],
    [{ kind: "stay", start: 120 * MIN, end: 120 * MIN }],
  );
  assert.deepEqual(seam.map((i) => i.kind), ["track", "stay", "track"]);
  assert.equal(seam[0].track.id, 2);
}

// --- clustering and place resolution -----------------------------------------------------------------
{
  // Nearest qualifying anchor wins, and a seed outranks chronology: the endpoint 40 m from the pin
  // joins the pin's cluster rather than founding one of its own.
  const places = [{ id: 7, label: "Home", lat: at(0).lat, lon: at(0).lon, radiusM: 350 }];
  const seeds = places.map((p) => ({ anchor: { lat: p.lat, lon: p.lon }, radiusM: p.radiusM }));
  const clusters = clusterEndpoints([at(40), at(900)], 150, flatDistance, seeds);
  assert.equal(clusters.length, 2, "the seed, plus one organic cluster for the distant endpoint");
  assert.deepEqual(clusters[0].memberIndices, [0]);
  assert.equal(clusters[0].seedIndex, 0);
  assert.equal(clusters[1].seedIndex, null);

  const { intervals, clusters: derived } = derive(homePair(), { placePins: seeds });
  const resolved = resolveClusters(stays(intervals), derived, places);
  const stay = stays(intervals)[0];
  assert.equal(resolved[stay.clusterId].label, "Home", "a seeded cluster resolves to its place");
  assert.equal(resolved[stay.clusterId].placeId, 7);
  // Both the between-tracks stay and the tail stay sit at home.
  assert.equal(resolved[stay.clusterId].visitCount, 2);

  // A stop of no duration is not a visit — the seam two tracks sharing an instant leave behind,
  // which the app drops from its counts for the same reason. Not a duration floor: the
  // one-millisecond stop beside it still counts.
  const seam = { ...stay, start: 9_000, end: 9_000 };
  const blink = { ...stay, start: 9_000, end: 9_001 };
  assert.equal(
    resolveClusters([...stays(intervals), seam], derived, places)[stay.clusterId].visitCount,
    2,
    "a seam adds nothing",
  );
  assert.equal(
    resolveClusters([...stays(intervals), blink], derived, places)[stay.clusterId].visitCount,
    3,
    "however brief, a real stop counts",
  );
}

// --- what a place is for -------------------------------------------------------------------------
{
  // PlaceCategory: the codes are the stored vocabulary, so the viewer reads a code it knows and
  // stays quiet about one it doesn't — the app's own tolerance, rather than inventing a label.
  assert.equal(categoryLabel("groceries"), "Groceries");
  assert.equal(categoryLabel("laundromat"), null, "a code from a newer app reads as untagged");
  assert.equal(categoryLabel(null), null);
  assert.equal(categoryLabel(undefined), null);

  const places = [{ id: 7, label: "Corner shop", lat: at(0).lat, lon: at(0).lon, radiusM: 350, category: "groceries" }];
  const seeds = places.map((p) => ({ anchor: { lat: p.lat, lon: p.lon }, radiusM: p.radiusM }));
  const { intervals, clusters } = derive(homePair(), { placePins: seeds });
  const resolved = resolveClusters(stays(intervals), clusters, places);
  const stay = stays(intervals)[0];
  const place = resolved[stay.clusterId];
  assert.equal(place.category, "groceries", "the stored code, not a label");
}

// --- what "now" means for a file ------------------------------------------------------------------
{
  const rows = [{ endedAt: 300 * MIN }, { endedAt: 120 * MIN }];
  assert.equal(derivationInstant(NOW, rows), NOW, "the export's own stamp, when it has one");
  assert.equal(derivationInstant(null, rows), 300 * MIN, "else the newest track's end…");
  assert.equal(derivationInstant(null, []), 0, "and an empty history is no instant at all");
  // …at which the tail stay is open but measures nothing: a file that doesn't say when it was
  // written attests no time past its last fix, so the stay can't run on to today.
  const last = track(1, 60 * MIN, 300 * MIN);
  const tail = intervalsOf([last], { now: derivationInstant(null, [last]) });
  assert.deepEqual(tail.map((i) => [i.start, i.end]), [[300 * MIN, null]]);
  assert.equal(reportableDurationMs(tail[0], derivationInstant(null, [last])), null);
}

// --- which places the map draws ------------------------------------------------------------------
{
  const p = (label, visitCount) => ({ label, visitCount });
  const shown = (showRare) => mapVisiblePlaces([
    p("Home", 12), // named and often visited
    p(null, 4), // an unnamed cluster worth naming
    p(null, 2), // a rare stop
    p("One-off", 1), // named, but on the strength of a single visit
    p("Dropped pin", 0), // named with no stays at all
    p(null, 0), // a pass-through: a stray endpoint a gap points at
  ], showRare).map((x) => x.label ?? `unnamed×${x.visitCount}`);

  assert.deepEqual(shown(false), ["Home", "unnamed×4"], "rare stops off: only the notable clusters");
  assert.deepEqual(
    shown(true),
    ["Home", "unnamed×4", "unnamed×2", "One-off", "Dropped pin"],
    "rare stops on: everything ever stayed at, plus every named pin — but never a pass-through",
  );
}

// --- how a row reads ---------------------------------------------------------------------------------
{
  // Fixed zone: the wording turns on local midnights, so the cases have to know where midnight is.
  process.env.TZ = "UTC";
  const day = Date.UTC(2026, 0, 5);
  const hm = (h, m = 0) => day + h * 60 * MIN + m * MIN;
  // Which bounds the slicing cut is stamped, not read off the clock — so a stay that merely begins
  // at midnight keeps its start time, where the old bounds-reading called it a slice.
  const stay = (start, end, holdsStart = true, holdsEnd = true) =>
    ({ kind: "stay", start, end, holdsStart, holdsEnd });
  const exportedAt = hm(23);

  // Clock times come out in the viewer's own locale, so the expectations are composed from the
  // same formatter — what's asserted here is the phrasing around them, not 24-hour vs 12-hour.
  const t = (h, m = 0) => formatTime(hm(h, m));
  const hourRow = `${t(9)} – ${t(10)} · 1 h 0 min`;

  assert.equal(stayMeta(stay(hm(9, 41), hm(11, 2)), null, exportedAt),
    `${t(9, 41)} – ${t(11, 2)} · 1 h 21 min`);
  assert.equal(stayMeta(stay(hm(9, 41), null), null, exportedAt), `since ${t(9, 41)} · 13 h 19 min`);
  assert.equal(stayMeta(stay(day, hm(9), false), null, exportedAt), `until ${t(9)}`,
    "a bound the slicing cut restates the clock time, so it carries no duration");
  assert.equal(stayMeta(stay(day, hm(9)), null, exportedAt), `${t(0)} – ${t(9)} · 9 h 0 min`,
    "the same bounds uncut are a stay that merely began at midnight, and it keeps both");
  assert.equal(stayMeta(stay(hm(20), day + DAY, true, false), null, exportedAt), `from ${t(20)}`);
  assert.equal(stayMeta(stay(day, day + DAY, false, false), null, exportedAt), "All day");
  assert.equal(stayMeta(stay(day, null, false), null, day + DAY), "All day");
  assert.equal(stayMeta(stay(hm(9, 11), hm(9, 11) + 3_000), null, exportedAt), t(9, 11),
    "a stop caught only at its tail lands on one clock minute, and its bounds measure nothing");

  assert.equal(stayMeta(stay(hm(9), hm(10)), { label: "Home", visitCount: 12 }, exportedAt),
    hourRow, "a named place needs no visit count — it has a name");
  assert.equal(stayMeta(stay(hm(9), hm(10)), { label: null, visitCount: 4 }, exportedAt),
    `${hourRow} · 4 visits`, "an unnamed cluster worth naming shows how often");
  assert.equal(stayMeta(stay(hm(9), hm(10)), { label: null, visitCount: 2 }, exportedAt),
    hourRow, "…but not one visited twice");

  // What the stop was for comes after how long it took, as the app's stay row orders them.
  assert.equal(stayMeta(stay(hm(9), hm(10)), { label: "Home", category: "home" }, exportedAt),
    `${hourRow} · Home`);
  assert.equal(stayMeta(stay(hm(9), hm(10)), { label: "Home", category: "laundromat" }, exportedAt),
    hourRow, "a category this viewer doesn't know reads as untagged");
  assert.equal(stayMeta(stay(hm(9), hm(10)), { label: "Home", category: null }, exportedAt),
    hourRow, "an untagged place says nothing extra");

  assert.equal(formatDurationMs(45 * MIN), "45 min");
  assert.equal(formatDurationMs(3 * DAY), "3 d");
  assert.equal(formatDurationMs(3 * DAY + 5 * 60 * MIN), "3 d 5 h");

  // Read off the flags the cut stamped, never off the bounds — which is what lets an absence that
  // genuinely began at midnight still say so, where a bounds-reading rule called it a seam.
  const gap = (start, end, holdsStart, holdsEnd) =>
    ({ kind: "gap", start, end, holdsStart, holdsEnd });
  assert.deepEqual(gapMeta(gap(hm(9), hm(11), true, true)),
    { text: "missing recording · 2 h 0 min" },
    "a whole absence inside one day measures itself and names both ends");
  assert.deepEqual(gapMeta(gap(hm(18), day + DAY, true, false)),
    { text: `missing recording · from ${t(18)}` },
    "the day it began says when recording stopped, and names the departure");
  assert.deepEqual(gapMeta(gap(day, hm(9), false, true)),
    { text: `missing recording · until ${t(9)}` },
    "the day it ended says when recording resumed, and names the arrival");
  assert.deepEqual(gapMeta(gap(day, day + DAY, true, true)),
    { text: "missing recording · 1 d" },
    "a real 24-hour absence measures itself, midnight bounds and all");
}

// --- the distance function itself -----------------------------------------------------------------
{
  // WGS84 reference values: a thousandth of a degree of latitude at the equator is 110.574 m of
  // meridian, the same step of longitude 111.319 m of parallel. A sphere formula misses both.
  assert.ok(Math.abs(metersBetween(0, 0, 0.001, 0) - 110.574) < 0.01);
  assert.ok(Math.abs(metersBetween(0, 0, 0, 0.001) - 111.319) < 0.01);
  // A degree of longitude shortens toward the pole — half of it at 60°.
  assert.ok(Math.abs(metersBetween(60, 0, 60, 0.001) - 55.8) < 0.1);
  assert.equal(metersBetween(1, -2, 1, -2), 0, "a point is no distance from itself");
}

console.log("all stay tests passed");

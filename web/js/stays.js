// Stays, gaps and the endpoint clustering they index into — a port of the app's `StayDeriver`,
// `PlaceClusterer` and `PlaceResolver.resolveClusters`, running on the backup's two inputs: every
// kept track's endpoints, and the liveness log. A stay is the interval between one track's end and
// the next's start when both endpoints land at "the same place" (same endpoint cluster, OR raw
// distance within the agreement radius, OR the same nearest named-place pin within that pin's own
// radius); endpoint disagreement means movement the recorder missed, reported as a gap instead.
// Provenance is OBSERVED only where the liveness log attests the app was alive and armed
// throughout — an outage, a disarm-rearm inside the interval, or history from before the log
// existed marks it INFERRED. The rules are the app's, restated once here because a viewer that
// reads the same history a second way is worse than one that doesn't read it at all; one
// presentation rule is ported too, being a rule about the data and not about pixels: which
// clusters are worth drawing (`mapVisiblePlaces`, from PlacesScreens.kt). Every constant below
// names its Kotlin counterpart, so changing a threshold in the app turns up this file in a grep
// for the symbol — the only thing standing between the two copies and silent drift.
// Pure ES module — the viewer imports it, node tests drive it directly with a stubbed distance.

import { metersBetween, reachBound } from "./geo.js";

/** Fallback: endpoints at most this far apart (meters) agree even across cluster lines.
 *  StayDeriver.Params.agreementRadiusM. */
const AGREEMENT_RADIUS_M = 100;

/**
 * Radius for clustering track endpoints into places: 1.5x the agreement radius, because a stay's
 * location is a midpoint of endpoints that may be a full radius apart, so same-place stays scatter
 * beyond it before they are truly elsewhere. PlaceClusterer.DEFAULT_RADIUS_M.
 */
export const PLACE_RADIUS_M = 150;

/** Below this a stay's length is not worth reporting: measured between two track *bounds*, it only
 * covers the part of a stop the recorder noticed — the stationary approach usually sits untrimmed
 * inside the previous track's tail — so it is not the length of anything the user did. Such a stay
 * is still a real stop and keeps its place on the timeline, but a bounds-derived duration would be
 * fiction, and rounding one to "0m" reads as a broken value. StayDeriver.REPORTABLE_DURATION_MS. */
const REPORTABLE_DURATION_MS = 60_000;

/** Visits at which an unnamed cluster is worth surfacing as a count — the app's naming invitation.
 *  PlaceResolver.NOTABLE_VISIT_MIN. */
const NOTABLE_VISIT_MIN = 3;

/**
 * Whether a cluster has been visited often enough to be worth a user's attention: it earns its
 * visit count on a stay row, and it survives the rare-stop filter on the map. One predicate so the
 * two can't disagree about where the floor is.
 */
export function isNotable(place) {
  return (place?.visitCount ?? 0) >= NOTABLE_VISIT_MIN;
}

const DEFAULT_PARAMS = {
  agreementRadiusM: AGREEMENT_RADIUS_M,
  placeRadiusM: PLACE_RADIUS_M,
};

// Endpoints are plain {lat, lon} objects, so cluster lookup is keyed by value rather than identity.
// Identical coordinates always land in the same cluster, which is what makes that safe even when
// endpoints repeat — the app's map is value-keyed for the same reason.
const key = (e) => `${e.lat},${e.lon}`;

/** Groups endpoints into *places* by anchor-based greedy leader clustering in chronological input
 * order: a cluster's anchor is its first-ever member's location, so appending newer history never
 * re-shuffles the clusters older stays belong to, and every member sits within its anchor's
 * capture radius, so a cluster can't chain-walk across a neighborhood. The user's named-place pins
 * enter as `seeds` ({anchor, radiusM}) — pre-existing anchors with their own venue-scale radii,
 * outranking chronology; a seeded cluster's identity *is* its place (`seedIndex`), killing the
 * anchor lottery (a skewed first visit cannot found a shadow cluster beside a named place).
 * Assignment is nearest-qualifying-anchor, so an endpoint closer to a distinct organic anchor
 * still goes there. */
export function clusterEndpoints(locations, radiusM, distance, seeds) {
  const anchors = [];
  const radii = [];
  const members = [];
  for (const seed of seeds) {
    anchors.push(seed.anchor);
    radii.push(seed.radiusM);
    members.push([]);
  }
  locations.forEach((location, index) => {
    // Nearest qualifying anchor, scanned inline: this runs per endpoint over the whole history, so
    // all but the handful of anchors in reach are rejected on their coordinates rather than on a
    // distance call.
    const outOfReach = reachBound(location.lat, location.lon, distance);
    let nearest = -1;
    let nearestD = Infinity;
    for (let ci = 0; ci < anchors.length; ci++) {
      const anchor = anchors[ci];
      if (outOfReach(anchor.lat, anchor.lon, radii[ci])) continue;
      const d = distance(anchor.lat, anchor.lon, location.lat, location.lon);
      if (d <= radii[ci] && d < nearestD) {
        nearest = ci;
        nearestD = d;
      }
    }
    if (nearest >= 0) {
      members[nearest].push(index);
    } else {
      anchors.push(location);
      radii.push(radiusM);
      members.push([index]);
    }
  });
  return anchors.map((anchor, ci) => {
    const locs = members[ci].map((i) => locations[i]);
    return {
      anchor,
      // A seed with no members keeps its pin as the centroid.
      centroid: locs.length === 0 ? anchor : {
        lat: locs.reduce((sum, l) => sum + l.lat, 0) / locs.length,
        lon: locs.reduce((sum, l) => sum + l.lon, 0) / locs.length,
      },
      memberIndices: members[ci],
      members: locs,
      radiusM: radii[ci],
      seedIndex: ci < seeds.length ? ci : null,
    };
  });
}

/** Derives the timeline's intervals from tracks + liveness.
 * @param tracks ascending by time: {trackId, startedAt, endedAt, start, end} — start/end the
 *   first/last *good* point's {lat, lon}, or null (an unknown endpoint can only produce gaps,
 *   as in the app).
 * @param liveness ascending: {type: "ARMED"|"DISARMED"|"OUTAGE", at, until}.
 * @param nowMs the instant the derivation is "as of" — the viewer passes the backup's export time,
 *   so an open tail stay is open as of the export rather than growing on every page load.
 * @param placePins named places as clustering seeds: {anchor: {lat, lon}, radiusM}, in places-list
 *   order, so a cluster's seedIndex identifies its place exactly.
 * @returns {{intervals: object[], clusters: object[]}} intervals ascending; a stay carries
 *   `clusterId`, a gap the cluster id of each known side. */
export function deriveStays({
  tracks,
  liveness = [],
  nowMs,
  params = {},
  distance = metersBetween,
  placePins = [],
}) {
  const p = { ...DEFAULT_PARAMS, ...params };
  const evidence = summarizeLiveness(liveness, nowMs);
  const endpoints = [];
  for (const track of tracks) {
    if (track.start) endpoints.push(track.start);
    if (track.end) endpoints.push(track.end);
  }
  const clusters = clusterEndpoints(endpoints, p.placeRadiusM, distance, placePins);
  const clusterOf = new Map();
  clusters.forEach((cluster, ci) => {
    for (const index of cluster.memberIndices) clusterOf.set(key(endpoints[index]), ci);
  });

  // Nearest pin whose own radius captures [e]. Pins out of reach cost coordinate arithmetic, not a
  // distance call — the same rejection the clustering above runs.
  const nearestPin = (e) => {
    if (placePins.length === 0) return null;
    const outOfReach = reachBound(e.lat, e.lon, distance);
    let best = -1;
    let bestD = Infinity;
    for (let i = 0; i < placePins.length; i++) {
      const pin = placePins[i];
      if (outOfReach(pin.anchor.lat, pin.anchor.lon, pin.radiusM)) continue;
      const d = distance(pin.anchor.lat, pin.anchor.lon, e.lat, e.lon);
      if (d <= pin.radiusM && d < bestD) {
        best = i;
        bestD = d;
      }
    }
    return best >= 0 ? best : null;
  };

  const samePlace = (a, b) => {
    if (clusterOf.get(key(a)) === clusterOf.get(key(b))) return true;
    if (distance(a.lat, a.lon, b.lat, b.lon) <= p.agreementRadiusM) return true;
    const pinA = nearestPin(a);
    return pinA != null && pinA === nearestPin(b);
  };

  const out = [];
  for (let i = 0; i < tracks.length - 1; i++) {
    const prev = tracks[i];
    const next = tracks[i + 1];
    const gapStart = prev.endedAt;
    const gapEnd = next.startedAt;
    // Negative gap (clock stepped backwards between tracks): emit nothing.
    if (gapEnd < gapStart) continue;
    const a = prev.end;
    const b = next.start;
    if (!a || !b || !samePlace(a, b)) {
      // A zero-length disagreement ("moved without recording, in zero time") is meaningless —
      // whereas a zero-length *agreeing* gap below is a split seam, an edge-stay trim's cut.
      if (gapEnd === gapStart) continue;
      out.push({
        kind: "gap",
        start: gapStart,
        end: gapEnd,
        reason: !a || !b ? "UNKNOWN_ENDPOINT" : "MOVED_UNRECORDED",
        afterTrackId: prev.trackId,
        fromClusterId: a ? clusterOf.get(key(a)) : null,
        toClusterId: b ? clusterOf.get(key(b)) : null,
      });
      continue;
    }
    out.push({
      kind: "stay",
      start: gapStart,
      end: gapEnd,
      location: midpoint(a, b),
      provenance: evidence.provenanceOver(gapStart, gapEnd),
      afterTrackId: prev.trackId,
      clusterId: clusterOf.get(key(a)),
    });
  }

  const tail = tailStay(tracks[tracks.length - 1], evidence, nowMs, clusterOf);
  if (tail) out.push(tail);
  return { intervals: out, clusters };
}

/** The stay after the last finished track: open-ended (where the user was left) unless the
 * liveness log says the app was disarmed — it can attest nothing past the disarm, so the stay
 * closes there. The app's third case — a live recording closing the tail at the active track's
 * start — can't arise from a backup: the export is a finished snapshot, and a track still
 * recording when it was written isn't in it. */
function tailStay(last, evidence, nowMs, clusterOf) {
  if (!last) return null;
  const location = last.end;
  if (!location) return null;
  const start = last.endedAt;
  if (start > nowMs) return null;
  const end = evidence.disarmedSince == null ? null : Math.max(evidence.disarmedSince, start);
  const effectiveEnd = end ?? nowMs;
  return {
    kind: "stay",
    start,
    end,
    location,
    provenance: evidence.provenanceOver(start, effectiveEnd),
    afterTrackId: last.trackId,
    clusterId: clusterOf.get(key(location)),
  };
}

function midpoint(a, b) {
  return { lat: (a.lat + b.lat) / 2, lon: (a.lon + b.lon) / 2 };
}

/** This stay's length when its own bounds are worth reporting as one, else null. */
export function reportableDurationMs(stay, nowMs) {
  const length = (stay.end ?? nowMs) - stay.start;
  return length >= REPORTABLE_DURATION_MS ? length : null;
}

/**
 * No time in it at all — the seam two tracks sharing an instant leave behind, which is a join
 * between them rather than a stop. The bare fact, owned here, because two readers turn it into
 * rules of their own: no visit is counted for one, and no row is drawn for one. An ongoing stay is
 * not one of these — no end is not an end equal to the start.
 */
export function hasNoDuration(stay) {
  return stay.end === stay.start;
}

// --- liveness evidence ---------------------------------------------------------------------------

function summarizeLiveness(liveness, nowMs) {
  const dead = []; // half-open [start, end) intervals where the app was known dead or disarmed
  let disarmedSince = null;
  // Time of the earliest liveness evidence; anything before it is unattested — which is how
  // history from before the log existed derives as inferred rather than as observed silence.
  const firstEvidenceAt = liveness.length ? Math.min(liveness[0].at, nowMs) : null;
  for (const event of liveness) {
    if (event.type === "OUTAGE") {
      // An outage row without an end says nothing bounded — the app skips it too.
      if (event.until != null) dead.push([Math.min(event.at, nowMs), Math.min(event.until, nowMs)]);
    } else if (event.type === "DISARMED") {
      if (disarmedSince == null) disarmedSince = Math.min(event.at, nowMs);
    } else if (event.type === "ARMED") {
      if (disarmedSince != null) dead.push([disarmedSince, Math.min(event.at, nowMs)]);
      disarmedSince = null;
    }
  }
  // A trailing disarm is dead through "now" for mid-list gaps; the tail stay handles it explicitly.
  if (disarmedSince != null) dead.push([disarmedSince, nowMs]);
  return {
    disarmedSince,
    provenanceOver(start, end) {
      if (firstEvidenceAt == null || start < firstEvidenceAt) return "INFERRED";
      if (dead.some(([ds, de]) => ds < end && start < de)) return "INFERRED";
      return "OBSERVED";
    },
  };
}

// --- display helpers -----------------------------------------------------------------------------

/** Midnight opening the local day that contains [ms] — where the timeline's days begin, and the one
 *  place the zone convention lives. */
function startOfLocalDay(ms) {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** The instant the local day containing [ms] ends — the JS stand-in for the app's ZoneId seam. */
function localNextMidnight(ms) {
  const d = new Date(startOfLocalDay(ms));
  d.setDate(d.getDate() + 1);
  return d.getTime();
}

/**
 * Splits **stays** at midnights so each piece falls inside one calendar day (a 20:00–09:00 stay
 * renders in both days with clamped bounds). An open-ended stay keeps its null end on the final
 * slice. [nextMidnight] is the zone: the default reads the viewer's local one.
 *
 * A **gap** is cut once instead, at the midnight opening the day it ended, matching the app. A stay
 * says something true about each day it covers; a gap says nothing about the days in the middle of
 * it, so those are folded into the departure half and get no row. Its two ends are real though, and
 * each belongs to its own day — so the departure day says when recording stopped and the arrival day
 * says when it resumed.
 *
 * Every piece carries `holdsStart`/`holdsEnd` saying which of its bounds are the interval's own,
 * stamped here because this is the only place that knows. A reader deciding it instead by testing a
 * bound against midnight cannot tell an interval that genuinely begins at 00:00 from one cut there.
 *
 * [clock] is the day boundary read from either side, as one object: the two halves must answer to
 * the same zone or the cut and the day headings disagree, and passing them separately made that a
 * rule a caller had to remember rather than one it cannot break. The app has no equivalent because
 * both of its readings derive from the one `ZoneId` it is handed.
 */
export const LOCAL_CLOCK = { startOfDay: startOfLocalDay, nextMidnight: localNextMidnight };

/** An absence as the two halves the day boundary makes of it — one piece where the whole of it
 *  already sits inside the day it ended on, which is the ordinary short outage. Mirrors the app's
 *  `halvesAtArrivalDay`, stamps included. */
function gapHalves(gap, startOfDay) {
  const arrivalDayStart = startOfDay(gap.end);
  if (arrivalDayStart <= gap.start) return [{ ...gap, holdsStart: true, holdsEnd: true }];
  return [
    { ...gap, end: arrivalDayStart, holdsStart: true, holdsEnd: false },
    { ...gap, start: arrivalDayStart, holdsStart: false, holdsEnd: true },
  ];
}

export function slicePerDay(intervals, nowMs, clock = LOCAL_CLOCK) {
  const out = [];
  for (const interval of intervals) {
    if (interval.kind === "gap") {
      out.push(...gapHalves(interval, clock.startOfDay));
      continue;
    }
    const { nextMidnight } = clock;
    const end = interval.end ?? nowMs;
    let sliceStart = interval.start;
    for (;;) {
      const holdsStart = sliceStart === interval.start;
      const boundary = nextMidnight(sliceStart);
      if (end <= boundary) {
        out.push({ ...interval, start: sliceStart, end: interval.end, holdsStart, holdsEnd: true });
        break;
      }
      out.push({ ...interval, start: sliceStart, end: boundary, holdsStart, holdsEnd: false });
      sliceStart = boundary;
    }
  }
  return out;
}

/** Merges the DESC track list with derived intervals (ASC) into one DESC timeline of
 * {kind: "track"|"stay"|"gap"} items. On a start-time tie the interval sorts newer only when it is
 * open-ended: a closed one ended the instant the track began (a zero-length trim seam), so the
 * departing track is newer and the interval sorts between the pair. */
export function interleave(tracks, intervals) {
  const descIntervals = intervals.slice().reverse();
  const out = [];
  let t = 0;
  let v = 0;
  while (t < tracks.length || v < descIntervals.length) {
    const track = tracks[t];
    const interval = descIntervals[v];
    const takeInterval = !!interval && (!track
      || interval.start > track.startedAt
      || (interval.start === track.startedAt && interval.end == null));
    if (takeInterval) {
      out.push(interval);
      v++;
    } else {
      out.push({ kind: "track", track });
      t++;
    }
  }
  return out;
}

/** Which resolved clusters a map should draw, mirroring the app's Places map exactly. Two rules,
 * and they are different rules: a cluster nothing ever stayed at is a *pass-through* — a stray
 * endpoint that only ever produced disagreements — never drawn, existing so a gap's side has
 * something to point at; a cluster below the notable-visit floor is a *rare stop*, drawn only when
 * [showRareStops] asks. A name doesn't exempt one — a place named on the strength of a single
 * visit is exactly the clutter the toggle is asked to clear, and a named cluster with no visits at
 * all (a dropped pin, or one whose stays were deleted) is rarer still. */
export function mapVisiblePlaces(resolved, showRareStops) {
  return resolved.filter((p) => (p.label != null || p.visitCount > 0)
    && (showRareStops || isNotable(p)));
}

/** Resolution of *every* endpoint cluster, indexed by cluster id: stays look up by `clusterId`,
 * gaps by their side cluster ids (whose clusters may have no stays at all — those resolve with a
 * zero visit count). The clustering was seeded by the place pins, so a cluster's seedIndex
 * identifies its place exactly and labels can't silently detach; organic clusters are unnamed.
 * Must run over the UNSLICED stays — after slicePerDay a 3-day stay would count as 3 visits. */
export function resolveClusters(stays, clusters, places) {
  const visits = new Map();
  for (const stay of stays) {
    // A stop of no duration is not a visit — that is a join between two tracks rather than time
    // spent anywhere. Not a duration floor: however brief, a stop the endpoints agree on counts.
    // The app drops the same interval in `PlaceResolver`, and for the same reason.
    if (hasNoDuration(stay)) continue;
    visits.set(stay.clusterId, (visits.get(stay.clusterId) ?? 0) + 1);
  }
  return clusters.map((cluster, clusterId) => {
    const place = cluster.seedIndex == null ? null : places[cluster.seedIndex] ?? null;
    return {
      // Carried on the row, not just implied by its position: a marker clicked on the map has to
      // find its way back to the cluster after the display filter has dropped most of them.
      clusterId,
      // Flattened from the matched place because that is what every consumer reads (the app's
      // PlaceResolver.ResolvedStay keeps the row and derives these; here there is no row to keep).
      label: place?.label ?? null,
      placeId: place?.id ?? null,
      category: place?.category ?? null,
      visitCount: visits.get(clusterId) ?? 0,
      anchor: cluster.anchor,
      radiusM: cluster.radiusM,
      endpoints: cluster.members,
    };
  });
}

/** The instant a derivation is "as of". A backup is a snapshot, so that is when it was exported —
 * not today, or an open tail stay would grow on every reload. A file without an export stamp
 * (nothing in the format guarantees one) falls back to the newest track's end: the tail stay is
 * then open at the instant the history stops and measures nothing — the honest reading, since a
 * file that doesn't say when it was written attests nothing past its last fix. */
export function derivationInstant(exportedAt, tracks) {
  if (exportedAt != null) return exportedAt;
  return tracks.reduce((newest, t) => Math.max(newest, t.endedAt ?? 0), 0);
}

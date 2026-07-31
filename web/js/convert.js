// Converts a parsed backup track (per-point arrays keyed by the export's pointFields header)
// into the viewer's storage shape: typed arrays plus a simplified overview geometry. Pure —
// the import worker uses it in the browser, node tests drive it directly.

import { simplify } from "./simplify.js";

// ~11 m of latitude — indistinguishable in the zoomed-out overview, ~10x fewer vertices.
const OVERVIEW_TOLERANCE_DEG = 1e-4;

// Flags bitmask per point. Whether a point is ignored is NOT a flag: the reason byte below carries
// that, and one value of it means "on the path" — two places saying the same thing could disagree.
//
// Stored NORMALIZED, not as the export wrote it: a break marks the boundary the recorder resumed at,
// and the export can leave it on a fix nothing draws — the first fix after a pause is exactly the
// cold-start stray the app's jump rule rejects, and a merge marks the later track's first point
// whatever its state. So convert() moves a break off an ignored fix onto the first good one after it
// and clears the original, exactly as the app's SegmentBreaks does on the way out of its database.
// Every reader then gets to ask a plain per-point question, and none of them carries state.
export const FLAG_SEGMENT_START = 1;

// Why a point is off the path, mirroring the app's IgnoreReason. The distinction is the whole point
// of keeping it: a low-accuracy fix is a position not to be trusted, while an edge stay is a
// perfectly good fix of a phone that had already arrived — drawn as scattered noise it reads as bad
// GPS instead of the arrival it is.
export const REASON_NONE = 0;
export const REASON_ACCURACY = 1;
export const REASON_JUMP = 2;
export const REASON_NO_GNSS = 3;
export const REASON_EDGE_STAY = 4;

// The export carries the app's code string. Anything unrecognised — including the null on points
// recorded before reasons were tracked — reads as low accuracy, which is how the app labels it too.
const REASON_BY_CODE = {
  accuracy: REASON_ACCURACY,
  jump: REASON_JUMP,
  no_gnss: REASON_NO_GNSS,
  edge_stay: REASON_EDGE_STAY,
};

/**
 * Resolves the header's pointFields list to the positions convert() reads. Mirrors the app
 * importer's contract: timestamp/lat/lon are mandatory (a file without them would otherwise
 * import as silent all-NaN tracks), everything else degrades to null/absent.
 */
export function indexFields(names) {
  const required = (name) => {
    const at = names.indexOf(name);
    if (at < 0) throw new Error(`export missing point field "${name}"`);
    return at;
  };
  const at = (name) => names.indexOf(name);
  return {
    timestamp: required("timestamp"),
    lat: required("lat"),
    lon: required("lon"),
    alt: at("alt"),
    accuracy: at("accuracy"),
    speed: at("speed"),
    ignored: at("ignored"),
    ignoreReason: at("ignoreReason"),
    segmentStart: at("segmentStart"),
  };
}

export function convertTrack(track, f) {
  const raw = track.points ?? [];
  const n = raw.length;
  const time = new Float64Array(n);
  const lonlat = new Float64Array(n * 2);
  const alt = new Float32Array(n);
  const speed = new Float32Array(n);
  const accuracy = new Float32Array(n);
  const flags = new Uint8Array(n);
  const reasons = new Uint8Array(n);

  // The good fixes as flat lon/lat, cut into the stretches the recorder actually watched: one
  // simplified line each, since simplifying across a break would let a leg nobody drew decide which
  // fixes either side of it survive. The bbox spans them all — a break moves no fix.
  const watched = [];
  let minLon = Infinity;
  let minLat = Infinity;
  let maxLon = -Infinity;
  let maxLat = -Infinity;
  let carriedBreak = false;
  for (let i = 0; i < n; i++) {
    const p = raw[i];
    const lat = p[f.lat];
    const lon = p[f.lon];
    time[i] = p[f.timestamp];
    lonlat[i * 2] = lon;
    lonlat[i * 2 + 1] = lat;
    alt[i] = p[f.alt] ?? NaN;
    speed[i] = p[f.speed] ?? NaN;
    accuracy[i] = p[f.accuracy] ?? NaN;
    const ignored = p[f.ignored] === 1;
    if (p[f.segmentStart] === 1) carriedBreak = true;
    reasons[i] = ignored ? (REASON_BY_CODE[p[f.ignoreReason]] ?? REASON_ACCURACY) : REASON_NONE;
    if (ignored) continue;
    // The break belongs to the boundary, so it lands here whichever row the export wrote it on.
    if (carriedBreak) flags[i] = FLAG_SEGMENT_START;
    if (carriedBreak || !watched.length) watched.push([]);
    carriedBreak = false;
    watched.at(-1).push(lon, lat);
    if (lon < minLon) minLon = lon;
    if (lat < minLat) minLat = lat;
    if (lon > maxLon) maxLon = lon;
    if (lat > maxLat) maxLat = lat;
  }

  const overview = watched
    .filter((segment) => segment.length >= 4)
    .map((segment) => simplify(segment, OVERVIEW_TOLERANCE_DEG).buffer);
  const row = {
    id: track.id,
    activityType: track.activityType,
    startedAt: track.startedAt,
    endedAt: track.endedAt,
    distanceMeters: track.distanceMeters,
    pointCount: track.pointCount,
    ignoredCount: track.ignoredCount,
    // The track's own first/last good-fix coordinates, taken from the export rather than recomputed
    // off the points: they are what the app derives stays from, and the row is where the app keeps
    // them. Null (a track with no good fix) is carried through as null — an endpoint the derivation
    // doesn't know is a gap it must report, not one to guess at.
    startLat: track.startLat ?? null,
    startLon: track.startLon ?? null,
    endLat: track.endLat ?? null,
    endLon: track.endLon ?? null,
    bbox: watched.length ? [minLon, minLat, maxLon, maxLat] : null,
    // One simplified line per watched stretch — several where the recording was interrupted, and the
    // usual one where it wasn't.
    overview,
  };
  // Geometry (what rendering a track needs) is stored separately from the metric arrays so
  // selecting a track never loads bytes it won't draw; the extras are kept for the planned
  // metric colouring (speed/altitude/accuracy ramps like the app's track detail).
  const geometry = {
    trackId: track.id,
    count: n,
    lonlat: lonlat.buffer,
    flags: flags.buffer,
    reasons: reasons.buffer,
  };
  const extras = {
    trackId: track.id,
    time: time.buffer,
    alt: alt.buffer,
    speed: speed.buffer,
    accuracy: accuracy.buffer,
  };
  return { row, geometry, extras };
}

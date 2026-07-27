// The distance seam the stay derivation runs on, and the coordinate-box prefilter that keeps its
// two anchor-scanning loops linear enough. Mirrors the app's `DistanceFn` / `ReachBound` pair:
// production passes `metersBetween`, tests inject a flat-earth stub, and everything downstream asks
// the function for the local scale rather than assuming Earth's.

/**
 * WGS84 ellipsoidal distance in meters — the same Vincenty inverse solution
 * `Location.distanceBetween` runs on the phone, so a cluster radius or the stay agreement
 * threshold decides identically in both places. A sphere formula is off by a few tenths of a
 * percent, which is under a metre at these radii but enough to sort a borderline pair of endpoints
 * differently from the app, and two answers to "is this the same place?" is worse than either.
 *
 * The result is rounded to float precision because the app's is a `float` — the difference is far
 * below anything the rules test for, but it costs one call to leave nothing to explain.
 */
export function metersBetween(latA, lonA, latB, lonB) {
  const MAXITERS = 20;
  const a = 6378137.0; // WGS84 major axis
  const b = 6356752.3142; // WGS84 semi-major axis
  const f = (a - b) / a;
  const aSqMinusBSqOverBSq = (a * a - b * b) / (b * b);

  const lat1 = latA * Math.PI / 180;
  const lat2 = latB * Math.PI / 180;
  const L = (lonB - lonA) * Math.PI / 180;

  const U1 = Math.atan((1 - f) * Math.tan(lat1));
  const U2 = Math.atan((1 - f) * Math.tan(lat2));
  const cosU1 = Math.cos(U1);
  const cosU2 = Math.cos(U2);
  const sinU1 = Math.sin(U1);
  const sinU2 = Math.sin(U2);
  const cosU1cosU2 = cosU1 * cosU2;
  const sinU1sinU2 = sinU1 * sinU2;

  let sigma = 0;
  let deltaSigma = 0;
  let A = 0;
  let lambda = L; // initial guess
  for (let iter = 0; iter < MAXITERS; iter++) {
    const lambdaOrig = lambda;
    const cosLambda = Math.cos(lambda);
    const sinLambda = Math.sin(lambda);
    const t1 = cosU2 * sinLambda;
    const t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda;
    const sinSigma = Math.sqrt(t1 * t1 + t2 * t2);
    const cosSigma = sinU1sinU2 + cosU1cosU2 * cosLambda;
    sigma = Math.atan2(sinSigma, cosSigma);
    const sinAlpha = sinSigma === 0 ? 0 : cosU1cosU2 * sinLambda / sinSigma;
    const cosSqAlpha = 1 - sinAlpha * sinAlpha;
    const cos2SM = cosSqAlpha === 0 ? 0 : cosSigma - 2 * sinU1sinU2 / cosSqAlpha;

    const uSquared = cosSqAlpha * aSqMinusBSqOverBSq;
    A = 1 + (uSquared / 16384) * (4096 + uSquared * (-768 + uSquared * (320 - 175 * uSquared)));
    const B = (uSquared / 1024) * (256 + uSquared * (-128 + uSquared * (74 - 47 * uSquared)));
    const C = (f / 16) * cosSqAlpha * (4 + f * (4 - 3 * cosSqAlpha));
    const cos2SMSq = cos2SM * cos2SM;
    deltaSigma = B * sinSigma * (cos2SM + (B / 4)
      * (cosSigma * (-1 + 2 * cos2SMSq)
        - (B / 6) * cos2SM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SMSq)));

    lambda = L + (1 - C) * f * sinAlpha
      * (sigma + C * sinSigma * (cos2SM + C * cosSigma * (-1 + 2 * cos2SM * cos2SM)));

    if (Math.abs((lambda - lambdaOrig) / lambda) < 1.0e-12) break;
  }
  return Math.fround(b * A * (sigma - deltaSigma));
}

/** Probe span in degrees (~100 m) — see the app's ReachBound for why it is sampled, not assumed. */
const PROBE_DEGREES = 0.001;

/**
 * How far past a radius the box still admits: the sampled scale is a measurement at one point, not
 * a proven bound over the whole box, and one part in a thousand covers the drift many times over.
 */
const SLACK = 1.001;

/**
 * A coordinate-box test that rules out anchors too far from ([lat], [lon]) to capture it, without
 * paying for a [distance] call. Both bounds understate the separation, so a candidate that would
 * qualify is never rejected — over-admitting costs only the distance call that would have run.
 *
 * The scale is asked of [distance], never assumed to be Earth's: a bound built on hardcoded
 * meters-per-degree would silently reject qualifying candidates under any other scale, which is
 * exactly what the tests inject.
 */
export function reachBound(lat, lon, distance) {
  // The latitude probe runs toward the equator so it cannot step past a pole, and toward the
  // equator the meridian is shorter — one more reason the bound can only understate.
  const latProbe = lat >= 0 ? -PROBE_DEGREES : PROBE_DEGREES;
  const latPerDegree = distance(lat, lon, lat + latProbe, lon) / PROBE_DEGREES;
  const lonPerDegree = distance(lat, lon, lat, lon + PROBE_DEGREES) / PROBE_DEGREES;
  return (otherLat, otherLon, radiusM) => {
    const reach = radiusM * SLACK;
    return Math.abs(otherLat - lat) * latPerDegree > reach
      || Math.abs(otherLon - lon) * lonPerDegree > reach;
  };
}

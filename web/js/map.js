// The MapLibre map: an all-tracks overview (simplified geometries, colored by activity) with a
// full-resolution layer for the selected track. Mirrors the app's map conventions where they matter:
// Protomaps basemap, the path as one polyline, rejected fixes as markers colored by why they were
// rejected, and the recorder's overrun as a grayed leg hanging off the path rather than as noise.

import {
  REASON_NONE, REASON_ACCURACY, REASON_JUMP, REASON_NO_GNSS, REASON_EDGE_STAY,
} from "./convert.js";

export const ACTIVITY_COLORS = {
  WALKING: "#4ade80",
  RUNNING: "#fbbf24",
  CYCLING: "#a78bfa",
  DRIVING: "#60a5fa",
  TAXI: "#38bdf8",
  UNKNOWN: "#9ca3af",
};

export function activityColor(activityType) {
  return ACTIVITY_COLORS[activityType] ?? ACTIVITY_COLORS.UNKNOWN;
}

// Overview paint, unselected state: every track in its activity color.
const OVERVIEW_COLOR = ["get", "color"];
const OVERVIEW_OPACITY = 0.4;
// …and with one track picked: the rest recede to a neutral gray so the selection is the only
// colored thing on the map, while the selected track's own overview line drops out entirely —
// it is a simplified geometry, and leaving it under the full-resolution line shows as a ghost
// wandering off the corners the simplification cut.
const MUTED_COLOR = "#6b7280";
const MUTED_OPACITY = 0.25;

// Places are history-wide, not per-track, so relatedness is geometric: a place is this trip's stop
// when the trip started or ended at it. Somewhere merely passed en route is not a stop of it. The
// radius covers a named place's own capture size plus the gap left where the recorder's overrun
// was trimmed off the track's ends.
// Rejected-fix marker colors, matching the app's legend chips (ic_marker_noisy / _jump / _gnss) so
// the same fix reads the same in both. EDGE_STAY never reaches this layer.
export const REASON_LABELS = {
  [REASON_ACCURACY]: "Low accuracy",
  [REASON_JUMP]: "Speed jump",
  [REASON_NO_GNSS]: "No satellite fix",
};
export const REASON_COLORS = {
  [REASON_ACCURACY]: "#ff8f00",
  [REASON_JUMP]: "#e53935",
  [REASON_NO_GNSS]: "#ab47bc",
};

// The recorder's overrun, in the app's own dim gray at the app's width — wider than the path line,
// so no colored fringe survives where the two meet.
export const OVERRUN_COLOR = "#424242";
const OVERRUN_OPACITY = 0.85;
const OVERRUN_WIDTH = 4;

const PLACE_COLOR = "#facc15";
const RELATED_PLACE_RADIUS_M = 150;
const RELATED = ["get", "related"];
// A filled dot and its label read fainter than a line at the same alpha, so the unrelated places
// sit above the tracks' muted level — still context, still legible as a name.
const MUTED_PLACE_OPACITY = 0.4;

export function createMap(container, protomapsKey, onTrackClick) {
  const map = new maplibregl.Map({
    container,
    style: `https://api.protomaps.com/styles/v5/dark/en.json?key=${protomapsKey}`,
    center: [0, 20],
    zoom: 1.5,
    attributionControl: { compact: true },
  });
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");

  map.on("load", () => {
    map.addSource("overview", { type: "geojson", data: emptyFc() });
    map.addLayer({
      id: "overview-lines",
      type: "line",
      source: "overview",
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": OVERVIEW_COLOR,
        "line-width": 1.6,
        "line-opacity": OVERVIEW_OPACITY,
      },
    });
    map.addSource("selected", { type: "geojson", data: emptyFc() });
    map.addLayer({
      id: "selected-casing",
      type: "line",
      source: "selected",
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": "#0b0e14", "line-width": 6, "line-opacity": 0.8 },
    });
    map.addLayer({
      id: "selected-line",
      type: "line",
      source: "selected",
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": ["get", "color"], "line-width": 3 },
    });
    // Over the path line and off its ends: those fixes are no longer part of the line at all, so
    // there is nothing underneath to dim instead.
    map.addSource("overrun", { type: "geojson", data: emptyFc() });
    map.addLayer({
      id: "overrun-lines",
      type: "line",
      source: "overrun",
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": OVERRUN_COLOR,
        "line-width": OVERRUN_WIDTH,
        "line-opacity": OVERRUN_OPACITY,
      },
    });
    map.addSource("ignored", { type: "geojson", data: emptyFc() });
    map.addLayer({
      id: "ignored-points",
      type: "circle",
      source: "ignored",
      paint: {
        "circle-radius": 3.5,
        // Colored by why the fix was rejected — one marker color for everything hides a teleport
        // among a cluster of weak fixes.
        "circle-color": [
          "match", ["get", "reason"],
          REASON_JUMP, REASON_COLORS[REASON_JUMP],
          REASON_NO_GNSS, REASON_COLORS[REASON_NO_GNSS],
          REASON_COLORS[REASON_ACCURACY],
        ],
        "circle-opacity": 0.8,
        "circle-stroke-width": 1,
        "circle-stroke-color": "#0b0e14",
      },
    });

    map.addSource("places", { type: "geojson", data: emptyFc() });
    map.addLayer({
      id: "place-dots",
      type: "circle",
      source: "places",
      paint: {
        "circle-radius": 4,
        "circle-color": ["case", RELATED, PLACE_COLOR, MUTED_COLOR],
        "circle-opacity": ["case", RELATED, 1, MUTED_PLACE_OPACITY],
        "circle-stroke-width": 1.5,
        "circle-stroke-color": "#0b0e14",
      },
    });
    map.addLayer({
      id: "place-labels",
      type: "symbol",
      source: "places",
      layout: {
        "text-field": ["get", "label"],
        "text-font": ["Noto Sans Medium"],
        "text-size": 12,
        "text-offset": [0, 1.1],
        "text-anchor": "top",
        "text-optional": true,
      },
      paint: {
        "text-color": ["case", RELATED, PLACE_COLOR, MUTED_COLOR],
        "text-opacity": ["case", RELATED, 1, MUTED_PLACE_OPACITY],
        "text-halo-color": "#0b0e14",
        "text-halo-width": 1.4,
      },
    });

    map.on("click", "overview-lines", (e) => {
      const f = e.features?.[0];
      if (f) onTrackClick(f.properties.id);
    });
    map.on("mouseenter", "overview-lines", () => { map.getCanvas().style.cursor = "pointer"; });
    map.on("mouseleave", "overview-lines", () => { map.getCanvas().style.cursor = ""; });
  });

  return map;
}

// Repaints the overview for the current selection (null = nothing selected). Paint properties
// survive a setData, so this is state the layer carries, not something setOverview re-applies.
function paintOverview(map, selectedId) {
  if (selectedId == null) {
    map.setPaintProperty("overview-lines", "line-color", OVERVIEW_COLOR);
    map.setPaintProperty("overview-lines", "line-opacity", OVERVIEW_OPACITY);
    return;
  }
  const isSelected = ["==", ["get", "id"], selectedId];
  map.setPaintProperty("overview-lines", "line-color", ["case", isSelected, OVERVIEW_COLOR, MUTED_COLOR]);
  map.setPaintProperty("overview-lines", "line-opacity", ["case", isSelected, 0, MUTED_OPACITY]);
}

const placesByMap = new WeakMap();

// Rebuilds the place pins, flagging each as related to the selected track's endpoints
// ([[lon, lat], [lon, lat]], or null when nothing is selected — then every place is related).
function paintPlaces(map, endpoints) {
  const places = placesByMap.get(map) ?? [];
  map.getSource("places").setData(fc(places.map((p) => pointFeature([p.lon, p.lat], {
    label: p.label,
    related: endpoints == null || endpoints.some(
      ([lon, lat]) => metersBetween(p.lon, p.lat, lon, lat) <= RELATED_PLACE_RADIUS_M,
    ),
  }))));
}

// Equirectangular approximation — exact enough either side of a 150 m threshold, and it keeps the
// viewer free of a geo dependency.
function metersBetween(lonA, latA, lonB, latB) {
  const perDegree = 111_320;
  const dLat = (latA - latB) * perDegree;
  const dLon = (lonA - lonB) * perDegree * Math.cos(((latA + latB) / 2) * Math.PI / 180);
  return Math.hypot(dLat, dLon);
}

function emptyFc() {
  return { type: "FeatureCollection", features: [] };
}

function fc(features) {
  return { type: "FeatureCollection", features };
}

function lineFeature(coordinates, properties = {}) {
  return { type: "Feature", properties, geometry: { type: "LineString", coordinates } };
}

function pointFeature(coordinates, properties = {}) {
  return { type: "Feature", properties, geometry: { type: "Point", coordinates } };
}

// Readiness means "the load handler ran, so the sources exist" — which is monotonic, unlike
// map.loaded() (false again whenever tiles stream or the camera moves, long after "load" has
// fired — gating on it would silently drop calls queued on a once-only event).
function whenLoaded(map, fn) {
  if (map.getSource("overview")) fn();
  else map.once("load", fn);
}

/** Rebuilds the overview from track rows ({id, activityType, overview: ArrayBuffer}). */
export function setOverview(map, tracks) {
  whenLoaded(map, () => {
    const features = [];
    const bounds = new maplibregl.LngLatBounds();
    for (const t of tracks) {
      const coords = new Float64Array(t.overview);
      if (coords.length < 4) continue;
      const line = [];
      for (let i = 0; i < coords.length; i += 2) line.push([coords[i], coords[i + 1]]);
      features.push(lineFeature(line, { id: t.id, color: activityColor(t.activityType) }));
      if (t.bbox) {
        bounds.extend([t.bbox[0], t.bbox[1]]);
        bounds.extend([t.bbox[2], t.bbox[3]]);
      }
    }
    map.getSource("overview").setData(fc(features));
    if (!bounds.isEmpty()) map.fitBounds(bounds, { padding: 48, duration: 0 });
  });
}

/** Shows the user-named places as labeled pins ({label, lat, lon} rows from the export). */
export function setPlaces(map, places) {
  // Held per map because relatedness is recomputed on every selection, and a GeoJSON source
  // won't hand its data back.
  placesByMap.set(map, places ?? []);
  whenLoaded(map, () => paintPlaces(map, null));
}

/**
 * Shows or hides the place pins. Layout visibility rather than opacity, so hidden places also
 * stop competing for label space with anything the basemap wants to put there.
 */
export function setPlacesVisible(map, visible) {
  whenLoaded(map, () => {
    for (const layer of ["place-dots", "place-labels"]) {
      map.setLayoutProperty(layer, "visibility", visible ? "visible" : "none");
    }
  });
}

/**
 * Splits a track's points the three ways they are drawn, mirroring the app's own split: the path,
 * the fixes rejected for quality, and the recorder's overrun grouped into runs.
 *
 * The path is ONE polyline. A segment break says the recorder stopped watching, not that the phone
 * stopped moving — the app draws straight through it on the map for the same reason, and its
 * distance now counts that ground. Where the break matters (GPX segments) the flag is carried, not
 * the drawing.
 *
 * Each overrun run is anchored to the good fix either side of it, so the grayed leg meets the path
 * instead of floating short of it. A run between two good fixes — what a merge leaves buried
 * mid-track — is connected on both sides rather than dropped: it is still recording the app knows
 * about, and a viewer that draws nothing there is the one place its own data goes missing.
 *
 * Exported for the node test; nothing else outside this module calls it.
 */
export function splitForDrawing(lonlat, reasons, n) {
  const path = [];
  const rejected = [];
  const overruns = [];
  let run = null;
  for (let i = 0; i < n; i++) {
    const c = [lonlat[i * 2], lonlat[i * 2 + 1]];
    const reason = reasons[i];
    if (reason === REASON_NONE) {
      if (run) {
        run.push(c); // close it onto the fix that resumes the path
        overruns.push(run);
        run = null;
      }
      path.push(c);
    } else if (reason === REASON_EDGE_STAY) {
      // A rejected fix inside a stay doesn't end it — the phone was still parked.
      if (!run) run = path.length ? [path.at(-1)] : [];
      run.push(c);
    } else {
      rejected.push(pointFeature(c, { reason }));
    }
  }
  if (run) overruns.push(run);
  return { path, rejected, overruns };
}

/**
 * Draws one track at full resolution and returns what it drew — `{ rejected: {reason: count},
 * overruns: n }` — for the legend, which names only the categories actually present.
 */
export function showTrack(map, track, geometry) {
  const lonlat = new Float64Array(geometry.lonlat);
  // Imports before the reason byte existed can't reach here (the store version forces a re-import),
  // so a missing array would be a bug, not an old file.
  const reasons = new Uint8Array(geometry.reasons);
  const { path, rejected, overruns } = splitForDrawing(lonlat, reasons, geometry.count);

  whenLoaded(map, () => {
    const color = activityColor(track.activityType);
    map.getSource("selected").setData(
      fc(path.length >= 2 ? [lineFeature(path, { color })] : []),
    );
    map.getSource("overrun").setData(fc(overruns.filter((r) => r.length >= 2).map(lineFeature)));
    map.getSource("ignored").setData(fc(rejected));
    paintOverview(map, track.id);
    // The good fixes the trip ran between, including a single one the line above can't draw. A
    // track with nothing but ignored fixes has no endpoints to judge places by, so it mutes none.
    paintPlaces(map, path.length ? [path[0], path.at(-1)] : null);
    if (track.bbox) {
      map.fitBounds([[track.bbox[0], track.bbox[1]], [track.bbox[2], track.bbox[3]]], {
        padding: 64,
        duration: 300,
        maxZoom: 17,
      });
    }
  });

  const counts = {};
  for (const f of rejected) {
    counts[f.properties.reason] = (counts[f.properties.reason] ?? 0) + 1;
  }
  return { rejected: counts, overruns: overruns.length };
}

export function clearSelection(map) {
  whenLoaded(map, () => {
    map.getSource("selected").setData(emptyFc());
    map.getSource("overrun").setData(emptyFc());
    map.getSource("ignored").setData(emptyFc());
    paintOverview(map, null);
    paintPlaces(map, null);
  });
}

// The MapLibre map: an all-tracks overview (simplified geometries, colored by activity) with a
// full-resolution layer for the selected track. Mirrors the app's map conventions where they matter:
// Protomaps basemap, the path as one polyline, rejected fixes as markers colored by why they were
// rejected, and the recorder's overrun as a grayed leg hanging off the path rather than as noise.

import {
  REASON_NONE, REASON_ACCURACY, REASON_JUMP, REASON_NO_GNSS, REASON_EDGE_STAY,
} from "./convert.js";
import { metersBetween } from "./geo.js";

export const ACTIVITY_COLORS = {
  WALKING: "#4ade80",
  RUNNING: "#fbbf24",
  CYCLING: "#a78bfa",
  DRIVING: "#60a5fa",
  TAXI: "#38bdf8",
  FERRY: "#f472b6",
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
// Places are history-wide, not per-track, so relatedness is geometric: a place is this trip's stop
// when the trip started or ended at it. Somewhere merely passed en route is not a stop of it. The
// radius covers a named place's own capture size plus the gap left where the recorder's overrun
// was trimmed off the track's ends.
const RELATED_PLACE_RADIUS_M = 150;
const RELATED = ["get", "related"];
const NAMED = ["get", "named"];
// Both ranks of place marker are clickable, and a label is as much a target as its dot.
const PLACE_LAYERS = ["place-dots", "place-labels"];
// A filled dot and its label read fainter than a line at the same alpha, so the unrelated places
// sit above the tracks' muted level — still context, still legible as a name.
const MUTED_PLACE_OPACITY = 0.4;

export function createMap(container, protomapsKey, onTrackClick, onPlaceClick) {
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
    // A selected stay's place: its capture circle and the endpoints it captured. Added before the
    // track layers so the circle sits under any line crossing it, as the app draws it.
    map.addSource("focus", { type: "geojson", data: emptyFc() });
    map.addLayer({
      id: "focus-fill",
      type: "fill",
      source: "focus",
      filter: ["==", ["geometry-type"], "Polygon"],
      paint: { "fill-color": PLACE_COLOR, "fill-opacity": 0.1 },
    });
    map.addLayer({
      id: "focus-outline",
      type: "line",
      source: "focus",
      filter: ["==", ["geometry-type"], "Polygon"],
      paint: { "line-color": PLACE_COLOR, "line-width": 1.2, "line-opacity": 0.6 },
    });
    map.addLayer({
      id: "focus-endpoints",
      type: "circle",
      source: "focus",
      filter: ["==", ["geometry-type"], "Point"],
      paint: {
        "circle-radius": 3,
        "circle-color": PLACE_COLOR,
        "circle-opacity": 0.75,
        "circle-stroke-width": 1,
        "circle-stroke-color": "#0b0e14",
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
    // Over the path line and off its ends: those fixes are not part of the line at all, so
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
        // Named places are the pins; an unnamed cluster is a smaller, fainter dot with no label —
        // the app's places map draws the same two ranks, by marker icon rather than by size.
        "circle-radius": ["case", NAMED, 4, 2.5],
        "circle-color": ["case", RELATED, PLACE_COLOR, MUTED_COLOR],
        "circle-opacity": ["case", RELATED, ["case", NAMED, 1, 0.7], MUTED_PLACE_OPACITY],
        "circle-stroke-width": ["case", NAMED, 1.5, 0.8],
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
      // A place marker sitting on a track line is the deliberate target of the two: it is small,
      // and a track can be picked anywhere else along its length.
      if (map.queryRenderedFeatures(e.point, { layers: PLACE_LAYERS }).length) return;
      const f = e.features?.[0];
      if (f) onTrackClick(f.properties.id);
    });
    for (const layer of PLACE_LAYERS) {
      map.on("click", layer, (e) => {
        const f = e.features?.[0];
        if (f) onPlaceClick(f.properties.id);
      });
    }
    for (const layer of ["overview-lines", ...PLACE_LAYERS]) {
      map.on("mouseenter", layer, () => { map.getCanvas().style.cursor = "pointer"; });
      map.on("mouseleave", layer, () => { map.getCanvas().style.cursor = ""; });
    }
  });

  return map;
}

/** Repaints the overview for the current selection: a track id lights that track and mutes the
 * rest, null lights everything, [MUTE_ALL] mutes everything — what a place selection wants, since
 * a place belongs to no one trip. Paint properties survive a setData, so this is state the layer
 * carries, not something setOverview re-applies. */
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

/** A selection that is no track's: every line mutes. No track id can collide with it. */
const MUTE_ALL = -1;

/** The sources one selection owns — cleared together whenever the selection changes. */
const SELECTION_SOURCES = ["selected", "overrun", "ignored", "focus"];

function clearSelectionSources(map) {
  for (const id of SELECTION_SOURCES) map.getSource(id).setData(emptyFc());
}

const placesByMap = new WeakMap();

// Rebuilds the place markers, flagging each as related to the selected track's endpoints
// ([[lon, lat], [lon, lat]], or null when nothing is selected — then every place is related).
function paintPlaces(map, endpoints) {
  const places = placesByMap.get(map) ?? [];
  map.getSource("places").setData(fc(places.map((p) => pointFeature([p.lon, p.lat], {
    id: p.id,
    label: p.label ?? "",
    named: p.label != null,
    related: endpoints == null || endpoints.some(
      ([lon, lat]) => metersBetween(p.lat, p.lon, lat, lon) <= RELATED_PLACE_RADIUS_M,
    ),
  }))));
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

function polygonFeature(coordinates, properties = {}) {
  return { type: "Feature", properties, geometry: { type: "Polygon", coordinates } };
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

/** Shows the places: `{id, lat, lon, label}` rows — the *derived* clusters, not the export's
 * named-place list, so an unnamed place the history keeps returning to appears as a dot the same
 * way it does on the app's places map; a row with no label is one of those. Which clusters arrive
 * here is the caller's filter (`mapVisiblePlaces`) — this draws what it is given, and hands [id]
 * back on click. */
export function setPlaces(map, places) {
  // Held per map because relatedness is recomputed on every selection, and a GeoJSON source won't
  // hand its data back. Sorted here rather than per repaint: unnamed dots first, so the named pins
  // draw — and label — on top of them.
  placesByMap.set(map, (places ?? []).slice()
    .sort((a, b) => Number(a.label != null) - Number(b.label != null)));
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

/** Frames one or both places a timeline interval sits at: each as its capture circle plus the
 * track endpoints the cluster captured — the app's place view, and the same picture that explains
 * a gap (two circles where one place split in two). Tracks recede to the muted level throughout:
 * the selection here is a place, so no single track is the subject.
 * @param places [{anchor: {lat, lon}, radiusM, endpoints: [{lat, lon}]}] */
export function focusPlaces(map, places) {
  whenLoaded(map, () => {
    const features = [];
    const bounds = new maplibregl.LngLatBounds();
    for (const place of places) {
      const ring = circleRing(place.anchor, place.radiusM);
      features.push(polygonFeature([ring]));
      for (const [lon, lat] of ring) bounds.extend([lon, lat]);
      for (const e of place.endpoints ?? []) features.push(pointFeature([e.lon, e.lat]));
    }
    // Whatever was drawn belongs to the previous selection.
    clearSelectionSources(map);
    map.getSource("focus").setData(fc(features));
    paintOverview(map, MUTE_ALL);
    // Relatedness is about a trip's stops; with a place selected, its own pin should stay lit
    // along with every other, so the labels around it stay readable.
    paintPlaces(map, null);
    if (!bounds.isEmpty()) map.fitBounds(bounds, { padding: 96, duration: 300, maxZoom: 17 });
  });
}

/**
 * A capture circle as a polygon ring — 64 segments is smooth at any zoom the viewer reaches. The
 * ring is drawn with a flat meters-per-degree, not the ellipsoidal distance the rules are decided
 * on: this is a shape on a screen, and the two differ by less than the stroke is wide.
 */
function circleRing(center, radiusM) {
  const SEGMENTS = 64;
  const METERS_PER_DEGREE = 111_320;
  const dLat = radiusM / METERS_PER_DEGREE;
  const dLon = radiusM / (METERS_PER_DEGREE * Math.cos(center.lat * Math.PI / 180));
  const ring = [];
  for (let i = 0; i <= SEGMENTS; i++) {
    const angle = (i / SEGMENTS) * 2 * Math.PI;
    ring.push([center.lon + dLon * Math.cos(angle), center.lat + dLat * Math.sin(angle)]);
  }
  return ring;
}

/** Splits a track's points the three ways they are drawn, mirroring the app's own split: the path,
 * the fixes rejected for quality, and the recorder's overrun grouped into runs. The path is ONE
 * polyline — a segment break says the recorder stopped watching, not that the phone stopped
 * moving; the app draws straight through it for the same reason and its distance counts that
 * ground, and where the break matters (GPX segments) the flag is carried, not the drawing. Each
 * overrun run is anchored to the good fix either side, so the grayed leg meets the path instead of
 * floating short of it; a run between two good fixes — what a merge leaves buried mid-track — is
 * connected on both sides rather than dropped: it is still recording the app knows about, and a
 * viewer that draws nothing there is the one place its own data goes missing.
 * Exported for the node test; nothing else outside this module calls it. */
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
    clearSelectionSources(map);
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
    clearSelectionSources(map);
    paintOverview(map, null);
    paintPlaces(map, null);
  });
}

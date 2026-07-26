// The MapLibre map: an all-tracks overview (simplified geometries, colored by activity) with a
// full-resolution layer for the selected track. Mirrors the app's map conventions where they
// matter: Protomaps basemap, ignored fixes as markers, segment gaps not drawn as lines.

import { FLAG_IGNORED, FLAG_SEGMENT_START } from "./convert.js";

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
    map.addSource("ignored", { type: "geojson", data: emptyFc() });
    map.addLayer({
      id: "ignored-points",
      type: "circle",
      source: "ignored",
      paint: {
        "circle-radius": 3.5,
        "circle-color": "#f87171",
        "circle-opacity": 0.7,
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

/** Draws one track at full resolution: segments split at auto-pause gaps, ignored fixes marked. */
export function showTrack(map, track, geometry) {
  whenLoaded(map, () => {
    const lonlat = new Float64Array(geometry.lonlat);
    const flags = new Uint8Array(geometry.flags);
    const n = geometry.count;
    const segments = [];
    let current = [];
    const ignored = [];
    for (let i = 0; i < n; i++) {
      const lon = lonlat[i * 2];
      const lat = lonlat[i * 2 + 1];
      if (flags[i] & FLAG_IGNORED) {
        ignored.push([lon, lat]);
        continue;
      }
      if (flags[i] & FLAG_SEGMENT_START && current.length) {
        segments.push(current);
        current = [];
      }
      current.push([lon, lat]);
    }
    if (current.length) segments.push(current);

    const color = activityColor(track.activityType);
    map.getSource("selected").setData(
      fc(segments.filter((s) => s.length >= 2).map((coords) => lineFeature(coords, { color }))),
    );
    map.getSource("ignored").setData(fc(ignored.map((c) => pointFeature(c))));
    paintOverview(map, track.id);
    // The good fixes the trip ran between — `segments` holds every one of them, including the
    // single-point ones the line filter above drops. A track with nothing but ignored fixes has
    // no endpoints to judge places by, so it mutes none of them.
    const first = segments[0]?.[0];
    const last = segments.at(-1)?.at(-1);
    paintPlaces(map, first ? [first, last] : null);
    if (track.bbox) {
      map.fitBounds([[track.bbox[0], track.bbox[1]], [track.bbox[2], track.bbox[3]]], {
        padding: 64,
        duration: 300,
        maxZoom: 17,
      });
    }
  });
}

export function clearSelection(map) {
  whenLoaded(map, () => {
    map.getSource("selected").setData(emptyFc());
    map.getSource("ignored").setData(emptyFc());
    paintOverview(map, null);
    paintPlaces(map, null);
  });
}

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
        "line-color": ["get", "color"],
        "line-width": 1.6,
        "line-opacity": 0.55,
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
        "circle-color": "#facc15",
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
        "text-color": "#facc15",
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
  whenLoaded(map, () => {
    map.getSource("places").setData(
      fc((places ?? []).map((p) => pointFeature([p.lon, p.lat], { label: p.label }))),
    );
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
  });
}

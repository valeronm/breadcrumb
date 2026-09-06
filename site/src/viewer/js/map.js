// The MapLibre map: an all-tracks overview (simplified geometries, colored by activity) with a
// full-resolution layer for the selected track. Mirrors the app's map conventions where they matter:
// Protomaps basemap, the path cut where the recorder stopped watching, rejected fixes as markers
// colored by why they were rejected, and the recorder's overrun as a grayed leg hanging off the
// path rather than as noise. `maplibregl` is the page's vendored script, read as a global rather
// than imported so that the draw suite can load this module under node, where MapLibre cannot load.

import {
  FLAG_SEGMENT_START,
  REASON_NONE, REASON_ACCURACY, REASON_JUMP, REASON_NO_GNSS, REASON_EDGE_STAY,
} from "./convert.js";
import { greatCircleArc, metersBetween } from "./geo.js";
import { activityColor } from "./discs.js";
import { addPinImages, pinImageId, pinImages } from "./pins.js";

// Overview paint, unselected state: every track in its activity color.
const OVERVIEW_COLOR = ["get", "color"];
const OVERVIEW_OPACITY = 0.4;
// …and with one track picked: the rest recede to a neutral gray so the selection is the only
// colored thing on the map, while the selected track's own overview line drops out entirely —
// it is a simplified geometry, and leaving it under the full-resolution line shows as a ghost
// wandering off the corners the simplification cut.
const MUTED_COLOR = "#6b7280";
const MUTED_OPACITY = 0.25;
const lineColor = (activityType) => activityColor(activityType) ?? MUTED_COLOR;

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

// The capture area as the app draws it (ui/MapLayers.kt): one soft blue for every place, the pin
// alone saying what the place is for.
const CAPTURE_COLOR = "#5b9bf0";
// Places are history-wide, not per-track, so relatedness is geometric: a place is this trip's stop
// when the trip started or ended at it. Somewhere merely passed en route is not a stop of it. The
// radius covers a named place's own capture size plus the gap left where the recorder's overrun
// was trimmed off the track's ends.
const RELATED_PLACE_RADIUS_M = 150;
const RELATED = ["get", "related"];
const NAMED = ["get", "named"];
// Both ranks of place marker are clickable, and a label is as much a target as its pin.
const PLACE_LAYERS = ["place-dots", "place-pins"];
// Whole numbers only: a layout property's camera expression is sampled at integer zooms, so a
// fractional threshold silently takes effect at the integer above it.
const PIN_GLYPH_ZOOM = 9;
const PIN_LABEL_ZOOM = 11;
// A filled dot and its label read fainter than a line at the same alpha, so the unrelated places
// sit above the tracks' muted level — still context, still legible as a name.
const MUTED_PLACE_OPACITY = 0.4;

// The overlay colours that only read against one ground: the ground itself, which halos a label
// and cases the selected line, the ink a label is written in, and the dot an unnamed cluster is.
// Read off the page's tokens, so the map's ground is the pane's in either theme.
function readTheme() {
  const token = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return { ground: token("--ground"), ink: token("--text"), dot: token("--muted") };
}

// The overlay is the previous style's geojson sources, data and all, and their layers, carried
// into the next style whole: what a switch changes is the basemap under them.
function carryOverlay(previous, next) {
  const sources = Object.fromEntries(
    Object.entries(previous.sources).filter(([, source]) => source.type === "geojson"),
  );
  const layers = previous.layers.filter((layer) => layer.source in sources);
  return { ...next, sources: { ...next.sources, ...sources }, layers: [...next.layers, ...layers] };
}

export function createMap(container, protomapsKey, onTrackClick, onPlaceClick) {
  const lightScheme = window.matchMedia("(prefers-color-scheme: light)");
  const styleUrl = () =>
    `https://api.protomaps.com/styles/v5/${lightScheme.matches ? "light" : "dark"}/en.json?key=${protomapsKey}`;
  const map = new maplibregl.Map({
    container,
    style: styleUrl(),
    center: [0, 20],
    zoom: 1.5,
    // The pins' glyphs are Material Icons, whose licence wants the notice to travel with them.
    attributionControl: {
      compact: true,
      customAttribution: 'Icons © <a href="https://fonts.google.com/icons">Google</a>, Apache 2.0',
    },
  });
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
  // Drawn while the style is still being fetched, rather than in front of its first frame.
  pinImages();

  // Per style load, not once: a theme change loads a new style. The overlay layers come with the
  // first load only, a switch carrying them across.
  map.on("style.load", () => {
    addPinImages(map);
    const theme = readTheme();
    if (map.getSource("overview")) paintTheme(map, theme);
    else installLayers(map, theme);
  });
  // A diffed switch would drop the overlay with no load to reinstall it on.
  lightScheme.addEventListener("change", () => {
    map.setStyle(styleUrl(), { diff: false, transformStyle: carryOverlay });
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

  return map;
}

/** The overlay's layers in drawing order, for one theme. The one statement of what a layer looks
 * like: the first load adds these, and a theme switch re-applies their paint. */
function overlayLayers(theme) {
  const round = { "line-cap": "round", "line-join": "round" };
  return [
    {
      id: "overview-lines",
      type: "line",
      source: "overview",
      layout: round,
      paint: { "line-color": OVERVIEW_COLOR, "line-width": 1.6, "line-opacity": OVERVIEW_OPACITY },
    },
    // A selected stay's place: its capture circle and the endpoints it captured. Before the track
    // layers so the circle sits under any line crossing it, as the app draws it.
    {
      id: "focus-fill",
      type: "fill",
      source: "focus",
      filter: ["==", ["geometry-type"], "Polygon"],
      paint: { "fill-color": CAPTURE_COLOR, "fill-opacity": 0.18 },
    },
    {
      id: "focus-outline",
      type: "line",
      source: "focus",
      filter: ["==", ["geometry-type"], "Polygon"],
      paint: { "line-color": CAPTURE_COLOR, "line-width": 1.2, "line-opacity": 0.6 },
    },
    {
      id: "focus-endpoints",
      type: "circle",
      source: "focus",
      filter: ["==", ["geometry-type"], "Point"],
      paint: {
        "circle-radius": 3,
        "circle-color": CAPTURE_COLOR,
        "circle-opacity": 0.75,
        "circle-stroke-width": 1,
        "circle-stroke-color": theme.ground,
      },
    },
    {
      id: "selected-casing",
      type: "line",
      source: "selected",
      layout: round,
      paint: { "line-color": theme.ground, "line-width": 6, "line-opacity": 0.8 },
    },
    {
      id: "selected-line",
      type: "line",
      source: "selected",
      layout: round,
      paint: { "line-color": ["get", "color"], "line-width": 3 },
    },
    // Over the path line and off its ends: those fixes are not part of the line at all, so there
    // is nothing underneath to dim instead.
    {
      id: "overrun-lines",
      type: "line",
      source: "overrun",
      layout: round,
      paint: { "line-color": OVERRUN_COLOR, "line-width": OVERRUN_WIDTH, "line-opacity": OVERRUN_OPACITY },
    },
    {
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
        "circle-stroke-color": theme.ground,
      },
    },
    // The app's places map draws two ranks: a named place is a pin, an unnamed cluster a dot.
    {
      id: "place-dots",
      type: "circle",
      source: "places",
      filter: ["!", NAMED],
      paint: {
        "circle-radius": 2.5,
        "circle-color": ["case", RELATED, theme.dot, MUTED_COLOR],
        "circle-opacity": ["case", RELATED, 0.7, MUTED_PLACE_OPACITY],
        "circle-stroke-width": 0.8,
        "circle-stroke-color": theme.ground,
      },
    },
    {
      id: "place-pins",
      type: "symbol",
      source: "places",
      filter: NAMED,
      layout: {
        // A pin gains its glyph first and its name second — shape, then word — and shrinks the
        // wider the view, since a wide view asks where the places are, not which one is which.
        "icon-image": ["step", ["zoom"], ["get", "disc"], PIN_GLYPH_ZOOM, ["get", "pin"]],
        "icon-size": ["interpolate", ["linear"], ["zoom"], 8, 0.4, PIN_GLYPH_ZOOM, 0.76, 12, 1],
        "icon-allow-overlap": true,
        "text-field": ["step", ["zoom"], "", PIN_LABEL_ZOOM, ["get", "label"]],
        "text-font": ["Noto Sans Medium"],
        "text-size": 12,
        "text-offset": [0, 1.3],
        "text-anchor": "top",
        "text-optional": true,
      },
      paint: {
        "icon-opacity": ["case", RELATED, 1, MUTED_PLACE_OPACITY],
        "text-color": ["case", RELATED, theme.ink, MUTED_COLOR],
        "text-opacity": ["case", RELATED, 1, MUTED_PLACE_OPACITY],
        "text-halo-color": theme.ground,
        "text-halo-width": 1.4,
      },
    },
  ];
}

function installLayers(map, theme) {
  const layers = overlayLayers(theme);
  for (const source of new Set(layers.map((l) => l.source))) {
    map.addSource(source, { type: "geojson", data: emptyFc() });
  }
  for (const layer of layers) map.addLayer(layer);
}

// The overview's paint is the selection (paintOverview), so a switch leaves it as carried.
function paintTheme(map, theme) {
  for (const layer of overlayLayers(theme)) {
    if (layer.id === "overview-lines") continue;
    for (const [prop, value] of Object.entries(layer.paint)) map.setPaintProperty(layer.id, prop, value);
  }
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
    pin: pinImageId(p.category, true),
    disc: pinImageId(p.category, false),
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

/** A manual track's typed legs drawn along their great circles ([greatCircleArc]) — the app's
 * display rule, applied at the same depth: the stored geometry stays the two typed fixes, only
 * the line the map draws is densified. Each leg starts from the previous drawn position, so the
 * unwrapped longitudes stay continuous across an antimeridian however many legs cross it. */
function arcLine(line) {
  if (line.length < 2) return line;
  const out = [line[0]];
  for (let i = 1; i < line.length; i++) {
    const from = out[out.length - 1];
    const arc = greatCircleArc(from[1], from[0], line[i][1], line[i][0]);
    for (let j = 1; j < arc.length; j++) out.push(arc[j]);
  }
  return out;
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
// Per style load rather than the map's one load, since the overlay is absent until a style is in.
function whenLoaded(map, fn) {
  if (map.getSource("overview")) fn();
  else map.once("style.load", fn);
}

/** Rebuilds the overview from track rows ({id, activityType, overview: ArrayBuffer[]}) — one feature
 * per watched stretch, so a track the recorder stopped watching mid-journey spans the gap here no
 * more than it does at full resolution. Every feature carries the track's id, so a click or a
 * selection still addresses the whole track. */
export function setOverview(map, tracks) {
  whenLoaded(map, () => {
    const features = [];
    const bounds = new maplibregl.LngLatBounds();
    for (const t of tracks) {
      const color = lineColor(t.activityType);
      for (const segment of t.overview) {
        const coords = new Float64Array(segment);
        let line = [];
        for (let i = 0; i < coords.length; i += 2) line.push([coords[i], coords[i + 1]]);
        if (t.source === "manual") line = arcLine(line);
        features.push(lineFeature(line, { id: t.id, color }));
      }
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
    for (const layer of PLACE_LAYERS) {
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
 * the fixes rejected for quality, and the recorder's overrun grouped into runs. The path comes back
 * as SEVERAL polylines, cut wherever a segment break says the recorder stopped watching: the ground
 * across one was covered — distance counts it, here as in the app — but nobody traced it, and a line
 * drawn through it claims a route that was never observed. The flag is read straight off the fix,
 * convert() having already moved it onto the good fix that resumed; nothing here carries state.
 * Each overrun run is anchored to the good fix either side, so the grayed leg meets the path instead
 * of floating short of it — except across a break, where anchoring it would draw the very leg the
 * path just refused to. A run between two good fixes — what a merge leaves buried mid-track — is
 * connected on both sides rather than dropped: it is still recording the app knows about, and a
 * viewer that draws nothing there is the one place its own data goes missing.
 * Exported for the node test; nothing else outside this module calls it. */
export function splitForDrawing(lonlat, reasons, flags, n) {
  const paths = [];
  const rejected = [];
  const overruns = [];
  let current = null;
  let run = null;
  for (let i = 0; i < n; i++) {
    const c = [lonlat[i * 2], lonlat[i * 2 + 1]];
    const reason = reasons[i];
    const resumes = (flags[i] & FLAG_SEGMENT_START) !== 0;
    if (reason === REASON_NONE) {
      if (run) {
        if (!resumes) run.push(c); // close it onto the fix that resumes the path
        overruns.push(run);
        run = null;
      }
      if (resumes || !current) {
        current = [];
        paths.push(current);
      }
      current.push(c);
    } else if (reason === REASON_EDGE_STAY) {
      // A rejected fix inside a stay doesn't end it — the phone was still parked.
      if (!run) run = current?.length ? [current.at(-1)] : [];
      run.push(c);
    } else {
      rejected.push(pointFeature(c, { reason }));
    }
  }
  if (run) overruns.push(run);
  return { paths, rejected, overruns };
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
  const flags = new Uint8Array(geometry.flags);
  const { paths, rejected, overruns } = splitForDrawing(lonlat, reasons, flags, geometry.count);

  whenLoaded(map, () => {
    const color = lineColor(track.activityType);
    const drawn = track.source === "manual" ? paths.map(arcLine) : paths;
    clearSelectionSources(map);
    map.getSource("selected").setData(
      fc(drawn.filter((p) => p.length >= 2).map((p) => lineFeature(p, { color }))),
    );
    map.getSource("overrun").setData(fc(overruns.filter((r) => r.length >= 2).map(lineFeature)));
    map.getSource("ignored").setData(fc(rejected));
    paintOverview(map, track.id);
    // The good fixes the trip ran between, including a single one the line above can't draw — the
    // trip's own two ends, not each segment's, however many breaks fall in between. A track with
    // nothing but ignored fixes has no endpoints to judge places by, so it mutes none.
    paintPlaces(map, paths.length ? [paths[0][0], paths.at(-1).at(-1)] : null);
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

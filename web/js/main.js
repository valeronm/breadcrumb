// UI glue: import flow, timeline, selection. Data: IndexedDB, imported once by the worker; the map
// draws simplified overview geometries, full points only for the selected track. The sidebar is the
// app's timeline, not a track list: tracks interleaved with the stays and gaps derived from their
// endpoints (js/stays.js), newest first, sliced at midnight so every row falls in one day — derived
// "as of" the export time (a backup is a snapshot), so the last stay is open then, not growing per reload.

import { openDb, getMeta, getAllTracks, getGeometry } from "./db.js";
import {
  createMap, setOverview, setPlaces, setPlacesVisible, showTrack, focusPlaces, clearSelection,
  activityColor, REASON_LABELS, REASON_COLORS, OVERRUN_COLOR,
} from "./map.js";
import {
  deriveStays, slicePerDay, interleave, resolveClusters, mapVisiblePlaces, derivationInstant,
  PLACE_RADIUS_M,
} from "./stays.js";
import {
  stayMeta, gapMeta, formatTime, formatDay, formatDate, formatDistance, formatDurationMs, activityLabel,
} from "./format.js";

const $ = (id) => document.getElementById(id);

// Hiding the place pins is a standing preference, not a per-visit one — absent means shown, so a
// first visit and a cleared storage both start with places on. Rare stops go the other way: absent
// means hidden, the app's own default.
const SHOW_PLACES_KEY = "breadcrumb.showPlaces";
const SHOW_RARE_KEY = "breadcrumb.showRareStops";

let db;
let map;
let tracks = [];
// Timeline rows (newest first) and the resolution of every endpoint cluster they index into.
let timeline = [];
let clusterPlaces = [];
// The instant the derivation is as of — the export's own, see the file header.
let nowMs = 0;
// One selection at a time, of two kinds: {kind: "row", id: index} for a timeline row, or
// {kind: "place", id: clusterId} for a place clicked on the map. One variable, so the two can
// never both be set.
let selected = null;
// The rows by timeline index, and the one wearing the highlight.
let rowElements = [];
let selectedRow = null;
// Missing-config message, shown in the always-visible summary slot: the empty-state hint is
// hidden as soon as an import exists, which is exactly when a returning user would hit this.
let configError = null;

async function boot() {
  let key;
  try {
    ({ PROTOMAPS_KEY: key } = await import("../config.js"));
  } catch {
    configError = "Missing web/config.js — copy config.example.js and add your Protomaps API key.";
  }
  db = await openDb();
  if (key) map = createMap("map", key, selectTrackById, selectPlace);

  $("file-input").addEventListener("change", (e) => {
    if (e.target.files[0]) startImport(e.target.files[0]);
  });
  const dropZone = document.body;
  dropZone.addEventListener("dragover", (e) => { e.preventDefault(); });
  dropZone.addEventListener("drop", (e) => {
    e.preventDefault();
    const file = e.dataTransfer.files[0];
    if (file) startImport(file);
  });
  $("import-button").addEventListener("click", () => $("file-input").click());
  const showPlacePins = $("show-places");
  const showRare = $("show-rare");
  showPlacePins.checked = localStorage.getItem(SHOW_PLACES_KEY) !== "0";
  showRare.checked = localStorage.getItem(SHOW_RARE_KEY) === "1";
  // The rare-stops filter picks among the pins; with the pins off it has nothing to say.
  showRare.disabled = !showPlacePins.checked;
  if (map) setPlacesVisible(map, showPlacePins.checked);
  showPlacePins.addEventListener("change", () => {
    localStorage.setItem(SHOW_PLACES_KEY, showPlacePins.checked ? "1" : "0");
    showRare.disabled = !showPlacePins.checked;
    if (map) setPlacesVisible(map, showPlacePins.checked);
  });
  showRare.addEventListener("change", () => {
    localStorage.setItem(SHOW_RARE_KEY, showRare.checked ? "1" : "0");
    paintPlaceLayer();
  });
  // One delegated listener instead of one closure per row (there can be thousands).
  $("timeline").addEventListener("click", (e) => {
    const row = e.target.closest("[data-row]");
    if (row) selectRow(Number(row.dataset.row));
  });

  await refresh();
}

async function refresh() {
  const meta = await getMeta(db);
  if (!meta) {
    $("empty").hidden = false;
    $("timeline").hidden = true;
    $("summary").textContent = configError ?? "";
    return;
  }
  $("empty").hidden = true;
  $("timeline").hidden = false;
  tracks = (await getAllTracks(db)).sort((a, b) => b.startedAt - a.startedAt);
  nowMs = derivationInstant(meta.exportedAt, tracks);
  const stays = buildTimeline(meta.places ?? [], meta.liveness ?? []);
  $("summary").textContent = configError ??
    `${meta.trackCount} tracks · ${stays} stays · ${(meta.pointCount / 1000).toFixed(0)}k points · ` +
    `exported ${formatDate(meta.exportedAt)}`;
  renderList();
  if (map) {
    setOverview(map, tracks);
    paintPlaceLayer();
  }
}

/**
 * Hands the map the places to draw: the derived clusters, filtered the way the app's places map
 * filters them — pass-through clusters never, rare stops only when the toggle asks. A named place
 * sits at its own pin, an unnamed cluster at its anchor.
 */
function paintPlaceLayer() {
  if (!map) return;
  setPlaces(map, mapVisiblePlaces(clusterPlaces, $("show-rare").checked).map((p) => ({
    id: p.clusterId,
    lat: p.anchor.lat,
    lon: p.anchor.lon,
    label: p.label,
  })));
}

/** Runs the derivation and lays out the rows; returns how many stays it found (counted before the
 * per-day slicing, which would count a three-day stay three times). Places seed the clustering in
 * their export order, so a cluster's seedIndex indexes straight back into this list — the same
 * contract the app relies on. */
function buildTimeline(places, liveness) {
  const ascending = tracks.slice().reverse();
  const { intervals, clusters } = deriveStays({
    tracks: ascending.map(toTrackEnd),
    liveness,
    nowMs,
    placePins: places.map((p) => ({
      anchor: { lat: p.lat, lon: p.lon },
      radiusM: p.radiusM ?? PLACE_RADIUS_M,
    })),
  });
  // Resolve over the UNSLICED stays: after slicing, a 3-day stay would count as 3 visits.
  const stays = intervals.filter((i) => i.kind === "stay");
  clusterPlaces = resolveClusters(stays, clusters, places);
  // A stay of no duration is the seam between two tracks that share an instant, and it says nothing
  // about where anyone was. The app keeps one only while it carries the offer to undo the join —
  // there is no merging here, so every seam is a row about nothing. Dropped as the timeline is
  // built, not while rendering: the rows are addressed by index from the map and the highlight.
  timeline = interleave(tracks, slicePerDay(intervals, nowMs))
    .filter((item) => item.kind !== "stay" || item.end !== item.start);
  return stays.length;
}

function toTrackEnd(t) {
  const endpoint = (lat, lon) => (lat == null || lon == null ? null : { lat, lon });
  return {
    trackId: t.id,
    startedAt: t.startedAt,
    endedAt: t.endedAt,
    start: endpoint(t.startLat, t.startLon),
    end: endpoint(t.endLat, t.endLon),
  };
}

// --- import ------------------------------------------------------------------------------------

function startImport(file) {
  const worker = new Worker("./js/import-worker.js", { type: "module" });
  $("progress").hidden = false;
  $("progress").textContent = "Reading…";
  // A worker that fails to even load never gets to post its in-band error message.
  worker.onerror = (e) => {
    $("progress").textContent = `Import failed: ${e.message ?? "worker error"}`;
    worker.terminate();
  };
  worker.onmessage = async (e) => {
    const msg = e.data;
    if (msg.type === "progress") {
      const total = msg.tracksTotal ? ` of ${msg.tracksTotal}` : "";
      $("progress").textContent = `Importing… track ${msg.tracksDone}${total}`;
    } else if (msg.type === "done") {
      $("progress").hidden = true;
      worker.terminate();
      // The imported ids are a fresh set, so a leftover selection would keep its full-resolution
      // line drawn and every other track muted against an id that no longer exists.
      deselect();
      await refresh();
    } else if (msg.type === "error") {
      $("progress").textContent = `Import failed: ${msg.message}`;
      worker.terminate();
    }
  };
  worker.postMessage({ file });
}

// --- timeline ------------------------------------------------------------------------------------

function renderList() {
  const list = $("timeline");
  list.textContent = "";
  rowElements = [];
  let currentDay = "";
  const fragment = document.createDocumentFragment();
  timeline.forEach((item, index) => {
    const day = formatDay(itemStart(item));
    if (day !== currentDay) {
      currentDay = day;
      const h = document.createElement("div");
      h.className = "day-header";
      h.textContent = day;
      fragment.appendChild(h);
    }
    const row = item.kind === "track" ? trackRow(item.track)
      : item.kind === "stay" ? stayRow(item)
        : gapRow(item);
    row.dataset.row = index;
    // Kept by index: highlighting is a lookup rather than a querySelector over a tree that runs to
    // tens of thousands of nodes on a full history.
    rowElements[index] = row;
    fragment.appendChild(row);
  });
  list.appendChild(fragment);
}

function itemStart(item) {
  return item.kind === "track" ? item.track.startedAt : item.start;
}

function trackRow(t) {
  const row = document.createElement("button");
  row.className = "row track-row";
  const dot = document.createElement("span");
  dot.className = "dot";
  dot.style.background = activityColor(t.activityType);
  const label = document.createElement("span");
  label.className = "label";
  label.textContent = `${activityLabel(t.activityType)} · ${formatTime(t.startedAt)}`;
  const stats = document.createElement("span");
  stats.className = "stats";
  stats.textContent = `${formatDistance(t.distanceMeters)} · ${formatDurationMs(t.endedAt - t.startedAt)}`;
  row.append(dot, label, stats);
  return row;
}

/**
 * A stationary period between two tracks. A resolved place shows its label; an unnamed cluster
 * visited often enough to be worth naming shows its visit count instead of a name it hasn't got.
 */
function stayRow(stay) {
  const place = clusterPlaces[stay.clusterId];
  const row = document.createElement("button");
  // The row carries the named flag, not just the label span: the pin's color follows from it, and
  // a class on the row is a plain match where `:has()` would put an invalidation dependency on
  // every row in the list.
  row.className = place?.label ? "row stay-row named" : "row stay-row";
  const pin = document.createElement("span");
  pin.className = "pin";
  const stats = document.createElement("span");
  stats.className = "stats";
  stats.textContent = stayMeta(stay, place, nowMs);
  row.append(pin, placeSpan(place, "Stayed", "label"), stats);
  return row;
}

/** A place's name where it has one, the fallback where it hasn't — named ones read differently. */
function placeSpan(place, fallback, className) {
  const span = document.createElement("span");
  span.className = place?.label ? `${className} named` : className;
  span.textContent = place?.label ?? fallback;
  return span;
}

/** The cluster ids a gap row names, newest-first (destination, then origin) — the one reading of
 * which sides a slice may speak for, so the row and the map it frames cannot disagree. Takes the
 * meta a caller has already computed: building a row costs one pass over the whole history, and the
 * per-row formatting is the dominant part of it. */
function namedSidesOf(gap, meta = gapMeta(gap)) {
  return [meta.namesTo ? gap.toClusterId : null, meta.namesFrom ? gap.fromClusterId : null];
}

/** Movement the recorder missed: the endpoints either side disagree. Most such gaps are really
 * one place clustered as two, so the row names both sides — newest-first, destination above the
 * dashed leg and origin below it, the way the trip ran. A side with no known endpoint renders
 * nothing; its absence is the story, and so is a side [namedSidesOf] withholds because the day this
 * slice covers isn't the day that end happened. */
function gapRow(gap) {
  const row = document.createElement("button");
  row.className = "row gap-row";
  const body = document.createElement("span");
  body.className = "gap-body";
  const side = (clusterId) => (clusterId == null
    ? null
    : placeSpan(clusterPlaces[clusterId], "unnamed place", "label"));
  const gapText = gapMeta(gap);
  const meta = document.createElement("span");
  meta.className = "stats";
  meta.textContent = gapText.text;
  const [to, from] = namedSidesOf(gap, gapText);
  body.append(...[side(to), meta, side(from)].filter(Boolean));
  row.append(body);
  return row;
}

// --- selection -----------------------------------------------------------------------------------

/** Map clicks arrive as a track id; the timeline holds rows, so find the one that draws it. */
function selectTrackById(id) {
  const index = timeline.findIndex((item) => item.kind === "track" && item.track.id === id);
  if (index >= 0) selectRow(index);
}

/**
 * A place clicked on the map, by cluster id: the same view a stay row opens — the capture circle
 * and the endpoints the cluster captured, framed. Clicking it again clears, as a row does.
 */
function selectPlace(clusterId) {
  if (isSelected("place", clusterId)) {
    deselect();
    return;
  }
  const place = clusterPlaces[clusterId];
  if (!place) return;
  select("place", clusterId);
  if (map) focusPlaces(map, [place]);
  renderLegend(null);
}

async function selectRow(index) {
  if (isSelected("row", index)) {
    deselect();
    return;
  }
  select("row", index);
  const item = timeline[index];
  if (item.kind === "track") {
    const geometry = await getGeometry(db, item.track.id);
    renderLegend(geometry && map ? showTrack(map, item.track, geometry) : null);
    return;
  }
  // A stay frames its place; a gap frames the sides its row names, which is the picture that
  // explains it. A day the absence only passes through names none, and framing places it doesn't
  // show would answer a question the row deliberately leaves open.
  const clusterIds = item.kind === "stay" ? [item.clusterId] : namedSidesOf(item);
  const places = clusterIds.map((id) => clusterPlaces[id]).filter(Boolean);
  if (map && places.length) focusPlaces(map, places);
  renderLegend(null);
}

function isSelected(kind, id) {
  return selected?.kind === kind && selected.id === id;
}

/** Takes the selection, moving the row highlight with it — only a row selection wears one. */
function select(kind, id) {
  selectedRow?.classList.remove("selected");
  selected = { kind, id };
  selectedRow = kind === "row" ? rowElements[id] : null;
  selectedRow?.classList.add("selected");
  selectedRow?.scrollIntoView({ block: "nearest" });
}

function deselect() {
  selectedRow?.classList.remove("selected");
  selected = null;
  selectedRow = null;
  if (map) clearSelection(map);
  renderLegend(null);
}

// --- legend ------------------------------------------------------------------------------------

/**
 * Names the off-path fixes drawn for the selected track, one row per category present. Absent
 * categories get no row: most tracks have neither, and an always-visible legend listing zeroes
 * teaches the eye to ignore it.
 */
function renderLegend(drawn) {
  const legend = $("legend");
  legend.textContent = "";
  const rows = [];
  if (drawn?.overruns) {
    rows.push([OVERRUN_COLOR, "Recording overrun", drawn.overruns, true]);
  }
  for (const [reason, count] of Object.entries(drawn?.rejected ?? {})) {
    rows.push([REASON_COLORS[reason], REASON_LABELS[reason], count, false]);
  }
  legend.hidden = rows.length === 0;
  for (const [color, label, count, isLine] of rows) {
    const row = document.createElement("div");
    row.className = "row";
    const swatch = document.createElement("span");
    swatch.className = isLine ? "swatch line" : "swatch";
    swatch.style.background = color;
    const text = document.createElement("span");
    text.textContent = label;
    const n = document.createElement("span");
    n.className = "count";
    n.textContent = count;
    row.append(swatch, text, n);
    legend.appendChild(row);
  }
}

boot();

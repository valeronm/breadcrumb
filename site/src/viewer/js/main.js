// UI glue: import flow, timeline, selection. Data: IndexedDB, imported once by the worker; the map
// draws simplified overview geometries, full points only for the selected track. The sidebar is the
// app's timeline, not a track list: tracks interleaved with the stays and gaps derived from their
// endpoints (js/stays.js), newest first, a stay sliced per day and a gap cut once — derived
// "as of" the export time (a backup is a snapshot), so the last stay is open then, not growing per reload.

import { openDb, clearAll, getMeta, getAllTracks, getGeometry } from "./db.js";
import {
  createMap, setOverview, setPlaces, setPlacesVisible, showTrack, focusPlaces, clearSelection,
  REASON_LABELS, REASON_COLORS, OVERRUN_COLOR,
} from "./map.js";
import { activityColor, activityIcon, categoryPinColor, categoryIcon } from "./discs.js";
import {
  deriveStays, slicePerDay, interleave, resolveClusters, mapVisiblePlaces, derivationInstant,
  hasNoDuration, PLACE_RADIUS_M,
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
// A build without the key still imports and lists.
const MAP_KEY = import.meta.env.PUBLIC_PROTOMAPS_KEY;

async function boot() {
  db = await openDb();
  if (MAP_KEY) {
    map = createMap("map", MAP_KEY, selectTrackById, selectPlace);
  } else {
    $("notice").textContent = "No map: PUBLIC_PROTOMAPS_KEY was not set when the site was built.";
    $("notice").hidden = false;
  }

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
  for (const id of ["import-button", "import-empty"]) {
    $(id).addEventListener("click", () => $("file-input").click());
  }
  $("clear-button").addEventListener("click", async () => {
    await clearAll(db);
    await refresh();
  });
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
  // The rows are about to be replaced, and a selection indexes into them.
  deselect();
  const meta = await getMeta(db);
  $("empty").hidden = Boolean(meta);
  $("head").hidden = !meta;
  $("timeline").hidden = !meta;
  tracks = (await getAllTracks(db)).sort((a, b) => b.startedAt - a.startedAt);
  nowMs = derivationInstant(meta?.exportedAt, tracks);
  const stays = buildTimeline(meta?.places ?? []);
  if (meta) {
    $("summary").textContent = `${meta.trackCount.toLocaleString()} trips · ${stays.toLocaleString()} stays`;
    $("exported").textContent = `Exported ${formatDate(meta.exportedAt)}`;
  }
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
    category: p.category,
  })));
}

/** Runs the derivation and lays out the rows; returns how many stays it found (counted before the
 * per-day slicing, which would count a three-day stay three times). Places seed the clustering in
 * their export order, so a cluster's seedIndex indexes straight back into this list — the same
 * contract the app relies on. */
function buildTimeline(places) {
  const ascending = tracks.slice().reverse();
  const { intervals, clusters } = deriveStays({
    tracks: ascending.map(toTrackEnd),
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
    .filter((item) => item.kind !== "stay" || !hasNoDuration(item));
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
  const worker = new Worker(new URL("./import-worker.js", import.meta.url), { type: "module" });
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
      const done = msg.tracksDone.toLocaleString();
      $("progress").textContent = msg.tracksTotal
        ? `Importing ${done} of ${msg.tracksTotal.toLocaleString()} trips…`
        : `Importing… ${done} trips so far`;
    } else if (msg.type === "done") {
      $("progress").hidden = true;
      worker.terminate();
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
  let card = null;
  const fragment = document.createDocumentFragment();
  timeline.forEach((item, index) => {
    const day = formatDay(itemStart(item));
    if (day !== currentDay) {
      currentDay = day;
      card = el("div", "day-card");
      fragment.append(el("div", "day-header", day), card);
    }
    const row = item.kind === "track" ? trackRow(item.track)
      : item.kind === "stay" ? stayRow(item)
        : gapRow(item);
    row.dataset.row = index;
    // Kept by index: highlighting is a lookup rather than a querySelector over a tree that runs to
    // tens of thousands of nodes on a full history.
    rowElements[index] = row;
    card.appendChild(row);
  });
  list.appendChild(fragment);
}

function itemStart(item) {
  return item.kind === "track" ? item.track.startedAt : item.start;
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

/** A row's text column: its title line(s) and the meta line, in the order given. */
function body(...children) {
  const node = el("span", "body");
  node.append(...children.filter(Boolean));
  return node;
}

const SVG = "http://www.w3.org/2000/svg";

function disc(iconName) {
  const node = el("span", "disc");
  const svg = document.createElementNS(SVG, "svg");
  const use = document.createElementNS(SVG, "use");
  use.setAttribute("href", `#icon-${iconName}`);
  svg.appendChild(use);
  node.appendChild(svg);
  return node;
}

function trackRow(t) {
  const row = el("button", "row track-row");
  const mark = disc(activityIcon(t.activityType));
  const tint = activityColor(t.activityType);
  if (tint) mark.style.setProperty("--tint", tint);
  const span = `${formatTime(t.startedAt)} – ${formatTime(t.endedAt)}`;
  row.append(mark, body(
    el("span", "title", `${activityLabel(t.activityType)} · ${formatDistance(t.distanceMeters)}`),
    el("span", "meta", `${span} · ${formatDurationMs(t.endedAt - t.startedAt)}`),
  ));
  return row;
}

/**
 * A stationary period between two tracks. A resolved place shows its label; an unnamed cluster
 * visited often enough to be worth naming shows its visit count instead of a name it hasn't got.
 */
function stayRow(stay) {
  const place = clusterPlaces[stay.clusterId];
  const row = el("button", "row stay-row");
  // The disc answers a second question beside the title: not whether the place was named, but
  // whether it was said what it is for.
  const mark = disc(categoryIcon(place?.category));
  const fill = categoryPinColor(place?.category);
  if (fill) {
    mark.classList.add("pin");
    mark.style.setProperty("--tint", fill);
  }
  row.append(mark, body(
    placeSpan(place, "Stayed"),
    el("span", "meta", stayMeta(stay, place, nowMs)),
  ));
  return row;
}

/** A place's name where it has one, the fallback where it hasn't — named ones read differently. */
function placeSpan(place, fallback) {
  return el("span", place?.label ? "title named" : "title", place?.label ?? fallback);
}

/** The cluster ids a gap row names, newest-first (destination, then origin) — the ends the slicer
 * stamped this half as speaking for, so the row and the map it frames cannot disagree. An id may
 * still be null where the history has no endpoint for that side. */
function namedSidesOf(gap) {
  return [gap.holdsEnd ? gap.toClusterId : null, gap.holdsStart ? gap.fromClusterId : null];
}

/** Movement the recorder missed: the endpoints either side disagree. Most such gaps are really
 * one place clustered as two, so the row names both sides — newest-first, destination above the
 * dashed leg and origin below it, the way the trip ran. A side with no known endpoint renders
 * nothing; its absence is the story. */
function gapRow(gap) {
  const row = el("button", "row gap-row");
  const side = (clusterId) => (clusterId == null
    ? null
    : placeSpan(clusterPlaces[clusterId], "unnamed place"));
  const [to, from] = namedSidesOf(gap);
  row.append(body(side(to), el("span", "meta", gapMeta(gap).text), side(from)));
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
  // explains it. A side the history has no endpoint for frames nothing, since showing a place the
  // row doesn't name would answer a question it deliberately leaves open.
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
    const row = el("div", "legend-row");
    const swatch = el("span", isLine ? "swatch line" : "swatch");
    swatch.style.background = color;
    row.append(swatch, el("span", "", label), el("span", "count", count));
    legend.appendChild(row);
  }
}

boot();

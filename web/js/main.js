// UI glue: import flow, track list, selection. Data comes from IndexedDB (imported once by the
// worker); the map renders the overview from simplified geometries and loads full points only
// for the selected track.

import { openDb, getMeta, getAllTracks, getGeometry } from "./db.js";
import { createMap, setOverview, setPlaces, showTrack, clearSelection, activityColor } from "./map.js";

const $ = (id) => document.getElementById(id);

let db;
let map;
let tracks = [];
let selectedId = null;
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
  if (key) map = createMap("map", key, selectTrack);

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
  // One delegated listener instead of one closure per row (there can be thousands).
  $("track-list").addEventListener("click", (e) => {
    const row = e.target.closest(".track-row");
    if (row) selectTrack(Number(row.dataset.id));
  });

  await refresh();
}

async function refresh() {
  const meta = await getMeta(db);
  if (!meta) {
    $("empty").hidden = false;
    $("track-list").hidden = true;
    $("summary").textContent = configError ?? "";
    return;
  }
  $("empty").hidden = true;
  $("track-list").hidden = false;
  tracks = (await getAllTracks(db)).sort((a, b) => b.startedAt - a.startedAt);
  $("summary").textContent = configError ??
    `${meta.trackCount} tracks · ${(meta.pointCount / 1000).toFixed(0)}k points · ` +
    `exported ${formatDate(meta.exportedAt)}`;
  renderList();
  if (map) {
    setOverview(map, tracks);
    setPlaces(map, meta.places);
  }
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
      selectedId = null;
      await refresh();
    } else if (msg.type === "error") {
      $("progress").textContent = `Import failed: ${msg.message}`;
      worker.terminate();
    }
  };
  worker.postMessage({ file });
}

// --- track list --------------------------------------------------------------------------------

function renderList() {
  const list = $("track-list");
  list.textContent = "";
  selectedRow = null;
  let currentDay = "";
  const fragment = document.createDocumentFragment();
  for (const t of tracks) {
    const day = formatDay(t.startedAt);
    if (day !== currentDay) {
      currentDay = day;
      const h = document.createElement("div");
      h.className = "day-header";
      h.textContent = day;
      fragment.appendChild(h);
    }
    fragment.appendChild(trackRow(t));
  }
  list.appendChild(fragment);
}

function trackRow(t) {
  const row = document.createElement("button");
  row.className = "track-row";
  row.dataset.id = t.id;
  const dot = document.createElement("span");
  dot.className = "dot";
  dot.style.background = activityColor(t.activityType);
  const label = document.createElement("span");
  label.className = "label";
  label.textContent = `${titleCase(t.activityType)} · ${timeFormat.format(t.startedAt)}`;
  const stats = document.createElement("span");
  stats.className = "stats";
  stats.textContent = `${formatDistance(t.distanceMeters)} · ${formatDuration(t)}`;
  row.append(dot, label, stats);
  return row;
}

async function selectTrack(id) {
  if (selectedId === id) {
    selectedId = null;
    if (map) clearSelection(map);
    highlightRow(null);
    return;
  }
  selectedId = id;
  highlightRow(id);
  const track = tracks.find((t) => t.id === id);
  const geometry = await getGeometry(db, id);
  if (track && geometry && map) showTrack(map, track, geometry);
}

let selectedRow = null;

function highlightRow(id) {
  selectedRow?.classList.remove("selected");
  selectedRow = id == null ? null : document.querySelector(`.track-row[data-id="${id}"]`);
  selectedRow?.classList.add("selected");
  selectedRow?.scrollIntoView({ block: "nearest" });
}

// --- formatting --------------------------------------------------------------------------------

// Cached formatters: toLocale*String constructs a fresh Intl.DateTimeFormat per call, which
// at thousands of rows is the dominant cost of building the list.
const dayFormat = new Intl.DateTimeFormat(undefined, {
  weekday: "short", day: "numeric", month: "short", year: "numeric",
});
const timeFormat = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" });
const dateTimeFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: "short", timeStyle: "short",
});

function titleCase(s) {
  return s.charAt(0) + s.slice(1).toLowerCase();
}

function formatDay(ms) {
  return dayFormat.format(ms);
}

function formatDate(ms) {
  return ms ? dateTimeFormat.format(ms) : "?";
}

function formatDistance(m) {
  return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`;
}

function formatDuration(t) {
  const totalMin = Math.round((t.endedAt - t.startedAt) / 60000);
  const h = Math.floor(totalMin / 60);
  const min = totalMin % 60;
  return h > 0 ? `${h} h ${min} min` : `${min} min`;
}

boot();

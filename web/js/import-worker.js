// Import pipeline, off the main thread: backup file → gunzip → streaming parse → typed arrays
// → IndexedDB. Posts { type: "progress", tracksDone, tracksTotal } as it goes, then
// { type: "done", summary } or { type: "error", message }.

import { BackupParser } from "./backup-parse.js";
import { indexFields, convertTrack } from "./convert.js";
import { openDb, clearAll, putTrackBatch, putMeta } from "./db.js";

// Tracks per IndexedDB transaction.
const BATCH = 25;

self.onmessage = async (e) => {
  try {
    const summary = await importFile(e.data.file);
    postMessage({ type: "done", summary });
  } catch (err) {
    postMessage({ type: "error", message: err?.message ?? String(err) });
  }
};

async function importFile(file) {
  const db = await openDb();

  let fields = null;
  let header = null;
  let places = [];
  let liveness = [];
  let tracksDone = 0;
  let pointsTotal = 0;
  let trackBatch = [];
  let geometryBatch = [];
  let extrasBatch = [];
  let pending = Promise.resolve();

  const flushBatch = () => {
    const tracks = trackBatch;
    const geometries = geometryBatch;
    const extras = extrasBatch;
    trackBatch = [];
    geometryBatch = [];
    extrasBatch = [];
    const total = header.trackCount ?? null;
    const done = tracksDone;
    pending = pending.then(() => putTrackBatch(db, tracks, geometries, extras)).then(() => {
      postMessage({ type: "progress", tracksDone: done, tracksTotal: total });
    });
  };

  const parser = new BackupParser({
    onHeader: (h) => {
      header = h;
      fields = indexFields(h.pointFields);
      // The previous dataset is dropped only now — after format, version and pointFields all
      // checked out — so a wrong or truncated file fails without destroying anything.
      pending = pending.then(() => clearAll(db));
    },
    onTrack: (track) => {
      const converted = convertTrack(track, fields);
      trackBatch.push(converted.row);
      geometryBatch.push(converted.geometry);
      extrasBatch.push(converted.extras);
      pointsTotal += converted.geometry.count;
      tracksDone++;
      if (trackBatch.length >= BATCH) flushBatch();
    },
    onPlaces: (p) => { places = p; },
    onLiveness: (l) => { liveness = l; },
  });

  const stream = file.stream()
    .pipeThrough(new DecompressionStream("gzip"))
    .pipeThrough(new TextDecoderStream());
  const reader = stream.getReader();
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    parser.push(value);
  }
  parser.finish();

  if (trackBatch.length > 0) flushBatch();
  await pending;
  await putMeta(db, {
    exportedAt: header.exportedAt ?? null,
    trackCount: tracksDone,
    pointCount: pointsTotal,
    places,
    // Not rendered yet — kept as the input for the planned stays/timeline derivation.
    liveness,
  });
  return { tracks: tracksDone, points: pointsTotal, places: places.length };
}

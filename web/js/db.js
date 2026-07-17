// IndexedDB layer. Four stores:
//   meta     — key "export": header info, places, liveness, import stamp (one small record)
//   tracks   — key id: track row + simplified overview geometry + bbox (loaded whole at startup)
//   geometry — key trackId: lon/lat + flags typed arrays (loaded per selected track)
//   extras   — key trackId: time/alt/speed/accuracy arrays (only a metric view needs these)
// Typed arrays go in as ArrayBuffers — structured clone stores them verbatim, and a full
// history stays a few hundred MB of buffers instead of millions of JS objects.

const DB_NAME = "breadcrumb-viewer";
const DB_VERSION = 2;
const STORES = ["meta", "tracks", "geometry", "extras"];

function req(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function openDb() {
  const request = indexedDB.open(DB_NAME, DB_VERSION);
  request.onupgradeneeded = () => {
    const db = request.result;
    // Imports wholesale-replace the data, so an upgrade just rebuilds the stores.
    for (const name of Array.from(db.objectStoreNames)) db.deleteObjectStore(name);
    db.createObjectStore("meta");
    db.createObjectStore("tracks", { keyPath: "id" });
    db.createObjectStore("geometry", { keyPath: "trackId" });
    db.createObjectStore("extras", { keyPath: "trackId" });
  };
  return req(request);
}

export async function clearAll(db) {
  const tx = db.transaction(STORES, "readwrite");
  for (const name of STORES) tx.objectStore(name).clear();
  return txDone(tx);
}

export function txDone(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error("transaction aborted"));
  });
}

export async function putTrackBatch(db, tracks, geometries, extras) {
  const tx = db.transaction(["tracks", "geometry", "extras"], "readwrite");
  const trackStore = tx.objectStore("tracks");
  const geometryStore = tx.objectStore("geometry");
  const extrasStore = tx.objectStore("extras");
  for (const t of tracks) trackStore.put(t);
  for (const g of geometries) geometryStore.put(g);
  for (const e of extras) extrasStore.put(e);
  return txDone(tx);
}

export async function putMeta(db, meta) {
  const tx = db.transaction("meta", "readwrite");
  tx.objectStore("meta").put(meta, "export");
  return txDone(tx);
}

export async function getMeta(db) {
  return req(db.transaction("meta").objectStore("meta").get("export"));
}

export async function getAllTracks(db) {
  return req(db.transaction("tracks").objectStore("tracks").getAll());
}

export async function getGeometry(db, trackId) {
  return req(db.transaction("geometry").objectStore("geometry").get(trackId));
}

export async function getExtras(db, trackId) {
  return req(db.transaction("extras").objectStore("extras").get(trackId));
}

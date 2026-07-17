// Drives BackupParser over a real export at several chunk sizes and checks the stream against
// a whole-file JSON.parse of the same data. Run: node web/test/parse-test.mjs <export.json.gz>
import assert from "node:assert/strict";
import { BackupParser } from "../js/backup-parse.js";
import { loadExportText, feed } from "./helpers.mjs";

const text = loadExportText(process.argv, "node parse-test.mjs");
const expected = JSON.parse(text);

function run(chunkSize) {
  const got = { tracks: [], places: null, liveness: null, header: null };
  const parser = new BackupParser({
    onHeader: (h) => { got.header = structuredClone(h); },
    onTrack: (t) => got.tracks.push(t),
    onPlaces: (p) => { got.places = p; },
    onLiveness: (l) => { got.liveness = l; },
  });
  feed(parser, text, chunkSize);

  assert.equal(got.header.format, expected.format);
  assert.equal(got.header.version, expected.version);
  assert.equal(got.header.trackCount, expected.trackCount);
  assert.deepEqual(got.header.pointFields, expected.pointFields);
  assert.equal(got.tracks.length, expected.tracks.length);
  assert.deepEqual(got.tracks, expected.tracks);
  assert.deepEqual(got.places, expected.places);
  assert.deepEqual(got.liveness, expected.liveness);
  console.log(`chunk=${chunkSize}: ok (${got.tracks.length} tracks)`);
}

// 1-char chunks catch every possible split point but are slow on a big file — use a prefix.
{
  const prefixEnd = text.indexOf('"points":[') + 2000;
  const prefix = text.slice(0, prefixEnd);
  const parser = new BackupParser({ onTrack: () => {}, onHeader: () => {} });
  for (const ch of prefix) parser.push(ch);
  console.log("1-char prefix feed: ok");
}
run(7);        // tiny, misaligned with any token
run(4096);
run(1 << 20);  // ~whole file per chunk
run(text.length);

// Rejection cases.
for (const [name, doc] of [
  ["foreign file", '{"format":"something-else","version":1,"pointFields":[],"tracks":[]}'],
  ["newer version", '{"format":"breadcrumb-export","version":99,"pointFields":[],"tracks":[]}'],
  ["truncated", text.slice(0, text.length - 5)],
  ["trailing garbage", text + "x"],
]) {
  let threw = false;
  try {
    const p = new BackupParser({});
    p.push(doc);
    p.finish();
  } catch {
    threw = true;
  }
  assert.ok(threw, `${name} should be rejected`);
  console.log(`${name}: rejected ok`);
}
console.log("all parse tests passed");

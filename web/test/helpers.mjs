// Shared fixture loading for the node tests: gunzip a real export to text.
import { gunzipSync } from "node:zlib";
import { readFileSync } from "node:fs";

export function loadExportText(argv, usage) {
  const path = argv[2];
  if (!path) throw new Error(`usage: ${usage} <export.json.gz>`);
  return gunzipSync(readFileSync(path)).toString("utf-8");
}

export function feed(parser, text, chunkSize) {
  for (let i = 0; i < text.length; i += chunkSize) parser.push(text.slice(i, i + chunkSize));
  parser.finish();
}

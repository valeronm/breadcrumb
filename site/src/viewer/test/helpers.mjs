// Shared fixture loading for the node tests: gunzip a real export, whole or in chunks.
import { createGunzip, gunzipSync } from "node:zlib";
import { createReadStream, readFileSync } from "node:fs";

function exportPath(argv, usage) {
  const path = argv[2];
  if (!path) throw new Error(`usage: ${usage} <export.json.gz>`);
  return path;
}

/** The whole export as one string — for tests that slice or mutate it. A full history decompresses
 * past V8's ~512 MB string cap and throws ERR_STRING_TOO_LONG here; feedExport is the way in for
 * anything that only reads forward. */
export function loadExportText(argv, usage) {
  return gunzipSync(readFileSync(exportPath(argv, usage))).toString("utf-8");
}

/** Streams the export through [parser] and resolves when it has been fed and finished. Nothing
 * holds the decompressed text, so this runs on an export of any size — which is the point of the
 * parser being incremental in the first place. */
export async function feedExport(parser, argv, usage) {
  // 1 MB either side rather than the 16 KB defaults: at the default the run is latency-bound on
  // threadpool round trips, one per inflated chunk, and a full history takes ~3x as long. The
  // parser itself is indifferent to how finely it's fed.
  const stream = createReadStream(exportPath(argv, usage), { highWaterMark: 1 << 20 })
    .pipe(createGunzip({ chunkSize: 1 << 20 }));
  stream.setEncoding("utf-8");
  for await (const chunk of stream) parser.push(chunk);
  parser.finish();
}

export function feed(parser, text, chunkSize) {
  for (let i = 0; i < text.length; i += chunkSize) parser.push(text.slice(i, i + chunkSize));
  parser.finish();
}

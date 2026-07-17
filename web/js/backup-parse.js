// Incremental parser for the Breadcrumb backup format (BackupExporter, format v1).
//
// The export is one JSON object whose "tracks" array dwarfs everything else — a full-history
// file runs to hundreds of MB of text, past what JSON.parse can take in one bite. This parser
// is fed decompressed text chunks and emits each track as soon as its object is complete, so
// memory stays at one value plus the current chunk.
//
// Two processing modes keep the work linear in the input:
// - token mode handles the small structural stream between values (keys, colons, commas,
//   braces) through a tiny `head` buffer;
// - value mode streams a value's chars through a depth/string machine directly off each
//   incoming chunk — pieces accumulate in an array and are joined once when the value closes.
// Nothing ever indexes into a growing concatenated string: with V8's rope strings that
// flattens (copies) the whole buffer on every chunk, which is quadratic on big files.
//
// Pure ES module — the import worker uses it in the browser, and node tests drive it directly.

export const FORMAT = "breadcrumb-export";
export const VERSION = 1;

const WS = " \t\n\r";

export class BackupParser {
  /**
   * @param {{onHeader?: (header: object) => void,
   *          onTrack?: (track: object) => void,
   *          onPlaces?: (places: object[]) => void,
   *          onLiveness?: (events: object[]) => void}} callbacks
   * onHeader fires once, before the first onTrack, with every scalar/small field seen so far —
   * pointFields is guaranteed present (the exporter writes it before tracks).
   */
  constructor(callbacks) {
    this.cb = callbacks;
    this.header = {};
    this.headerSent = false;
    this.done = false;
    // Structural state: what the next token means.
    this.state = "start"; // start | key | colon | value | trackArrayStart | trackElement
    this.currentKey = null;
    this.inTracks = false;
    // Token mode scratch: the unconsumed structural tail (keys, punctuation) — always small.
    this.head = "";
    // Value mode: the machine and collected pieces of the value currently streaming in.
    this.value = null; // { kind: 'container'|'string'|'scalar', depth, inString, escape, parts }
  }

  /** Feed the next decompressed text chunk. Throws on malformed input. */
  push(chunk) {
    let i = 0;
    while (i < chunk.length) {
      i = this.value ? this.scanValue(chunk, i) : this.scanTokens(chunk, i);
    }
  }

  /** Signal end of input. Throws if the document is incomplete. */
  finish() {
    // Format validity is enforced as it streams: takeValue rejects a wrong format value, and
    // sendHeader (which finishDocument also routes through) rejects a document without one.
    if (this.value || !this.done) throw new Error("unexpected end of input");
  }

  // --- token mode ------------------------------------------------------------------------------

  /** Consumes structural tokens from [chunk] starting at [i]; returns the next unread index. */
  scanTokens(chunk, i) {
    // The head carries at most a partial key/punctuation run between chunks — it stays tiny,
    // so appending the pending structural span here is O(span).
    while (i < chunk.length && !this.value) {
      const c = chunk[i];
      if (this.done) {
        if (!WS.includes(c)) throw new Error("trailing content after document");
        i++;
        continue;
      }
      switch (this.state) {
        case "start":
          if (WS.includes(c)) { i++; break; }
          if (c !== "{") throw new Error("not a JSON object");
          i++;
          this.state = "key";
          break;
        case "key": {
          if (WS.includes(c) || c === ",") { i++; break; }
          if (c === "}") {
            if (this.inTracks) throw new Error("unexpected '}' in tracks array");
            i++;
            this.finishDocument();
            break;
          }
          if (c === "]") { // end of the tracks array
            if (!this.inTracks) throw new Error("unexpected ']'");
            i++;
            this.inTracks = false;
            break;
          }
          if (this.inTracks) {
            // Inside the tracks array every element is a value (an object).
            this.beginValue(c);
            return i;
          }
          i = this.readKey(chunk, i);
          break;
        }
        case "colon":
          if (WS.includes(c)) { i++; break; }
          if (c !== ":") throw new Error(`expected ':' after "${this.currentKey}"`);
          i++;
          this.state = this.currentKey === "tracks" ? "trackArrayStart" : "value";
          break;
        case "value":
          if (WS.includes(c)) { i++; break; }
          this.beginValue(c);
          return i;
        case "trackArrayStart":
          if (WS.includes(c)) { i++; break; }
          if (c !== "[") throw new Error('expected "tracks" array');
          i++;
          this.sendHeader();
          this.inTracks = true;
          this.state = "key"; // elements and the closing ']' route through "key" handling
          break;
        default:
          throw new Error(`bad state ${this.state}`);
      }
    }
    return i;
  }

  /** Reads a (short) key string, buffering across chunks in [head] if needed. The format's
   *  keys are fixed plain-ASCII identifiers, so escapes are rejected rather than decoded. */
  readKey(chunk, i) {
    if (this.head === "" && chunk[i] !== '"') throw new Error("expected string key");
    for (let j = this.head === "" ? i + 1 : i; j < chunk.length; j++) {
      const c = chunk[j];
      if (c === "\\") throw new Error("escapes in keys are not supported");
      if (c === '"') {
        const raw = this.head + chunk.slice(i, j + 1);
        this.head = "";
        this.currentKey = JSON.parse(raw);
        this.state = "colon";
        return j + 1;
      }
    }
    this.head += chunk.slice(i);
    return chunk.length;
  }

  // --- value mode ------------------------------------------------------------------------------

  beginValue(firstChar) {
    const kind = firstChar === "{" || firstChar === "[" ? "container"
      : firstChar === '"' ? "string" : "scalar";
    this.value = { kind, depth: 0, inString: false, escape: false, parts: [] };
  }

  /**
   * Runs the value machine over [chunk] from [i]; returns the next unread index. Every byte of
   * the export flows through this loop, so it compares char codes, not string literals.
   */
  scanValue(chunk, i) {
    const v = this.value;
    const scalar = v.kind === "scalar";
    const bareString = v.kind === "string";
    for (let j = i; j < chunk.length; j++) {
      const c = chunk.charCodeAt(j);
      if (scalar) {
        if (c === 44 /* , */ || c === 125 /* } */ || c === 93 /* ] */) {
          this.endValue(v.parts.join("") + chunk.slice(i, j));
          return j; // the delimiter belongs to the token stream
        }
        continue;
      }
      if (v.inString) {
        if (v.escape) v.escape = false;
        else if (c === 92 /* \ */) v.escape = true;
        else if (c === 34 /* " */) {
          v.inString = false;
          if (bareString) {
            this.endValue(v.parts.join("") + chunk.slice(i, j + 1));
            return j + 1;
          }
        }
        continue;
      }
      if (c === 34 /* " */) v.inString = true;
      else if (c === 123 /* { */ || c === 91 /* [ */) v.depth++;
      else if (c === 125 /* } */ || c === 93 /* ] */) {
        v.depth--;
        if (v.depth === 0) {
          this.endValue(v.parts.join("") + chunk.slice(i, j + 1));
          return j + 1;
        }
      }
    }
    v.parts.push(chunk.slice(i));
    return chunk.length;
  }

  endValue(raw) {
    this.value = null;
    const parsed = JSON.parse(raw);
    if (this.inTracks) {
      this.cb.onTrack?.(parsed);
    } else {
      this.takeValue(this.currentKey, parsed);
    }
    this.state = "key";
  }

  // --- document assembly -----------------------------------------------------------------------

  takeValue(key, value) {
    if (key === "places") this.cb.onPlaces?.(value);
    else if (key === "liveness") this.cb.onLiveness?.(value);
    else {
      this.header[key] = value;
      if (key === "format" && value !== FORMAT) throw new Error("not a Breadcrumb export");
      if (key === "version" && !(value >= 1 && value <= VERSION)) {
        throw new Error(`export version ${value} needs a newer viewer`);
      }
    }
  }

  sendHeader() {
    if (this.headerSent) return;
    if (this.header.format !== FORMAT) throw new Error("not a Breadcrumb export");
    if (!Array.isArray(this.header.pointFields)) throw new Error("tracks before pointFields");
    this.headerSent = true;
    this.cb.onHeader?.(this.header);
  }

  finishDocument() {
    if (!this.headerSent) this.sendHeader(); // an export with zero tracks still has a header
    this.done = true;
  }
}

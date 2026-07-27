// How a row reads: every formatter the sidebar uses, and the wording rules behind them. Pure —
// main.js builds the DOM around these, node tests drive them directly. The stay wording is the
// app's timeline rows, restated here for the same reason the derivation is: two readings of one
// history is worse than either.

import { reportableDurationMs, isNotable, startOfLocalDay } from "./stays.js";

// Cached formatters: toLocale*String constructs a fresh Intl.DateTimeFormat per call, which at
// thousands of rows is the dominant cost of building the list.
const dayFormat = new Intl.DateTimeFormat(undefined, {
  weekday: "short", day: "numeric", month: "short", year: "numeric",
});
const timeFormat = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" });
const dateTimeFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: "short", timeStyle: "short",
});

export function formatTime(ms) {
  return timeFormat.format(ms);
}

export function formatDay(ms) {
  return dayFormat.format(ms);
}

export function formatDate(ms) {
  return ms ? dateTimeFormat.format(ms) : "?";
}

export function formatDistance(m) {
  return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`;
}

/** Tracks run in minutes, stays in days — one scale so a row's length reads the same either way. */
export function formatDurationMs(ms) {
  const totalMin = Math.round(ms / 60000);
  const h = Math.floor(totalMin / 60);
  const min = totalMin % 60;
  if (h >= 24) {
    const days = Math.floor(h / 24);
    return h % 24 === 0 ? `${days} d` : `${days} d ${h % 24} h`;
  }
  return h > 0 ? `${h} h ${min} min` : `${min} min`;
}

export function titleCase(s) {
  return s.charAt(0) + s.slice(1).toLowerCase();
}

function visitLabel(n) {
  return n === 1 ? "1 visit" : `${n} visits`;
}

/**
 * What a place is for, keyed by the code stored on the place row. PlaceCategory — the codes are the
 * stored vocabulary and must match it exactly; the labels are display text the app is free to
 * reword. A place with no category, or one carrying a code from a newer app than this viewer, reads
 * as untagged: the same tolerance the app applies, so neither side invents a name for it.
 */
const CATEGORY_LABELS = {
  home: "Home",
  groceries: "Groceries",
  shopping: "Shopping",
  kids_school: "Kids & school",
  sports: "Sports & fitness",
  outdoors: "Outdoors",
  friends_family: "Friends & family",
  services: "Services",
  health: "Health",
  travel: "Travel",
  food: "Food & drink",
  entertainment: "Entertainment",
  sightseeing: "Sightseeing",
  gas_station: "Gas station",
  parking: "Parking",
  work: "Work",
};

/** Display label for a stored category code, or null when untagged or unrecognized. */
export function categoryLabel(code) {
  return CATEGORY_LABELS[code] ?? null;
}

/**
 * A stay row's metadata line: when it was, how long, and — for an unnamed cluster the user visits
 * often enough to want to name — how many visits.
 *
 * A duration appears only where the bounds can carry one. A midnight-clamped bound makes it both
 * redundant (it restates the clock time) and misleading (the stay continues across the slice), and
 * a stay shorter than its own bounds can measure gets none either: the stop was longer than the
 * bounds say, so "0m" would be worse than silence.
 *
 * @param stay one interval, possibly a per-day slice of a longer one
 * @param place its resolved cluster (see resolveClusters), or undefined
 * @param nowMs what an open-ended stay is measured to — the export's instant, not today's
 */
export function stayMeta(stay, place, nowMs) {
  const start = formatTime(stay.start);
  const startsAtMidnight = isLocalMidnight(stay.start);
  const endsAtMidnight = stay.end != null && isLocalMidnight(stay.end);
  let phrase;
  if (startsAtMidnight && (stay.end == null || endsAtMidnight)) {
    // Open from midnight = all of that day; completed midnight-to-midnight slices read the same.
    phrase = "All day";
  } else if (stay.end == null) {
    // Where the export left the user. Not "– now": the file's now is the export's, not today's.
    phrase = `since ${start}`;
  } else if (startsAtMidnight) {
    phrase = `until ${formatTime(stay.end)}`;
  } else if (endsAtMidnight) {
    phrase = `from ${start}`;
  } else {
    // A stop the recorder only caught the tail end of lands on one clock minute at both bounds;
    // "09:11 – 09:11" reads as a rendering fault rather than as a moment.
    const end = formatTime(stay.end);
    phrase = end === start ? start : `${start} – ${end}`;
  }
  const duration = startsAtMidnight || endsAtMidnight ? null : reportableDurationMs(stay, nowMs);
  const visits = !place?.label && isNotable(place) ? place.visitCount : null;
  // Category after duration, as the app's stay row orders them. Never alongside the visit count:
  // that belongs to unnamed clusters, which have no place row to carry a category.
  return [
    phrase,
    duration && formatDurationMs(duration),
    categoryLabel(place?.category),
    visits && visitLabel(visits),
  ].filter(Boolean).join(" · ");
}

/** A bound the day slicing put there, rather than a time anything happened at. */
function isLocalMidnight(ms) {
  return startOfLocalDay(ms) === ms;
}

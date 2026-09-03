// How a row reads: every formatter the sidebar uses, and the wording rules behind them. Pure —
// main.js builds the DOM around these, node tests drive them directly. The stay wording is the
// app's timeline rows, restated here for the same reason the derivation is: two readings of one
// history is worse than either.

import { reportableDurationMs, isNotable } from "./stays.js";

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

/** Display label for a stored activity code — ActivityType.labelFor's port: the code is the
 * permanent vocabulary, the label is what the app rewords (FERRY covers any waterborne carrier,
 * hence "Boat"); anything unmapped title-cases, the same fallback the app applies. */
const ACTIVITY_LABELS = {
  FERRY: "Boat",
  TRANSIT: "Public transit",
  STILL: "Stationary",
  UNKNOWN: "Moving",
};

export function activityLabel(code) {
  return ACTIVITY_LABELS[code] ?? titleCase(code);
}

function visitLabel(n) {
  return n === 1 ? "1 visit" : `${n} visits`;
}

/** What a place is for, keyed by the code stored on the place row. PlaceCategory — the codes are
 * the stored vocabulary and must match it exactly; the labels are display text the app is free to
 * reword. No category, or a code from a newer app than this viewer, reads as untagged: the same
 * tolerance the app applies, so neither side invents a name for it. */
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
  transit: "Transit",
  work: "Work",
};

/** Display label for a stored category code, or null when untagged or unrecognized. */
export function categoryLabel(code) {
  return CATEGORY_LABELS[code] ?? null;
}

/** A stay row's metadata line: when it was, how long, and — for an unnamed cluster the user
 * visits often enough to want to name — how many visits. A duration appears only where the bounds
 * can carry one: a bound the slicing cut makes it redundant (restates the clock time) and
 * misleading (the stay continues past it), and a stay shorter than its own bounds can
 * measure gets none either — the stop was longer than the bounds say, so "0m" would be worse
 * than silence.
 * Which bounds are the stay's own is read off the flags `slicePerDay` stamps at the cut — see there
 * for why nothing may decide it from a clock instead.
 * @param stay one stay slice, carrying holdsStart/holdsEnd
 * @param place its resolved cluster (see resolveClusters), or undefined
 * @param nowMs what an open-ended stay is measured to — the export's instant, not today's */
export function stayMeta(stay, place, nowMs) {
  const start = formatTime(stay.start);
  const cutAtStart = !stay.holdsStart;
  const cutAtEnd = !stay.holdsEnd;
  let phrase;
  if (cutAtStart && (stay.end == null || cutAtEnd)) {
    // Cut at both ends, or cut at its start and still open: a whole day either way.
    phrase = "All day";
  } else if (stay.end == null) {
    // Where the export left the user. Not "– now": the file's now is the export's, not today's.
    phrase = `since ${start}`;
  } else if (cutAtStart) {
    phrase = `until ${formatTime(stay.end)}`;
  } else if (cutAtEnd) {
    phrase = `from ${start}`;
  } else {
    // A stop the recorder only caught the tail end of lands on one clock minute at both bounds;
    // "09:11 – 09:11" reads as a rendering fault rather than as a moment.
    const end = formatTime(stay.end);
    phrase = end === start ? start : `${start} – ${end}`;
  }
  const duration = cutAtStart || cutAtEnd ? null : reportableDurationMs(stay, nowMs);
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

/**
 * A gap row's metadata line — read off the flags `slicePerDay` stamped when it cut, never recovered
 * from the bounds. A half that does not hold an end says nothing about it: that end is a fact about
 * the other day's row, and which sides the row may name is `holdsStart`/`holdsEnd` itself.
 *
 * A gap is cut at most once, at the midnight opening the day it ended, so a row holds both ends (the
 * whole absence sat inside one day, and a duration says it) or exactly one (and states that end's
 * clock time). Days in the middle are folded into the departure half, so a row holding neither is
 * refused rather than worded — as the app refuses it, since the only sentence left would name a
 * time for an end this row does not speak for.
 * @param gap one gap slice, carrying holdsStart/holdsEnd
 */
export function gapMeta(gap) {
  if (gap.holdsStart && gap.holdsEnd) {
    return { text: `missing recording · ${formatDurationMs(gap.end - gap.start)}` };
  }
  if (gap.holdsStart) return { text: `missing recording · from ${formatTime(gap.start)}` };
  if (gap.holdsEnd) return { text: `missing recording · until ${formatTime(gap.end)}` };
  throw new Error("a gap row holds at least one end; this one was stamped with neither");
}

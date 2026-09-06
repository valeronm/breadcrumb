// The app's disc vocabulary (ui/Palette.kt, ui/Glyphs.kt): what colour and glyph a kind of travel
// and a kind of stop wear, ported so a row and a map marker read the same here as on the phone.
// One table per kind, so a category cannot have a colour and no glyph or the reverse. The page
// reads it at build to inline the glyph sprite; pure ES module, so node tests drive it directly.

// One saturation and lightness per set, only the hue rotates, so no entry outweighs another.
// Green nudged toward teal so it stays clear of the app's own green accent.
const ACTIVITY_SAT = 50;
const ACTIVITY_LUM = 62;

const ACTIVITIES = {
  DRIVING: { hue: 210, icon: "directions_car" },
  TAXI: { hue: 48, icon: "local_taxi" },
  FERRY: { hue: 330, icon: "directions_boat" },
  FLIGHT: { hue: 195, icon: "flight" },
  TRANSIT: { hue: 242, icon: "directions_transit" },
  CYCLING: { hue: 165, icon: "directions_bike" },
  RUNNING: { hue: 30, icon: "directions_run" },
  WALKING: { hue: 275, icon: "directions_walk" },
};

// A movement nothing named — STILL, UNKNOWN, a type from a newer app — is a route in the neutral.
const ROUTE = "route";

/** An activity's hue, or null for one that wears the neutral. */
export function activityColor(activityType) {
  const hue = ACTIVITIES[activityType]?.hue;
  return hue == null ? null : `hsl(${hue} ${ACTIVITY_SAT}% ${ACTIVITY_LUM}%)`;
}

export function activityIcon(activityType) {
  return ACTIVITIES[activityType]?.icon ?? ROUTE;
}

// A place is coloured by its category's group, never its own category: a hue per group is a
// pattern a scroll picks up, a hue per category a legend. The transient pair is the faintest, kept
// close to blue and drained of chroma, but still a hue: a true neutral is what an untagged place
// wears.
const PIN_SAT = 85;
const PIN_TRANSIENT_SAT = 38;
const GROUPS = {
  HOME_PEOPLE: { hue: 217, sat: PIN_SAT },
  ERRANDS: { hue: 22, sat: PIN_SAT },
  ROUTINE: { hue: 285, sat: PIN_SAT },
  AWAY: { hue: 116, sat: PIN_SAT },
  TRANSIENT: { hue: 200, sat: PIN_TRANSIENT_SAT },
};

const CATEGORIES = {
  friends_family: { group: "HOME_PEOPLE", icon: "people" },
  home: { group: "HOME_PEOPLE", icon: "home" },
  groceries: { group: "ERRANDS", icon: "shopping_basket" },
  food: { group: "ERRANDS", icon: "restaurant" },
  shopping: { group: "ERRANDS", icon: "local_mall" },
  services: { group: "ERRANDS", icon: "handyman" },
  health: { group: "ERRANDS", icon: "medical_services" },
  kids_school: { group: "ROUTINE", icon: "school" },
  sports: { group: "ROUTINE", icon: "fitness_center" },
  work: { group: "ROUTINE", icon: "work" },
  outdoors: { group: "AWAY", icon: "terrain" },
  sightseeing: { group: "AWAY", icon: "account_balance" },
  travel: { group: "AWAY", icon: "luggage" },
  entertainment: { group: "AWAY", icon: "local_activity" },
  parking: { group: "TRANSIENT", icon: "local_parking" },
  transit: { group: "TRANSIENT", icon: "departure_board" },
  gas_station: { group: "TRANSIENT", icon: "local_gas_station" },
  service_area: { group: "TRANSIENT", icon: "signpost" },
};

// A place with no category, and an unnamed cluster, is a pin.
const PLACE = "place";

/** The stored codes this viewer knows; one from a newer app reads as untagged. */
export const CATEGORY_CODES = Object.keys(CATEGORIES);

/** Every glyph name the sprite has to carry. */
export const ICON_NAMES = [
  ...Object.values(ACTIVITIES).map((a) => a.icon), ROUTE,
  ...Object.values(CATEGORIES).map((c) => c.icon), PLACE,
];

export function categoryIcon(code) {
  return CATEGORIES[code]?.icon ?? PLACE;
}

// The lightest a fill may be.
const PIN_MAX_LUM = 66;
const PIN_MIN_LUM = 18;
// 3:1 is the bar for a graphical object (WCAG 1.4.11): a glyph is a shape to recognise, not text.
const PIN_GLYPH_CONTRAST = 3;

const GROUP_FILLS = Object.fromEntries(Object.entries(GROUPS).map(([group, { hue, sat }]) => [
  group, forWhiteGlyph(hue, sat, PIN_MAX_LUM),
]));

/** The pin an untagged place wears: chroma-free, so it can never be mistaken for a group's colour. */
export const UNTAGGED_PIN_COLOR = forWhiteGlyph(0, 0, PIN_MAX_LUM);

/** The solid fill a categorized place's disc and map pin wear, or null for an untagged place. */
export function categoryPinColor(code) {
  return GROUP_FILLS[CATEGORIES[code]?.group] ?? null;
}

/** Darkens a hue from the ceiling, saturation held, until white ink on it reads at the glyph bar.
 * Equal contrast rather than equal HSL lightness: at one lightness a green sits far more luminous
 * than a blue. */
function forWhiteGlyph(hue, sat, lum) {
  while (lum > PIN_MIN_LUM && contrastWithWhite(hue, sat, lum) < PIN_GLYPH_CONTRAST) lum -= 1;
  return `hsl(${hue} ${sat}% ${lum}%)`;
}

export function contrastWithWhite(hue, sat, lum) {
  return 1.05 / (relativeLuminance(hslToRgb(hue, sat, lum)) + 0.05);
}

function hslToRgb(h, s, l) {
  s /= 100;
  l /= 100;
  const a = s * Math.min(l, 1 - l);
  const f = (n) => {
    const k = (n + h / 30) % 12;
    return l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1));
  };
  return [f(0), f(8), f(4)];
}

function relativeLuminance([r, g, b]) {
  const lin = (c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

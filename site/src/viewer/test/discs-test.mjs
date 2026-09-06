// The app's disc vocabulary, ported: every pin fill reads under white ink at the glyph bar and
// holds its group's hue, every activity has a hue of its own, and every glyph a disc can name is
// in the sprite. Run: node site/src/viewer/test/discs-test.mjs
import assert from "node:assert/strict";
import {
  activityColor, activityIcon, categoryPinColor, categoryIcon, contrastWithWhite, CATEGORY_CODES,
  ICON_NAMES, UNTAGGED_PIN_COLOR,
} from "../js/discs.js";

const hsl = (s) => s.match(/hsl\((\d+) (\d+)% (\d+)%\)/).slice(1).map(Number);

for (const code of CATEGORY_CODES) {
  const [h, s, l] = hsl(categoryPinColor(code));
  assert.ok(contrastWithWhite(h, s, l) >= 3, `${code}: white ink reads on the fill`);
  assert.ok(l <= 66, `${code}: no lighter than the ceiling`);
  assert.ok(ICON_NAMES.includes(categoryIcon(code)), `${code}: its glyph is in the sprite`);
}
assert.ok(hsl(UNTAGGED_PIN_COLOR)[1] === 0, "untagged is chroma-free");
// One group, one hue: a category is never told apart from its siblings by colour.
assert.equal(hsl(categoryPinColor("home"))[0], hsl(categoryPinColor("friends_family"))[0]);
assert.equal(hsl(categoryPinColor("parking"))[0], hsl(categoryPinColor("transit"))[0]);
assert.ok(hsl(categoryPinColor("parking"))[1] < hsl(categoryPinColor("home"))[1], "transient is the faintest");
assert.equal(categoryPinColor(null), null);
assert.equal(categoryPinColor("from_a_newer_app"), null);
assert.equal(categoryIcon("from_a_newer_app"), categoryIcon(null), "an unknown code is drawn as untagged");

const activities = ["WALKING", "RUNNING", "CYCLING", "DRIVING", "TAXI", "FERRY", "TRANSIT", "FLIGHT"];
const hues = new Set(activities.map((a) => hsl(activityColor(a))[0]));
assert.equal(hues.size, activities.length, "every activity has its own hue");
for (const a of activities) assert.ok(ICON_NAMES.includes(activityIcon(a)), `${a}: its glyph is in the sprite`);
assert.equal(activityColor("UNKNOWN"), null, "a movement nothing named wears the neutral");
assert.ok(ICON_NAMES.includes(activityIcon("UNKNOWN")));

console.log("all disc tests passed");

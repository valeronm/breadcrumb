// The place pin as the app's map draws it (ui/MarkerImages.kt): a white halo, a fill saying what
// the place is for, the category's white glyph inside it, and a soft shadow lifting it off the
// basemap. Drawn on a canvas because the fill varies per category. Browser-only — it reads the
// page's glyph sprite — so map.js calls it at style load and nothing imports it under node.

import { categoryPinColor, categoryIcon, CATEGORY_CODES, UNTAGGED_PIN_COLOR } from "./discs.js";

// Geometry as fractions of the pin, in the 24-unit viewport the app's drawable used. The halo is
// the thinnest ring that still separates a pin from the basemap; the glyph's box is wider than the
// fill looks to allow because a Material icon carries its own padding inside its viewport.
const PIN_SIZE = 28;
const HALO_RADIUS = 11 / 24;
const FILL_RADIUS = 9 / 24;
const GLYPH_BOX = 12 / 24;
// The shadow a map's subject carries: blur, drop and ink, scaled with the pin.
const SHADOW_BLUR = 4;
const SHADOW_DROP = 2.3;
const SHADOW_ALPHA = 90 / 255;
const PAD = Math.ceil(SHADOW_BLUR * 2 + SHADOW_DROP);

export const pinImageId = (code, glyphed) =>
  `pin-${categoryPinColor(code) ? code : "untagged"}-${glyphed ? "glyph" : "disc"}`;

let drawn = null;

/** The whole pin set, drawn once: a style load takes it from here, and the first call can run
 * while the style is still in flight. */
export function pinImages() {
  if (drawn) return drawn;
  const ratio = Math.min(window.devicePixelRatio || 1, 3);
  drawn = { ratio, images: [] };
  for (const code of [null, ...CATEGORY_CODES]) {
    const fill = categoryPinColor(code) ?? UNTAGGED_PIN_COLOR;
    for (const glyphed of [false, true]) {
      drawn.images.push([pinImageId(code, glyphed), pinImage(fill, glyphed ? categoryIcon(code) : null, ratio)]);
    }
  }
  return drawn;
}

/** Images are not stylesheet state, so every style load starts without them. */
export function addPinImages(map) {
  const { ratio, images } = pinImages();
  for (const [id, image] of images) map.addImage(id, image, { pixelRatio: ratio });
}

function pinImage(fill, iconName, ratio) {
  const size = PIN_SIZE + PAD * 2;
  const canvas = document.createElement("canvas");
  canvas.width = size * ratio;
  canvas.height = size * ratio;
  const ctx = canvas.getContext("2d");
  ctx.scale(ratio, ratio);
  const center = size / 2;
  ctx.shadowColor = `rgba(0, 0, 0, ${SHADOW_ALPHA})`;
  ctx.shadowBlur = SHADOW_BLUR;
  ctx.shadowOffsetY = SHADOW_DROP;
  ctx.fillStyle = "white";
  circle(ctx, center, PIN_SIZE * HALO_RADIUS);
  ctx.shadowColor = "transparent";
  ctx.fillStyle = fill;
  circle(ctx, center, PIN_SIZE * FILL_RADIUS);
  if (iconName) {
    const box = PIN_SIZE * GLYPH_BOX;
    ctx.save();
    ctx.translate(center - box / 2, center - box / 2);
    ctx.scale(box / 24, box / 24);
    ctx.fillStyle = "white";
    for (const path of glyphPaths(iconName)) ctx.fill(path);
    ctx.restore();
  }
  return ctx.getImageData(0, 0, canvas.width, canvas.height);
}

function circle(ctx, center, radius) {
  ctx.beginPath();
  ctx.arc(center, center, radius, 0, Math.PI * 2);
  ctx.fill();
}

/** The glyph's outlines, read from the sprite the page inlined so the pin and the row draw one
 * shape for a category. */
function glyphPaths(iconName) {
  const symbol = document.getElementById(`icon-${iconName}`);
  return [...symbol.querySelectorAll("path")].map((p) => new Path2D(p.getAttribute("d")));
}

import { defineConfig } from "astro/config";

export default defineConfig({
  // The viewer is served as it is written, ES modules and all, so it lives in public/ and is
  // never bundled; the landing page and anything with a layout is under src/.
  image: {
    // Screenshots are imported from docs/ and re-encoded per width at build; the PNGs the README
    // embeds stay the one set.
    layout: "constrained",
  },
});

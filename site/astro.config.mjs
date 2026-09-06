import { defineConfig } from "astro/config";

export default defineConfig({
  site: "https://breadcrumb.place",
  // The viewer is served as it is written, ES modules and all, so it lives in public/ and is
  // never bundled; the landing page and anything with a layout is under src/.
  image: {
    // Screenshots are imported from docs/ and re-encoded per width at build; the PNGs the README
    // embeds stay the one set.
    layout: "constrained",
    // The width prop is then the one statement of an image's size: Astro's own styles cap it at
    // the container and keep the aspect, so the stylesheet sizes nothing.
    responsiveStyles: true,
  },
});

import type { APIRoute } from "astro";

export const GET: APIRoute = ({ site }) =>
  new Response(`Sitemap: ${new URL("/sitemap-index.xml", site)}\n`);

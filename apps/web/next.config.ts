import type { NextConfig } from "next";

const isDev = process.env.NODE_ENV === "development";

// The app loads everything from its own origin: fonts are self-hosted by next/font and the API is
// reached through the /api proxy route. Inline scripts stay allowed (Next.js bootstraps with them
// and the theme script runs before paint); a nonce-based policy would force every page to render
// dynamically. Development also needs eval for React's debugging. Images and fonts may be data:
// or blob: URLs (icons, exports).
const csp = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${isDev ? " 'unsafe-eval'" : ""}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' blob: data:",
  "font-src 'self' data:",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

const securityHeaders = [
  { key: "Content-Security-Policy", value: csp },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "no-referrer" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), browsing-topics=()" },
  { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
];

const nextConfig: NextConfig = {
  // Self-contained server bundle for the Docker image.
  output: "standalone",
  // The app shows no image through next/image, so the server needs no image optimizer: images
  // are served as they are, and the standalone output leaves out sharp and the libvips it
  // bundles (LGPL-3.0-or-later), which the Docker image would carry and never run (ADR-0066;
  // scripts/check-standalone.mjs checks the output). The key is "*", not the documented "/*":
  // only "*" also matches "next-server", the server's own trace, which is what pulls in sharp.
  images: { unoptimized: true },
  outputFileTracingExcludes: { "*": ["node_modules/sharp/**", "node_modules/@img/**"] },
  poweredByHeader: false,
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

// The public website, archsim.io (ADR-0078): `ARCHSIM_SITE=1 next build` (npm run build:site)
// exports the routes named *.site.tsx and *.site.ts to out/ as static files for GitHub Pages, and
// nothing else; the app's build above sees none of them. A static host sends no headers, so
// headers() is left out (the site's layout sets its policy in a meta tag). Type checking is left
// to `npm run typecheck`, which checks the site's files with the app's: here the app's typed
// routes (PageProps<"/projects/[id]">) would not exist.
const siteConfig: NextConfig = {
  output: "export",
  trailingSlash: true,
  pageExtensions: ["site.tsx", "site.ts"],
  images: { unoptimized: true },
  poweredByHeader: false,
  typescript: { ignoreBuildErrors: true },
  turbopack: nextConfig.turbopack,
};

export default process.env.ARCHSIM_SITE === "1" ? siteConfig : nextConfig;

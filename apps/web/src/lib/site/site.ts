/**
 * The public website's identity (ADR-0078): where it lives, how each page describes itself to
 * search engines and link previews, and the build-time switch for the hosted demo.
 */
import type { Metadata } from "next";

export const SITE_URL = "https://archsim.io";
export const SITE_NAME = "ArchSim";
export const SITE_DESCRIPTION =
  "Crash-test your architecture before your users do. An open-source simulator for distributed systems: draw your stack with real technologies, throw traffic and failures at it, and get a straight yes or no, with the bottleneck and the monthly bill.";

/**
 * The site's content security policy, as a meta tag (GitHub Pages sends no headers): everything
 * from the site's own origin, so no third-party script, font or beacon can load. Inline scripts
 * stay allowed for Next.js's bootstrap and the theme script; development also needs eval.
 */
export function contentSecurityPolicy(development: boolean): string {
  return [
    "default-src 'self'",
    `script-src 'self' 'unsafe-inline'${development ? " 'unsafe-eval'" : ""}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'none'",
  ].join("; ");
}

/** docs/images/social-preview.png, served under /docs-assets like every image of docs/. */
export const SOCIAL_IMAGE = { url: `${SITE_URL}/docs-assets/images/social-preview.png`, width: 1280, height: 640, alt: "ArchSim: crash-test your architecture before your users do" };

export function siteUrl(path: string): string {
  return `${SITE_URL}${path.startsWith("/") ? path : `/${path}`}`;
}

/** A page's title, description, canonical URL and link preview. */
export function pageMetadata({ title, description, path }: { title: string; description: string; path: string }): Metadata {
  const url = siteUrl(path);
  return {
    title,
    description,
    alternates: { canonical: url },
    openGraph: { type: "website", siteName: SITE_NAME, url, title, description, images: [SOCIAL_IMAGE] },
    twitter: { card: "summary_large_image", title, description, images: [SOCIAL_IMAGE.url] },
  };
}

const escapeXml = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

export function sitemapXml(paths: string[]): string {
  const urls = paths.map((p) => `<url><loc>${escapeXml(siteUrl(p))}</loc></url>`).join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`;
}

export function robotsTxt(): string {
  return `User-agent: *\nAllow: /\n\nSitemap: ${siteUrl("/sitemap.xml")}\n`;
}

/**
 * The hosted demo the "Try it live" links open: `ARCHSIM_SITE_DEMO_URL` at build time (the
 * repository variable of the same name in the site's workflow), or null, and the links hidden,
 * when it is unset.
 */
export function demoUrl(env: Record<string, string | undefined>): string | null {
  const raw = env.ARCHSIM_SITE_DEMO_URL?.trim();
  if (!raw) return null;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new Error(`ARCHSIM_SITE_DEMO_URL is not a URL: ${raw}`);
  }
  if (url.protocol !== "https:") throw new Error(`ARCHSIM_SITE_DEMO_URL must be an https URL: ${raw}`);
  return url.href;
}

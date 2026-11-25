/**
 * The public website's identity (ADR-0078): where it lives, how each page describes itself to
 * search engines and link previews, and the build-time switch for the hosted demo.
 */
import type { Metadata } from "next";

export const SITE_URL = "https://archsim.io";
export const SITE_NAME = "ArchSim";
/** The home page in a search result: at most about 155 characters, so it is not cut off. */
export const SITE_DESCRIPTION =
  "Crash-test your architecture before your users do: an open-source simulator that finds bottlenecks and compares design decisions, with their monthly cost.";
/** The home page in a link preview, which has room for the longer pitch. */
export const SITE_SUMMARY =
  "Crash-test your architecture before your users do. ArchSim simulates distributed systems so you can uncover bottlenecks, rehearse failures and compare architectural decisions, with their monthly cost, before writing production code.";

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

/**
 * A page's title, description, canonical URL and link preview. `socialDescription`, when given, is
 * the preview's longer description; search results keep `description`.
 */
export function pageMetadata({
  title,
  description,
  socialDescription = description,
  path,
}: {
  title: string;
  description: string;
  socialDescription?: string;
  path: string;
}): Metadata {
  const url = siteUrl(path);
  return {
    title,
    description,
    alternates: { canonical: url },
    openGraph: { type: "website", siteName: SITE_NAME, url, title, description: socialDescription, images: [SOCIAL_IMAGE] },
    twitter: { card: "summary_large_image", title, description: socialDescription, images: [SOCIAL_IMAGE.url] },
  };
}

const escapeXml = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

/** The sitemap of `pages`, each with the date it last changed when that is known. */
export function sitemapXml(pages: { path: string; lastmod?: string }[]): string {
  const urls = pages.map(({ path, lastmod }) => `<url><loc>${escapeXml(siteUrl(path))}</loc>${lastmod ? `<lastmod>${escapeXml(lastmod)}</lastmod>` : ""}</url>`).join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`;
}

export function robotsTxt(): string {
  return `User-agent: *\nAllow: /\n\nSitemap: ${siteUrl("/sitemap.xml")}\n`;
}

/**
 * The hosted demo "Try ArchSim" opens: `ARCHSIM_SITE_DEMO_URL` at build time (the repository
 * variable of the same name in the site's workflow), or null when it is unset, and "Try ArchSim"
 * goes to the home page's quick start instead.
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

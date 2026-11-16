import { describe, expect, it } from "vitest";
import { SITE_DESCRIPTION, SITE_SUMMARY, contentSecurityPolicy, demoUrl, pageMetadata, robotsTxt, sitemapXml } from "./site";

describe("pageMetadata", () => {
  it("gives every page its canonical URL on archsim.io and the social preview", () => {
    const meta = pageMetadata({ title: "Getting started", description: "Run it in Docker.", path: "/docs/getting-started/" });

    expect(meta.title).toBe("Getting started");
    expect(meta.description).toBe("Run it in Docker.");
    expect(meta.alternates?.canonical).toBe("https://archsim.io/docs/getting-started/");
    expect(meta.openGraph).toMatchObject({
      url: "https://archsim.io/docs/getting-started/",
      siteName: "ArchSim",
      title: "Getting started",
      images: [{ url: "https://archsim.io/docs-assets/images/social-preview.png", width: 1280, height: 640 }],
    });
    expect(meta.twitter).toMatchObject({ card: "summary_large_image", images: ["https://archsim.io/docs-assets/images/social-preview.png"] });
  });

  it("gives link previews a longer description than search results, when a page has one", () => {
    const meta = pageMetadata({ title: "ArchSim", description: "Short.", socialDescription: "Short, and longer.", path: "/" });

    expect(meta.description).toBe("Short.");
    expect(meta.openGraph?.description).toBe("Short, and longer.");
    expect(meta.twitter?.description).toBe("Short, and longer.");
  });
});

describe("the home page's descriptions", () => {
  it("fit a search result, and keep the long one for link previews", () => {
    expect(SITE_DESCRIPTION.length).toBeLessThanOrEqual(155);
    expect(SITE_SUMMARY.length).toBeGreaterThan(SITE_DESCRIPTION.length);
    expect(SITE_SUMMARY).toMatch(/^Crash-test your architecture before your users do\./);
  });
});

describe("sitemapXml", () => {
  it("lists every page by its full URL", () => {
    const xml = sitemapXml([{ path: "/" }, { path: "/docs/" }, { path: "/docs/a&b/" }]);

    expect(xml).toMatch(/^<\?xml version="1.0" encoding="UTF-8"\?>\n<urlset xmlns="http:\/\/www.sitemaps.org\/schemas\/sitemap\/0.9">/);
    expect(xml).toContain("<url><loc>https://archsim.io/</loc></url>");
    expect(xml).toContain("<url><loc>https://archsim.io/docs/a&amp;b/</loc></url>");
  });

  it("says when a page last changed, where it knows", () => {
    const xml = sitemapXml([{ path: "/docs/", lastmod: "2026-11-15T22:01:03+01:00" }, { path: "/changelog/" }]);

    expect(xml).toContain("<url><loc>https://archsim.io/docs/</loc><lastmod>2026-11-15T22:01:03+01:00</lastmod></url>");
    expect(xml).toContain("<url><loc>https://archsim.io/changelog/</loc></url>");
  });
});

describe("robotsTxt", () => {
  it("lets every crawler in and points at the sitemap", () => {
    expect(robotsTxt()).toBe("User-agent: *\nAllow: /\n\nSitemap: https://archsim.io/sitemap.xml\n");
  });
});

describe("contentSecurityPolicy", () => {
  it("lets nothing load from another origin, and eval only in development", () => {
    const policy = contentSecurityPolicy(false);

    expect(policy.split("; ").every((d) => !/https?:|\*/.test(d))).toBe(true);
    expect(policy).toContain("default-src 'self'");
    expect(policy).not.toContain("unsafe-eval");
    expect(contentSecurityPolicy(true)).toContain("unsafe-eval");
  });
});

describe("demoUrl", () => {
  it("is off unless the build names one", () => {
    expect(demoUrl({})).toBeNull();
    expect(demoUrl({ ARCHSIM_SITE_DEMO_URL: "  " })).toBeNull();
    expect(demoUrl({ ARCHSIM_SITE_DEMO_URL: "https://app.archsim.io" })).toBe("https://app.archsim.io/");
  });

  it("refuses anything but an https URL", () => {
    expect(() => demoUrl({ ARCHSIM_SITE_DEMO_URL: "app.archsim.io" })).toThrow(/ARCHSIM_SITE_DEMO_URL/);
    expect(() => demoUrl({ ARCHSIM_SITE_DEMO_URL: "javascript:alert(1)" })).toThrow(/ARCHSIM_SITE_DEMO_URL/);
  });
});

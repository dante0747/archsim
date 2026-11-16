/**
 * Every page the website's sitemap lists, plus its 404, on a phone and a desktop in both themes:
 * it renders its heading, it does not scroll sideways, and the browser reports no error (a
 * resource the content security policy blocked would be one).
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { type APIRequestContext, type Page, expect, test } from "@playwright/test";

const out = resolve(process.cwd(), "out");
const pages = [...readFileSync(resolve(out, "sitemap.xml"), "utf8").matchAll(/<loc>https:\/\/archsim\.io([^<]*)<\/loc>/g)].map((m) => m[1]);
const VIEWPORTS = { phone: { width: 375, height: 812 }, desktop: { width: 1440, height: 900 } };
const THEMES = ["dark", "light"] as const;
const MISSING = "/no-such-page/";
const SHOTS: [string, string][] = [
  ["home", "/"],
  ["docs", "/docs/"],
  ["doc-getting-started", "/docs/getting-started/"],
  ["doc-architecture", "/docs/architecture/"],
  ["doc-adr", "/docs/adr/0067-license-under-agpl-3.0-with-a-commercial-license/"],
  ["accuracy", "/accuracy/"],
  ["changelog", "/changelog/"],
  ["privacy", "/privacy/"],
  ["not-found", MISSING],
];

async function useTheme(page: Page, theme: (typeof THEMES)[number]) {
  await page.addInitScript((t) => localStorage.setItem("archsim-theme-studio", t), theme);
}

for (const [device, viewport] of Object.entries(VIEWPORTS)) {
  for (const theme of THEMES) {
    test(`every page renders without scrolling sideways (${device}, ${theme})`, async ({ page }) => {
      expect(pages.length).toBeGreaterThan(5);
      await page.setViewportSize(viewport);
      await useTheme(page, theme);
      const errors: string[] = [];
      // the 404 page's own status is the one error the browser should report
      const expected = (text: string) => page.url().endsWith(MISSING) && text.includes("status of 404");
      page.on("pageerror", (e) => errors.push(`${page.url()}: ${e.message}`));
      page.on("console", (m) => m.type() === "error" && !expected(m.text()) && errors.push(`${page.url()}: ${m.text()}`));
      const wide: string[] = [];
      for (const path of [...pages, MISSING]) {
        const response = await page.goto(path);
        expect(response?.status(), path).toBe(path === MISSING ? 404 : 200);
        await expect(page.locator("h1").first(), path).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.classList.contains("dark")), path).toBe(theme === "dark");
        const { scroll, client } = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }));
        if (scroll > client) wide.push(`${path} (${scroll} > ${client})`);
      }
      expect(wide, "pages that scroll sideways").toEqual([]);
      expect(errors, "browser errors").toEqual([]);
    });
  }
}

test.describe("search engines", () => {
  const decode = (s: string) => s.replace(/&quot;/g, '"').replace(/&#x27;|&#39;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");
  const DESCRIPTION = /<meta name="description" content="([^"]*)"/;

  async function structuredData(request: APIRequestContext, path: string): Promise<Record<string, unknown>[]> {
    const html = await (await request.get(path)).text();
    return [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].flatMap((m) => JSON.parse(m[1])["@graph"]);
  }

  test("every page names its language, its canonical address and what it is about", async ({ request }) => {
    const weak: string[] = [];
    for (const path of pages) {
      const html = await (await request.get(path)).text();
      expect(html, path).toMatch(/<html lang="en"/);
      expect(html, path).toContain(`<link rel="canonical" href="https://archsim.io${path}"/>`);
      const description = decode(DESCRIPTION.exec(html)?.[1] ?? "");
      // the docs' and the changelog's are their first paragraph of prose, cut to fit a search result
      const derived = path.startsWith("/docs/") || path === "/changelog/";
      if (!description || description.endsWith(":") || (derived && description.length > 155)) weak.push(`${path}: "${description}"`);
    }
    expect(weak, "pages without a fitting description").toEqual([]);
  });

  test("the 404 page asks not to be indexed", async ({ request }) => {
    const response = await request.get(MISSING);

    expect(response.status()).toBe(404);
    expect(await response.text()).toMatch(/<meta name="robots" content="noindex"/);
  });

  test("the home page describes ArchSim as software, at the version it was built from", async ({ request }) => {
    const { version } = JSON.parse(readFileSync(resolve(process.cwd(), "package.json"), "utf8")) as { version: string };

    const graph = await structuredData(request, "/");

    expect(graph.find((n) => n["@type"] === "SoftwareApplication")).toMatchObject({ name: "ArchSim", softwareVersion: version, url: "https://archsim.io/" });
    expect(graph.find((n) => n["@type"] === "WebSite")).toMatchObject({ name: "ArchSim", url: "https://archsim.io/" });
  });

  test("a decision record is an article with the breadcrumbs that lead to it", async ({ request }) => {
    const path = "/docs/adr/0067-license-under-agpl-3.0-with-a-commercial-license/";

    const graph = await structuredData(request, path);

    expect(graph.find((n) => n["@type"] === "TechArticle")).toMatchObject({ url: `https://archsim.io${path}` });
    const crumbs = graph.find((n) => n["@type"] === "BreadcrumbList")?.itemListElement as { item: string }[];
    expect(crumbs.map((c) => c.item)).toEqual(["https://archsim.io/docs/", "https://archsim.io/docs/adr/", `https://archsim.io${path}`]);
  });
});

test.describe("the demo's privacy notice", () => {
  test("is a footer link away, and says who runs the demo, how to reach them and what it keeps", async ({ page }) => {
    await page.goto("/accuracy/");

    await page.getByRole("contentinfo").getByRole("link", { name: "Privacy" }).click();

    await expect(page).toHaveURL(/\/privacy\/$/);
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Privacy");
    const main = page.getByRole("main");
    await expect(main).toContainText("Majid Abarghooei");
    await expect(main.getByRole("link", { name: "abarghooeimajid@gmail.com" }).first()).toHaveAttribute("href", "mailto:abarghooeimajid@gmail.com");
    await expect(main).toContainText("deleted 24 hours after the sandbox starts");
    await expect(main.getByRole("heading", { level: 2, name: "Having your data deleted" })).toBeVisible();
  });
});

test.describe("screenshots", () => {
  test.skip(!process.env.SITE_SCREENSHOTS, "set SITE_SCREENSHOTS=1 to save them");
  test.use({ reducedMotion: "reduce" });

  for (const [device, viewport] of Object.entries(VIEWPORTS)) {
    for (const theme of THEMES) {
      test(`main pages (${device}, ${theme})`, async ({ page }) => {
        await page.setViewportSize(viewport);
        await useTheme(page, theme);
        for (const [name, path] of SHOTS) {
          await page.goto(path);
          await page.evaluate(() => document.fonts.ready);
          // scroll through once, so lazy images load before the full-page shot
          await page.evaluate(async () => {
            for (let y = 0; y < document.documentElement.scrollHeight; y += 700) {
              window.scrollTo(0, y);
              await new Promise((r) => setTimeout(r, 40));
            }
            window.scrollTo(0, 0);
          });
          await page.waitForFunction(() => [...document.images].every((i) => i.complete));
          await page.screenshot({ path: `test-results/site/${device}-${theme}-${name}.png`, fullPage: true });
        }
      });
    }
  }
});

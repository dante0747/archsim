import { existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { INDEXNOW_KEY } from "../../scripts/indexnow.mjs";
import { GET as indexNowKey } from "./92a951fcad63c58a46b68f9e5a6a3098.txt/route.site";
import { GET as cname } from "./CNAME/route.site";
import { GET as robots } from "./robots.txt/route.site";

// The website's own files at its root (ADR-0078). The sitemap lists pages read from docs/, so the
// site's build checks it instead (scripts/check-site.mjs): this suite reads nothing outside apps/web.
describe("the website's root files", () => {
  it("names archsim.io for GitHub Pages' custom domain", async () => {
    expect(await cname().text()).toBe("archsim.io\n");
  });

  it("lets crawlers in and points them at the sitemap", async () => {
    expect(await robots().text()).toBe("User-agent: *\nAllow: /\n\nSitemap: https://archsim.io/sitemap.xml\n");
  });

  it("serves the IndexNow key at the address the deploy's ping names", async () => {
    expect(INDEXNOW_KEY).not.toBe("");
    expect(existsSync(join(import.meta.dirname, `${INDEXNOW_KEY}.txt`, "route.site.ts"))).toBe(true);
    expect(await indexNowKey().text()).toBe(INDEXNOW_KEY);
  });
});

import { describe, expect, it } from "vitest";
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
});

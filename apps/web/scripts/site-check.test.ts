import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { checkSite } from "./site-check.mjs";

let root: string;
let out: string;

function file(path: string, text = "") {
  mkdirSync(dirname(join(root, path)), { recursive: true });
  writeFileSync(join(root, path), text);
}

const page = (body: string, head = "") => `<!DOCTYPE html><html><head>${head}</head><body>${body}</body></html>`;

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "archsim-site-check-"));
  out = join(root, "apps/web/out");
  file("docs/guide.md");
  file("benchmarks/results/run.json");
  file("apps/web/out/_next/static/app.js");
  file("apps/web/out/docs-assets/images/shot.jpg");
  file("apps/web/out/docs/guide/index.html", page('<h2 id="setup">Setup</h2>'));
  file("apps/web/out/sitemap.xml", "<urlset><url><loc>https://archsim.io/</loc></url><url><loc>https://archsim.io/docs/guide/</loc></url></urlset>");
});

afterEach(() => rmSync(root, { recursive: true, force: true }));

describe("checkSite", () => {
  it("passes a site whose every link, anchor, image and script is there", () => {
    file(
      "apps/web/out/index.html",
      page(
        '<a href="/docs/guide/#setup">Guide</a><a href="docs/guide/">Relative</a><a href="#top" id="top">Top</a><img src="/docs-assets/images/shot.jpg" alt="">' +
          '<a href="https://github.com/dante0747/archsim/blob/main/docs/guide.md">Source</a><a href="https://github.com/dante0747/archsim/tree/main/benchmarks/results/">Results</a>' +
          '<a href="https://example.com/">Elsewhere</a><a href="mailto:a@example.com">Mail</a><script>self.x("<a href=\\"/nowhere/\\">")</script>',
        '<link rel="canonical" href="https://archsim.io/"><script src="/_next/static/app.js"></script>',
      ),
    );

    expect(checkSite({ out, repository: root })).toEqual([]);
  });

  it("reports missing pages, anchors, images and repository files", () => {
    file(
      "apps/web/out/index.html",
      page(
        '<a href="/docs/missing/">Missing</a><a href="/docs/guide/#nowhere">Anchor</a><img src="/docs-assets/images/gone.png" alt="">' +
          '<a href="https://github.com/dante0747/archsim/blob/main/docs/gone.md">Gone</a>',
      ),
    );

    expect(checkSite({ out, repository: root }).map((p) => `${p.page} ${p.url}`)).toEqual([
      "/ /docs/missing/",
      "/ /docs/guide/#nowhere",
      "/ /docs-assets/images/gone.png",
      "/ https://github.com/dante0747/archsim/blob/main/docs/gone.md",
    ]);
  });

  it("refuses scripts, styles and images from another origin", () => {
    file("apps/web/out/index.html", page('<img src="https://cdn.example.com/a.png" alt="">', '<script src="https://cdn.example.com/x.js"></script><link rel="stylesheet" href="https://fonts.example.com/a.css">'));

    expect(checkSite({ out, repository: root }).map((p) => p.problem)).toEqual([
      "loads a resource from another origin",
      "loads a resource from another origin",
      "loads a resource from another origin",
    ]);
  });

  it("checks every image a srcset offers, on an img and on a picture's source", () => {
    file(
      "apps/web/out/index.html",
      page(
        '<picture><source media="(max-width: 767px)" srcset="/docs-assets/images/shot.jpg 400w, /docs-assets/images/crop-800.webp 800w">' +
          '<img src="/docs-assets/images/shot.jpg" srcset="/docs-assets/images/shot.jpg 960w, /docs-assets/images/wide-1920.webp 1920w" alt=""></picture>',
      ),
    );

    expect(checkSite({ out, repository: root }).map((p) => `${p.url} ${p.problem}`)).toEqual([
      "/docs-assets/images/crop-800.webp no such page",
      "/docs-assets/images/wide-1920.webp no such page",
    ]);
  });

  it("checks that every page the sitemap lists exists", () => {
    file("apps/web/out/index.html", page(""));
    file("apps/web/out/sitemap.xml", "<urlset><url><loc>https://archsim.io/docs/old/</loc></url></urlset>");

    expect(checkSite({ out, repository: root })).toEqual([{ page: "/sitemap.xml", url: "https://archsim.io/docs/old/", problem: "no such page" }]);
  });
});

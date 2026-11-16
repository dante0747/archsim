import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { Repository } from "./content";

let root: string;

function file(path: string, text = "") {
  mkdirSync(dirname(join(root, path)), { recursive: true });
  writeFileSync(join(root, path), text);
}

beforeAll(() => {
  root = mkdtempSync(join(tmpdir(), "archsim-site-"));
  file("docs/README.md", "# Docs\n\n## Start here\n\n| Page | What |\n| --- | --- |\n| [Guide](guide.md) | The guide |\n");
  file("docs/guide.md", "# The guide\n\nSee [the decisions](adr/README.md) and [the config](../config/).\n");
  file("docs/adr/README.md", "# Decisions\n\nAll of them.\n");
  file("docs/adr/0001-record.md", "---\nstatus: accepted\n---\n\n# 0001. Record decisions\n\nWhy.\n");
  file("docs/images/shot.jpg");
  file("docs/validation/chart.svg");
  file("docs/validation/data.json");
  file("config/checkstyle.xml");
  file("NOTICE", "ArchSim\nCopyright 2026 Someone and the ArchSim contributors\n\nThis product is licensed.\n");
  file("CHANGELOG.md", "# Changelog\n\n## [1.0.0]\n\nFirst. See [the guide](docs/guide.md).\n");
});

afterAll(() => rmSync(root, { recursive: true, force: true }));

describe("Repository", () => {
  it("lists every document under docs/ and every image it serves", () => {
    const repo = new Repository(root);

    expect(repo.docFiles()).toEqual(["docs/README.md", "docs/adr/0001-record.md", "docs/adr/README.md", "docs/guide.md"]);
    expect(repo.docAssets()).toEqual(["docs/images/shot.jpg", "docs/validation/chart.svg"]);
  });

  it("finds a document by its page and renders it with the site's links", () => {
    const repo = new Repository(root);

    const guide = repo.doc(["guide"]);
    expect(guide.path).toBe("docs/guide.md");
    expect(guide.title).toBe("The guide");
    expect(guide.html).toContain('href="/docs/adr/"');
    expect(guide.html).toContain('href="https://github.com/dante0747/archsim/tree/main/config/"');
    expect(repo.doc([]).path).toBe("docs/README.md");
    expect(repo.doc(["adr"]).path).toBe("docs/adr/README.md");
    expect(repo.doc(["adr", "0001-record"]).frontMatter.status).toBe("accepted");
    expect(() => repo.doc(["missing"])).toThrow(/no document/);
  });

  it("names the pages above a document, for its breadcrumbs", () => {
    const repo = new Repository(root);

    expect(repo.ancestors(["adr", "0001-record"])).toEqual([{ title: "Decisions", path: "/docs/adr/" }]);
    expect(repo.ancestors(["guide"])).toEqual([]);
    expect(repo.ancestors(["validation", "deep", "page"])).toEqual([]);
  });

  it("lists every page of the site, for the sitemap", () => {
    expect(new Repository(root).pages()).toEqual(["/", "/docs/", "/docs/adr/0001-record/", "/docs/adr/", "/docs/guide/", "/accuracy/", "/changelog/"]);
  });

  it("reads the navigation, the changelog and NOTICE's copyright line", () => {
    const repo = new Repository(root);

    expect(repo.nav()[0].items[0]).toMatchObject({ title: "Guide", href: "/docs/guide/" });
    expect(repo.changelog().html).toContain('href="/docs/guide/"');
    expect(repo.copyright()).toBe("Copyright 2026 Someone and the ArchSim contributors");
  });
});

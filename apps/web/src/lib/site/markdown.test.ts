import { describe, expect, it } from "vitest";
import { renderMarkdown } from "./markdown";

const ctx = (from: string) => ({ from, isFolder: (p: string) => p === "benchmarks/results" });

const doc = `# Getting started

Run ArchSim in **Docker** or from source.
It takes five minutes.

## What you need

Docker. See [the features](features.md#design) and [the API's module](../archsim-app/README.md).

![The editor](images/build.jpg)

### Ports and URLs

| Port | What |
| --- | --- |
| 3000 | the web app |

\`\`\`bash
docker compose up
\`\`\`

<script>alert(1)</script>
`;

describe("renderMarkdown", () => {
  it("takes the first heading as the title and the first paragraph as the description", () => {
    const page = renderMarkdown(doc, ctx("docs/getting-started.md"));

    expect(page.title).toBe("Getting started");
    expect(page.description).toBe("Run ArchSim in Docker or from source. It takes five minutes.");
    expect(page.html).not.toContain("<h1");
  });

  it("gives headings GitHub's anchors and lists them for the page's contents", () => {
    const page = renderMarkdown(doc, ctx("docs/getting-started.md"));

    expect(page.headings).toEqual([
      { id: "what-you-need", text: "What you need", depth: 2 },
      { id: "ports-and-urls", text: "Ports and URLs", depth: 3 },
    ]);
    expect(page.html).toContain('<h2 id="what-you-need">');
    expect(page.html).toContain('href="#what-you-need"');
  });

  it("rewrites links and images for the site and keeps alt text", () => {
    const page = renderMarkdown(doc, ctx("docs/getting-started.md"));

    expect(page.html).toContain('href="/docs/features/#design"');
    expect(page.html).toContain('href="https://github.com/dante0747/archsim/blob/main/archsim-app/README.md"');
    expect(page.html).toMatch(/<img src="\/docs-assets\/images\/build.jpg" alt="The editor"[^>]* loading="lazy"/);
  });

  it("rewrites links in tables and headings too", () => {
    const page = renderMarkdown("# Index\n\n## See [the schema](schema.md)\n\n| Page | What |\n| --- | --- |\n| [API](api.md) | endpoints |\n", ctx("docs/README.md"));

    expect(page.html).toContain('href="/docs/api/"');
    expect(page.html).toContain('href="/docs/schema/"');
  });

  it("lets wide tables and code scroll on their own, and highlights code", () => {
    const page = renderMarkdown(doc, ctx("docs/getting-started.md"));

    expect(page.html).toMatch(/<div class="table-scroll" tabindex="0"><table>/);
    expect(page.html).toMatch(/<pre tabindex="0"><code class="hljs language-bash">/);
  });

  it("drops raw HTML", () => {
    expect(renderMarkdown(doc, ctx("docs/getting-started.md")).html).not.toContain("<script");
  });

  it("reads the front matter of a decision record and leaves it out of the page", () => {
    const adr = "---\n# proposed | accepted\nstatus: accepted\ndate: 2026-11-15\n---\n\n# 0077. Publish release images\n\nBody.\n";

    const page = renderMarkdown(adr, ctx("docs/adr/0077-publish.md"));

    expect(page.frontMatter).toEqual({ status: "accepted", date: "2026-11-15" });
    expect(page.title).toBe("0077. Publish release images");
    expect(page.html).not.toContain("status:");
  });

  it("shows a Mermaid diagram's source with a link to the drawing on GitHub", () => {
    const page = renderMarkdown("# Architecture\n\n```mermaid\nflowchart LR\n  a --> b\n```\n", ctx("docs/architecture.md"));

    expect(page.html).toContain('<figure class="diagram">');
    expect(page.html).toContain("flowchart LR");
    expect(page.html).toContain('href="https://github.com/dante0747/archsim/blob/main/docs/architecture.md"');
  });
});

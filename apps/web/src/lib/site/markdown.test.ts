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

  it("describes a page by its first sentence, not a line that leads into a list", () => {
    // docs/getting-started.md as it opens
    const gettingStarted = `# Getting started

Three ways in, from the quickest:

1. [Run everything in Docker](#run-everything-in-docker): the web app, the API and a simulation
   worker, without installing Node.js or PostgreSQL.
2. [Run from source](#run-from-source): the same stack, each part in a terminal of its own, for
   working on ArchSim.

Then take [the five-minute tour](#a-five-minute-tour).
`;

    expect(renderMarkdown(gettingStarted, ctx("docs/getting-started.md")).description).toBe("Then take the five-minute tour.");
  });

  it("keeps the sentences a paragraph has before the line that leads into a list", () => {
    // docs/adr/0007-spring-boot-4.md as its context opens
    const adr = `# 0007. Spring Boot 4

## Context

The Java application replaces the FastAPI service and keeps its contract with the web app. It
covers:

- the HTTP API
- the run queue

It also runs the simulation workers (ADR-0014).
`;

    expect(renderMarkdown(adr, ctx("docs/adr/0007-spring-boot-4.md")).description).toBe("The Java application replaces the FastAPI service and keeps its contract with the web app.");
  });

  it("describes a page by a line ending in a colon when it has nothing else", () => {
    expect(renderMarkdown("# Pricing\n\nA design says where its prices come from:\n\n- the catalog\n", ctx("docs/pricing.md")).description).toBe("A design says where its prices come from:");
  });

  it("cuts a long description on a word boundary near 155 characters, marking the cut", () => {
    const short = "ArchSim predicts latency and capacity from a model, and this page checks those predictions against real systems running on one machine, stack by stack.";
    const long =
      "ArchSim predicts latency and capacity from a model. This page checks those predictions against real systems running on one machine: for each stack, the model is calibrated from a few measurements, then predicts the rest.";

    expect(renderMarkdown(`# Validation\n\n${short}\n`, ctx("docs/validation.md")).description).toBe(short);
    expect(renderMarkdown(`# Validation\n\n${long}\n`, ctx("docs/validation.md")).description).toBe(
      "ArchSim predicts latency and capacity from a model. This page checks those predictions against real systems running on one machine: for each stack, the…",
    );
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

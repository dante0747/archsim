import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { paragraphContent, readmeContent } from "./readme";

const readme = `<h1 align="center">ArchSim</h1>

## Why ArchSim

- **Real technologies, real behaviour.** PostgreSQL locks rows and waits for its write-ahead log.
- **Hypotheses to test.** Whether a design meets your objectives under the assumptions you state.

## Features

<table>
<tr>
<td width="50%" valign="top"><img src="docs/images/build.jpg" alt="Design workspace: the Black Friday diagram" width="380"><br><b>Design with real technologies</b><br>Pick PostgreSQL, Kafka or a Go service.</td>
<td width="50%" valign="top"><img src="docs/images/scorecard.jpg" alt="Results Answer tab" width="380"><br><b>Get an answer to test</b><br>The objective that failed and the bill.</td>
</tr>
</table>

And beyond the canvas:

- **Calibrate** a design from Prometheus metrics ([calibration](docs/calibration.md)).

## Quick start

You need Docker with Compose, nothing else.

\`\`\`bash
git clone https://github.com/dante0747/archsim.git
docker compose up
\`\`\`

Open <http://localhost:3000> and press **Simulate**. [Getting started](docs/getting-started.md) covers more.

### Command line

\`\`\`bash
archsim run examples/cache-aside-starter.json
\`\`\`

## How it works

\`\`\`mermaid
flowchart LR
  web --> api
\`\`\`

A design is one versioned JSON document. More in [docs/architecture.md](docs/architecture.md).

## How accurate is it?

Measured, not claimed. In four experiments, ArchSim was calibrated at low load and predicted where each would break:

| Experiments | Median error |
| --- | --- |
| **2 held out** | −5% to +5% |

These are three runs per experiment with engine 9.9.9. It ties plain arithmetic (median error 16.5% against 16.2%). [docs/validation.md](docs/validation.md) shows every run.

> **Results are estimates.** Trust comparisons
> more than absolute values. What is modeled: [docs/simulation-model.md](docs/simulation-model.md).

## Contributing

Pull requests wait for the [Contributor License Agreement](CLA.md).

## License

ArchSim is licensed under the AGPL. To ask for a commercial license, write to [someone@example.com](mailto:someone@example.com).
`;

const ctx = { from: "README.md", isFolder: () => false };

describe("paragraphContent", () => {
  it("gives a rendered paragraph's content, to show inside a paragraph of the page's own", () => {
    expect(paragraphContent("<p>Open it and press <strong>Simulate</strong>.</p>\n", "Quick start")).toBe("Open it and press <strong>Simulate</strong>.");
  });

  it("fails the build for more than one paragraph, which inside a <p> would break the page", () => {
    expect(() => paragraphContent("<p>One.</p>\n<blockquote>\n<p>Two.</p>\n</blockquote>", "How accurate is it?")).toThrow(
      /README.md's "How accurate is it\?" section is more than one paragraph where the home page shows one/,
    );
  });
});

describe("readmeContent", () => {
  it("reads the feature grid: each screenshot, served by the site, with its alt text, title and caption", () => {
    expect(readmeContent(readme, ctx).features).toEqual([
      { image: "/docs-assets/images/build.jpg", alt: "Design workspace: the Black Friday diagram", title: "Design with real technologies", caption: "Pick PostgreSQL, Kafka or a Go service." },
      { image: "/docs-assets/images/scorecard.jpg", alt: "Results Answer tab", title: "Get an answer to test", caption: "The objective that failed and the bill." },
    ]);
  });

  it("reads the quick start: what you need, the commands, and the first step after them", () => {
    const { quickStart } = readmeContent(readme, ctx);

    expect(quickStart.needs).toBe("You need Docker with Compose, nothing else.");
    expect(quickStart.commands).toBe("git clone https://github.com/dante0747/archsim.git\ndocker compose up");
    expect(quickStart.next).toBe('Open <a href="http://localhost:3000">http://localhost:3000</a> and press <strong>Simulate</strong>.');
  });

  it("reads how accurate ArchSim is in the README's words: the experiments, their table, what qualifies them and the caveat", () => {
    const { accuracy } = readmeContent(readme, ctx);

    expect(accuracy.intro).toBe("Measured, not claimed. In four experiments, ArchSim was calibrated at low load and predicted where each would break:");
    expect(accuracy.table).toContain("<table>");
    expect(accuracy.table).toContain("<strong>2 held out</strong>");
    expect(accuracy.notes).toBe("These are three runs per experiment with engine 9.9.9. It ties plain arithmetic (median error 16.5% against 16.2%).");
    expect(accuracy.caveat).toBe("<strong>Results are estimates.</strong> Trust comparisons\nmore than absolute values.");
  });


  it("finds who to ask for a commercial license", () => {
    expect(readmeContent(readme, ctx).commercialContact).toBe("mailto:someone@example.com");
  });

  it("names the section it cannot find, so a README change cannot quietly empty the home page", () => {
    expect(() => readmeContent(readme.replace("## Quick start", "## Install"), ctx)).toThrow(/README.md has no "Quick start" section/);
  });
});

describe("the repository's README", () => {
  // vitest runs in apps/web; the README is two levels up
  const source = readFileSync(resolve(process.cwd(), "../../README.md"), "utf8");

  it("presents ArchSim as an experimentation tool whose results are hypotheses to test, not a yes or no or a gate", () => {
    expect(source).toContain("An architecture experimentation tool for distributed systems.");
    expect(source).toContain("- **Hypotheses to test.**");
    expect(source).not.toMatch(/straight (yes or no|answer)|yes or no against|gate pull requests/i);
  });

  it("gives the home page a caveat that says what results are not, and that the figures are calibrated models'", () => {
    const { accuracy } = readmeContent(source, ctx);
    const words = (html: string) => html.replace(/\s+/g, " ");

    expect(words(accuracy.caveat)).toMatch(/^<strong>Results are hypotheses to test, not production guarantees\.<\/strong>/);
    expect(words(accuracy.caveat)).toContain("not as sign-off, audit evidence or a deployment gate");
    expect(words(accuracy.notes)).toContain("These figures are for models calibrated on the stack they predict");
  });
});

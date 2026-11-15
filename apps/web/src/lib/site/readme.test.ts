import { describe, expect, it } from "vitest";
import { readmeContent } from "./readme";

const readme = `<h1 align="center">ArchSim</h1>

## Why ArchSim

- **Real technologies, real behaviour.** PostgreSQL locks rows and waits for its write-ahead log.
- **A straight answer.** Yes or no against your objectives.

## Features

<table>
<tr>
<td width="50%" valign="top"><img src="docs/images/build.jpg" alt="Design workspace: the Black Friday diagram" width="380"><br><b>Design with real technologies</b><br>Pick PostgreSQL, Kafka or a Go service.</td>
<td width="50%" valign="top"><img src="docs/images/scorecard.jpg" alt="Results Answer tab" width="380"><br><b>Get a straight answer</b><br>The verdict and the bill.</td>
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

## Contributing

Pull requests wait for the [Contributor License Agreement](CLA.md).

## License

ArchSim is licensed under the AGPL. To ask for a commercial license, write to [someone@example.com](mailto:someone@example.com).
`;

const ctx = { from: "README.md", isFolder: () => false };

describe("readmeContent", () => {
  it("reads why ArchSim, title and text of each reason", () => {
    expect(readmeContent(readme, ctx).why).toEqual([
      { title: "Real technologies, real behaviour", text: "PostgreSQL locks rows and waits for its write-ahead log." },
      { title: "A straight answer", text: "Yes or no against your objectives." },
    ]);
  });

  it("reads the feature grid: each screenshot, served by the site, with its alt text, title and caption", () => {
    expect(readmeContent(readme, ctx).features).toEqual([
      { image: "/docs-assets/images/build.jpg", alt: "Design workspace: the Black Friday diagram", title: "Design with real technologies", caption: "Pick PostgreSQL, Kafka or a Go service." },
      { image: "/docs-assets/images/scorecard.jpg", alt: "Results Answer tab", title: "Get a straight answer", caption: "The verdict and the bill." },
    ]);
  });

  it("renders what lies beyond the canvas, its links pointing at the site's docs", () => {
    const { beyond } = readmeContent(readme, ctx);

    expect(beyond).toContain("<strong>Calibrate</strong>");
    expect(beyond).toContain('href="/docs/calibration/"');
  });

  it("reads the quick start: what you need, the commands, and what comes next", () => {
    const { quickStart } = readmeContent(readme, ctx);

    expect(quickStart.needs).toBe("You need Docker with Compose, nothing else.");
    expect(quickStart.commands).toBe("git clone https://github.com/dante0747/archsim.git\ndocker compose up");
    expect(quickStart.next).toContain('href="http://localhost:3000"');
    expect(quickStart.next).toContain('href="/docs/getting-started/"');
  });

  it("renders how it works without the diagram, and finds who to ask for a commercial license", () => {
    const content = readmeContent(readme, ctx);

    expect(content.howItWorks).toContain("A design is one versioned JSON document.");
    expect(content.howItWorks).not.toContain("flowchart");
    expect(content.commercialContact).toBe("mailto:someone@example.com");
  });

  it("renders the license and contributing sections as they are", () => {
    const content = readmeContent(readme, ctx);

    expect(content.license).toContain("ArchSim is licensed under the AGPL.");
    expect(content.contributing).toContain('href="https://github.com/dante0747/archsim/blob/main/CLA.md"');
  });

  it("names the section it cannot find, so a README change cannot quietly empty the home page", () => {
    expect(() => readmeContent(readme.replace("## Why ArchSim", "## Reasons"), ctx)).toThrow(/README.md has no "Why ArchSim" section/);
  });
});

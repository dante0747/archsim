import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { accuracy } from "@/lib/accuracy";
import { accuracyHighlights } from "@/lib/site/accuracy-highlights";
import type { ReadmeContent } from "@/lib/site/readme";
import { SiteHome, type SiteHomeProps } from "./SiteHome";

const readme: ReadmeContent = {
  why: [
    { title: "Real technologies, real behaviour", text: "PostgreSQL locks rows." },
    { title: "A straight answer", text: "Yes or no." },
  ],
  features: [{ image: "/docs-assets/images/build.jpg", alt: "Design workspace with a PostgreSQL database selected", title: "Design with real technologies", caption: "Pick PostgreSQL." }],
  beyond: "<ul><li><strong>Calibrate</strong> a design</li></ul>",
  quickStart: { needs: "You need Docker with Compose, nothing else.", commands: "git clone https://github.com/dante0747/archsim.git\ncd archsim\ndocker compose up", next: "<p>Open http://localhost:3000.</p>" },
  howItWorks: "<p>A design is one versioned JSON document.</p>",
  commercialContact: "mailto:someone@example.com",
  license: "<p>ArchSim is licensed under the AGPL-3.0-only.</p>",
  contributing: "<p>Bug reports are welcome.</p>",
};

const props: SiteHomeProps = {
  readme,
  accuracy: accuracyHighlights(accuracy),
  chips: [{ key: "database:postgresql", name: "PostgreSQL", technology: "postgresql", kind: "database" }],
  stats: { technologies: 40, tiers: "12 core, 28 experimental", clientKinds: 4, templates: 25, scenarios: 125 },
  version: "1.0.0",
  demoUrl: null,
};

describe("SiteHome", () => {
  beforeEach(() => {
    vi.stubEnv("__NEXT_TRAILING_SLASH", "true");
    // reduced motion: the replay draws one still frame and sections show at once
    vi.stubGlobal("matchMedia", (query: string) => ({ matches: query.includes("reduce"), addEventListener() {}, removeEventListener() {} }));
    const Observer = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
    vi.stubGlobal("IntersectionObserver", Observer);
    vi.stubGlobal("ResizeObserver", Observer);
  });
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("leads with the promise and the quick start, from the README", () => {
    render(<SiteHome {...props} />);

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Crash-test your architecture before your users do.");
    const quick = screen.getByRole("region", { name: /quick start|up and running/i });
    expect(within(quick).getByText("You need Docker with Compose, nothing else.")).toBeInTheDocument();
    expect(quick.querySelector("pre")?.textContent).toBe(readme.quickStart.commands);
    expect(within(quick).getByRole("button", { name: /copy/i })).toBeInTheDocument();
  });

  it("shows why ArchSim and every screenshot of the README with its alt text", () => {
    render(<SiteHome {...props} />);

    for (const why of readme.why) expect(screen.getByRole("heading", { level: 3, name: why.title })).toBeInTheDocument();
    const shot = screen.getByRole("img", { name: readme.features[0].alt });
    expect(shot).toHaveAttribute("src", readme.features[0].image);
  });

  it("is honest about accuracy: the held-out misses come first, with a link to every run", () => {
    render(<SiteHome {...props} />);

    const section = screen.getByRole("region", { name: /^Measured, not claimed/ });
    const h = props.accuracy.heldOut;
    if (h) {
      expect(section).toHaveTextContent(`${h.within} of ${h.total}`);
      if (h.overRange) expect(section).toHaveTextContent(h.overRange);
    }
    expect(within(section).getByRole("link", { name: /every run|accuracy/i })).toHaveAttribute("href", "/accuracy/");
  });

  it("describes the experiments as the accuracy page does: small synthetic services, on one machine it names", () => {
    render(<SiteHome {...props} />);

    const section = screen.getByRole("region", { name: /^Measured, not claimed/ });
    expect(section).toHaveTextContent("small synthetic services on real PostgreSQL, Redis and Kafka");
    expect(section).toHaveTextContent(`on ${props.accuracy.machine}`);
    expect(section.textContent).not.toMatch(/on real services/);
  });

  it("says running it on your own machine needs no account, as a fact about running it yourself", () => {
    render(<SiteHome {...props} demoUrl="https://app.archsim.io/" />);

    expect(screen.getByText("Open source · run it on your own machine: no account, no telemetry")).toBeInTheDocument();
    expect(screen.getByText("when you run it yourself: no account")).toBeInTheDocument();
  });

  it("states the license and how to ask for a commercial one", () => {
    render(<SiteHome {...props} />);

    expect(screen.getByText("ArchSim is licensed under the AGPL-3.0-only.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /commercial license/i })).toHaveAttribute("href", "mailto:someone@example.com");
  });

  it("offers Try it live only when there is a demo", () => {
    const { rerender } = render(<SiteHome {...props} />);
    expect(screen.queryByRole("link", { name: /try it live/i })).toBeNull();

    rerender(<SiteHome {...props} demoUrl="https://app.archsim.io/" />);
    expect(screen.getAllByRole("link", { name: /try it live/i })[0]).toHaveAttribute("href", "https://app.archsim.io/");
  });
});

import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PRODUCT_VIEWS, productViews, renditions } from "@/lib/site/product-views";
import type { Feature } from "@/lib/site/readme";
import { SiteHome, type SiteHomeProps } from "./SiteHome";

const feature = (name: string, title: string, alt: string): Feature => ({ image: `/docs-assets/images/${name}.jpg`, alt, title, caption: `${title}, in a sentence.` });

const features: Feature[] = [
  feature("build", "Design with real technologies", "Design workspace with a PostgreSQL database selected"),
  feature("editor-results", "Test with traffic and failures", "Test workspace mid-ramp, beside a test plan that did not hold up"),
  feature("scorecard", "Get an answer to test", "Results Answer tab: No, it doesn't hold up"),
  feature("tradeoffs", "Compare the options", "Results Compare tab: monthly cost against p95 latency for every run"),
];

const props: SiteHomeProps = {
  views: productViews(
    features,
    Object.keys(PRODUCT_VIEWS).flatMap((name) => renditions(name).map((r) => `docs/${r.path}`)),
  ),
  quickStart: {
    needs: "You need Docker with Compose, nothing else.",
    commands: "git clone https://github.com/dante0747/archsim.git\ncd archsim\ndocker compose up",
    next: 'Open <a href="http://localhost:3000">http://localhost:3000</a> and press <strong>Simulate</strong>.',
  },
  accuracy: {
    intro: "Measured, not claimed. In 14 experiments, ArchSim was calibrated at low load, predicted where each would break, and only then was the breaking point measured with k6:",
    table: "<table><thead><tr><th>Experiments</th><th>Median error</th></tr></thead><tbody><tr><td><strong>5 held out</strong></td><td>−25% to +15%</td></tr></tbody></table>",
    notes: "These are three runs per experiment with engine 1.6.0. On a typical held-out run ArchSim is level with plain bottleneck arithmetic on the same calibration (median error 16.5% against 16.2%).",
    caveat: "<strong>Results are hypotheses to test, not production guarantees.</strong> Every number comes from the model you drew.",
  },
  releaseNotes: "/changelog/#130---2026-11-17",
  technologies: {
    featured: [
      { key: "database:postgresql", name: "PostgreSQL", logo: { path: "M0 0h24v24H0z", color: "#4169E1" } },
      { key: "cache:redis", name: "Redis", logo: { path: "M0 0h24v24H0z", color: "#FF4438" } },
    ],
    count: 44,
    tiers: "13 core, 25 experimental, 6 third-party APIs",
  },
  version: "1.3.0",
  demoUrl: null,
};

const hero = () => screen.getByRole("region", { name: /Crash-test your architecture/ });

describe("SiteHome", () => {
  beforeEach(() => {
    vi.stubEnv("__NEXT_TRAILING_SLASH", "true");
    vi.stubGlobal("matchMedia", (query: string) => ({ matches: query.includes("reduce"), addEventListener() {}, removeEventListener() {} }));
  });
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("says what ArchSim does and offers one primary action, with GitHub beside it", () => {
    render(<SiteHome {...props} />);

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Crash-test your architecture before your users do.");
    expect(hero()).toHaveTextContent("Simulate distributed systems, uncover bottlenecks, and compare architectural decisions before writing production code.");
    const actions = within(hero()).getAllByRole("link").filter((a) => a.dataset.cta);
    expect(actions.map((a) => [a.textContent, a.dataset.cta])).toEqual([
      ["Try ArchSim", "primary"],
      ["View on GitHub", "secondary"],
    ]);
  });

  it("sends Try ArchSim to the hosted demo when the build names one, and to the quick start otherwise", () => {
    const { rerender } = render(<SiteHome {...props} />);
    expect(within(hero()).getByRole("link", { name: "Try ArchSim" })).toHaveAttribute("href", "#quick-start");

    rerender(<SiteHome {...props} demoUrl="https://app.archsim.io/" />);
    expect(within(hero()).getByRole("link", { name: "Try ArchSim" })).toHaveAttribute("href", "https://app.archsim.io/");
  });

  it("qualifies no account and no telemetry as true when self-hosted", () => {
    render(<SiteHome {...props} demoUrl="https://app.archsim.io/" />);

    expect(hero()).toHaveTextContent("No account, no telemetry when self-hosted");
    expect(hero()).toHaveTextContent("Open source (AGPL-3.0)");
  });

  it("names the release, linking its notes, without competing with the headline", () => {
    render(<SiteHome {...props} />);

    expect(within(hero()).getByRole("link", { name: "v1.3.0 release notes" })).toHaveAttribute("href", "/changelog/#130---2026-11-17");
  });

  it("shows a real screenshot first, its words from the README, cropped for a phone", () => {
    render(<SiteHome {...props} />);

    const shot = within(hero()).getByRole("img", { name: features[1].alt });
    expect(shot).toHaveAttribute("src", props.views.hero.full.src);
    expect(shot).not.toHaveAttribute("loading", "lazy");
    expect(shot.closest("picture")?.querySelector("source")).toHaveAttribute("srcset", props.views.hero.crop?.srcSet);
  });

  it("tells one story, problem to what to test, then how to run it", () => {
    render(<SiteHome {...props} />);

    expect(screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent)).toEqual([
      "Architecture diagrams show what you intend. Simulation shows what might happen.",
      "Sketch it. Shake it. Learn what to test.",
      "Answer the questions every design review asks.",
      "Real screens from a real run.",
      "Model the stack you actually run.",
      "Transparent by design.",
      "Run ArchSim locally.",
      "Challenge your assumptions before you build.",
    ]);
  });

  it("presents what a run finds as hypotheses to test, never a verdict or a gate", () => {
    render(<SiteHome {...props} />);

    expect(hero()).toHaveTextContent("Open-source architecture experimentation tool");
    expect(screen.getByRole("region", { name: "Sketch it. Shake it. Learn what to test." })).toHaveTextContent("as hypotheses to confirm with a load test");
    expect(screen.getByRole("region", { name: "Run ArchSim locally." })).toHaveTextContent("a warning to read, not a gate");
    expect(screen.getByRole("main").textContent).not.toMatch(/yes or no|straight answer|ship the one|CI gate|fail a pull request/i);
  });

  it("walks through three steps and six capabilities, each said once", () => {
    render(<SiteHome {...props} />);

    const steps = screen.getByRole("region", { name: "Sketch it. Shake it. Learn what to test." });
    expect(within(steps).getAllByRole("listitem").map((s) => within(s).getByRole("heading").textContent)).toEqual(["Design", "Simulate", "Learn"]);
    const capabilities = screen.getByRole("region", { name: "Answer the questions every design review asks." });
    expect(within(capabilities).getAllByRole("heading", { level: 3 })).toHaveLength(6);
  });

  it("shows three product views in tabs, switched with the arrow keys", async () => {
    const user = userEvent.setup();
    render(<SiteHome {...props} />);

    const tabs = screen.getAllByRole("tab");
    expect(tabs.map((t) => t.textContent)).toEqual(["Design", "Results", "Compare"]);
    expect(tabs[0]).toHaveAttribute("aria-selected", "true");
    await user.click(tabs[0]);
    await user.keyboard("{ArrowRight}");
    expect(tabs[1]).toHaveAttribute("aria-selected", "true");
    expect(tabs[1]).toHaveFocus();
    const panel = screen.getByRole("tabpanel", { name: "Results" });
    expect(within(panel).getByRole("img", { name: features[2].alt })).toHaveAttribute("loading", "lazy");
    // a phone shows the three as a plain list: no panel without its tab
    expect(screen.getAllByRole("tabpanel")).toHaveLength(1);
  });

  it("features a few core technologies and links to every profile with its tier", () => {
    render(<SiteHome {...props} />);

    const section = screen.getByRole("region", { name: "Model the stack you actually run." });
    expect(within(section).getAllByRole("listitem").map((li) => li.textContent)).toEqual(["PostgreSQL", "Redis"]);
    expect(section).toHaveTextContent("44 technology profiles: 13 core, 25 experimental, 6 third-party APIs");
    expect(section).toHaveTextContent("No technology is validated against measurements yet");
    expect(within(section).getByRole("link", { name: /every technology profile/i })).toHaveAttribute("href", "/docs/assumptions/#technology-tiers");
  });

  it("says how accurate ArchSim is in the README's words, the comparison with plain arithmetic and the caveat included", () => {
    render(<SiteHome {...props} />);

    const section = screen.getByRole("region", { name: "Transparent by design." });
    expect(section).toHaveTextContent("ArchSim was calibrated at low load, predicted where each would break");
    expect(within(section).getByRole("table")).toHaveTextContent("5 held out");
    expect(section).toHaveTextContent("level with plain bottleneck arithmetic on the same calibration (median error 16.5% against 16.2%)");
    expect(section).toHaveTextContent("Results are hypotheses to test, not production guarantees.");
    expect(within(section).getByRole("link", { name: /accuracy report/i })).toHaveAttribute("href", "/accuracy/");
  });

  it("claims what the docs claim: prices are AWS on-demand list prices, and the formulas sit beside what was simulated", () => {
    render(<SiteHome {...props} />);

    const capabilities = screen.getByRole("region", { name: "Answer the questions every design review asks." });
    expect(capabilities).toHaveTextContent("AWS on-demand list prices");
    expect(capabilities).not.toHaveTextContent("Every number shows its formula");
  });

  it("runs locally with the README's commands, copyable, and points to the other ways in", () => {
    render(<SiteHome {...props} />);

    const quick = screen.getByRole("region", { name: "Run ArchSim locally." });
    expect(quick).toHaveAttribute("id", "quick-start");
    expect(within(quick).getByText(props.quickStart.needs)).toBeInTheDocument();
    expect(quick.querySelector("pre")?.textContent).toBe(props.quickStart.commands);
    expect(within(quick).getByRole("button", { name: /copy/i })).toBeInTheDocument();
    expect(quick).toHaveTextContent("Open http://localhost:3000 and press Simulate.");
    for (const name of ["Command line", "MCP server", "CI check", "Import and export"]) expect(within(quick).getByRole("link", { name })).toBeInTheDocument();
  });

  it("ends with one call to action", () => {
    render(<SiteHome {...props} demoUrl="https://app.archsim.io/" />);

    const last = screen.getByRole("region", { name: "Challenge your assumptions before you build." });
    expect(
      within(last)
        .getAllByRole("link")
        .map((a) => [a.textContent, a.dataset.cta]),
    ).toEqual([
      ["Try ArchSim", "primary"],
      ["View on GitHub", "secondary"],
    ]);
  });

  it("keeps the sections other pages link to", () => {
    render(<SiteHome {...props} />);

    for (const id of ["why", "how", "features", "tour", "technologies", "accuracy", "quick-start"]) expect(document.getElementById(id), id).not.toBeNull();
  });
});

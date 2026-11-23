import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AccuracyPage } from "@/components/accuracy/AccuracyPage";
import {
  BENCHMARK_WORKFLOW,
  CALIBRATION_DOC,
  JAVA_PARITY_DOC,
  VALIDATION_DOC,
  accuracy,
  cpuSentence,
  kneeSentence,
  measuredOn,
  minus,
  spreadSentence,
  topStepText,
} from "@/lib/accuracy";

// Expectations come from the data, so this keeps passing when the benchmarks rerun.
describe("AccuracyPage", () => {
  it("shows good and bad results, the spread, every run and the calibration steps", () => {
    const { container } = render(<AccuracyPage data={accuracy} />);
    const text = container.textContent ?? "";
    expect(text).not.toMatch(/NaN|undefined|null|Infinity|- ms/);

    expect(screen.getByRole("heading", { level: 1, name: "How accurate is ArchSim?" })).toBeInTheDocument();
    expect(text).toContain(minus(accuracy.summary.kneeErrorRangeText));
    expect(screen.getByText("Knees move between runs")).toBeInTheDocument();

    for (const x of accuracy.experiments) {
      expect(text).toContain(kneeSentence(x)); // the short version, good and bad alike
      if (x.spread) expect(text).toContain(spreadSentence(x));
      const card = container.querySelector(`article#${x.id}`) as HTMLElement | null;
      expect(card, x.id).not.toBeNull();
      expect(within(card as HTMLElement).getByText(x.name)).toBeInTheDocument();
      expect(card?.textContent).toContain(x.environment.text);
      // a run measured with a since-fixed flaw of the benchmark says so on its card
      if (x.caveat) expect(within(card as HTMLElement).getByRole("note")).toHaveTextContent("Measured with a benchmark flaw.");
      if (x.knee.measured.kneeRps !== null) {
        expect(within(card as HTMLElement).getByRole("img", { name: new RegExp(`Measured knee ${x.knee.text.measured} req/s`) })).toBeInTheDocument();
      }
    }

    // the held-out experiments lead: first in the short version, counted in the headline, and
    // every card says whether the engine that predicted it was tuned on it
    const held = accuracy.experiments.filter((x) => x.sample === "held-out");
    const tuned = accuracy.experiments.filter((x) => x.sample === "tuned-on");
    if (held.length) {
      expect(screen.getByText(`held-out knees predicted within ±${Math.round(accuracy.thresholds.kneeError * 100)}%, latest run of each`)).toBeInTheDocument();
      const at = (x: (typeof held)[number]) => text.indexOf(kneeSentence(x));
      expect(Math.max(...held.map(at))).toBeLessThan(Math.min(...tuned.map(at)));
    }
    for (const x of accuracy.experiments) {
      const card = container.querySelector(`article#${x.id}`) as HTMLElement;
      expect(within(card).getByText(x.sample === "held-out" ? "Held out" : "Tuned on")).toBeInTheDocument();
    }

    // CPU and memory: one card per experiment that recorded them, every part with its numbers
    const withResources = accuracy.experiments.filter((x) => x.resources);
    expect(screen.getByRole("heading", { level: 2, name: "CPU and memory" })).toBeInTheDocument();
    const cpu = cpuSentence(accuracy.experiments);
    if (cpu) expect(text).toContain(cpu);
    for (const x of withResources) {
      const r = x.resources;
      if (!r) continue;
      const card = container.querySelector(`article[aria-labelledby="${x.id}-resources"]`) as HTMLElement | null;
      expect(card, x.id).not.toBeNull();
      const top = topStepText(r);
      if (top) expect(card?.textContent).toContain(`Busy cores at ${top} req/s`);
      for (const p of r.parts) {
        const row = within(card as HTMLElement).getByRole("rowheader", { name: p.name }).closest("tr") as HTMLElement;
        if (p.top) expect(row.textContent).toContain(p.top.text.measuredCores);
        expect(row.textContent).toContain(p.modelled.cores ? (p.top?.text.predictedCores ?? "-") : "not modelled");
      }
    }

    const runs = Object.values(accuracy.history).flat();
    // each card's steps + each history table + each resources card's summary and steps
    expect(screen.getAllByRole("table").length).toBe(accuracy.experiments.length * 2 + withResources.length * 2);
    expect(text).toContain(`${runs.length} runs `);
    for (const r of runs) expect(text).toContain(r.machine);
    expect(screen.getByRole("heading", { name: "How to calibrate your own design" })).toBeInTheDocument();
    // the repository is public: each file the page names links to it on GitHub, at main
    for (const doc of [VALIDATION_DOC, CALIBRATION_DOC]) {
      const links = screen.getAllByRole("link", { name: doc });
      expect(links.length, doc).toBeGreaterThan(0);
      for (const a of links) expect(a).toHaveAttribute("href", `https://github.com/dante0747/archsim/blob/main/${doc}`);
    }
    expect(screen.getAllByRole("link", { name: "benchmarks/results/" })[0]).toHaveAttribute("href", "https://github.com/dante0747/archsim/tree/main/benchmarks/results/");
    // and nothing else leaves the site
    const hrefs = [...container.querySelectorAll("a[href]")].map((a) => a.getAttribute("href") ?? "");
    expect(hrefs.filter((h) => !h.startsWith("/") && !h.startsWith("#") && !h.startsWith("https://github.com/dante0747/archsim/"))).toEqual([]);
  });

  it("gives way to the page's own header and footer when it is given them", () => {
    render(<AccuracyPage data={accuracy} header={<header>Site header</header>} footer={<footer>Site footer</footer>} />);

    expect(screen.getByRole("banner")).toHaveTextContent("Site header");
    expect(screen.getByRole("contentinfo")).toHaveTextContent("Site footer");
    expect(screen.queryByRole("link", { name: "Templates" })).toBeNull();
  });

  it("says once, under how to read the numbers, what the Java engine reproduces, re-simulated", () => {
    const java = accuracy.javaEngine;
    expect(java).toBeTruthy();
    const { container } = render(<AccuracyPage data={accuracy} />);

    const read = container.querySelector("section#read") as HTMLElement;
    const entry = within(read).getByText("Java engine").closest("div") as HTMLElement;
    expect(entry.textContent).toContain(java?.text);
    expect(within(entry).getByRole("link", { name: JAVA_PARITY_DOC })).toHaveAttribute("href", `https://github.com/dante0747/archsim/blob/main/${JAVA_PARITY_DOC}`);
    expect(container.textContent?.split(java?.text ?? "").length).toBe(2);
  });

  it("says nothing of the Java engine before it has re-simulated", () => {
    const { container } = render(<AccuracyPage data={{ ...accuracy, javaEngine: null }} />);

    expect(within(container).queryByText("Java engine")).toBeNull();
  });

  it("says near the top where every run was measured", () => {
    const { container } = render(<AccuracyPage data={accuracy} />);

    const hero = container.querySelector("main > section") as HTMLElement;
    expect(hero).toHaveTextContent(measuredOn(accuracy).sentence);
    expect(hero.textContent).toMatch(/small synthetic services/);
  });

  it("says the benchmarks rerun by hand while the workflow's schedule is off", () => {
    // vitest runs in apps/web: the workflow is two levels up
    const workflow = readFileSync(resolve(process.cwd(), "../..", BENCHMARK_WORKFLOW), "utf8");
    const scheduled = /^\s+schedule:/m.test(workflow);
    const { container } = render(<AccuracyPage data={accuracy} />);

    const history = container.querySelector("section#history") as HTMLElement;
    expect(scheduled).toBe(false);
    expect(history.textContent).not.toMatch(/every week|every release/);
    expect(history.textContent).toMatch(/started by hand/);
  });
});

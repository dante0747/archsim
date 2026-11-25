import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import landing from "@/data/landing-run.json";
import { formatUsd } from "@/lib/cost";
import { HeroDemo } from "./HeroDemo";
import { HowItWorks } from "./HowItWorks";

// The landing page's figures are a real run's: archsim-engine's GeneratedFileTests holds
// src/data/landing-run.json to the engine's output today. These tests hold the page to the file,
// and the file to the template it ran (vitest runs in apps/web; examples/ is two levels up).
const template = JSON.parse(readFileSync(resolve(process.cwd(), "../../examples", `${landing.template}.json`), "utf8")) as {
  objectives: typeof landing.objectives;
  scenarios: { id: string }[];
};

describe("the landing page's figures", () => {
  beforeEach(() => {
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
    vi.unstubAllGlobals();
  });

  it("come from a scenario of the template the hero shows, held to its objectives", () => {
    expect(landing.template).toBe("black-friday-kubernetes");
    expect(template.scenarios.map((s) => s.id)).toContain(landing.scenario);
    expect(landing.objectives).toEqual(template.objectives);
  });

  it("show the run's throughput, p95 and monthly bill in Learn", () => {
    render(<HowItWorks />);

    expect(screen.getByText(`${Math.round(landing.throughputRps)}/s`)).toBeInTheDocument();
    expect(screen.getByText(`${Math.round(landing.p95Ms)} ms`)).toBeInTheDocument();
    // the app's money is dollars (formatUsd), as the run's is
    expect(landing.currency).toBe("USD");
    expect(screen.getByText(formatUsd(landing.monthlyCost))).toBeInTheDocument();
    expect(screen.getByText(`per month · ${landing.provider.toUpperCase()}`)).toBeInTheDocument();
  });

  it("say yes because the engine found the run meets the template's objectives", () => {
    render(<HowItWorks />);

    expect(landing.meetsObjectives).toBe(true);
    expect(screen.getByText("Yes, it holds up.")).toBeInTheDocument();
  });

  it("give the hero's replay the template's p95 objective as its goal", () => {
    render(<HeroDemo />);

    expect(screen.getByText(`p95 · goal ${template.objectives.latencyP95Ms}`)).toBeInTheDocument();
  });
});

import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { Run } from "@/lib/types";
import { useRuns } from "@/store/runs";
import { RunPicker, runOptionText } from "./shared";

const run = (id: string, patch: Partial<Run> = {}) =>
  ({ id, label: "Current design", scenarioName: null, seed: 42, mode: "full", createdAt: new Date(Date.now() - 43_000).toISOString(), ...patch }) as Run;

describe("a run as a run picker lists it", () => {
  it("tells two runs of the same design apart by their short ids", () => {
    const a = runOptionText(run("3f9a2c1d00000000000000000000aaaa"));
    const b = runOptionText(run("77b0e41200000000000000000000bbbb"));

    expect(a).toMatch(/^Current design · run 3f9a2c1d · seed 42 · /);
    expect(b).toMatch(/^Current design · run 77b0e412 · seed 42 · /);
  });

  it("names a scenario's run by its scenario, and a quick check as estimated", () => {
    expect(runOptionText(run("aaaa1111bbbb", { label: null, scenarioName: "1 API replica", mode: "sampled" }))).toMatch(
      /^1 API replica · run aaaa1111 · seed 42 · estimated · /,
    );
  });
});

describe("the run picker", () => {
  it("is named Run and keeps the whole label of the run it shows in a tooltip", () => {
    const shown = run("3f9a2c1d00000000000000000000aaaa", { status: "succeeded", label: "Black Friday at five times the traffic" });
    useRuns.setState({ runs: [run("77b0e41200000000000000000000bbbb", { status: "succeeded" }), shown] });

    render(<RunPicker value={shown.id} onChange={() => {}} />);

    expect(screen.getByRole("combobox", { name: "Run" })).toHaveAttribute("title", runOptionText(shown));
  });
});

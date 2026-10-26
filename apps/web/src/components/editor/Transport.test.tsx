import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TooltipProvider } from "@/components/ui/misc";
import { fixtureReport } from "@/test/fixtures/dashboard-report";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";
import { TransportButtons, TransportLcd } from "./Transport";

function show() {
  return render(
    <TooltipProvider>
      <TransportButtons />
      <TransportLcd />
    </TooltipProvider>,
  );
}

const lcd = () => screen.getByTestId("transport-lcd");

describe("Transport", () => {
  beforeEach(() => {
    useRuns.setState({ runs: [], reports: {}, activeRunId: null, followRunId: null });
    useUi.setState({ playhead: null, playing: false, mode: "build" });
    useEditor.setState({ objectives: { ...useEditor.getState().objectives, latencyP95Ms: 50 } });
  });
  afterEach(cleanup);

  it("waits for a first run", () => {
    show();
    expect(lcd()).toHaveAttribute("aria-label", "No run yet");
    expect(screen.getByTestId("transport-play")).toBeDisabled();
  });

  it("shows the run's summary when nothing is playing", () => {
    useRuns.setState({ reports: { r1: fixtureReport() }, activeRunId: "r1" });
    show();
    expect(lcd()).toHaveAttribute("aria-label", "Last run");
    expect(lcd()).toHaveTextContent("475");
    expect(lcd()).toHaveTextContent("p95 · goal 50");
    expect(lcd()).toHaveTextContent("95.0");
    // p95 55 ms is over the 50 ms goal
    expect(screen.getByText("55.0")).toHaveStyle({ color: "var(--status-critical)" });
  });

  it("follows the playhead while replaying, and falls back to the summary at the end", () => {
    useRuns.setState({ reports: { r1: fixtureReport() }, activeRunId: "r1" });
    show();
    act(() => useUi.setState({ mode: "stress", playhead: 10, playing: true }));
    expect(lcd()).toHaveAttribute("aria-label", "Replay at 00:10.0");
    expect(lcd()).toHaveTextContent("00:10.0");
    act(() => useUi.setState({ playhead: 40, playing: false }));
    expect(lcd()).toHaveAttribute("aria-label", "Last run");
  });

  it("starts the replay in the Test workspace", () => {
    useRuns.setState({ reports: { r1: fixtureReport() }, activeRunId: "r1" });
    show();
    fireEvent.click(screen.getByTestId("transport-play"));
    expect(useUi.getState()).toMatchObject({ mode: "stress", playing: true, playhead: 0 });
    fireEvent.click(screen.getByTestId("transport-play"));
    expect(useUi.getState().playing).toBe(false);
  });
});

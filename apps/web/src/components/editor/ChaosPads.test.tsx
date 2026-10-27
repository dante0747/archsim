import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ArchNode } from "@/lib/document";
import { useEditor } from "@/store/editor";
import { ChaosPads } from "./ChaosPads";

const run = vi.fn();
vi.mock("@/components/editor/actions", () => ({ useEditorActions: () => ({ run }) }));

const node = (id: string, type: string, label: string) => ({ id, type: "arch", position: { x: 0, y: 0 }, data: { type, label, config: {} } }) as unknown as ArchNode;

describe("ChaosPads", () => {
  beforeEach(() => {
    run.mockClear();
    localStorage.clear();
    useEditor.setState({
      nodes: [node("cache", "cache", "Redis"), node("api", "service", "API")],
      edges: [],
      workload: { ...useEditor.getState().workload, faults: [] },
    });
  });
  afterEach(cleanup);

  it("offers one pad per experiment the design allows", () => {
    render(<ChaosPads />);
    expect(screen.getByRole("button", { name: /Lose Redis for 15 s/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Kill API for 10 s/ })).toBeInTheDocument();
  });

  it("plans the failure, simulates, and lights the pad", () => {
    render(<ChaosPads />);
    const pad = screen.getByRole("button", { name: /Lose Redis for 15 s/ });
    expect(pad).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(pad);
    expect(useEditor.getState().workload.faults).toEqual([expect.objectContaining({ kind: "outage", target: "cache" })]);
    expect(run).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: /Lose Redis for 15 s/ })).toHaveAttribute("aria-pressed", "true");
    // Clear removes the planned failure
    fireEvent.click(screen.getByRole("button", { name: /Clear/ }));
    expect(useEditor.getState().workload.faults).toEqual([]);
  });

  it("keeps the pad lit after a reload fills in the engine's defaults", () => {
    render(<ChaosPads />);
    fireEvent.click(screen.getByRole("button", { name: /Lose Redis for 15 s/ }));
    const [f] = useEditor.getState().workload.faults ?? [];
    // As the API stores it: every fault gets a factor, extra latency and a label.
    act(() => {
      useEditor.setState({ workload: { ...useEditor.getState().workload, faults: [{ ...f, factor: 4, extraMs: 200, label: null }] } });
    });
    expect(screen.getByRole("button", { name: /Lose Redis for 15 s/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /Kill API for 10 s/ })).toHaveAttribute("aria-pressed", "false");
  });

  it("folds into a small button and remembers it", () => {
    render(<ChaosPads />);
    fireEvent.click(screen.getByRole("button", { name: "Hide chaos pads" }));
    expect(screen.queryByRole("region", { name: "Chaos pads" })).toBeNull();
    expect(localStorage.getItem("archsim-chaos-open")).toBe("0");
    fireEvent.click(screen.getByRole("button", { name: "Show chaos pads" }));
    expect(screen.getByRole("region", { name: "Chaos pads" })).toBeInTheDocument();
  });
});

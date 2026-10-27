import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TooltipProvider } from "@/components/ui/misc";
import { finding, sharedFixture } from "@/test/fixtures/share";
import { FindingCard } from "./FindingCard";
import { ProvenanceBar } from "./ProvenanceBar";

describe("report pieces shared by Results and share pages", () => {
  afterEach(cleanup);

  it("show a finding's part on the canvas only where there is one", () => {
    const { rerender } = render(
      <ul>
        <FindingCard f={finding()} />
      </ul>,
    );
    expect(screen.getByText("Api is running hot")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Show on canvas" })).toBeNull();
    const onShowNode = vi.fn();
    rerender(
      <ul>
        <FindingCard f={finding()} onShowNode={onShowNode} />
      </ul>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Show on canvas" }));
    expect(onShowNode).toHaveBeenCalledWith("api");
  });

  it("offer Reproduce only in the editor", () => {
    const report = sharedFixture().report;
    const { rerender } = render(
      <TooltipProvider>
        <ProvenanceBar report={report} />
      </TooltipProvider>,
    );
    expect(screen.getByText("seed 42")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Reproduce/ })).toBeNull();
    const onReproduce = vi.fn();
    rerender(
      <TooltipProvider>
        <ProvenanceBar report={report} onReproduce={onReproduce} />
      </TooltipProvider>,
    );
    fireEvent.click(screen.getByRole("button", { name: /Reproduce/ }));
    expect(onReproduce).toHaveBeenCalled();
  });
});

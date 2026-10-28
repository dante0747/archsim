import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { type PricingSource, api } from "@/lib/api";
import type { Pricing } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { PricingCard } from "./PricingCard";

const sources: PricingSource[] = [
  { provider: "generic", region: null, date: null, illustrative: true },
  { provider: "aws", region: "us-east-1", date: "2026-11-01", illustrative: false, sha256: "b".repeat(64), newest: true },
  { provider: "aws", region: "us-east-1", date: "2026-10-01", illustrative: false, sha256: "a".repeat(64), newest: false },
];

function withPricing(pricing: Pricing | null) {
  useEditor.setState({ extras: { provenance: {}, calibration: null, pricing } });
}

async function shown() {
  render(<PricingCard />);
  const select = (await screen.findByRole("combobox")) as HTMLSelectElement;
  await screen.findAllByText(/prices of 2026-11-01/);
  return select;
}

const selectedText = (select: HTMLSelectElement) => select.options[select.selectedIndex].text;

describe("PricingCard", () => {
  beforeEach(() => {
    vi.spyOn(api, "pricing").mockResolvedValue({ sources });
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("lets a design turn burstable t4g types off, and keeps that choice", async () => {
    withPricing({ provider: "aws", region: "us-east-1", snapshotDate: "2026-11-01" });
    await shown();
    const box = screen.getByTestId("pricing-burstable") as HTMLInputElement;
    expect(box.checked).toBe(true); // auto, the default
    fireEvent.click(box);
    expect(useEditor.getState().extras?.pricing?.burstable).toBe("never");
    fireEvent.change(screen.getByRole("combobox"), { target: { value: `aws|us-east-1|2026-11-01|${"b".repeat(64)}` } });
    expect(useEditor.getState().extras?.pricing?.burstable).toBe("never");
    fireEvent.click(screen.getByTestId("pricing-burstable"));
    expect(useEditor.getState().extras?.pricing?.burstable).toBeUndefined();
  });

  it("choosing a snapshot pins its date", async () => {
    withPricing(null);
    const select = await shown();
    // older snapshots are not offered to a design that is not pinned to one
    expect(screen.queryByText(/2026-10-01/)).toBeNull();
    fireEvent.change(select, { target: { value: select.options[1].value } });
    expect(useEditor.getState().extras?.pricing).toMatchObject({ provider: "aws", region: "us-east-1", snapshotDate: "2026-11-01" });
  });

  it("a design naming only the provider uses the newest snapshot, as the engine does", async () => {
    withPricing({ provider: "aws", region: null });
    const select = await shown();
    expect(screen.queryByText(/no snapshot here/)).toBeNull();
    expect(selectedText(select)).toMatch(/aws · newest here \(prices of 2026-11-01\)/);
    expect(screen.getByText(/Not pinned/)).toBeTruthy();
  });

  it("a design pinned to an older snapshot keeps it", async () => {
    withPricing({ provider: "aws", region: "us-east-1", snapshotDate: "2026-10-01" });
    const select = await shown();
    expect(selectedText(select)).toMatch(/prices of 2026-10-01 \(older\)/);
  });

  it("a pinned snapshot this machine lacks is shown as missing, not swapped", async () => {
    withPricing({ provider: "aws", region: "us-east-1", snapshotDate: "2026-08-01" });
    const select = await shown();
    expect(selectedText(select)).toBe("aws us-east-1 of 2026-08-01 (no snapshot here)");
    expect(screen.getByText(/never swapped for another/)).toBeTruthy();
  });
});

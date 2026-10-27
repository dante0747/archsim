import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { viewReport } from "@/lib/share-view";
import { SLUG, sharedFixture } from "@/test/fixtures/share";
import { SharePage, type SharePageData } from "./SharePage";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const auth = vi.hoisted(() => ({ state: { status: "ready", config: { mode: "none", providers: [] }, me: null, workspaceId: null, workspace: null } as Record<string, unknown> }));
vi.mock("@/store/auth", () => ({ useAuthState: () => auth.state }));

const copy = vi.hoisted(() => vi.fn());
vi.mock("@/lib/share-api", async (importOriginal) => {
  const real = await importOriginal<typeof import("@/lib/share-api")>();
  return { ...real, shareApi: { ...real.shareApi, copy } };
});

class SizedObserver {
  constructor(private cb: ResizeObserverCallback) {}
  observe(el: Element) {
    Object.defineProperty(el, "clientWidth", { configurable: true, value: 360 });
    Object.defineProperty(el, "clientHeight", { configurable: true, value: 200 });
    this.cb([], this as unknown as ResizeObserver);
  }
  unobserve() {}
  disconnect() {}
}

function data(over: Parameters<typeof sharedFixture>[0] = {}): SharePageData {
  const { report, ...rest } = sharedFixture(over);
  return { ...rest, report: viewReport(report) };
}

describe("the share page", () => {
  beforeAll(() => {
    globalThis.ResizeObserver = SizedObserver as unknown as typeof ResizeObserver;
  });
  afterEach(cleanup);
  beforeEach(() => {
    push.mockReset();
    copy.mockReset();
    auth.state = { status: "ready", config: { mode: "none", providers: [] }, me: null, workspaceId: null, workspace: null };
  });

  it("leads with the verdict and its confidence, then numbers, objectives and findings", () => {
    render(<SharePage share={data()} />);
    expect(screen.getByTestId("share-verdict").textContent).toBe("Yes, it holds up.");
    expect(screen.getByText("Meets all 2 objectives at 500 req/s.")).toBeTruthy();
    expect(screen.getByTestId("confidence-badge").dataset.level).toBe("high");
    expect(screen.getByRole("link", { name: "measured accuracy" }).getAttribute("href")).toBe("/accuracy");

    const numbers = screen.getByRole("region", { name: "Key numbers" });
    expect(within(numbers).getByText("475 req/s")).toBeTruthy();
    expect(within(numbers).getByText("2/2")).toBeTruthy();

    const objectives = screen.getByRole("region", { name: "Objectives" });
    expect(within(objectives).getAllByText("Pass")).toHaveLength(2);

    const findings = screen.getByRole("region", { name: "What the simulation found" });
    expect(within(findings).getByText("Api is running hot")).toBeTruthy();
    expect(within(findings).queryByText("Healthy")).toBeNull(); // only when nothing else is found
    expect(screen.queryByRole("button", { name: "Show on canvas" })).toBeNull(); // no canvas here
    expect(screen.queryByRole("button", { name: /Reproduce/ })).toBeNull();
    expect(screen.getByRole("region", { name: "Latency over time" })).toBeTruthy();
    expect(screen.getByRole("meter", { name: "Error budget used" })).toBeTruthy();
    expect(screen.getByText(/the link works until Nov 19, 2026/)).toBeTruthy();
  });

  it("says what to fix first when objectives are missed", () => {
    render(<SharePage share={data({ objectives: { latencyP95Ms: 30, availability: null } })} />);
    expect(screen.getByTestId("share-verdict").textContent).toBe("No, it doesn’t hold up.");
    expect(screen.getByRole("region", { name: "Fix first" })).toBeTruthy();
    expect(screen.queryByRole("meter", { name: "Error budget used" })).toBeNull(); // no availability target
  });

  it("opens a copy in your workspace and goes to it", async () => {
    copy.mockResolvedValue({ id: "p9" });
    render(<SharePage share={data()} />);
    fireEvent.click(screen.getByTestId("open-copy"));
    await waitFor(() => expect(push).toHaveBeenCalledWith("/projects/p9"));
    expect(copy).toHaveBeenCalledWith(SLUG);
  });

  it("asks you to sign in first on a signed-in server, then comes back", () => {
    auth.state = { ...auth.state, status: "signed-out", config: { mode: "oauth", providers: ["github"] } };
    render(<SharePage share={data()} />);
    const link = screen.getByTestId("open-copy");
    expect(link.textContent).toContain("Sign in to open a copy");
    expect(link.getAttribute("href")).toBe(`/signin?next=${encodeURIComponent(`/r/${SLUG}`)}`);
  });

  it("offers no copy when the share holds no design", () => {
    render(<SharePage share={data({ canCopy: false })} />);
    expect(screen.queryByTestId("open-copy")).toBeNull();
  });
});

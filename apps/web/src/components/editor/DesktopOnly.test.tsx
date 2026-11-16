import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Me } from "@/lib/auth-types";

const auth = vi.hoisted(() => ({ me: null as Me | null }));
vi.mock("@/store/auth", () => ({ useAuthState: () => ({ status: "ready", config: null, me: auth.me, workspaceId: null, workspace: null }) }));
vi.mock("@/components/share/LatestResultsLink", () => ({ LatestResultsLink: () => null }));
const download = vi.hoisted(() => vi.fn());
vi.mock("@/components/editor/actions", () => ({ downloadDesign: download }));

import { useEditor } from "@/store/editor";
import { DesktopOnly } from "./DesktopOnly";

const person = (guest: boolean): Me => ({
  mode: "oauth",
  kind: "session",
  user: { id: "u", name: guest ? "Guest" : "Ada", email: null, avatarUrl: null, guest, expiresAt: guest ? new Date(Date.now() + 3600_000).toISOString() : null },
  workspaces: [],
  csrfToken: "t",
});

describe("the editor on a phone", () => {
  beforeEach(() => {
    download.mockReset();
    useEditor.setState({ metadata: { ...useEditor.getState().metadata, name: "Checkout" } });
  });

  it("offers the link to someone signed in, to open on a computer", () => {
    auth.me = person(false);

    render(<DesktopOnly projectId="p1" onOpenAnyway={() => {}} />);

    expect(screen.getByRole("button", { name: "Copy link" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Export JSON/ })).toBeNull();
  });

  it("offers a guest the design's JSON instead, as their sandbox opens in this browser only", () => {
    auth.me = person(true);

    render(<DesktopOnly projectId="p1" onOpenAnyway={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: "Export JSON" }));

    expect(screen.queryByRole("button", { name: "Copy link" })).toBeNull();
    expect(screen.getByText(/sandbox belongs to this browser/)).toBeInTheDocument();
    expect(download).toHaveBeenCalledOnce();
  });
});

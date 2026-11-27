import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Me, WorkspaceInfo } from "@/lib/auth-types";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }), usePathname: () => "/settings" }));
vi.mock("@/components/auth/UserMenu", () => ({ UserMenu: () => null, Avatar: () => null }));
vi.mock("@/components/auth/TokensSection", () => ({ TokensSection: () => null }));
const ada = { userId: "u1", name: "Ada", email: null, avatarUrl: null, role: "owner", joinedAt: "2026-11-27T00:00:00Z" };
vi.mock("@/lib/auth-api", () => ({ authApi: { members: () => Promise.resolve([ada]), invitations: () => Promise.resolve([]) } }));

const state: { me: Me | null; workspace: WorkspaceInfo | null } = { me: null, workspace: null };
vi.mock("@/store/auth", () => {
  const store = { signOut: vi.fn(), load: vi.fn(), switchWorkspace: vi.fn() };
  const useAuth = Object.assign((pick: (s: typeof store) => unknown) => pick(store), { getState: () => store });
  return { useAuth, useAuthState: () => ({ status: "ready", ...state, workspaceId: state.workspace?.id ?? null }) };
});

import { SettingsPage } from "./SettingsPage";

function signedIn(kind: WorkspaceInfo["kind"], guest: boolean) {
  const workspace: WorkspaceInfo = {
    id: "w1",
    name: guest ? "Sandbox" : "Team",
    kind,
    role: "owner",
    maxConcurrentRuns: null,
    maxRunsPerHour: null,
    createdAt: "2026-11-27T00:00:00Z",
  };
  state.workspace = workspace;
  state.me = {
    mode: "oauth",
    kind: "session",
    user: { id: "u1", name: "Ada", email: null, avatarUrl: null, guest, expiresAt: guest ? "2026-11-28T00:00:00Z" : null },
    workspaces: [workspace],
    csrfToken: "t",
  } as Me;
}

/** The current workspace's name field (the new team workspace's card has a Name field too). */
function workspaceName(): HTMLElement | null {
  return document.getElementById("ws-name");
}

describe("the settings page's workspace name", () => {
  beforeEach(() => {
    state.me = null;
    state.workspace = null;
  });

  it("is the owner's to change", () => {
    signedIn("team", false);

    render(<SettingsPage />);

    expect(workspaceName()).toBeEnabled();
    expect(screen.getByRole("button", { name: /save/i })).toBeInTheDocument();
  });

  it("is not a sandbox guest's to change, as renaming a workspace takes an account", () => {
    signedIn("sandbox", true);

    render(<SettingsPage />);

    expect(workspaceName()).toBeDisabled();
    expect(screen.queryByRole("button", { name: /save/i })).toBeNull();
  });
});

describe("the settings page's members", () => {
  it("are the owner's to manage", async () => {
    signedIn("team", false);

    render(<SettingsPage />);

    expect(await screen.findByLabelText("Role of Ada")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Leave workspace" })).toBeInTheDocument();
  });

  it("are not a sandbox guest's to manage, as roles and leaving take an account", async () => {
    signedIn("sandbox", true);

    render(<SettingsPage />);

    expect(await screen.findByText("(you)")).toBeInTheDocument();
    expect(screen.queryByLabelText("Role of Ada")).toBeNull();
    expect(screen.queryByRole("button", { name: "Leave workspace" })).toBeNull();
  });
});

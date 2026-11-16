import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthConfig, Me } from "@/lib/auth-types";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }), usePathname: () => "/projects" }));

const auth = vi.hoisted(() => ({
  state: { status: "signed-out", config: null, me: null, workspaceId: null, workspace: null } as Record<string, unknown>,
  startSandbox: vi.fn(),
}));
vi.mock("@/store/auth", () => ({
  useAuthState: () => auth.state,
  useAuth: (pick: (s: { startSandbox: () => Promise<void> }) => unknown) => pick({ startSandbox: auth.startSandbox }),
}));

import { SandboxBanner } from "./SandboxBanner";
import { SignInPage } from "./SignInPage";

const config = (sandboxes: boolean): AuthConfig => ({ mode: "oauth", providers: ["github"], sandboxes, csrfToken: sandboxes ? "t" : null });

const IN_TWO_HOURS = new Date(Date.now() + 2 * 3600_000).toISOString();

const guest: Me = {
  mode: "oauth",
  kind: "session",
  user: { id: "g1", name: "Guest", email: null, avatarUrl: null, guest: true, expiresAt: IN_TWO_HOURS },
  workspaces: [],
  csrfToken: "s",
};

describe("trying ArchSim without signing in", () => {
  beforeEach(() => {
    push.mockReset();
    auth.startSandbox.mockReset();
    auth.state = { status: "signed-out", config: config(true), me: null, workspaceId: null, workspace: null };
  });

  it("is offered on the sign-in page where the server offers sandboxes", () => {
    render(<SignInPage next={null} error={null} />);

    expect(screen.getByRole("button", { name: /Try it without signing in/ })).toBeInTheDocument();
  });

  it("is not offered where the server offers none", () => {
    auth.state = { ...auth.state, config: config(false) };

    render(<SignInPage next={null} error={null} />);

    expect(screen.queryByRole("button", { name: /Try it without signing in/ })).toBeNull();
  });

  it("starts a sandbox and goes where sign-in would have", async () => {
    auth.startSandbox.mockResolvedValue(undefined);
    render(<SignInPage next="/templates" error={null} />);

    fireEvent.click(screen.getByRole("button", { name: /Try it without signing in/ }));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/templates"));
  });

  it("says why when the server cannot start one", async () => {
    auth.startSandbox.mockRejectedValue(new Error("Every sandbox is taken right now: sign in, or try again later."));
    render(<SignInPage next={null} error={null} />);

    fireEvent.click(screen.getByRole("button", { name: /Try it without signing in/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Every sandbox is taken right now");
  });

  it("lets a guest sign in with a provider, saying the sandbox stays behind", () => {
    auth.state = { ...auth.state, status: "ready", me: guest };

    render(<SignInPage next={null} error={null} />);

    expect(screen.getByTestId("signin-github")).toBeInTheDocument();
    expect(screen.getByText(/designs in your sandbox are not moved/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Try it without signing in/ })).toBeNull();
  });
});

describe("the sandbox banner", () => {
  it("tells a guest when the sandbox goes, and how to keep their work", () => {
    auth.state = { status: "ready", config: config(true), me: guest, workspaceId: null, workspace: null };

    render(<SandboxBanner />);

    expect(screen.getByTestId("sandbox-banner")).toHaveTextContent(/its designs are deleted (today|tomorrow) at/);
    expect(screen.getByRole("link", { name: "Sign in to keep your work" }).getAttribute("href")).toMatch(/^\/signin\?next=/);
  });

  it("shows nothing to someone signed in", () => {
    auth.state = { status: "ready", config: config(true), me: { ...guest, user: { ...guest.user, guest: false, expiresAt: null } } };

    render(<SandboxBanner />);

    expect(screen.queryByTestId("sandbox-banner")).toBeNull();
  });
});

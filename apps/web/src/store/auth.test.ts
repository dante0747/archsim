import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { AuthConfig, Me } from "@/lib/auth-types";
import { activeWorkspace, requestHeaders, setActiveWorkspace, setCsrfToken } from "@/lib/session";

const api = vi.hoisted(() => ({ config: vi.fn(), me: vi.fn(), startSandbox: vi.fn(), logout: vi.fn() }));
vi.mock("@/lib/auth-api", () => ({ authApi: api }));

import { useAuth } from "./auth";

const CONFIG: AuthConfig = { mode: "oauth", providers: ["github"], sandboxes: true, csrfToken: "config-token" };

const GUEST: Me = {
  mode: "oauth",
  kind: "session",
  user: { id: "g1", name: "Guest", email: null, avatarUrl: null, guest: true, expiresAt: "2026-11-17T09:30:00Z" },
  workspaces: [
    { id: "sb1", name: "Sandbox", kind: "sandbox", role: "owner", maxConcurrentRuns: 1, maxRunsPerHour: 20, createdAt: "2026-11-16T09:30:00Z" },
  ],
  csrfToken: "session-token",
};

describe("the auth store with sandboxes", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    setActiveWorkspace(null);
    setCsrfToken(null);
    useAuth.setState({ status: "idle", config: null, me: null, workspaceId: null, error: null });
  });

  it("keeps the config's CSRF token when signed out, so starting a sandbox can send it", async () => {
    api.config.mockResolvedValue(CONFIG);
    api.me.mockRejectedValue(new ApiError("Sign in to continue", 401));

    await useAuth.getState().load();

    expect(useAuth.getState().status).toBe("signed-out");
    expect(requestHeaders("POST")["X-ArchSim-CSRF"]).toBe("config-token");
  });

  it("signs the browser in as the guest, in their sandbox", async () => {
    api.config.mockResolvedValue(CONFIG);
    api.me.mockRejectedValue(new ApiError("Sign in to continue", 401));
    await useAuth.getState().load();
    api.startSandbox.mockResolvedValue(GUEST);

    await useAuth.getState().startSandbox();

    expect(useAuth.getState()).toMatchObject({ status: "ready", me: GUEST, workspaceId: "sb1" });
    expect(activeWorkspace()).toBe("sb1");
    expect(requestHeaders("POST")["X-ArchSim-CSRF"]).toBe("session-token");
  });
});

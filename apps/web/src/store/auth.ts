"use client";

/**
 * Who is signed in and which workspace lists and new projects use. Loaded once per page from
 * /auth/config and /auth/me; without sign-in on the server (mode "none") the user is the local
 * owner of the default workspace and nothing here asks anyone to sign in.
 */
import { useEffect } from "react";
import { create } from "zustand";
import { ApiError } from "@/lib/api";
import { authApi } from "@/lib/auth-api";
import type { AuthConfig, Me, WorkspaceInfo } from "@/lib/auth-types";
import { onUnauthorized, setActiveWorkspace, storedWorkspace } from "@/lib/session";

export type AuthStatus = "idle" | "loading" | "ready" | "signed-out" | "error";

interface AuthState {
  status: AuthStatus;
  config: AuthConfig | null;
  me: Me | null;
  /** The current workspace (validated against your memberships). */
  workspaceId: string | null;
  error: string | null;
  load: (force?: boolean) => Promise<void>;
  switchWorkspace: (id: string) => void;
  signOut: (everywhere?: boolean) => Promise<void>;
}

/** The stored choice if you still belong to it, else your personal workspace, else default. */
export function pickWorkspace(workspaces: WorkspaceInfo[], stored: string | null): string | null {
  if (stored && workspaces.some((w) => w.id === stored)) return stored;
  return (
    workspaces.find((w) => w.kind === "personal")?.id ??
    workspaces.find((w) => w.id === "default")?.id ??
    workspaces[0]?.id ??
    null
  );
}

let inflight: Promise<void> | null = null;

export const useAuth = create<AuthState>((set, get) => ({
  status: "idle",
  config: null,
  me: null,
  workspaceId: null,
  error: null,

  load: (force = false) => {
    const { status } = get();
    if (!force && (status === "ready" || status === "signed-out")) return Promise.resolve();
    if (inflight) return inflight;
    if (status === "idle") set({ status: "loading" });
    inflight = (async () => {
      try {
        const config = await authApi.config();
        try {
          const me = await authApi.me();
          const id = pickWorkspace(me.workspaces, get().workspaceId ?? storedWorkspace());
          setActiveWorkspace(id);
          set({ status: "ready", config, me, workspaceId: id, error: null });
        } catch (e) {
          if (!(e instanceof ApiError && e.status === 401)) throw e;
          setActiveWorkspace(null);
          set({ status: "signed-out", config, me: null, workspaceId: null, error: null });
        }
      } catch (e) {
        set({ status: "error", error: e instanceof Error ? e.message : String(e) });
      } finally {
        inflight = null;
      }
    })();
    return inflight;
  },

  switchWorkspace: (id) => {
    if (!get().me?.workspaces.some((w) => w.id === id)) return;
    setActiveWorkspace(id);
    set({ workspaceId: id });
  },

  signOut: async (everywhere = false) => {
    await authApi.logout(everywhere);
    setActiveWorkspace(null);
    set({ status: "signed-out", me: null, workspaceId: null });
  },
}));

// A request answered 401: the session ended (signed out elsewhere, expired). Show "Sign in"
// instead of failing quietly; pages that need a session send you to the sign-in page.
onUnauthorized(() => {
  if (useAuth.getState().config?.mode === "oauth") useAuth.setState({ status: "signed-out", me: null });
});

/** The auth state, loading it on first use. */
export function useAuthState() {
  const status = useAuth((s) => s.status);
  const config = useAuth((s) => s.config);
  const me = useAuth((s) => s.me);
  const workspaceId = useAuth((s) => s.workspaceId);
  useEffect(() => {
    void useAuth.getState().load();
  }, []);
  const workspace = me?.workspaces.find((w) => w.id === workspaceId) ?? null;
  return { status, config, me, workspaceId, workspace };
}

/** Lists can be fetched: signed in (or no sign-in here), or the server could not say. */
export function authSettled(status: AuthStatus): boolean {
  return status === "ready" || status === "error";
}

/**
 * What the API client adds to every request: the current workspace. Kept apart from the auth
 * store (store/auth.ts) so lib/api.ts depends on nothing but this.
 */

export const WORKSPACE_STORAGE_KEY = "archsim-workspace";
export const WORKSPACE_HEADER = "X-ArchSim-Workspace";

let workspaceId: string | null = null;
let unauthorized: (() => void) | null = null;

/** The workspace lists and new projects go to; null: the API picks (your personal one). */
export function activeWorkspace(): string | null {
  return workspaceId;
}

export function setActiveWorkspace(id: string | null): void {
  workspaceId = id;
  try {
    if (id) localStorage.setItem(WORKSPACE_STORAGE_KEY, id);
  } catch {
    /* storage blocked: the choice lasts until the page reloads */
  }
}

export function storedWorkspace(): string | null {
  try {
    return localStorage.getItem(WORKSPACE_STORAGE_KEY);
  } catch {
    return null;
  }
}

/** Headers for one request. */
export function requestHeaders(): Record<string, string> {
  return workspaceId ? { [WORKSPACE_HEADER]: workspaceId } : {};
}

/** Called when the API answers 401 (the sign-in ended): the auth store shows "Sign in". */
export function onUnauthorized(handler: (() => void) | null): void {
  unauthorized = handler;
}

export function notifyUnauthorized(): void {
  unauthorized?.();
}

/** The sign-in page, coming back to `next` (a path on this site) afterwards. */
export function signInPage(next?: string): string {
  const here = next ?? (typeof window === "undefined" ? "/" : window.location.pathname + window.location.search);
  return `/signin?next=${encodeURIComponent(here)}`;
}

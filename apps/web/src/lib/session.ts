/**
 * What the API client adds to every request: the current workspace, and on changes the CSRF
 * token of the signed-in session. Kept apart from the auth store (store/auth.ts) so lib/api.ts
 * depends on nothing but this.
 */

export const WORKSPACE_STORAGE_KEY = "archsim-workspace";
export const WORKSPACE_HEADER = "X-ArchSim-Workspace";
export const CSRF_HEADER = "X-ArchSim-CSRF";
export const CSRF_COOKIE = "__Host-archsim_csrf";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

let workspaceId: string | null = null;
let csrfFromMe: string | null = null;
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

/** The session's CSRF token, as /auth/me gave it (the cookie is read first). */
export function setCsrfToken(token: string | null): void {
  csrfFromMe = token;
}

/** The token from the __Host-archsim_csrf cookie the API set at sign-in (readable on purpose:
 * another site can make the browser send cookies, but cannot read this one to copy it). */
export function csrfToken(cookies: string = typeof document === "undefined" ? "" : document.cookie): string | null {
  const m = cookies.match(/(?:^|;\s*)__Host-archsim_csrf=([^;]*)/);
  return (m && m[1] ? decodeURIComponent(m[1]) : null) ?? csrfFromMe;
}

/** Headers for one request: the workspace, and the CSRF token unless it only reads. */
export function requestHeaders(method = "GET"): Record<string, string> {
  const out: Record<string, string> = {};
  if (workspaceId) out[WORKSPACE_HEADER] = workspaceId;
  if (!SAFE_METHODS.has(method.toUpperCase())) {
    const token = csrfToken();
    if (token) out[CSRF_HEADER] = token;
  }
  return out;
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

/** Browser calls for share links (through the same-origin /api proxy). */
import { request } from "./api";
import type { Share } from "./share-types";
import type { Project } from "./types";

const json = (body: unknown) => JSON.stringify(body);

export const shareApi = {
  /** A frozen copy of a finished run's results, readable by anyone with the link. */
  create: (runId: string, body: { title?: string | null; expiresInDays?: number | null } = {}) =>
    request<Share>(`/runs/${encodeURIComponent(runId)}/shares`, { method: "POST", body: json(body) }),
  /** The project's links, newest first (revoked and expired ones included). */
  list: (projectId: string) => request<Share[]>(`/projects/${encodeURIComponent(projectId)}/shares`),
  /** Stops a link working at once and deletes its copy. */
  revoke: (id: string) => request<void>(`/shares/${encodeURIComponent(id)}`, { method: "DELETE" }),
  /** "Open a copy": a project in your current workspace from the shared design. */
  copy: (slug: string, name?: string) => request<Project>("/projects/from-share", { method: "POST", body: json({ slug, name }) }),
};

/** The full link to a share: the API's public address when it knows one, else this site's. */
export function shareUrl(share: Pick<Share, "url" | "path">, origin: string = typeof window === "undefined" ? "" : window.location.origin): string {
  return share.url ?? `${origin}${share.path}`;
}

/** The project's newest link that still works, if any. */
export function latestActive(shares: Share[]): Share | null {
  return shares.find((s) => s.status === "active") ?? null;
}

/** API calls for sign-in, workspaces, members and invitations. */
import { request } from "./api";
import type {
  AuthConfig,
  Invitation,
  InvitationCreated,
  InvitationPreview,
  Me,
  Member,
  Role,
  WorkspaceInfo,
} from "./auth-types";

const json = (body: unknown) => JSON.stringify(body);
const ws = (id: string) => `/workspaces/${encodeURIComponent(id)}`;

export const authApi = {
  config: () => request<AuthConfig>("/auth/config"),
  me: () => request<Me>("/auth/me"),
  logout: (everywhere = false) => request<void>(`/auth/logout${everywhere ? "?everywhere=true" : ""}`, { method: "POST" }),

  workspaces: () => request<WorkspaceInfo[]>("/workspaces"),
  createWorkspace: (name: string) => request<WorkspaceInfo>("/workspaces", { method: "POST", body: json({ name }) }),
  renameWorkspace: (id: string, name: string) => request<WorkspaceInfo>(ws(id), { method: "PATCH", body: json({ name }) }),
  deleteWorkspace: (id: string) => request<void>(ws(id), { method: "DELETE" }),

  members: (id: string) => request<Member[]>(`${ws(id)}/members`),
  setRole: (id: string, userId: string, role: Role) =>
    request<Member>(`${ws(id)}/members/${encodeURIComponent(userId)}`, { method: "PATCH", body: json({ role }) }),
  removeMember: (id: string, userId: string) => request<void>(`${ws(id)}/members/${encodeURIComponent(userId)}`, { method: "DELETE" }),

  invitations: (id: string) => request<Invitation[]>(`${ws(id)}/invitations`),
  invite: (id: string, body: { role: Role; email?: string | null; expiresInDays?: number }) =>
    request<InvitationCreated>(`${ws(id)}/invitations`, { method: "POST", body: json(body) }),
  revokeInvitation: (id: string, invitationId: string) =>
    request<void>(`${ws(id)}/invitations/${encodeURIComponent(invitationId)}`, { method: "DELETE" }),
  previewInvitation: (token: string) => request<InvitationPreview>("/invitations/preview", { method: "POST", body: json({ token }) }),
  acceptInvitation: (token: string) => request<WorkspaceInfo>("/invitations/accept", { method: "POST", body: json({ token }) }),
};

/** Starts signing in with a provider (a full-page navigation: the API redirects to it). */
export function providerLoginHref(provider: string, next: string): string {
  return `/api/v1/auth/${encodeURIComponent(provider)}/login?next=${encodeURIComponent(next)}`;
}

export const PROVIDER_NAMES: Record<string, string> = { github: "GitHub", google: "Google" };

/** "Ada Lovelace" -> "AL"; for avatars without loading pictures from other sites. */
export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  return ((parts[0][0] ?? "") + (parts.length > 1 ? (parts[parts.length - 1][0] ?? "") : "")).toUpperCase();
}

/** Sign-in, workspaces, members, invitations and API tokens, as the API returns them. */

export type AuthMode = "none" | "oauth";
export type Role = "viewer" | "editor" | "owner";
export type WorkspaceKind = "personal" | "team" | "sandbox";

export const ROLES: Role[] = ["viewer", "editor", "owner"];

export const ROLE_HELP: Record<Role, string> = {
  viewer: "Opens projects and results",
  editor: "Also edits, simulates and deletes",
  owner: "Also manages members and the workspace",
};

export interface AuthConfig {
  mode: AuthMode;
  /** Sign-in providers set up on this server: "github", "google". */
  providers: string[];
}

export interface UserInfo {
  id: string;
  name: string;
  email: string | null;
  avatarUrl: string | null;
}

export interface WorkspaceInfo {
  id: string;
  name: string;
  kind: WorkspaceKind;
  /** Your role in it. */
  role: Role;
  maxConcurrentRuns: number | null;
  maxRunsPerHour: number | null;
  createdAt: string;
}

export interface Me {
  mode: AuthMode;
  /** local: no sign-in on this server; session: signed in; token: an API token. */
  kind: "local" | "session" | "token";
  user: UserInfo;
  workspaces: WorkspaceInfo[];
  csrfToken: string | null;
}

export interface Member {
  userId: string;
  name: string;
  email: string | null;
  avatarUrl: string | null;
  role: Role;
  joinedAt: string;
}

export interface Invitation {
  id: string;
  workspaceId: string;
  role: Role;
  email: string | null;
  createdAt: string;
  expiresAt: string;
  acceptedAt: string | null;
}

export interface InvitationCreated extends Invitation {
  /** Shown once: the link is /invite/<token>. */
  token: string;
}

export interface InvitationPreview {
  workspaceName: string;
  role: Role;
  email: string | null;
  expiresAt: string;
  invitedBy: string | null;
}

/** Comments, review sign-offs and history, as the API returns them. */

export type AnchorKind = "node" | "edge" | "project" | "run";
export type Decision = "approved" | "changes_requested";

export interface Person {
  id: string;
  name: string;
  avatarUrl: string | null;
}

export interface TeamComment {
  id: string;
  projectId: string;
  anchorKind: AnchorKind;
  /** The node or edge id in the document, or the run id; null for the project. */
  anchorId: string | null;
  runId: string | null;
  /** The thread's first comment; null for a first comment. */
  parentId: string | null;
  author: Person | null;
  body: string;
  createdAt: string;
  editedAt: string | null;
  resolvedAt: string | null;
  resolvedBy: Person | null;
  /** Its part or run is gone from the saved design. */
  detached: boolean;
  canEdit: boolean;
  canDelete: boolean;
}

export interface Signoff {
  id: string;
  projectId: string;
  runId: string | null;
  runLabel: string | null;
  projectRevision: number;
  currentRevision: number;
  /** The design was saved again since. */
  stale: boolean;
  inputDigest: string | null;
  resultDigest: string | null;
  reviewer: Person | null;
  decision: Decision;
  note: string | null;
  createdAt: string;
  canWithdraw: boolean;
}

export interface ActivityEvent {
  id: number;
  action: string;
  targetKind: string;
  targetId: string | null;
  projectId: string | null;
  actor: Person | null;
  actorKind: "user" | "token" | "system";
  meta: Record<string, unknown> | null;
  /** Workspace owners only. */
  ip: string | null;
  createdAt: string;
}

export interface ActivityPage {
  items: ActivityEvent[];
  /** Pass as `before` for older events; null on the last page. */
  nextBefore: number | null;
}

export interface CommentDraft {
  anchorKind: AnchorKind;
  anchorId?: string | null;
  runId?: string | null;
  parentId?: string | null;
  body: string;
}

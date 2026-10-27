/** API calls for comments, review sign-offs and history. */
import { request } from "./api";
import type { ActivityPage, CommentDraft, Decision, Signoff, TeamComment } from "./team-types";

const json = (body: unknown) => JSON.stringify(body);
const id = (v: string) => encodeURIComponent(v);

export const teamApi = {
  comments: (projectId: string) => request<TeamComment[]>(`/projects/${id(projectId)}/comments`),
  addComment: (projectId: string, draft: CommentDraft) =>
    request<TeamComment>(`/projects/${id(projectId)}/comments`, { method: "POST", body: json(draft) }),
  editComment: (commentId: string, body: string) =>
    request<TeamComment>(`/comments/${id(commentId)}`, { method: "PATCH", body: json({ body }) }),
  deleteComment: (commentId: string) => request<void>(`/comments/${id(commentId)}`, { method: "DELETE" }),
  /** Resolves (or reopens) the thread the comment is in; answers with its first comment. */
  resolve: (commentId: string, resolved: boolean) =>
    request<TeamComment>(`/comments/${id(commentId)}/resolve`, { method: "POST", body: json({ resolved }) }),

  signoffs: (projectId: string) => request<Signoff[]>(`/projects/${id(projectId)}/signoffs`),
  signOff: (runId: string, decision: Decision, note?: string | null) =>
    request<Signoff>(`/runs/${id(runId)}/signoffs`, { method: "POST", body: json({ decision, note: note || null }) }),
  withdraw: (signoffId: string) => request<void>(`/signoffs/${id(signoffId)}`, { method: "DELETE" }),

  activity: (projectId: string, before?: number | null, limit = 50) =>
    request<ActivityPage>(
      `/projects/${id(projectId)}/activity?limit=${limit}${before ? `&before=${before}` : ""}`,
    ),
};

/** Typed client for the ArchSim v1 API (proxied same-origin under /api/v1). */
import type {
  ArchitectureDocument,
  Catalog,
  Comparison,
  ExampleSummary,
  Issue,
  Project,
  ProjectSummary,
  Report,
  Run,
} from "./types";

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public issues: Issue[] = [],
  ) {
    super(message);
    this.name = "ApiError";
  }
}

const BASE = "/api/v1";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, {
      ...init,
      headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
      cache: "no-store",
    });
  } catch {
    throw new ApiError("Network error: cannot reach the ArchSim web server.", 0);
  }
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    let issues: Issue[] = [];
    try {
      const body = await res.json();
      const detail = body?.detail;
      if (typeof detail === "string") message = detail;
      else if (detail?.message) {
        message = detail.message;
        issues = detail.issues ?? [];
      } else if (Array.isArray(detail)) {
        message = detail.map((d: { msg?: string }) => d.msg).join("; ");
      }
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(message, res.status, issues);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

const json = (body: unknown) => JSON.stringify(body);

export const api = {
  components: () => request<Catalog>("/components"),
  examples: () => request<ExampleSummary[]>("/examples"),
  example: (id: string) => request<ArchitectureDocument>(`/examples/${encodeURIComponent(id)}`),
  validate: (document: ArchitectureDocument, scenarioId?: string | null) =>
    request<{ valid: boolean; issues: Issue[] }>("/validate", {
      method: "POST",
      body: json({ document, scenarioId: scenarioId ?? null }),
    }),

  listProjects: () => request<ProjectSummary[]>("/projects"),
  createProject: (body: {
    name?: string;
    description?: string;
    document?: ArchitectureDocument;
    exampleId?: string;
    /** With exampleId and no name: reopen the copy made last time if it was never saved. */
    reuseUnedited?: boolean;
  }) => request<Project>("/projects", { method: "POST", body: json(body) }),
  importProject: (document: unknown) =>
    request<Project>("/projects/import", { method: "POST", body: json(document) }),
  getProject: (id: string) => request<Project>(`/projects/${id}`),
  updateProject: (
    id: string,
    body: { name?: string; description?: string; document?: ArchitectureDocument; expectedRevision?: number },
  ) => request<Project>(`/projects/${id}`, { method: "PUT", body: json(body) }),
  deleteProject: (id: string) => request<void>(`/projects/${id}`, { method: "DELETE" }),
  duplicateProject: (id: string) => request<Project>(`/projects/${id}/duplicate`, { method: "POST" }),

  createRun: (
    projectId: string,
    body: { scenarioId?: string | null; label?: string; document?: ArchitectureDocument },
  ) => request<Run>(`/projects/${projectId}/runs`, { method: "POST", body: json(body) }),
  batchRuns: (projectId: string, scenarioIds: (string | null)[], document?: ArchitectureDocument) =>
    request<Run[]>(`/projects/${projectId}/runs/batch`, {
      method: "POST",
      body: json({ scenarioIds, document }),
    }),
  listRuns: (projectId: string) => request<Run[]>(`/projects/${projectId}/runs`),
  getRun: (id: string) => request<Run>(`/runs/${id}`),
  getReport: (id: string) => request<Report>(`/runs/${id}/report`),
  cancelRun: (id: string) => request<Run>(`/runs/${id}/cancel`, { method: "POST" }),
  rerun: (id: string) => request<Run>(`/runs/${id}/rerun`, { method: "POST" }),
  deleteRun: (id: string) => request<void>(`/runs/${id}`, { method: "DELETE" }),
  compare: (a: string, b: string) =>
    request<Comparison>(`/compare?a=${encodeURIComponent(a)}&b=${encodeURIComponent(b)}`),
};

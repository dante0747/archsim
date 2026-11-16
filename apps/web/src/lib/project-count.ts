/**
 * The projects page's count of the current workspace's projects: in a sandbox, against the most it
 * holds ("4 of 5 in Sandbox."), so a guest knows before the sixth is refused.
 */
import type { WorkspaceInfo } from "./auth-types";
import { SANDBOX_PROJECT_LIMIT } from "./sandbox";

/** `count` is null while the projects load; `workspace` null on a local install. */
export function projectCountText(count: number | null, workspace: WorkspaceInfo | null): string {
  if (!workspace || workspace.id === "default") return count !== null ? `${count} saved in your ArchSim database.` : "Saved in your ArchSim database.";
  if (workspace.kind === "sandbox") return count !== null ? `${count} of ${SANDBOX_PROJECT_LIMIT} in ${workspace.name}.` : `Up to ${SANDBOX_PROJECT_LIMIT} in ${workspace.name}.`;
  const readOnly = workspace.role === "viewer" ? " You can open them; editors change them." : "";
  return `${count !== null ? `${count} ` : ""}in ${workspace.name}.${readOnly}`;
}

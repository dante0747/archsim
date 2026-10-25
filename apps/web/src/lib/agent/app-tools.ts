/**
 * Tools available on every ArchSim page: find a template or project and open it. Opening one
 * navigates to the editor, which registers the design tools (editor-tools.ts).
 */
import { api } from "@/lib/api";
import type { AgentTool } from "./webmcp";

export function appTools(navigate: (path: string) => void): AgentTool[] {
  return [
    {
      name: "list_templates",
      title: "List templates",
      description:
        "Ready-made ArchSim designs with real technologies and scenarios to compare (e.g. a cache-aside web API, Black Friday on Kubernetes, a Kafka pipeline, a sharded MongoDB). Open one with open_template.",
      inputSchema: { type: "object", properties: {} },
      annotations: { readOnlyHint: true },
      execute: async () =>
        (await api.examples()).map((e) => ({ id: e.id, name: e.name, description: e.description, tags: e.tags, scenarios: e.scenarioCount })),
    },
    {
      name: "open_template",
      title: "Open a template",
      description:
        "Open a template in the editor (reusing the user's unedited copy of it, else creating one), where the design tools (get_design, add_part, simulate...) become available.",
      inputSchema: { type: "object", properties: { id: { type: "string" } }, required: ["id"] },
      execute: async (input) => {
        const p = await api.createProject({ exampleId: String(input.id), reuseUnedited: true });
        navigate(`/projects/${p.id}`);
        return { projectId: p.id, name: p.name, summary: `opened the "${p.name}" template` };
      },
    },
    {
      name: "new_project",
      title: "Start a blank design",
      description: "Create an empty project and open it in the editor to build a design from scratch with add_part and connect_parts.",
      inputSchema: { type: "object", properties: { name: { type: "string" } } },
      execute: async (input) => {
        const name = typeof input.name === "string" && input.name.trim() ? input.name.trim().slice(0, 120) : "Untitled architecture";
        const p = await api.createProject({ name });
        navigate(`/projects/${p.id}`);
        return { projectId: p.id, name: p.name, summary: `started "${p.name}"` };
      },
    },
    {
      name: "list_projects",
      title: "List saved projects",
      description: "The user's saved ArchSim projects (newest first) with their ids.",
      inputSchema: { type: "object", properties: {} },
      annotations: { readOnlyHint: true },
      execute: async () =>
        (await api.listProjects()).map((p) => ({ id: p.id, name: p.name, parts: p.nodeCount, scenarios: p.scenarioCount, updatedAt: p.updatedAt })),
    },
    {
      name: "open_project",
      title: "Open a project",
      description: "Open a saved project in the editor.",
      inputSchema: { type: "object", properties: { id: { type: "string" } }, required: ["id"] },
      annotations: { readOnlyHint: true },
      execute: async (input) => {
        const p = await api.getProject(String(input.id));
        navigate(`/projects/${p.id}`);
        return { projectId: p.id, name: p.name, summary: `opened "${p.name}"` };
      },
    },
  ];
}

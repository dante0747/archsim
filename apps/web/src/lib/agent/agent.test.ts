import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "@/lib/api";
import analysisJson from "@/test/fixtures/analysis-starter.json";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import type { Analysis, ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";
import { editorTools } from "./editor-tools";
import { type AgentTool, modelContext, registerAgentTools } from "./webmcp";

type Registered = { name: string; execute: (input: unknown) => Promise<{ content: { text: string }[]; isError?: boolean }>; annotations: Record<string, boolean> };

function fakeContext(shape: "document" | "navigator") {
  const tools = new Map<string, Registered>();
  const ctx = {
    registerTool: vi.fn(async (tool: Registered, options?: { signal?: AbortSignal }) => {
      if (tools.has(tool.name)) throw new DOMException("duplicate", "InvalidStateError");
      tools.set(tool.name, tool);
      // the draft unregisters when the signal aborts
      if (shape === "document") options?.signal?.addEventListener("abort", () => tools.delete(tool.name));
    }),
    unregisterTool: shape === "navigator" ? vi.fn((name: string) => tools.delete(name)) : undefined,
  };
  Object.defineProperty(shape === "document" ? document : navigator, "modelContext", { value: ctx, configurable: true });
  return { ctx, tools };
}

function clearContexts() {
  for (const target of [document, navigator]) {
    if ("modelContext" in target) delete (target as unknown as { modelContext?: unknown }).modelContext;
  }
}

const tool = (name: string, execute: AgentTool["execute"], readOnly = false): AgentTool => ({
  name,
  title: name,
  description: name,
  inputSchema: { type: "object", properties: {} },
  annotations: { readOnlyHint: readOnly },
  execute,
});

const flush = () => new Promise((r) => setTimeout(r, 0));

describe("WebMCP adapter", () => {
  afterEach(clearContexts);

  it("does nothing without a model context", () => {
    expect(modelContext()).toBeNull();
    const ac = new AbortController();
    expect(() => registerAgentTools([tool("x", async () => 1)], ac.signal)).not.toThrow();
    ac.abort();
  });

  it("registers on document.modelContext, returns JSON content and errors, and unregisters on abort", async () => {
    const { tools } = fakeContext("document");
    const calls: string[] = [];
    const ac = new AbortController();
    registerAgentTools(
      [tool("ok", async (input) => ({ echoed: input.value }), true), tool("bad", async () => Promise.reject(new Error("nope")))],
      ac.signal,
      (t, _i, outcome) => calls.push(`${t.name}:${outcome.ok}`),
    );
    await flush();
    expect([...tools.keys()]).toEqual(["ok", "bad"]);
    expect(tools.get("ok")!.annotations.readOnlyHint).toBe(true);
    const ok = await tools.get("ok")!.execute({ value: 3 });
    expect(JSON.parse(ok.content[0].text)).toEqual({ echoed: 3 });
    const bad = await tools.get("bad")!.execute({});
    expect(bad.isError).toBe(true);
    expect(bad.content[0].text).toBe("Error: nope");
    expect(calls).toEqual(["ok:true", "bad:false"]);
    ac.abort();
    expect(tools.size).toBe(0);
  });

  it("supports navigator.modelContext with unregisterTool, replacing a stale registration", async () => {
    const { ctx, tools } = fakeContext("navigator");
    const stale = new AbortController();
    registerAgentTools([tool("t", async () => "first")], stale.signal);
    await flush();
    // a second mount without the first being cleaned up (hot reload)
    const ac = new AbortController();
    registerAgentTools([tool("t", async () => "second")], ac.signal);
    await flush();
    expect(ctx.unregisterTool).toHaveBeenCalledWith("t");
    expect((await tools.get("t")!.execute({})).content[0].text).toBe("second");
    ac.abort();
    expect(tools.size).toBe(0);
  });
});

const catalog = catalogJson as unknown as Catalog;
const project: Project = {
  id: "p1",
  name: "Starter",
  description: null,
  revision: 1,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  nodeCount: 5,
  scenarioCount: 0,
  document: starterJson as unknown as ArchitectureDocument,
};

describe("editor tools", () => {
  const tools = Object.fromEntries(editorTools().map((t) => [t.name, t]));
  const call = (name: string, input: Record<string, unknown> = {}) => tools[name].execute(input) as Promise<Record<string, unknown>>;
  beforeEach(() => {
    useEditor.getState().load(structuredClone(project), catalog);
    useUi.getState().resetForProject();
    // sizes and review come from the engine (POST /analyze): its real output for the starter
    vi.spyOn(api, "analyze").mockResolvedValue(analysisJson as unknown as Analysis);
  });
  afterEach(() => vi.restoreAllMocks());

  it("describes itself well enough for an agent", () => {
    for (const t of editorTools()) {
      expect(t.name).toMatch(/^[a-z_]+$/);
      expect(t.description.length).toBeGreaterThan(30);
      expect(t.inputSchema.type).toBe("object");
    }
    expect(tools.get_design.annotations?.readOnlyHint).toBe(true);
    expect(tools.save_design.annotations?.consequentialHint).toBe(true);
  });

  it("reads the design with changed settings, links and auto sizes", async () => {
    const d = await call("get_design");
    const parts = d.parts as { id: string; settings: Record<string, unknown>; autoSize: unknown }[];
    expect(parts.map((p) => p.id)).toContain("api");
    expect((d.links as unknown[]).length).toBeGreaterThan(0);
    expect(parts.find((p) => p.id === "api")!.autoSize).toMatchObject({ cpuCores: expect.any(Number) });
    expect(d.traffic).toMatchObject({ rps: expect.any(Number) });
  });

  it("lists part types and one type's settings", async () => {
    const all = (await tools.list_part_types.execute({})) as { type: string }[];
    expect(all.map((t) => t.type)).toEqual(expect.arrayContaining(["service", "database", "cache", "kubernetes_cluster"]));
    const db = (await call("list_part_types", { type: "database" })) as { settings: { key: string }[]; presets: { id: string }[] };
    expect(db.settings.map((s) => s.key)).toContain("readReplicas");
    expect(db.presets.length).toBeGreaterThan(3);
  });

  it("adds a part from a preset next to another, connects it and changes it, all undoable", async () => {
    const before = useEditor.getState().nodes.length;
    const added = await call("add_part", { type: "cache", preset: "redis", label: "Session cache", near: "api", settings: { hitRatio: 0.9 } });
    const s = useEditor.getState();
    const node = s.nodes.find((n) => n.id === added.id)!;
    expect(node.data).toMatchObject({ label: "Session cache", config: { technology: "redis", hitRatio: 0.9 } });
    const link = await call("connect_parts", { from: "api", to: String(added.id), requestTypes: ["read"], timeoutMs: 50 });
    expect(useEditor.getState().edges.find((e) => e.id === link.id)!.data!.config).toMatchObject({ requestTypes: ["read"], timeoutMs: 50 });
    await call("update_part", { id: String(added.id), settings: { hitRatio: 0.95 } });
    expect(useEditor.getState().nodes.find((n) => n.id === added.id)!.data.config.hitRatio).toBe(0.95);
    for (let i = 0; i < 10; i++) await call("undo");
    expect(useEditor.getState().nodes.length).toBe(before);
  });

  it("explains mistakes instead of guessing", async () => {
    await expect(call("add_part", { type: "mainframe" })).rejects.toThrow(/Unknown part type/);
    await expect(call("add_part", { type: "cache", preset: "oracle" })).rejects.toThrow(/no preset "oracle"/);
    await expect(call("update_part", { id: "api", settings: { colour: "red" } })).rejects.toThrow(/no setting "colour"/);
    await expect(call("connect_parts", { from: "api", to: "nope" })).rejects.toThrow(/No part with id "nope"/);
    await expect(call("set_traffic", { readRatio: 2 })).rejects.toThrow(/between 0 and 1/);
    await expect(call("remove", { ids: ["ghost"] })).rejects.toThrow(/Not in the design: ghost/);
    await expect(call("get_results")).rejects.toThrow(/No simulation yet/);
  });

  it("sets traffic and objectives, removes parts, and reviews the design", async () => {
    await call("set_traffic", { rps: 900, pattern: { kind: "ramp", startRps: 100, endRps: 900 } });
    expect(useEditor.getState().workload).toMatchObject({ rps: 900, pattern: { kind: "ramp", endRps: 900 } });
    await call("set_objectives", { latencyP95Ms: 150, availability: 0.999 });
    expect(useEditor.getState().objectives).toMatchObject({ latencyP95Ms: 150, availability: 0.999 });
    const review = (await tools.review_design.execute({})) as { severity: string; title: string }[];
    expect(Array.isArray(review)).toBe(true);
    await call("remove", { ids: ["cache"] });
    expect(useEditor.getState().nodes.some((n) => n.id === "cache")).toBe(false);
  });

  it("saves the design as the editor's Save does, and reports the revision", async () => {
    await call("update_part", { id: "api", settings: { replicas: 3 } });
    const update = vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, revision: 2 });

    const saved = await call("save_design");

    expect(saved).toEqual({ revision: 2, summary: "saved revision 2" });
    expect(update).toHaveBeenCalledWith("p1", expect.objectContaining({ expectedRevision: 1 }));
    expect(useEditor.getState()).toMatchObject({ dirty: false, revision: 2 });
  });

  it("saves nothing when the project was saved elsewhere, and leaves the choice to the person", async () => {
    await call("update_part", { id: "api", settings: { replicas: 3 } });
    vi.spyOn(api, "updateProject").mockRejectedValue(
      new ApiError("Project was modified elsewhere (revision 2, you had 1). Reload before saving.", 409),
    );

    await expect(call("save_design")).rejects.toThrow(/saved elsewhere since it was opened, so nothing was saved/);

    expect(useEditor.getState()).toMatchObject({ conflict: true, dirty: true, revision: 1 });
    expect(useUi.getState().saveConflictOpen).toBe(true);
  });
});

/**
 * Tools an AI agent can use on the open design (registered by the editor through WebMCP). They
 * call the same store actions as the interface, so every edit shows on the canvas and can be
 * undone. Results are compact JSON: ids, labels and the numbers that matter.
 */
import { api } from "@/lib/api";
import { freeSpot } from "@/lib/placement";
import { saveDesign } from "@/lib/save";
import { evaluateSlo } from "@/lib/slo";
import { partTier } from "@/lib/tier";
import type { ComponentType, EdgeConfig, Report, Workload } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";
import type { AgentTool } from "./webmcp";

const ed = () => useEditor.getState();

function catalog() {
  const c = ed().catalog;
  if (!c) throw new Error("The part catalog is still loading; try again in a moment.");
  return c;
}

function componentType(type: string): ComponentType {
  const c = catalog().components.find((x) => x.type === type);
  if (!c) throw new Error(`Unknown part type "${type}". Call list_part_types for the types.`);
  return c;
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

/** Settings that differ from the part type's defaults (what makes this part what it is). */
function changedConfig(config: Record<string, unknown>, comp: ComponentType | undefined): Record<string, unknown> {
  const defaults = comp?.defaultConfig ?? {};
  return Object.fromEntries(Object.entries(config).filter(([k, v]) => v !== undefined && !same(v, defaults[k])));
}

function checkKeys(comp: ComponentType, config: Record<string, unknown>) {
  const known = new Set(comp.fields.map((f) => f.key));
  const unknown = Object.keys(config).filter((k) => !known.has(k));
  if (unknown.length) {
    throw new Error(`${comp.type} has no setting ${unknown.map((k) => `"${k}"`).join(", ")}. Its settings: ${[...known].join(", ")}.`);
  }
}

function nodeById(id: string) {
  const n = ed().nodes.find((x) => x.id === id);
  if (!n) throw new Error(`No part with id "${id}". Call get_design for the ids.`);
  return n;
}

function edgeConfigPatch(input: Record<string, unknown>): Partial<EdgeConfig> & Record<string, unknown> {
  const patch: Record<string, unknown> = {};
  if (Array.isArray(input.requestTypes)) patch.requestTypes = input.requestTypes;
  for (const k of ["timeoutMs", "maxAttempts", "retryBackoffMs"]) if (k in input) patch[k] = input[k];
  if (typeof input.latencyMs === "number") patch.latencyMs = { kind: "constant", value: input.latencyMs };
  return patch;
}

const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : null);
const round = (v: number | null | undefined, d = 1) => (v == null ? null : Math.round(v * 10 ** d) / 10 ** d);

/** A compact summary of a run's report for an agent. */
export function summarizeReport(report: Report, runId: string) {
  const s = report.summary;
  const objectives = ed().objectives;
  const slo = evaluateSlo(objectives, report);
  const cost = report.cost ?? null; // priced by the engine with the run
  return {
    runId,
    /** Cite it: re-running the same input reproduces this digest exactly. */
    resultDigest: report.provenance?.resultDigest ?? null,
    verdict: slo.pass === true ? "meets all objectives" : slo.pass === false ? `misses ${slo.total - slo.met} of ${slo.total} objectives` : "no verdict",
    objectives: slo.checks.map((c) => ({ objective: c.label, target: c.target, actual: round(c.actual, 3), met: c.pass })),
    offeredRps: round(s.offeredRps),
    throughputRps: round(s.throughputRps),
    successRate: round(s.successRate, 4),
    latencyMs: { p50: round(s.latencyMs.p50), p95: round(s.latencyMs.p95), p99: round(s.latencyMs.p99) },
    failures: s.failureBreakdown.slice(0, 5).map((f) => ({ reason: f.reasonText || f.reason, at: f.nodeLabel ?? f.nodeId, count: f.count })),
    findings: report.findings
      .filter((f) => f.severity !== "ok")
      .slice(0, 8)
      .map((f) => ({ severity: f.severity, part: f.nodeId, title: f.title, detail: f.detail, suggestion: f.suggestion ?? null })),
    parts: report.nodes.map((n) => ({
      id: n.id,
      label: n.label,
      status: n.status,
      utilization: round(n.summary.utilization, 3),
      arrivalRps: round(n.summary.arrivalRps),
      p95Ms: round(n.summary.latencyMs?.p95 ?? null),
    })),
    costPerMonthUsd: cost?.hasCost ? Math.round(cost.monthly) : null,
    /** Parts the monthly cost leaves out for want of a price (ADR-0081). */
    costNotPriced: cost?.unpriced ?? [],
  };
}

function waitForRun(runId: string, timeoutMs: number): Promise<Report> {
  return new Promise((resolve, reject) => {
    const check = () => {
      const r = useRuns.getState();
      const run = r.runs.find((x) => x.id === runId);
      if (run?.status === "failed") return { error: run.error?.message ?? "The run failed." };
      if (run?.status === "cancelled") return { error: "The run was cancelled." };
      const report = r.reports[runId];
      return report ? { report } : null;
    };
    const finish = (out: { report?: Report; error?: string }) => {
      clearTimeout(timer);
      unsubscribe();
      if (out.report) resolve(out.report);
      else reject(new Error(out.error));
    };
    const timer = setTimeout(() => {
      unsubscribe();
      reject(new Error(`Still running after ${Math.round(timeoutMs / 1000)} s; call get_results with runId "${runId}" later.`));
    }, timeoutMs);
    const unsubscribe = useRuns.subscribe(() => {
      const out = check();
      if (out) finish(out);
    });
    const first = check();
    if (first) finish(first);
  });
}

/** Where a new part goes: right of `near`, else right of everything, on a free spot. */
function placeFor(near?: string): { x: number; y: number } {
  const nodes = ed().nodes;
  let x = 0;
  let y = 0;
  if (near) {
    const n = nodeById(near);
    x = n.position.x + 300;
    y = n.position.y;
  } else if (nodes.length) {
    x = Math.max(...nodes.map((n) => n.position.x)) + 300;
    y = Math.round(nodes.reduce((sum, n) => sum + n.position.y, 0) / nodes.length);
  }
  return freeSpot(nodes, { x, y });
}

const STRING = { type: "string" };
const PATTERN = {
  type: "object",
  description: 'Traffic shape: {"kind":"constant"}, {"kind":"ramp","startRps":100,"endRps":1000} or {"kind":"burst","burstRps":2000,"startS":30,"durationS":20}.',
  properties: {
    kind: { type: "string", enum: ["constant", "ramp", "burst"] },
    startRps: { type: "number" },
    endRps: { type: "number" },
    burstRps: { type: "number" },
    startS: { type: "number" },
    durationS: { type: "number" },
  },
  required: ["kind"],
};

export function editorTools(): AgentTool[] {
  return [
    {
      name: "get_design",
      title: "Read the design",
      description:
        "Return the open ArchSim design: its parts (id, type, label, technology and its tier (core, or experimental: not yet validated against measurements), settings that differ from the defaults, auto-sized CPU/memory/storage), links between parts, traffic, objectives, scenarios and current validation problems. Start here.",
      inputSchema: { type: "object", properties: { includeDefaults: { type: "boolean", description: "Include every setting, not only changed ones." } } },
      annotations: { readOnlyHint: true },
      execute: async (input) => {
        const s = ed();
        const cat = catalog();
        const doc = s.toDocument();
        // sized by the engine, from the design as it is now (not the last debounced analysis)
        const sizes = (await api.analyze(doc)).sizing.nodes;
        return {
          name: s.metadata.name,
          parts: s.nodes.map((n) => {
            const comp = cat.components.find((c) => c.type === n.data.type);
            const size = sizes[n.id];
            return {
              id: n.id,
              type: n.data.type,
              label: n.data.label,
              technology: typeof n.data.config.technology === "string" ? n.data.config.technology : null,
              tier: partTier(comp, n.data.config),
              settings: input.includeDefaults ? { ...(comp?.defaultConfig ?? {}), ...n.data.config } : changedConfig(n.data.config, comp),
              autoSize: size && !size.skipped ? { ...size.auto, peakRps: round(size.peakRps) } : null,
            };
          }),
          links: doc.architecture.edges.map((e) => ({
            id: e.id,
            from: e.source,
            to: e.target,
            port: e.sourcePort,
            requestTypes: e.config.requestTypes,
            timeoutMs: e.config.timeoutMs ?? null,
            maxAttempts: e.config.maxAttempts ?? null,
          })),
          traffic: s.workload,
          objectives: s.objectives,
          scenarios: s.scenarios.map((x) => ({ id: x.id, name: x.name, description: x.description ?? null })),
          problems: s.issues.map((i) => ({ severity: i.severity, code: i.code, message: i.message, part: i.nodeId ?? null, link: i.edgeId ?? null })),
        };
      },
    },
    {
      name: "list_part_types",
      title: "List part types",
      description:
        "Without a type: every kind of part you can add (services, databases, caches, queues, load balancers, clients, third-party APIs, Kubernetes clusters) with their technology presets. With a type: its settings (key, kind, default, options, unit, limits, meaning), ports and presets.",
      inputSchema: { type: "object", properties: { type: { ...STRING, description: 'A part type, e.g. "database".' } } },
      annotations: { readOnlyHint: true },
      execute: async (input) => {
        const cat = catalog();
        if (typeof input.type === "string" && input.type) {
          const c = componentType(input.type);
          return {
            type: c.type,
            name: c.displayName,
            description: c.description,
            ports: c.ports.map((p) => ({ name: p.name, direction: p.direction, requestTypes: p.requestTypes, description: p.description })),
            settings: c.fields.map((f) => ({
              key: f.key,
              label: f.label,
              kind: f.kind,
              default: f.default,
              options: f.options,
              unit: f.unit,
              min: f.min,
              max: f.max,
              nullable: f.nullable,
              description: f.description,
            })),
            presets: (c.presets ?? []).map((p) => ({ id: p.id, name: p.name, technology: p.technology, tier: p.tier ?? null, description: p.description })),
          };
        }
        return cat.components.map((c) => ({
          type: c.type,
          name: c.displayName,
          category: c.category,
          description: c.description,
          presets: (c.presets ?? []).map((p) => ({ id: p.id, name: p.name, technology: p.technology, tier: p.tier ?? null })),
        }));
      },
    },
    {
      name: "add_part",
      title: "Add a part",
      description:
        "Add a part to the design. Give a type (see list_part_types) and optionally a preset (a preset id or technology, e.g. \"postgresql\", \"redis\", \"kafka\") for typical numbers, a label, settings to change, and the id of a part to place it next to. Returns the new part's id. Connect it with connect_parts.",
      inputSchema: {
        type: "object",
        properties: {
          type: STRING,
          preset: { ...STRING, description: "Preset id or technology value." },
          label: STRING,
          settings: { type: "object", description: "Setting keys and values (see list_part_types with this type)." },
          near: { ...STRING, description: "Place it to the right of this part." },
        },
        required: ["type"],
      },
      execute: async (input) => {
        const type = String(input.type);
        const comp = componentType(type);
        const settings = (input.settings ?? {}) as Record<string, unknown>;
        checkKeys(comp, settings);
        let preset = undefined;
        if (typeof input.preset === "string" && input.preset) {
          preset = (comp.presets ?? []).find((p) => p.id === input.preset || p.technology === input.preset);
          if (!preset) throw new Error(`${type} has no preset "${input.preset}". Presets: ${(comp.presets ?? []).map((p) => p.id).join(", ") || "none"}.`);
        }
        const pos = placeFor(typeof input.near === "string" ? input.near : undefined);
        const s = ed();
        const id = preset ? s.addPresetNode(type, pos, preset) : s.addNode(type, pos);
        if (!id) throw new Error(`Could not add a ${type}.`);
        if (typeof input.label === "string" && input.label.trim()) ed().updateNode(id, { label: input.label.trim().slice(0, 120) });
        for (const [k, v] of Object.entries(settings)) ed().setNodeConfig(id, k, v);
        const n = nodeById(id);
        return { id, label: n.data.label, type, technology: n.data.config.technology ?? null, summary: `added ${n.data.label}` };
      },
    },
    {
      name: "connect_parts",
      title: "Connect two parts",
      description:
        "Link two parts so requests flow from one to the other (clients → load balancer → service → cache/database/queue...). Optionally limit the link to reads or writes and set its timeout, attempts or network latency. The ports are chosen automatically (a cache's misses go to its miss port). Returns the link id.",
      inputSchema: {
        type: "object",
        properties: {
          from: STRING,
          to: STRING,
          requestTypes: { type: "array", items: { type: "string", enum: ["read", "write"] } },
          timeoutMs: { type: ["number", "null"] },
          maxAttempts: { type: "integer", minimum: 1 },
          latencyMs: { type: "number", description: "Constant network latency on the link." },
        },
        required: ["from", "to"],
      },
      execute: async (input) => {
        const from = String(input.from);
        const to = String(input.to);
        nodeById(from);
        nodeById(to);
        const before = new Set(ed().edges.map((e) => e.id));
        const r = ed().connect({ source: from, target: to, sourceHandle: null, targetHandle: null });
        if (!r.ok) throw new Error(r.reason ?? "These parts cannot be connected.");
        const edge = ed().edges.find((e) => !before.has(e.id));
        if (!edge) throw new Error("The link was not created.");
        for (const [k, v] of Object.entries(edgeConfigPatch(input))) ed().setEdgeConfig(edge.id, k, v);
        return { id: edge.id, from, to, summary: `connected ${nodeById(from).data.label} → ${nodeById(to).data.label}` };
      },
    },
    {
      name: "update_part",
      title: "Change a part",
      description: "Rename a part or change its settings (keys from list_part_types). A null value goes back to the default or to auto (for cpuCores, memoryGib and storageGib).",
      inputSchema: { type: "object", properties: { id: STRING, label: STRING, settings: { type: "object" } }, required: ["id"] },
      execute: async (input) => {
        const n = nodeById(String(input.id));
        const comp = componentType(n.data.type);
        const settings = (input.settings ?? {}) as Record<string, unknown>;
        checkKeys(comp, settings);
        if (typeof input.label === "string" && input.label.trim()) ed().updateNode(n.id, { label: input.label.trim().slice(0, 120) });
        for (const [k, v] of Object.entries(settings)) ed().setNodeConfig(n.id, k, v);
        const after = nodeById(n.id);
        return { id: n.id, label: after.data.label, settings: changedConfig(after.data.config, comp), summary: `changed ${after.data.label}` };
      },
    },
    {
      name: "update_link",
      title: "Change a link",
      description: "Change a link's request types (read/write), timeout, attempts (retries), retry backoff or network latency.",
      inputSchema: {
        type: "object",
        properties: {
          id: STRING,
          requestTypes: { type: "array", items: { type: "string", enum: ["read", "write"] } },
          timeoutMs: { type: ["number", "null"] },
          maxAttempts: { type: "integer", minimum: 1 },
          retryBackoffMs: { type: "number" },
          latencyMs: { type: "number" },
        },
        required: ["id"],
      },
      execute: async (input) => {
        const id = String(input.id);
        if (!ed().edges.some((e) => e.id === id)) throw new Error(`No link with id "${id}". Call get_design for the ids.`);
        for (const [k, v] of Object.entries(edgeConfigPatch(input))) ed().setEdgeConfig(id, k, v);
        return { id, config: ed().edges.find((e) => e.id === id)?.data?.config, summary: `changed link ${id}` };
      },
    },
    {
      name: "remove",
      title: "Remove parts or links",
      description: "Remove parts (with their links) or links by id. Undoable with the undo tool.",
      inputSchema: { type: "object", properties: { ids: { type: "array", items: STRING, minItems: 1 } }, required: ["ids"] },
      execute: async (input) => {
        const ids = (Array.isArray(input.ids) ? input.ids : []).map(String);
        const s = ed();
        const nodeIds = ids.filter((id) => s.nodes.some((n) => n.id === id));
        const edgeIds = ids.filter((id) => s.edges.some((e) => e.id === id));
        const missing = ids.filter((id) => !nodeIds.includes(id) && !edgeIds.includes(id));
        if (missing.length) throw new Error(`Not in the design: ${missing.join(", ")}.`);
        s.deleteElements(nodeIds, edgeIds);
        return { removed: ids, summary: `removed ${ids.length} item${ids.length === 1 ? "" : "s"}` };
      },
    },
    {
      name: "set_traffic",
      title: "Set the traffic",
      description:
        "Change the workload: requests per second, share of reads (0-1), run length and warm-up in seconds, traffic shape (constant, ramp or burst), number of distinct keys and their skew, random seed. Only the values given change.",
      inputSchema: {
        type: "object",
        properties: {
          rps: { type: "number", exclusiveMinimum: 0 },
          readRatio: { type: "number", minimum: 0, maximum: 1 },
          durationS: { type: "number", exclusiveMinimum: 0 },
          warmupS: { type: "number", minimum: 0 },
          pattern: PATTERN,
          keyCount: { type: "integer", minimum: 1 },
          keySkew: { type: "number", minimum: 0 },
          seed: { type: "integer" },
        },
      },
      execute: async (input) => {
        const keys = ["rps", "readRatio", "durationS", "warmupS", "pattern", "keyCount", "keySkew", "seed"] as const;
        const patch = Object.fromEntries(keys.filter((k) => input[k] !== undefined).map((k) => [k, input[k]])) as Partial<Workload>;
        if (!Object.keys(patch).length) throw new Error("Give at least one traffic value to change.");
        if (patch.rps != null && !(num(patch.rps)! > 0)) throw new Error("rps must be above 0.");
        if (patch.readRatio != null && !(num(patch.readRatio)! >= 0 && num(patch.readRatio)! <= 1)) throw new Error("readRatio must be between 0 and 1.");
        ed().setWorkload(patch, "agent-traffic");
        return { traffic: ed().workload, summary: "changed the traffic" };
      },
    },
    {
      name: "set_objectives",
      title: "Set the objectives",
      description: "Set what the design must achieve: p95 and p99 latency (ms), availability (0-1, e.g. 0.999) and minimum throughput (req/s). null removes an objective.",
      inputSchema: {
        type: "object",
        properties: {
          latencyP95Ms: { type: ["number", "null"] },
          latencyP99Ms: { type: ["number", "null"] },
          availability: { type: ["number", "null"], minimum: 0, maximum: 1 },
          minThroughputRps: { type: ["number", "null"] },
          messageAgeP95Ms: { type: ["number", "null"], description: "p95 age of a message when processed, at the slowest queue (ms)" },
        },
      },
      execute: async (input) => {
        const keys = ["latencyP95Ms", "latencyP99Ms", "availability", "minThroughputRps", "messageAgeP95Ms"] as const;
        const patch = Object.fromEntries(keys.filter((k) => input[k] !== undefined).map((k) => [k, input[k]]));
        ed().setObjectives(patch, "agent-objectives");
        return { objectives: ed().objectives, summary: "changed the objectives" };
      },
    },
    {
      name: "review_design",
      title: "Review the design",
      description: "Static design review before simulating: capacity at the peak, timeouts and retries, queues without limits, single points of failure, missing rate limits... Each finding has a severity, the parts involved and a suggestion.",
      inputSchema: { type: "object", properties: {} },
      annotations: { readOnlyHint: true },
      execute: async () => {
        return (await api.analyze(ed().toDocument())).review.map((f) => ({
          severity: f.severity,
          title: f.title,
          detail: f.detail,
          suggestion: f.suggestion,
          parts: f.nodeIds,
        }));
      },
    },
    {
      name: "simulate",
      title: "Simulate the design",
      description:
        "Run the discrete-event simulation of the design as it is now (or one of its scenarios) and wait for the result: verdict against the objectives, throughput, success rate, latency percentiles, failures, findings (bottlenecks and what to do), per-part status and monthly cost. Results also appear on the canvas. Runs locally; typically 5-60 s.",
      inputSchema: {
        type: "object",
        properties: {
          scenarioId: { ...STRING, description: "A scenario id from get_design; omit for the design as drawn." },
          waitSeconds: { type: "number", minimum: 5, maximum: 600, description: "How long to wait (default 180)." },
        },
      },
      execute: async (input) => {
        const s = ed();
        const errors = s.issues.filter((i) => i.severity === "error");
        if (errors.length) throw new Error(`Fix these first: ${errors.map((e) => e.message).join(" | ")}`);
        const scenarioId = typeof input.scenarioId === "string" && input.scenarioId ? input.scenarioId : null;
        if (scenarioId && !s.scenarios.some((x) => x.id === scenarioId)) throw new Error(`No scenario "${scenarioId}".`);
        const run = await useRuns.getState().startRun(s.toDocument(), scenarioId);
        if (!run) throw new Error("The simulation could not start (is the project saved and the API running?).");
        const report = await waitForRun(run.id, (num(input.waitSeconds) ?? 180) * 1000);
        return { ...summarizeReport(report, run.id), summary: `simulated: ${Math.round((report.summary.successRate ?? 0) * 1000) / 10}% succeeded` };
      },
    },
    {
      name: "get_results",
      title: "Read the results",
      description: "Summary of the latest simulation shown in the editor (or of a run by id): verdict, throughput, success rate, latency, failures, findings, per-part status and cost.",
      inputSchema: { type: "object", properties: { runId: STRING } },
      annotations: { readOnlyHint: true },
      execute: async (input) => {
        const r = useRuns.getState();
        const runId = typeof input.runId === "string" && input.runId ? input.runId : r.activeRunId;
        if (!runId) throw new Error("No simulation yet: call simulate.");
        if (!r.reports[runId]) await r.showRun(runId);
        const report = useRuns.getState().reports[runId];
        if (!report) throw new Error(`No results for run "${runId}" (still running or failed).`);
        return summarizeReport(report, runId);
      },
    },
    {
      name: "tidy_layout",
      title: "Tidy the layout",
      description: "Arrange the parts left to right along the request flow so the diagram reads well.",
      inputSchema: { type: "object", properties: {} },
      execute: async () => {
        await ed().autoLayout();
        return { summary: "tidied the layout" };
      },
    },
    {
      name: "undo",
      title: "Undo",
      description: "Undo the last change to the design.",
      inputSchema: { type: "object", properties: {} },
      execute: async () => {
        ed().undo();
        return { summary: "undid the last change" };
      },
    },
    {
      name: "save_design",
      title: "Save the design",
      description: "Save the design to the project (overwrites the saved version; earlier runs keep their own copies).",
      inputSchema: { type: "object", properties: {} },
      annotations: { consequentialHint: true },
      execute: async () => {
        if (!ed().projectId) throw new Error("No project is open.");
        const result = await saveDesign();
        switch (result.status) {
          case "saved":
            return { revision: result.revision, summary: `saved revision ${result.revision}` };
          case "conflict":
            // the choice is the person's: show it to them
            useUi.getState().setSaveConflictOpen(true);
            throw new Error(
              "The project was saved elsewhere since it was opened, so nothing was saved. The editor now asks the user whether to reload that version, overwrite it or save a copy.",
            );
          case "failed":
            throw new Error(result.message);
          case "skipped":
            throw new Error("A save is already on its way; try again in a moment.");
        }
      },
    },
  ];
}


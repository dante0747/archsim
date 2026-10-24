"use client";

import {
  Activity,
  ArrowRight,
  ChevronRight,
  CircleAlert,
  CircleCheck,
  Copy,
  FlaskConical,
  Info,
  LayoutDashboard,
  SlidersHorizontal,
  Trash2,
  TriangleAlert,
} from "lucide-react";
import { useEffect, useMemo } from "react";
import { AssumptionTag, FieldGroups } from "@/components/editor/FieldControl";
import { ScenariosPanel } from "@/components/editor/ScenariosPanel";
import { TechnologyPicker } from "@/components/build/TechnologyPicker";
import { SHAPE_OF, ShapeGlyph } from "@/components/shapes";
import { WorkloadPanel } from "@/components/editor/WorkloadPanel";
import { Button } from "@/components/ui/button";
import { Input, Label, Textarea } from "@/components/ui/input";
import { Badge, Section } from "@/components/ui/misc";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/overlays";
import { describeDistribution } from "@/lib/distributions";
import { compact, fmtMs, fmtNum, fmtPct, fmtRps } from "@/lib/format";
import { componentOf } from "@/lib/graph-rules";
import { NODE_STATUS } from "@/lib/status";
import { KIND_LABEL, REQ_GLYPH, kindOf, type Kind } from "@/lib/theme";
import type { FieldDescriptor, Issue, RequestType } from "@/lib/types";
import { cn } from "@/lib/utils";
import { type RightTab, useEditor } from "@/store/editor";
import { useActiveReport, useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

export function Inspector() {
  const rightTab = useEditor((s) => s.rightTab);
  const setRightTab = useEditor((s) => s.setRightTab);
  const scenarioCount = useEditor((s) => s.scenarios.length);
  const hasSelection = useEditor((s) => s.nodes.some((n) => n.selected) || s.edges.some((e) => e.selected));
  const markTrafficVisited = useUi((s) => s.markTrafficVisited);
  useEffect(() => {
    if (rightTab === "workload") markTrafficVisited();
  }, [rightTab, markTrafficVisited]);
  return (
    <aside className="flex h-full w-[352px] shrink-0 flex-col border-l border-line bg-surface" aria-label="Side panel">
      <Tabs value={rightTab} onValueChange={(v) => setRightTab(v as RightTab)} className="flex min-h-0 flex-1 flex-col">
        <TabsList className="px-2">
          <TabsTrigger value="inspect">
            {hasSelection ? <SlidersHorizontal /> : <LayoutDashboard />} {hasSelection ? "Selected" : "Overview"}
          </TabsTrigger>
          <TabsTrigger value="workload">
            <Activity /> Traffic
          </TabsTrigger>
          <TabsTrigger value="scenarios">
            <FlaskConical /> Scenarios
            {scenarioCount > 0 && <Badge className="ml-0.5 font-mono">{scenarioCount}</Badge>}
          </TabsTrigger>
        </TabsList>
        <TabsContent value="inspect" className="min-h-0 flex-1 overflow-y-auto">
          <SelectionInspector />
        </TabsContent>
        <TabsContent value="workload" className="min-h-0 flex-1 overflow-y-auto">
          <WorkloadPanel />
        </TabsContent>
        <TabsContent value="scenarios" className="min-h-0 flex-1 overflow-y-auto">
          <ScenariosPanel />
        </TabsContent>
      </Tabs>
    </aside>
  );
}

function SelectionInspector() {
  const nodes = useEditor((s) => s.nodes);
  const edges = useEditor((s) => s.edges);
  const selectedNodes = useMemo(() => nodes.filter((n) => n.selected), [nodes]);
  const selectedEdges = useMemo(() => edges.filter((e) => e.selected), [edges]);
  if (selectedNodes.length === 1 && selectedEdges.length === 0) return <NodeInspector nodeId={selectedNodes[0].id} />;
  if (selectedEdges.length === 1 && selectedNodes.length === 0) return <EdgeInspector edgeId={selectedEdges[0].id} />;
  if (selectedNodes.length + selectedEdges.length > 1) {
    return <MultiSelection nodeIds={selectedNodes.map((n) => n.id)} edgeIds={selectedEdges.map((e) => e.id)} />;
  }
  return <DesignOverview />;
}

// ---------------------------------------------------------------------------------------------
// Overview (nothing selected)
// ---------------------------------------------------------------------------------------------

export function TrafficCard({ compactCard = false }: { compactCard?: boolean }) {
  const w = useEditor((s) => s.workload);
  const setRightTab = useEditor((s) => s.setRightTab);
  const pattern =
    w.pattern.kind === "constant"
      ? "steady"
      : w.pattern.kind === "ramp"
        ? `ramp ${compact(w.pattern.startRps)}→${compact(w.pattern.endRps)}/s`
        : `burst to ${compact(w.pattern.burstRps)}/s`;
  return (
    <button
      type="button"
      onClick={() => setRightTab("workload")}
      className="group w-full rounded-md border border-line bg-surface-2 p-3 text-left transition-colors hover:border-line-strong"
    >
      <div className="flex items-center justify-between">
        <span className="microlabel">Traffic</span>
        <span className="flex items-center gap-0.5 text-[11px] text-ink-2 group-hover:text-ink">
          Edit <ChevronRight className="size-3.5" />
        </span>
      </div>
      <p className="mt-1 font-mono text-2xl font-semibold tracking-tight text-ink">
        {w.pattern.kind === "ramp" ? `${compact(w.pattern.startRps)}–${compact(w.pattern.endRps)}` : compact(w.rps)}
        <span className="ml-1 text-sm font-normal text-ink-3">req/s</span>
      </p>
      {!compactCard && (
        <>
          <div className="mt-2 flex h-1.5 gap-px overflow-hidden rounded-[1px]" aria-hidden>
            <span style={{ width: `${w.readRatio * 100}%`, background: "var(--ink-2)" }} />
            <span style={{ width: `${(1 - w.readRatio) * 100}%`, background: "var(--line-strong)" }} />
          </div>
          <p className="mt-1.5 font-mono text-[11px] text-ink-2">
            {REQ_GLYPH.read} {Math.round(w.readRatio * 100)}% · {REQ_GLYPH.write} {Math.round((1 - w.readRatio) * 100)}% · {pattern} · {compact(w.durationS)} s
          </p>
        </>
      )}
    </button>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-md border border-line px-3 py-2">
      <p className="microlabel !text-[9px]">{label}</p>
      <p className="font-mono text-lg font-semibold text-ink">{value}</p>
    </div>
  );
}

function DesignOverview() {
  const metadata = useEditor((s) => s.metadata);
  const nodeCount = useEditor((s) => s.nodes.length);
  const edgeCount = useEditor((s) => s.edges.length);
  const scenarioCount = useEditor((s) => s.scenarios.length);
  const kinds: Kind[] = ["traffic", "network", "compute", "cache", "database", "messaging", "external"];
  const roles: Record<string, string> = {
    traffic: "generates requests",
    network: "routes and rate-limits them",
    compute: "processes them",
    cache: "serves hot reads",
    database: "stores the data",
    messaging: "buffers async work",
    external: "third-party dependency",
  };
  return (
    <div className="space-y-5 p-4">
      <div>
        <p className="microlabel">Design</p>
        <h2 className="mt-1 text-[15px] font-semibold leading-snug text-ink">{metadata.name || "Untitled architecture"}</h2>
        {metadata.description && <p className="mt-1.5 line-clamp-5 text-xs leading-relaxed text-ink-2">{metadata.description}</p>}
      </div>
      <div className="grid grid-cols-3 gap-2">
        <Stat label="Components" value={nodeCount} />
        <Stat label="Links" value={edgeCount} />
        <Stat label="Scenarios" value={scenarioCount} />
      </div>
      <TrafficCard />
      <ProblemsList />
      <Section title="Reading the canvas">
        <ul className="space-y-1.5 text-xs text-ink-2">
          {kinds.map((k) => (
            <li key={k} className="flex items-center gap-2">
              <ShapeGlyph shape={SHAPE_OF[k]} className="shrink-0 text-ink-2" w={24} h={16} />
              <span className="font-mono text-[11px] text-ink">{KIND_LABEL[k]}</span>
              <span className="text-ink-3">
                — {roles[k]}
              </span>
            </li>
          ))}
          <li className="flex items-center gap-2 pt-1 font-mono text-[11px]">
            <span className="w-6 text-center text-accent">{REQ_GLYPH.read}</span> read
            <span className="ml-2 text-accent">{REQ_GLYPH.write}</span> write
            <span className="text-ink-3">· dots on links show live traffic</span>
          </li>
          <li className="flex items-center gap-2">
            <svg width="24" height="6" className="shrink-0" aria-hidden>
              <line x1="0" y1="3" x2="24" y2="3" stroke="var(--wire)" strokeWidth="1.5" strokeDasharray="5 3" />
            </svg>
            Dashed link = cache-miss path
          </li>
          <li className="flex flex-wrap items-center gap-x-2.5 gap-y-1 font-mono text-[11px]">
            <span className="w-6" aria-hidden />
            {(["ok", "warm", "hot", "saturated"] as const).map((st) => (
              <span key={st} className="flex items-center gap-1" title={NODE_STATUS[st].description}>
                <span className="size-1.5 rounded-full" style={{ background: NODE_STATUS[st].color }} aria-hidden />
                {NODE_STATUS[st].label.toLowerCase()}
              </span>
            ))}
            <span className="text-ink-3">· after a run</span>
          </li>
          <li className="flex items-center gap-2">
            <AssumptionTag /> marks values you assume rather than measure
          </li>
        </ul>
      </Section>
      <p className="flex gap-2 rounded-md border border-dashed border-line-strong p-3 text-[11.5px] leading-relaxed text-ink-2">
        <Info className="mt-0.5 size-3.5 shrink-0 text-ink-3" />
        Click a component or link on the canvas to edit it. Its most important settings come first. Press Ctrl K to search
        every command.
      </p>
    </div>
  );
}

export function IssueRow({ issue, onSelect }: { issue: Issue; onSelect?: () => void }) {
  const Icon = issue.severity === "error" ? CircleAlert : TriangleAlert;
  return (
    <li>
      <button
        type="button"
        onClick={onSelect}
        disabled={!onSelect}
        className="flex w-full gap-2 rounded-sm p-2 text-left hover:bg-surface-2 disabled:hover:bg-transparent"
      >
        <Icon
          className="mt-0.5 size-4 shrink-0"
          style={{ color: issue.severity === "error" ? "var(--status-critical)" : "var(--status-warning)" }}
          aria-label={issue.severity}
        />
        <span className="min-w-0">
          <span className="block text-xs text-ink">{issue.message}</span>
          {issue.hint && <span className="mt-0.5 block text-[11px] text-ink-3">{issue.hint}</span>}
        </span>
      </button>
    </li>
  );
}

export function ProblemsList() {
  const issues = useEditor((s) => s.issues);
  const validation = useEditor((s) => s.validation);
  const select = useEditor((s) => s.select);
  const errors = issues.filter((i) => i.severity === "error").length;
  return (
    <Section
      title={`Checks${issues.length ? ` · ${issues.length}` : ""}`}
      actions={validation === "pending" ? <span className="font-mono text-[10px] text-ink-3">checking…</span> : null}
    >
      {issues.length === 0 ? (
        <p className="flex items-center gap-2 rounded-md border border-line px-3 py-2 font-mono text-[11.5px] text-ink">
          <CircleCheck className="size-4" style={{ color: "var(--status-good)" }} /> valid model · ready to simulate
        </p>
      ) : (
        <div className="rounded-md border border-line">
          {errors > 0 && <p className="border-b border-line px-3 py-2 text-[11.5px] text-ink-2">Fix errors before simulating. Warnings do not block a run.</p>}
          <ul className="p-1">
            {issues.map((i, k) => (
              <IssueRow key={`${i.code}-${k}`} issue={i} onSelect={i.nodeId || i.edgeId ? () => select({ nodeId: i.nodeId, edgeId: i.edgeId }) : undefined} />
            ))}
          </ul>
        </div>
      )}
    </Section>
  );
}

// ---------------------------------------------------------------------------------------------
// Node
// ---------------------------------------------------------------------------------------------

function SettingsBlocks({
  fields,
  values,
  onChange,
  idPrefix,
  allowedRequestTypes,
  extraAdvanced,
}: {
  fields: FieldDescriptor[];
  values: Record<string, unknown>;
  onChange: (key: string, v: unknown) => void;
  idPrefix: string;
  allowedRequestTypes?: RequestType[];
  extraAdvanced?: React.ReactNode;
}) {
  const key = fields.filter((f) => f.headline);
  const rest = fields.filter((f) => !f.headline);
  return (
    <>
      {key.length > 0 && (
        <Section title="Key settings">
          <FieldGroups fields={key} values={values} onChange={onChange} idPrefix={idPrefix} allowedRequestTypes={allowedRequestTypes} flat />
        </Section>
      )}
      {(rest.length > 0 || extraAdvanced) && (
        <details className="group rounded-md border border-line" open={key.length === 0}>
          <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2.5 font-mono text-[11px] uppercase tracking-wider text-ink-2 hover:text-ink">
            <ChevronRight className="size-3.5 transition-transform group-open:rotate-90" />
            Advanced settings
            <span className="font-mono text-[10px] text-ink-3">{rest.length}</span>
          </summary>
          <div className="space-y-5 border-t border-line px-3.5 py-4">
            <FieldGroups fields={rest} values={values} onChange={onChange} idPrefix={`${idPrefix}-adv`} allowedRequestTypes={allowedRequestTypes} />
            {extraAdvanced}
          </div>
        </details>
      )}
    </>
  );
}

function NodeInspector({ nodeId }: { nodeId: string }) {
  const node = useEditor((s) => s.nodes.find((n) => n.id === nodeId));
  const catalog = useEditor((s) => s.catalog);
  const issues = useEditor((s) => s.issues);
  const setNodeConfig = useEditor((s) => s.setNodeConfig);
  const updateNode = useEditor((s) => s.updateNode);
  const deleteElements = useEditor((s) => s.deleteElements);
  const duplicate = useEditor((s) => s.duplicateSelection);
  if (!node) return null;
  const comp = componentOf(catalog, node.data.type);
  const kind = kindOf(node.data.type, comp?.category);
  const nodeIssues = issues.filter((i) => i.nodeId === nodeId);
  return (
    <div className="space-y-5 p-4">
      <div className="rounded-md border border-line bg-surface-2 p-3">
        <div className="flex items-center gap-3">
          <span className="flex size-12 shrink-0 items-center justify-center rounded-md border border-line bg-surface text-ink-2">
            <ShapeGlyph shape={SHAPE_OF[kind]} w={34} h={24} layers={typeof node.data.config.replicas === "number" ? node.data.config.replicas : 2} />
          </span>
          <div className="min-w-0 flex-1">
            <p className="microlabel">{comp?.displayName ?? node.data.type}</p>
            <input
              value={node.data.label}
              maxLength={120}
              aria-label="Component name"
              onChange={(e) => updateNode(nodeId, { label: e.target.value })}
              className="-ml-1 mt-0.5 h-8 w-full rounded-sm border border-transparent bg-transparent px-1 font-mono text-[14px] font-semibold text-ink hover:border-line focus-visible:border-accent focus-visible:bg-surface focus-visible:outline-none"
            />
          </div>
        </div>
        {comp?.description && <p className="mt-2 text-[11.5px] leading-relaxed text-ink-2">{comp.description}</p>}
        <TechnologyPicker nodeId={nodeId} comp={comp} config={node.data.config} />
      </div>

      {nodeIssues.length > 0 && (
        <ul className="rounded-md border border-line p-1">
          {nodeIssues.map((i, k) => (
            <IssueRow key={k} issue={i} />
          ))}
        </ul>
      )}

      {comp?.isTrafficSource && <TrafficCard />}

      {comp ? (
        <SettingsBlocks
          fields={comp.fields}
          values={node.data.config}
          onChange={(key, v) => setNodeConfig(nodeId, key, v)}
          idPrefix={`node-${nodeId}`}
          extraAdvanced={
            <div className="space-y-1.5">
              <Label htmlFor="node-desc">Notes</Label>
              <Textarea id="node-desc" rows={2} value={node.data.description ?? ""} placeholder="Optional description" onChange={(e) => updateNode(nodeId, { description: e.target.value || null })} />
              <p className="font-mono text-[10px] text-ink-3">id {node.id}</p>
            </div>
          }
        />
      ) : (
        <p className="text-xs text-danger-ink">Unknown component type “{node.data.type}”.</p>
      )}

      <NodeResults nodeId={nodeId} />

      {comp && (comp.modeled.length > 0 || comp.notModeled.length > 0) && (
        <details className="group rounded-md border border-line text-xs">
          <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2.5 font-medium text-ink-2 hover:text-ink">
            <ChevronRight className="size-3.5 transition-transform group-open:rotate-90" /> What this model does and doesn&apos;t include
          </summary>
          <div className="grid gap-3 border-t border-line px-3.5 py-3 text-ink-2">
            <div>
              <p className="microlabel mb-1">Modeled</p>
              <ul className="list-disc space-y-1 pl-4">
                {comp.modeled.map((m) => (
                  <li key={m}>{m}</li>
                ))}
              </ul>
            </div>
            <div>
              <p className="microlabel mb-1">Not modeled</p>
              <ul className="list-disc space-y-1 pl-4">
                {comp.notModeled.map((m) => (
                  <li key={m}>{m}</li>
                ))}
              </ul>
            </div>
          </div>
        </details>
      )}

      <div className="flex gap-2">
        <Button size="sm" onClick={duplicate}>
          <Copy /> Duplicate
        </Button>
        <Button variant="danger" size="sm" onClick={() => deleteElements([nodeId], [])}>
          <Trash2 /> Delete
        </Button>
      </div>
    </div>
  );
}

function NodeResults({ nodeId }: { nodeId: string }) {
  const report = useActiveReport();
  const openDrawer = useRuns((s) => s.openDrawer);
  const focusNode = useRuns((s) => s.focusNode);
  const nr = report?.nodes.find((n) => n.id === nodeId);
  if (!report || !nr) return null;
  const s = nr.summary;
  const meta = NODE_STATUS[nr.status];
  const tiles: [string, string][] = [
    ["Arrivals", fmtRps(s.arrivalRps)],
    ["Busy avg/peak", `${fmtPct(s.utilization, 0)} / ${fmtPct(s.peakUtilization, 0)}`],
    ["Queue avg/peak", `${fmtNum(s.meanQueue, 1)} / ${fmtNum(s.peakQueue)}`],
    ["Wait p95", fmtMs(s.p95QueueWaitMs)],
    ["Rejected", `${fmtNum(s.rejectedTotal)}`],
    s.hitRatio != null ? ["Hit ratio", fmtPct(s.hitRatio)] : ["Capacity est.", fmtRps(s.capacityRps)],
  ];
  return (
    <Section
      title={
        <span className="flex items-center gap-1.5">
          Measured in last run
        </span>
      }
    >
      <div className="overflow-hidden rounded-md border border-line">
        <div className="flex items-center gap-2 border-l-2 bg-surface-2 px-3 py-2.5" style={{ borderLeftColor: meta.color }}>
          <span className="size-1.5 rounded-full" style={{ background: meta.color }} aria-hidden />
          <span className="font-mono text-[11px] font-semibold uppercase tracking-wider text-ink">{meta.label}</span>
          <span className="truncate text-[11px] text-ink-2">{nr.statusReasons.join(", ") || meta.description}</span>
        </div>
        <dl className="grid grid-cols-2 gap-px bg-line">
          {tiles.map(([k, v]) => (
            <div key={k} className="bg-surface px-3 py-2">
              <dt className="microlabel !text-[9px]">{k}</dt>
              <dd className="tabular font-mono text-[12.5px] font-semibold text-ink">{v}</dd>
            </div>
          ))}
        </dl>
        {s.cpuUtilization != null && s.utilization ? (
          <p className="border-t border-line px-3.5 py-2 text-[11px] text-ink-2">
            {fmtPct(s.cpuUtilization / s.utilization, 0)} of busy worker time was its own processing; the rest was waiting on downstream calls.
          </p>
        ) : null}
      </div>
      <Button
        size="sm"
        variant="ghost"
        onClick={() => {
          focusNode(nodeId);
          openDrawer("components");
        }}
      >
        Charts for this component <ArrowRight />
      </Button>
    </Section>
  );
}

// ---------------------------------------------------------------------------------------------
// Edge
// ---------------------------------------------------------------------------------------------

function EdgeInspector({ edgeId }: { edgeId: string }) {
  const edge = useEditor((s) => s.edges.find((e) => e.id === edgeId));
  const nodes = useEditor((s) => s.nodes);
  const catalog = useEditor((s) => s.catalog);
  const issues = useEditor((s) => s.issues);
  const setEdgeConfig = useEditor((s) => s.setEdgeConfig);
  const setEdgeLabel = useEditor((s) => s.setEdgeLabel);
  const deleteElements = useEditor((s) => s.deleteElements);
  const report = useActiveReport();
  if (!edge || !edge.data) return null;
  const src = nodes.find((n) => n.id === edge.source);
  const tgt = nodes.find((n) => n.id === edge.target);
  const srcComp = src ? componentOf(catalog, src.data.type) : undefined;
  const tgtComp = tgt ? componentOf(catalog, tgt.data.type) : undefined;
  const sp = srcComp?.ports.find((p) => p.name === (edge.sourceHandle ?? "out"));
  const tp = tgtComp?.ports.find((p) => p.name === (edge.targetHandle ?? "in"));
  const allowed = (sp?.requestTypes ?? ["read", "write"]).filter((t) => (tp?.requestTypes ?? ["read", "write"]).includes(t)) as RequestType[];
  const fromSource = srcComp?.isTrafficSource;
  const fields = (catalog?.edgeFields ?? [])
    .filter((f) => !(fromSource && ["timeoutMs", "maxAttempts", "retryBackoffMs", "order"].includes(f.key)))
    .map((f) => ({ ...f, headline: f.key === "requestTypes" || f.key === "latencyMs" }));
  const er = report?.edges.find((e) => e.id === edgeId);
  const edgeIssues = issues.filter((i) => i.edgeId === edgeId);
  const chip = (label: string, type: string | undefined, category: string | undefined) => {
    const k = kindOf(type ?? "", category);
    return (
      <span className="inline-flex max-w-[140px] items-center gap-1.5 truncate rounded-sm border border-line bg-surface px-1.5 py-1 font-mono text-[11px] text-ink">
        <ShapeGlyph shape={SHAPE_OF[k]} w={18} h={12} className="shrink-0 text-ink-2" />
        <span className="truncate">{label}</span>
      </span>
    );
  };
  return (
    <div className="space-y-5 p-4">
      <div className="rounded-md border border-line bg-surface-2 p-3">
        <p className="microlabel">{sp && sp.name !== "out" ? `${sp.label} link` : "Link"}</p>
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          {chip(src?.data.label ?? edge.source, src?.data.type, srcComp?.category)}
          <ArrowRight className="size-3.5 text-ink-3" />
          {chip(tgt?.data.label ?? edge.target, tgt?.data.type, tgtComp?.category)}
        </div>
        <p className="mt-2 text-[11.5px] leading-relaxed text-ink-2">
          Requests of the selected types travel this link. Latency is added in each direction
          {edge.data.config.latencyMs ? ` (${describeDistribution(edge.data.config.latencyMs)})` : ""}.
        </p>
      </div>
      {edgeIssues.length > 0 && (
        <ul className="rounded-md border border-line p-1">
          {edgeIssues.map((i, k) => (
            <IssueRow key={k} issue={i} />
          ))}
        </ul>
      )}
      {fromSource && (
        <p className="flex gap-2 rounded-md border border-dashed border-line-strong p-3 text-[11.5px] leading-relaxed text-ink-2">
          <Info className="mt-0.5 size-3.5 shrink-0 text-ink-3" />
          Client timeout and retries are set on the Clients component.
        </p>
      )}
      <SettingsBlocks
        fields={fields}
        values={edge.data.config as unknown as Record<string, unknown>}
        onChange={(key, v) => setEdgeConfig(edgeId, key, v)}
        idPrefix={`edge-${edgeId}`}
        allowedRequestTypes={allowed}
        extraAdvanced={
          <div className="space-y-1.5">
            <Label htmlFor="edge-label">Label on canvas</Label>
            <Input id="edge-label" value={edge.data.label ?? ""} placeholder="Optional, e.g. HTTPS" maxLength={120} onChange={(e) => setEdgeLabel(edgeId, e.target.value || null)} />
          </div>
        }
      />
      {er && (
        <Section title="Measured in last run">
          <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-md border border-line bg-line">
            {(
              [
                ["Calls", fmtRps(er.callRps)],
                ["Retries", fmtNum(er.retries)],
                ["Timeouts", fmtNum(er.timeouts)],
                ["Failed calls", fmtNum(er.failures)],
              ] as [string, string][]
            ).map(([k, v]) => (
              <div key={k} className="bg-surface px-3 py-2">
                <dt className="microlabel !text-[9px]">{k}</dt>
                <dd className={cn("tabular font-mono text-[12.5px] font-semibold text-ink")}>{v}</dd>
              </div>
            ))}
          </dl>
        </Section>
      )}
      <Button variant="danger" size="sm" onClick={() => deleteElements([], [edgeId])}>
        <Trash2 /> Delete link
      </Button>
    </div>
  );
}

function MultiSelection({ nodeIds, edgeIds }: { nodeIds: string[]; edgeIds: string[] }) {
  const deleteElements = useEditor((s) => s.deleteElements);
  const duplicate = useEditor((s) => s.duplicateSelection);
  return (
    <div className="space-y-3 p-4">
      <p className="microlabel">Selection</p>
      <p className="text-sm font-medium text-ink">
        {nodeIds.length} component{nodeIds.length === 1 ? "" : "s"} and {edgeIds.length} link{edgeIds.length === 1 ? "" : "s"}
      </p>
      <div className="flex gap-2">
        {nodeIds.length > 0 && (
          <Button size="sm" onClick={duplicate}>
            <Copy /> Duplicate
          </Button>
        )}
        <Button size="sm" variant="danger" onClick={() => deleteElements(nodeIds, edgeIds)}>
          <Trash2 /> Delete selection
        </Button>
      </div>
    </div>
  );
}

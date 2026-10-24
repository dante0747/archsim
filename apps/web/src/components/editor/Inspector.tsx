"use client";

import { ArrowRight, CircleAlert, CircleCheck, FlaskConical, Info, MousePointer2, SlidersHorizontal, Trash2, TriangleAlert } from "lucide-react";
import { useMemo } from "react";
import { FieldGroups } from "@/components/editor/FieldControl";
import { ComponentIcon } from "@/components/editor/icons";
import { ScenariosPanel } from "@/components/editor/ScenariosPanel";
import { WorkloadPanel } from "@/components/editor/WorkloadPanel";
import { Button } from "@/components/ui/button";
import { Input, Label, Textarea } from "@/components/ui/input";
import { Badge, Section } from "@/components/ui/misc";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/overlays";
import { fmtMs, fmtNum, fmtPct, fmtRps } from "@/lib/format";
import { componentOf } from "@/lib/graph-rules";
import { NODE_STATUS } from "@/lib/status";
import type { Issue, RequestType } from "@/lib/types";
import { cn } from "@/lib/utils";
import { type RightTab, useEditor } from "@/store/editor";
import { useActiveReport, useRuns } from "@/store/runs";

export function Inspector() {
  const rightTab = useEditor((s) => s.rightTab);
  const setRightTab = useEditor((s) => s.setRightTab);
  const scenarioCount = useEditor((s) => s.scenarios.length);
  return (
    <aside className="flex h-full w-[340px] shrink-0 flex-col border-l border-line bg-surface" aria-label="Inspector">
      <Tabs value={rightTab} onValueChange={(v) => setRightTab(v as RightTab)} className="flex min-h-0 flex-1 flex-col">
        <TabsList className="px-2">
          <TabsTrigger value="inspect">
            <SlidersHorizontal /> Inspect
          </TabsTrigger>
          <TabsTrigger value="workload">
            <ArrowRight /> Workload
          </TabsTrigger>
          <TabsTrigger value="scenarios">
            <FlaskConical /> Scenarios
            {scenarioCount > 0 && <Badge className="ml-0.5">{scenarioCount}</Badge>}
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
  return (
    <div className="space-y-5 p-4">
      <div className="flex items-start gap-2 rounded-lg bg-surface-2 p-3 text-xs leading-relaxed text-ink-2">
        <MousePointer2 className="mt-0.5 size-4 shrink-0 text-ink-3" />
        <p>Select a component or link to edit its settings. Values tagged <b>assumption</b> are your estimates, not measurements.</p>
      </div>
      <ProblemsList />
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
        className="flex w-full gap-2 rounded-md p-2 text-left hover:bg-surface-2 disabled:hover:bg-transparent"
      >
        <Icon
          className="mt-0.5 size-4 shrink-0"
          style={{ color: issue.severity === "error" ? "var(--status-critical)" : "var(--status-serious)" }}
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
      title={`Problems${issues.length ? ` (${issues.length})` : ""}`}
      actions={validation === "pending" ? <span className="text-[11px] text-ink-3">checking…</span> : null}
    >
      {issues.length === 0 ? (
        <p className="flex items-center gap-2 text-xs text-ink-2">
          <CircleCheck className="size-4" style={{ color: "var(--status-good)" }} /> The model is valid and ready to simulate.
        </p>
      ) : (
        <>
          {errors > 0 && <p className="text-xs text-ink-2">Fix the errors below before simulating. Warnings do not block a run.</p>}
          <ul className="-mx-2">
            {issues.map((i, k) => (
              <IssueRow
                key={`${i.code}-${k}`}
                issue={i}
                onSelect={i.nodeId || i.edgeId ? () => select({ nodeId: i.nodeId, edgeId: i.edgeId }) : undefined}
              />
            ))}
          </ul>
        </>
      )}
    </Section>
  );
}

function NodeInspector({ nodeId }: { nodeId: string }) {
  const node = useEditor((s) => s.nodes.find((n) => n.id === nodeId));
  const catalog = useEditor((s) => s.catalog);
  const issues = useEditor((s) => s.issues);
  const setNodeConfig = useEditor((s) => s.setNodeConfig);
  const updateNode = useEditor((s) => s.updateNode);
  const deleteElements = useEditor((s) => s.deleteElements);
  if (!node) return null;
  const comp = componentOf(catalog, node.data.type);
  const nodeIssues = issues.filter((i) => i.nodeId === nodeId);
  return (
    <div className="space-y-5 p-4">
      <div className="flex items-start gap-3">
        <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-surface-2 text-ink-2">
          <ComponentIcon name={comp?.icon} className="size-4" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-medium uppercase tracking-wide text-ink-3">{comp?.displayName ?? node.data.type}</p>
          <p className="text-xs leading-relaxed text-ink-2">{comp?.description}</p>
        </div>
      </div>

      <div className="space-y-3">
        <div className="space-y-1.5">
          <Label htmlFor="node-label">Name</Label>
          <Input
            id="node-label"
            value={node.data.label}
            maxLength={120}
            onChange={(e) => updateNode(nodeId, { label: e.target.value })}
          />
          <p className="text-[11px] text-ink-3">
            id <code className="font-mono">{node.id}</code>
          </p>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="node-desc">Notes</Label>
          <Textarea
            id="node-desc"
            rows={2}
            value={node.data.description ?? ""}
            placeholder="Optional description"
            onChange={(e) => updateNode(nodeId, { description: e.target.value || null })}
          />
        </div>
      </div>

      {nodeIssues.length > 0 && (
        <ul className="-mx-2 rounded-lg border border-line">
          {nodeIssues.map((i, k) => (
            <IssueRow key={k} issue={i} />
          ))}
        </ul>
      )}

      {comp ? (
        <FieldGroups
          fields={comp.fields}
          values={node.data.config}
          onChange={(key, v) => setNodeConfig(nodeId, key, v)}
          idPrefix={`node-${nodeId}`}
        />
      ) : (
        <p className="text-xs text-danger-ink">Unknown component type “{node.data.type}”.</p>
      )}

      <NodeResults nodeId={nodeId} />

      {comp && (comp.modeled.length > 0 || comp.notModeled.length > 0) && (
        <details className="rounded-lg border border-line p-3 text-xs">
          <summary className="cursor-pointer font-medium text-ink">What this model includes</summary>
          <div className="mt-2 space-y-2 text-ink-2">
            <ul className="list-disc space-y-1 pl-4">
              {comp.modeled.map((m) => (
                <li key={m}>{m}</li>
              ))}
            </ul>
            <p className="font-medium text-ink">Not modeled</p>
            <ul className="list-disc space-y-1 pl-4">
              {comp.notModeled.map((m) => (
                <li key={m}>{m}</li>
              ))}
            </ul>
          </div>
        </details>
      )}

      <Button variant="danger" size="sm" onClick={() => deleteElements([nodeId], [])}>
        <Trash2 /> Delete component
      </Button>
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
  const rows: [string, string][] = [
    ["Arrivals", fmtRps(s.arrivalRps)],
    ["Utilization (avg / peak)", `${fmtPct(s.utilization, 0)} / ${fmtPct(s.peakUtilization, 0)}`],
    ...(s.cpuUtilization != null ? ([["Own processing share", fmtPct(s.utilization ? s.cpuUtilization / s.utilization : null, 0)]] as [string, string][]) : []),
    ["Queue (mean / peak)", `${fmtNum(s.meanQueue, 1)} / ${fmtNum(s.peakQueue)}`],
    ["Queue wait p95", fmtMs(s.p95QueueWaitMs)],
    ["Rejected", `${fmtNum(s.rejectedTotal)} (${fmtPct(s.rejectionRate)})`],
    ...(s.hitRatio != null ? ([["Hit ratio", fmtPct(s.hitRatio)]] as [string, string][]) : []),
    ...(s.capacityRps != null ? ([["Est. capacity at observed hold time", fmtRps(s.capacityRps)]] as [string, string][]) : []),
  ];
  return (
    <Section
      title={
        <span className="flex items-center gap-1.5">
          Simulation-derived <Badge tone="accent">last run</Badge>
        </span>
      }
    >
      <div className="rounded-lg border border-line">
        <div className="flex items-center gap-2 border-b border-line px-3 py-2">
          <span className="size-2 rounded-full" style={{ background: meta.color }} aria-hidden />
          <span className="text-xs font-medium text-ink">{meta.label}</span>
          <span className="truncate text-[11px] text-ink-3">{nr.statusReasons.join(", ") || meta.description}</span>
        </div>
        <dl className="divide-y divide-line text-xs">
          {rows.map(([k, v]) => (
            <div key={k} className="flex justify-between gap-2 px-3 py-1.5">
              <dt className="text-ink-2">{k}</dt>
              <dd className="tabular text-ink">{v}</dd>
            </div>
          ))}
        </dl>
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
  const fields = (catalog?.edgeFields ?? []).filter(
    (f) => !(fromSource && ["timeoutMs", "maxAttempts", "retryBackoffMs", "order"].includes(f.key)),
  );
  const er = report?.edges.find((e) => e.id === edgeId);
  const edgeIssues = issues.filter((i) => i.edgeId === edgeId);
  return (
    <div className="space-y-5 p-4">
      <div>
        <p className="text-[11px] font-medium uppercase tracking-wide text-ink-3">Link</p>
        <p className="mt-1 flex flex-wrap items-center gap-1.5 text-sm font-medium text-ink">
          {src?.data.label ?? edge.source}
          {sp && sp.name !== "out" && <Badge>{sp.label}</Badge>}
          <ArrowRight className="size-3.5 text-ink-3" />
          {tgt?.data.label ?? edge.target}
        </p>
        <p className="mt-1 text-xs leading-relaxed text-ink-2">
          {sp?.description} Requests matching the selected types travel this link; latency applies in each direction.
        </p>
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="edge-label">Label</Label>
        <Input
          id="edge-label"
          value={edge.data.label ?? ""}
          placeholder="Optional, e.g. HTTPS"
          maxLength={120}
          onChange={(e) => setEdgeLabel(edgeId, e.target.value || null)}
        />
      </div>
      {edgeIssues.length > 0 && (
        <ul className="-mx-2 rounded-lg border border-line">
          {edgeIssues.map((i, k) => (
            <IssueRow key={k} issue={i} />
          ))}
        </ul>
      )}
      {fromSource && (
        <p className="flex gap-2 rounded-lg bg-surface-2 p-2.5 text-[11px] leading-relaxed text-ink-2">
          <Info className="mt-0.5 size-3.5 shrink-0 text-ink-3" />
          Client timeout and retries are configured on the traffic source itself.
        </p>
      )}
      <FieldGroups
        fields={fields}
        values={edge.data.config as unknown as Record<string, unknown>}
        onChange={(key, v) => setEdgeConfig(edgeId, key, v)}
        idPrefix={`edge-${edgeId}`}
        allowedRequestTypes={allowed}
      />
      {er && (
        <Section title="Simulation-derived">
          <dl className="divide-y divide-line rounded-lg border border-line text-xs">
            {(
              [
                ["Calls", fmtRps(er.callRps)],
                ["Retries", fmtNum(er.retries)],
                ["Timeouts", fmtNum(er.timeouts)],
                ["Failed calls", fmtNum(er.failures)],
              ] as [string, string][]
            ).map(([k, v]) => (
              <div key={k} className="flex justify-between px-3 py-1.5">
                <dt className="text-ink-2">{k}</dt>
                <dd className={cn("tabular text-ink")}>{v}</dd>
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
      <p className="text-sm font-medium text-ink">
        {nodeIds.length} components and {edgeIds.length} links selected
      </p>
      <div className="flex gap-2">
        {nodeIds.length > 0 && (
          <Button size="sm" onClick={duplicate}>
            Duplicate
          </Button>
        )}
        <Button size="sm" variant="danger" onClick={() => deleteElements(nodeIds, edgeIds)}>
          <Trash2 /> Delete selection
        </Button>
      </div>
    </div>
  );
}

"use client";

import {
  Activity,
  ArrowRight,
  ChevronRight,
  CircleAlert,
  CircleCheck,
  Copy,
  FlaskConical,
  LayoutDashboard,
  SlidersHorizontal,
  Trash2,
  TriangleAlert,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { CLIENT_BLOCK_FIELDS, ClientBlock } from "@/components/editor/ClientBlock";
import { type AutoHint, FieldGroups, isFieldVisible } from "@/components/editor/FieldControl";
import { PricingCard } from "@/components/editor/PricingCard";
import { RESOURCE_FIELDS, ResourceBlock } from "@/components/editor/ResourceBlock";
import { SiblingSpeedCard } from "@/components/editor/SiblingSpeedCard";
import { TechIcon } from "@/components/TechIcon";
import { ScenariosPanel } from "@/components/editor/ScenariosPanel";
import { TechnologyPicker } from "@/components/build/TechnologyPicker";
import { openDecide } from "@/components/editor/workflow";
import { FormulaCard } from "@/components/explain/FormulaCard";
import { TechTraits } from "@/components/explain/TechTraits";
import { WorkloadPanel } from "@/components/editor/WorkloadPanel";
import { CommentsSection } from "@/components/team/CommentsSection";
import { Button } from "@/components/ui/button";
import { Input, Label, Textarea } from "@/components/ui/input";
import { Badge, Section, Tip } from "@/components/ui/misc";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/overlays";
import { compact, fmtMs, fmtNum, fmtPct, fmtRps } from "@/lib/format";
import { edgeSourcePort, edgeTargetPort, stillMeasured } from "@/lib/document";
import { componentOf } from "@/lib/graph-rules";
import { NODE_STATUS } from "@/lib/status";
import { defaultSource } from "@/lib/tier";
import { KIND_LABEL, REQ_GLYPH, kindOf, technologyLabel, type Kind } from "@/lib/theme";
import type { DocExtras } from "@/lib/document";
import type { ClassifiedProvenance, ComponentType, FieldDescriptor, InputEntry, InputProvenance, Issue, NodeConfig, RequestType, SizingEntry } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useDecide } from "@/store/decide";
import { type RightTab, useEditor } from "@/store/editor";
import { useActiveReport, useRuns } from "@/store/runs";
import { countedVcpus, useAutoSize } from "@/store/sizes";
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
    <aside data-tour="panel" className="island flex h-full w-[352px] shrink-0 flex-col border-l border-line" aria-label="Side panel">
      <Tabs value={rightTab} onValueChange={(v) => setRightTab(v as RightTab)} className="flex min-h-0 flex-1 flex-col">
        <TabsList>
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
        : w.pattern.kind === "trace"
          ? `replayed traffic (${w.pattern.points.length} points)`
          : `burst to ${compact(w.pattern.burstRps)}/s`;
  return (
    <button
      type="button"
      onClick={() => setRightTab("workload")}
      className="group w-full rounded-xl bg-surface-2 p-3.5 text-left transition-colors hover:bg-surface-3"
    >
      <div className="flex items-center justify-between">
        <span className="microlabel">Traffic</span>
        <span className="flex items-center gap-0.5 text-xs text-ink-2 group-hover:text-ink">
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
          <p className="mt-1.5 font-mono text-xs text-ink-2">
            {REQ_GLYPH.read} {Math.round(w.readRatio * 100)}% · {REQ_GLYPH.write} {Math.round((1 - w.readRatio) * 100)}% · {pattern} · {compact(w.durationS)} s
          </p>
        </>
      )}
    </button>
  );
}


function DesignOverview() {
  const kinds: Kind[] = ["traffic", "network", "compute", "cache", "database", "messaging", "external"];
  return (
    <div className="space-y-4 p-4">
      <TrafficCard />
      <PricingCard />
      <SiblingSpeedCard />
      <ProblemsList />
      <CommentsSection anchorKind="project" />
      <details className="group rounded-xl bg-surface-2/60">
        <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2 text-[13px] font-medium text-ink-2 hover:text-ink [&::-webkit-details-marker]:hidden">
          <ChevronRight className="size-3.5 shrink-0 transition-transform group-open:rotate-90" aria-hidden />
          Legend
        </summary>
        <ul className="space-y-1.5 px-3 pb-3 text-xs text-ink-2">
          {kinds.map((k) => (
            <li key={k} className="flex items-center gap-2">
              <TechIcon kind={k} size={16} tile />
              {KIND_LABEL[k]}
            </li>
          ))}
          <li className="flex flex-wrap items-center gap-x-2.5 gap-y-1 pt-1">
            {(["ok", "warm", "hot", "saturated"] as const).map((st) => (
              <span key={st} className="flex items-center gap-1" title={NODE_STATUS[st].description}>
                <span className="size-1.5 rounded-full" style={{ background: NODE_STATUS[st].color }} aria-hidden />
                {NODE_STATUS[st].label.toLowerCase()}
              </span>
            ))}
          </li>
        </ul>
      </details>
      <p className="text-xs text-ink-3">Click a part to edit it.</p>
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
          {issue.hint && <span className="mt-0.5 block text-xs text-ink-3">{issue.hint}</span>}
        </span>
      </button>
    </li>
  );
}

export function ProblemsList() {
  const issues = useEditor((s) => s.issues);
  const validation = useEditor((s) => s.validation);
  const select = useEditor((s) => s.select);
  const empty = useEditor((s) => s.nodes.length === 0);
  const errors = issues.filter((i) => i.severity === "error").length;
  if (empty) return null;
  return (
    <Section
      title={`Checks${issues.length ? ` · ${issues.length}` : ""}`}
      actions={validation === "pending" ? <span className="font-mono text-[11px] text-ink-3">checking…</span> : null}
    >
      {issues.length === 0 ? (
        <p className="flex items-center gap-2 rounded-xl bg-surface-2/60 px-3 py-2.5 text-[13px] text-ink">
          <CircleCheck className="size-4" style={{ color: "var(--status-good)" }} /> valid model · ready to simulate
        </p>
      ) : (
        <div className="rounded-xl bg-surface-2/60">
          {errors > 0 && <p className="border-b border-line px-3 py-2 text-xs text-ink-2">Fix errors before simulating. Warnings do not block a run.</p>}
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

/** How many headline settings show before the rest fold into "More settings". */
const KEY_LIMIT = 3;

function SettingsBlocks({
  fields,
  values,
  onChange,
  idPrefix,
  allowedRequestTypes,
  extraAdvanced,
  between,
  autoValues,
  provenance,
  sources,
}: {
  fields: FieldDescriptor[];
  values: Record<string, unknown>;
  onChange: (key: string, v: unknown) => void;
  idPrefix: string;
  allowedRequestTypes?: RequestType[];
  autoValues?: Record<string, AutoHint>;
  /** Where values came from, by field key. */
  provenance?: Record<string, InputProvenance>;
  /** Where the design's analysis says each performance input comes from, by field key. */
  sources?: Record<string, ClassifiedProvenance>;
  extraAdvanced?: React.ReactNode;
  /** Shown between the key settings and "More settings" (e.g. resources). */
  between?: React.ReactNode;
}) {
  const headline = fields.filter((f) => f.headline && isFieldVisible(f, values));
  const key = headline.slice(0, KEY_LIMIT);
  const rest = fields.filter((f) => !key.includes(f));
  const restCount = rest.filter((f) => isFieldVisible(f, values)).length;
  return (
    <>
      {key.length > 0 && (
        <section aria-label="Settings" className="space-y-3">
          <h3 className="text-[13px] font-semibold text-ink">Settings</h3>
          <FieldGroups fields={key} values={values} onChange={onChange} idPrefix={idPrefix} allowedRequestTypes={allowedRequestTypes} autoValues={autoValues} provenance={provenance} sources={sources} flat />
        </section>
      )}
      {between}
      {(restCount > 0 || extraAdvanced) && (
        <details className="group rounded-xl bg-surface-2/60" open={key.length === 0}>
          <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2.5 text-[13px] font-medium text-ink-2 hover:text-ink [&::-webkit-details-marker]:hidden">
            <ChevronRight className="size-3.5 transition-transform group-open:rotate-90" />
            More settings
            {restCount > 0 && <span className="text-ink-3">{restCount}</span>}
          </summary>
          <div className="space-y-5 border-t border-line px-3.5 py-4">
            <FieldGroups
              fields={rest}
              values={values}
              onChange={onChange}
              idPrefix={`${idPrefix}-adv`}
              allowedRequestTypes={allowedRequestTypes}
              autoValues={autoValues}
              provenance={provenance}
              sources={sources}
            />
            {extraAdvanced}
          </div>
        </details>
      )}
    </>
  );
}

/** What the component model covers, folded inside "More settings". */
function ModelScope({ modeled, notModeled }: { modeled: string[]; notModeled: string[] }) {
  if (!modeled.length && !notModeled.length) return null;
  return (
    <details className="group/scope text-xs">
      <summary className="flex cursor-pointer list-none items-center gap-1.5 text-ink-3 hover:text-ink [&::-webkit-details-marker]:hidden">
        <ChevronRight className="size-3.5 transition-transform group-open/scope:rotate-90" /> About this model
      </summary>
      <div className="mt-2 grid gap-3 text-ink-2">
        {modeled.length > 0 && (
          <div>
            <p className="mb-1 font-medium text-ink">Modeled</p>
            <ul className="list-disc space-y-1 pl-4">
              {modeled.map((m) => (
                <li key={m}>{m}</li>
              ))}
            </ul>
          </div>
        )}
        {notModeled.length > 0 && (
          <div>
            <p className="mb-1 font-medium text-ink">Not modeled</p>
            <ul className="list-disc space-y-1 pl-4">
              {notModeled.map((m) => (
                <li key={m}>{m}</li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </details>
  );
}

function IconAction({ label, onClick, danger, children }: { label: string; onClick: () => void; danger?: boolean; children: React.ReactNode }) {
  return (
    <Tip content={label}>
      <button
        type="button"
        onClick={onClick}
        aria-label={label}
        className={cn(
          "rounded-md p-1.5 text-ink-3 transition-colors [&_svg]:size-4",
          danger ? "hover:bg-critical-soft hover:text-danger-ink" : "hover:bg-surface-2 hover:text-ink",
        )}
      >
        {children}
      </button>
    </Tip>
  );
}

function NodeInspector({ nodeId }: { nodeId: string }) {
  const node = useEditor((s) => s.nodes.find((n) => n.id === nodeId));
  const catalog = useEditor((s) => s.catalog);
  const issues = useEditor((s) => s.issues);
  const setNodeConfig = useEditor((s) => s.setNodeConfig);
  const extras = useEditor((s) => s.extras);
  const updateNode = useEditor((s) => s.updateNode);
  const deleteElements = useEditor((s) => s.deleteElements);
  const duplicate = useEditor((s) => s.duplicateSelection);
  const sizing = useAutoSize(nodeId);
  const inputs = useEditor((s) => s.analysis?.inputs);
  if (!node) return null;
  const comp = componentOf(catalog, node.data.type);
  const kind = kindOf(node.data.type, comp?.category);
  const nodeIssues = issues.filter((i) => i.nodeId === nodeId);
  return (
    <div className="space-y-5 p-4">
      <div>
        <div className="flex items-center gap-3">
          <TechIcon technology={typeof node.data.config.technology === "string" ? node.data.config.technology : null} kind={kind} size={24} tile />
          <div className="min-w-0 flex-1">
            <input
              value={node.data.label}
              maxLength={120}
              aria-label="Component name"
              onChange={(e) => updateNode(nodeId, { label: e.target.value })}
              className="-ml-1 h-8 w-full rounded-md border border-transparent bg-transparent px-1 text-[15px] font-semibold text-ink hover:border-line focus-visible:border-accent focus-visible:bg-surface-2 focus-visible:outline-none"
            />
            <p className="truncate text-xs text-ink-3" title={comp?.description}>
              {comp?.displayName.split(" / ")[0] ?? node.data.type}
            </p>
          </div>
          <div className="flex shrink-0 items-center">
            <IconAction label="Duplicate (Ctrl D)" onClick={duplicate}>
              <Copy />
            </IconAction>
            <IconAction label="Delete (Del)" onClick={() => deleteElements([nodeId], [])} danger>
              <Trash2 />
            </IconAction>
          </div>
        </div>
        {!comp?.isTrafficSource && <TechnologyPicker nodeId={nodeId} comp={comp} config={node.data.config} />}
      </div>

      {comp && <TechnologyBehaviour comp={comp} config={node.data.config} />}

      {nodeIssues.length > 0 && (
        <ul className="rounded-xl bg-surface-2/60 p-1">
          {nodeIssues.map((i, k) => (
            <IssueRow key={k} issue={i} />
          ))}
        </ul>
      )}

      <NodeResults nodeId={nodeId} />

      {comp?.isTrafficSource && <TrafficCard />}
      {comp?.isTrafficSource && <ClientBlock nodeId={nodeId} comp={comp} config={node.data.config} />}

      {comp ? (
        <SettingsBlocks
          provenance={provenanceUnder(extras, `nodes/${nodeId}/`, node.data.config)}
          sources={sourcesOf(inputs, "node", nodeId)}
          fields={comp.fields.filter((f) => !RESOURCE_FIELDS.includes(f.key) && !(comp.isTrafficSource && CLIENT_BLOCK_FIELDS.includes(f.key)))}
          values={node.data.config}
          onChange={(key, v) => setNodeConfig(nodeId, key, v)}
          idPrefix={`node-${nodeId}`}
          autoValues={partHints(comp, node.data.config, sizing)}
          between={<ResourceBlock nodeId={nodeId} comp={comp} config={node.data.config} />}
          extraAdvanced={
            <div className="space-y-3">
              <div className="space-y-1.5">
                <Label htmlFor="node-desc">Notes</Label>
                <Textarea id="node-desc" rows={2} value={node.data.description ?? ""} placeholder="Optional" onChange={(e) => updateNode(nodeId, { description: e.target.value || null })} />
              </div>
              <ModelScope modeled={comp.modeled} notModeled={comp.notModeled} />
            </div>
          }
        />
      ) : (
        <p className="text-xs text-danger-ink">Unknown component type “{node.data.type}”.</p>
      )}
      <CommentsSection anchorKind="node" anchorId={nodeId} />
    </div>
  );
}

/** The provenance entries under ``prefix`` (nodes/<id>/ or edges/<id>/), keyed by field. A
 * measured entry whose value has changed since (an edit made outside the editor: CLI, MCP, API)
 * is left out, so the badge never marks a value that was not measured. */
function provenanceUnder(extras: DocExtras | undefined, prefix: string, values: Record<string, unknown>): Record<string, InputProvenance> | undefined {
  if (!extras) return undefined;
  const out: Record<string, InputProvenance> = {};
  for (const [k, v] of Object.entries(extras.provenance)) {
    if (!k.startsWith(prefix)) continue;
    const key = k.slice(prefix.length);
    if (v.kind === "measured" && !stillMeasured(v, values[key])) continue;
    out[key] = v;
  }
  return out;
}

/** Where the design's analysis says each of a part's or a link's performance inputs comes from, by field. */
function sourcesOf(inputs: InputEntry[] | undefined, scope: "node" | "edge", id: string): Record<string, ClassifiedProvenance> | undefined {
  if (!inputs) return undefined;
  const out: Record<string, ClassifiedProvenance> = {};
  for (const i of inputs) if (i.scope === scope && i.id === id && i.provenance) out[i.field] = i.provenance;
  return out;
}

/** What a part's empty settings stand for: its technology's defaults, and for its sibling speed the one
 * the last analysis counted its vCPUs at (schema 1.11). */
export function partHints(comp: ComponentType, config: NodeConfig, sizing: SizingEntry | null | undefined): Record<string, AutoHint> | undefined {
  const hints = autoHints(comp, config);
  const vcpus = countedVcpus(sizing);
  if (!vcpus) return hints;
  return { ...hints, siblingSpeed: { value: vcpus.value, source: "the last analysis", text: vcpus.text } };
}

/** The technology defaults behind this node's "auto" and empty settings. */
function autoHints(comp: ComponentType, config: NodeConfig): Record<string, AutoHint> | undefined {
  const tech = typeof config.technology === "string" ? config.technology : null;
  const defaults = comp.technologyDefaults;
  if (!tech || !defaults) return undefined;
  const source = technologyLabel(comp, tech) ?? tech;
  const out: Record<string, AutoHint> = {};
  for (const [key, byTech] of Object.entries(defaults)) {
    if (tech in byTech) out[key] = { value: byTech[tech], source, link: defaultSource(comp, key, tech) };
  }
  return out;
}

/** What the selected technology does differently, with the documentation each behaviour comes from. */
function TechnologyBehaviour({ comp, config }: { comp: ComponentType; config: NodeConfig }) {
  const tech = typeof config.technology === "string" ? config.technology : null;
  const traits = (tech && comp.technologyTraits?.[tech]) || [];
  if (!traits.length) return null;
  const name = technologyLabel(comp, tech) ?? tech;
  return (
    <details className="group/tech rounded-xl bg-surface-2/60">
      <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2.5 text-[13px] font-medium text-ink [&::-webkit-details-marker]:hidden">
        <ChevronRight className="size-3.5 text-ink-3 transition-transform group-open/tech:rotate-90" />
        <span className="min-w-0 flex-1 truncate">How {name} behaves</span>
        <span className="text-xs text-ink-3">{traits.length}</span>
      </summary>
      <div className="border-t border-line px-3 py-3">
        <p className="mb-2.5 text-xs leading-relaxed text-ink-3">
          The simulation does this because you picked {name}. Each one is a setting under More settings, Technology behaviour; set it to override.
        </p>
        <TechTraits traits={traits} dense />
      </div>
    </details>
  );
}

/** The last run's calculations for this node, folded; the full set lives in Results. */
function NodeCalculations({ nodeId }: { nodeId: string }) {
  const report = useActiveReport();
  const setDetailsView = useDecide((s) => s.setDetailsView);
  const entries = report?.nodes.find((n) => n.id === nodeId)?.explain ?? [];
  if (!entries.length) return null;
  return (
    <details className="group/calc border-t border-line">
      <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2 text-xs font-medium text-ink-2 hover:text-ink [&::-webkit-details-marker]:hidden">
        <ChevronRight className="size-3.5 transition-transform group-open/calc:rotate-90" />
        How it&apos;s calculated
        <span className="text-ink-3">{entries.length}</span>
      </summary>
      <div className="space-y-2 px-3 pb-3">
        {entries.slice(0, 4).map((e) => (
          <FormulaCard key={e.id} entry={e} compact />
        ))}
        <button
          type="button"
          onClick={() => {
            setDetailsView("calculations");
            openDecide();
          }}
          className="flex items-center gap-1 text-xs font-medium text-accent hover:underline"
        >
          All calculations and notes <ArrowRight className="size-3.5" />
        </button>
      </div>
    </details>
  );
}

/** Memory for people: MiB below 1 GiB, otherwise GiB with at most one decimal. */
function fmtGiB(v: number): string {
  if (!Number.isFinite(v)) return "–";
  return v < 1 ? `${Math.round(v * 1024)} MiB` : `${+v.toFixed(1)} GiB`;
}

/** How many of a part's run facts show before "N more". */
const FACT_LIMIT = 3;

/** A part's facts from the run, one a line: the first few, the rest one click away. */
function RunFacts({ facts }: { facts: string[] }) {
  const [all, setAll] = useState(false);
  if (!facts.length) return null;
  const shown = all ? facts : facts.slice(0, FACT_LIMIT);
  return (
    <div className="px-3 pb-2.5">
      <ul className="space-y-0.5 text-xs leading-relaxed text-ink-2" aria-label="From the run">
        {shown.map((f) => (
          <li key={f} className="flex gap-1.5">
            <span className="mt-[7px] size-1 shrink-0 rounded-full bg-ink-3" aria-hidden />
            {f}
          </li>
        ))}
      </ul>
      {facts.length > FACT_LIMIT && (
        <button type="button" onClick={() => setAll((v) => !v)} className="mt-1 text-xs font-medium text-ink-3 hover:text-ink">
          {all ? "Show fewer" : `${facts.length - FACT_LIMIT} more`}
        </button>
      )}
    </div>
  );
}

/** After a run: one status line and three numbers; charts are one click away. */
function NodeResults({ nodeId }: { nodeId: string }) {
  const report = useActiveReport();
  const openDrawer = useRuns((s) => s.openDrawer);
  const focusNode = useRuns((s) => s.focusNode);
  const nr = report?.nodes.find((n) => n.id === nodeId);
  if (!report || !nr) return null;
  const s = nr.summary;
  const meta = NODE_STATUS[nr.status];
  const n = (k: string): number | null => {
    const v = (s as unknown as Record<string, unknown>)[k];
    return typeof v === "number" ? v : null;
  };
  const isCluster = nr.type === "kubernetes_cluster";
  // A Kubernetes cluster takes no requests: its numbers are nodes and pods.
  const stats: [string, string][] = isCluster
    ? [
        ["nodes at peak", fmtNum(n("nodesPeak") ?? 0)],
        ["Pending at peak", fmtNum(n("podsPendingPeak") ?? 0)],
        ["longest wait", `${fmtNum(Math.round(n("maxPendingS") ?? 0))} s`],
      ]
    : [
        ["busy", s.utilization != null ? fmtPct(s.utilization, 0) : fmtRps(s.arrivalRps)],
        ["wait p95", fmtMs(s.p95QueueWaitMs)],
        s.hitRatio != null ? ["hit ratio", fmtPct(s.hitRatio, 0)] : ["rejected", fmtNum(s.rejectedTotal)],
      ];
  // Resource specs (when set): how hard the CPU, memory, buffer cache and key space were used.
  const cfg = nr.config;
  const resourceStats = [
    s.cpuCoreUtilization != null &&
      `CPU ${fmtPct(s.cpuCoreUtilization, 0)} busy${s.busiestInstanceCpuUtilization != null ? ` (busiest instance ${fmtPct(s.busiestInstanceCpuUtilization, 0)})` : ""}`,
    s.capacityLimit === "cpu" && s.capacityRps != null && `CPU caps it at ${fmtRps(s.capacityRps)}`,
    s.capacityLimit === "memory" && s.capacityRps != null && `memory caps it at ${fmtRps(s.capacityRps)}`,
    s.memoryPeakGiB != null && `memory peak ${fmtGiB(s.memoryPeakGiB)} of ${fmtGiB(Number(cfg.memoryGib))}`,
    (s.rejected?.out_of_memory ?? 0) > 0 && `${fmtNum(s.rejected.out_of_memory)} refused (memory)`,
    s.bufferHitRatio != null && `buffer cache ${fmtPct(s.bufferHitRatio, 0)} of reads`,
    s.capacityKeys != null && `${fmtNum(s.keysCached ?? 0)} of ${fmtNum(s.capacityKeys)} keys held`,
    (s.counters?.evictions ?? 0) > 0 && `${fmtNum(s.counters.evictions)} evictions`,
    s.meanLockWaitMs != null && s.meanLockWaitMs >= 0.05 && `row locks ${fmtMs(s.meanLockWaitMs)} per write`,
    s.maxReplicationLagMs != null && s.maxReplicationLagMs >= 1 && `replica lag up to ${fmtMs(s.maxReplicationLagMs)}`,
    (s.counters?.staleReads ?? 0) > 0 && `${fmtNum(s.counters.staleReads)} stale reads`,
    (s.rejected?.throttled ?? 0) > 0 && `${fmtNum(s.rejected.throttled)} throttled`,
    (s.gcPauses ?? 0) > 0 && `${fmtNum(s.gcPauses)} GC pauses`,
    (s.counters?.redeliveries ?? 0) > 0 && `${fmtNum(s.counters.redeliveries)} redelivered`,
    isCluster && n("cpuRequestedRatio") != null && `${fmtPct(n("cpuRequestedRatio") as number, 0)} of node CPU requested`,
    isCluster && (s.counters?.nodeAdds ?? 0) > 0 && `${fmtNum(s.counters.nodeAdds)} node(s) added`,
    isCluster && (n("podsPendingEnd") ?? 0) > 0 && `${fmtNum(n("podsPendingEnd") as number)} still Pending at the end`,
    !isCluster && (n("podsPendingPeak") ?? 0) > 0 && `up to ${fmtNum(n("podsPendingPeak") as number)} pods Pending`,
  ].filter((x): x is string => typeof x === "string");
  return (
    <section aria-label="Last run" className="overflow-hidden rounded-xl bg-surface-2/60">
      <div className="flex items-center gap-2 px-3 pt-2.5">
        <span className="size-2 shrink-0 rounded-full" style={{ background: meta.color }} aria-hidden />
        <span className="text-[13px] font-semibold text-ink">{meta.label}</span>
        <span className="min-w-0 flex-1 truncate text-xs text-ink-3" title={nr.statusReasons.join(", ") || meta.description}>
          in the last run
        </span>
        <button
          type="button"
          onClick={() => {
            focusNode(nodeId);
            openDrawer("components");
          }}
          className="flex shrink-0 items-center gap-1 text-xs font-medium text-accent hover:underline"
        >
          Charts <ArrowRight className="size-3.5" />
        </button>
      </div>
      <dl className="grid grid-cols-3">
        {stats.map(([k, v]) => (
          <div key={k} className="px-3 py-2">
            <dd className="tabular font-mono text-[13px] font-semibold text-ink">{v}</dd>
            <dt className="text-xs text-ink-3">{k}</dt>
          </div>
        ))}
      </dl>
      <RunFacts facts={resourceStats} />
      <NodeCalculations nodeId={nodeId} />
    </section>
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
  const extras = useEditor((s) => s.extras);
  const setEdgeLabel = useEditor((s) => s.setEdgeLabel);
  const deleteElements = useEditor((s) => s.deleteElements);
  const report = useActiveReport();
  const inputs = useEditor((s) => s.analysis?.inputs);
  if (!edge || !edge.data) return null;
  const src = nodes.find((n) => n.id === edge.source);
  const tgt = nodes.find((n) => n.id === edge.target);
  const srcComp = src ? componentOf(catalog, src.data.type) : undefined;
  const tgtComp = tgt ? componentOf(catalog, tgt.data.type) : undefined;
  const sp = srcComp?.ports.find((p) => p.name === edgeSourcePort(edge));
  const tp = tgtComp?.ports.find((p) => p.name === edgeTargetPort(edge));
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
      <span className="inline-flex max-w-[140px] items-center gap-1.5 truncate rounded-md bg-surface-2 px-1.5 py-1 text-xs font-medium text-ink">
        <TechIcon kind={k} size={14} />
        <span className="truncate">{label}</span>
      </span>
    );
  };
  return (
    <div className="space-y-5 p-4">
      <div className="rounded-xl bg-surface-2 p-3">
        <div className="flex items-center justify-between">
          <p className="text-xs text-ink-3">{sp && sp.name !== "out" ? `${sp.label} link` : "Link"}</p>
          <IconAction label="Delete link (Del)" onClick={() => deleteElements([], [edgeId])} danger>
            <Trash2 />
          </IconAction>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          {chip(src?.data.label ?? edge.source, src?.data.type, srcComp?.category)}
          <ArrowRight className="size-3.5 text-ink-3" />
          {chip(tgt?.data.label ?? edge.target, tgt?.data.type, tgtComp?.category)}
        </div>
      </div>
      {edgeIssues.length > 0 && (
        <ul className="rounded-xl bg-surface-2/60 p-1">
          {edgeIssues.map((i, k) => (
            <IssueRow key={k} issue={i} />
          ))}
        </ul>
      )}
      <SettingsBlocks
        provenance={provenanceUnder(extras, `edges/${edgeId}/`, edge.data.config as unknown as Record<string, unknown>)}
        sources={sourcesOf(inputs, "edge", edgeId)}
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
                <dt className="microlabel !text-[11px]">{k}</dt>
                <dd className={cn("tabular font-mono text-[13px] font-semibold text-ink")}>{v}</dd>
              </div>
            ))}
          </dl>
        </Section>
      )}
      <CommentsSection anchorKind="edge" anchorId={edgeId} />
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

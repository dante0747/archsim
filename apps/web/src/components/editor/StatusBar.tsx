"use client";

import { useStore } from "@xyflow/react";
import { LoaderCircle } from "lucide-react";
import { Kbd } from "@/components/ui/misc";
import { compact, fmtNum } from "@/lib/format";
import { REQ_GLYPH } from "@/lib/theme";
import { cn, modKey } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

const Sep = () => (
  <span className="text-line-strong" aria-hidden>
    │
  </span>
);

/** IDE-style status line: model validity, size, workload, run state, zoom and the command key. */
export function StatusBar() {
  const issues = useEditor((s) => s.issues);
  const validation = useEditor((s) => s.validation);
  const nodeCount = useEditor((s) => s.nodes.length);
  const edgeCount = useEditor((s) => s.edges.length);
  const w = useEditor((s) => s.workload);
  const setRightTab = useEditor((s) => s.setRightTab);
  const select = useEditor((s) => s.select);
  const followRun = useRuns((s) => s.runs.find((r) => r.id === s.followRunId) ?? null);
  const activeRun = useRuns((s) => s.runs.find((r) => r.id === s.activeRunId) ?? null);
  const report = useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
  const setPaletteOpen = useUi((s) => s.setPaletteOpen);
  const zoom = useStore((s) => s.transform[2]);

  const errors = issues.filter((i) => i.severity === "error");
  const warnings = issues.filter((i) => i.severity === "warning");
  const running = !!followRun && (followRun.status === "queued" || followRun.status === "running");
  const reads = Math.round(w.readRatio * 100);

  const empty = nodeCount === 0;
  const validity = empty ? (
    <span className="text-ink-3">empty design</span>
  ) : validation === "pending" ? (
      <>
        <LoaderCircle className="size-3 animate-spin" /> checking
      </>
    ) : errors.length ? (
      <span className="text-danger-ink">✕ {errors.length} error{errors.length > 1 ? "s" : ""}</span>
    ) : warnings.length ? (
      <span className="text-ink-2">! {warnings.length} warning{warnings.length > 1 ? "s" : ""}</span>
    ) : (
      <>
        <span className="size-1.5 rounded-full" style={{ background: "var(--status-good)" }} aria-hidden /> valid
      </>
    );

  return (
    <footer
      className="relative z-20 flex h-7 shrink-0 items-center gap-3 overflow-hidden whitespace-nowrap border-t border-line bg-surface px-3 font-mono text-[10.5px] text-ink-3"
      aria-label="Status bar"
    >
      <button
        type="button"
        onClick={() => {
          setRightTab("inspect");
          const first = errors[0] ?? warnings[0];
          if (first) select({ nodeId: first.nodeId ?? null, edgeId: first.edgeId ?? null });
          else select({});
        }}
        className="flex items-center gap-1.5 hover:text-ink"
        title="Model checks"
      >
        {validity}
      </button>
      <Sep />
      <span>
        {nodeCount} component{nodeCount === 1 ? "" : "s"} · {edgeCount} link{edgeCount === 1 ? "" : "s"}
      </span>
      <Sep />
      <button type="button" onClick={() => setRightTab("workload")} className="hidden hover:text-ink sm:inline" title="Traffic settings">
        {w.pattern.kind === "constant" ? `${compact(w.rps)} req/s` : w.pattern.kind} · {REQ_GLYPH.read}
        {reads} {REQ_GLYPH.write}
        {100 - reads} · {compact(w.durationS)} s · seed {w.seed}
      </button>
      <span className="hidden sm:inline">
        <Sep />
      </span>
      {running && followRun ? (
        <span className="flex items-center gap-2 text-ink" role="status">
          <span className="block h-1.5 w-14 overflow-hidden rounded-[1px] bg-surface-3" aria-hidden>
            <span className="progress-stripes block h-full" style={{ width: `${Math.max(4, followRun.progress * 100)}%` }} />
          </span>
          {followRun.status === "queued" ? "queued" : `simulating ${Math.round(followRun.progress * 100)}%`}
        </span>
      ) : report && activeRun ? (
        <span className="hidden truncate md:inline" title="Last run shown on the canvas">
          last run: {activeRun.label} · {fmtNum(report.provenance.requestsGenerated)} req · {report.provenance.wallSeconds.toFixed(1)} s wall
        </span>
      ) : (
        <span className="hidden md:inline">no runs yet</span>
      )}
      <span className="ml-auto" />
      <span className="hidden lg:inline">zoom {Math.round(zoom * 100)}%</span>
      {report && (
        <span className="hidden lg:inline">
          <Sep /> engine {report.provenance.engineVersion}
        </span>
      )}
      <Sep />
      <button type="button" onClick={() => setPaletteOpen(true)} className={cn("flex items-center gap-1.5 hover:text-ink")}>
        <Kbd>{modKey()} K</Kbd> commands
      </button>
    </footer>
  );
}

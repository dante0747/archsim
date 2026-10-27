"use client";

import { Copy, Download, FileText } from "lucide-react";
import { Fragment, useMemo, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { slugify, toDocument } from "@/lib/document";
import { type ReviewItem, decisionReportMarkdown } from "@/lib/report-md";
import { analyzeSweep } from "@/lib/sweep";
import { cn } from "@/lib/utils";
import { useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useSeedGroup } from "@/store/seed-groups";
import { PageHeader, useDecideRun } from "./shared";

/** Inline Markdown: **bold** and `code`. */
function Inline({ text }: { text: string }) {
  const parts = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
  return (
    <>
      {parts.map((p, i) =>
        p.startsWith("**") && p.endsWith("**") ? (
          <strong key={i} className="font-semibold text-ink">
            {p.slice(2, -2)}
          </strong>
        ) : p.startsWith("`") && p.endsWith("`") ? (
          <code key={i} className="rounded-sm bg-surface-2 px-1 font-code text-[0.92em]">
            {p.slice(1, -1)}
          </code>
        ) : (
          <Fragment key={i}>{p.replace(/\\\|/g, "|")}</Fragment>
        ),
      )}
    </>
  );
}

const splitRow = (line: string) =>
  line
    .trim()
    .replace(/^\|/, "")
    .replace(/\|$/, "")
    .split(/(?<!\\)\|/)
    .map((c) => c.trim());

/** Renders the subset of Markdown the decision report uses (headings, lists, tables, quotes). */
function MarkdownPreview({ md }: { md: string }) {
  const lines = md.split("\n");
  const blocks: React.ReactNode[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) {
      i++;
      continue;
    }
    if (line.startsWith("|")) {
      const rows: string[][] = [];
      while (i < lines.length && lines[i].startsWith("|")) {
        if (!/^\|\s*-/.test(lines[i])) rows.push(splitRow(lines[i]));
        i++;
      }
      const [head, ...body] = rows;
      blocks.push(
        <div key={`t${i}`} className="my-3 overflow-x-auto">
          <table className="tabular w-full text-xs">
            <thead>
              <tr className="border-b border-line-strong text-left">
                {head.map((h, k) => (
                  <th key={k} className="px-2 py-1.5 font-mono text-[11px] font-normal text-ink-3">
                    <Inline text={h} />
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {body.map((r, k) => (
                <tr key={k} className="border-b border-line last:border-0">
                  {r.map((c, j) => (
                    <td key={j} className="px-2 py-1.5 text-ink-2">
                      <Inline text={c} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>,
      );
      continue;
    }
    if (/^\s*- /.test(line)) {
      const items: { depth: number; text: string }[] = [];
      while (i < lines.length && /^\s*- /.test(lines[i])) {
        const m = /^(\s*)- (.*)$/.exec(lines[i])!;
        items.push({ depth: m[1].length >= 2 ? 1 : 0, text: m[2] });
        i++;
      }
      blocks.push(
        <ul key={`l${i}`} className="my-2 space-y-1">
          {items.map((it, k) => (
            <li key={k} className={cn("relative pl-4 text-[13px] leading-relaxed text-ink-2", it.depth && "ml-4")}>
              <span className="absolute left-0 text-ink-3" aria-hidden>
                {it.depth ? "·" : "–"}
              </span>
              <Inline text={it.text} />
            </li>
          ))}
        </ul>,
      );
      continue;
    }
    if (line.startsWith("> ")) {
      blocks.push(
        <p key={`q${i}`} className="my-1 border-l-2 border-line-strong pl-3 text-xs leading-relaxed text-ink-3">
          <Inline text={line.slice(2)} />
        </p>,
      );
      i++;
      continue;
    }
    const h = /^(#{1,3}) (.*)$/.exec(line);
    if (h) {
      const level = h[1].length;
      blocks.push(
        level === 1 ? (
          <h1 key={`h${i}`} className="mb-2 text-xl font-semibold tracking-tight text-ink">
            {h[2]}
          </h1>
        ) : level === 2 ? (
          <h2 key={`h${i}`} className="mb-1 mt-6 border-b border-line pb-1 font-mono text-xs font-semibold text-ink">
            {h[2]}
          </h2>
        ) : (
          <h3 key={`h${i}`} className="mb-1 mt-4 text-[13px] font-semibold text-ink">
            {h[2]}
          </h3>
        ),
      );
      i++;
      continue;
    }
    blocks.push(
      <p key={`p${i}`} className="my-2 text-[13px] leading-relaxed text-ink-2">
        <Inline text={line} />
      </p>,
    );
    i++;
  }
  return <article>{blocks}</article>;
}

export function ReportSection({ review }: { review?: ReviewItem[] }) {
  const nodes = useEditor((s) => s.nodes);
  const edges = useEditor((s) => s.edges);
  const workload = useEditor((s) => s.workload);
  const scenarios = useEditor((s) => s.scenarios);
  const metadata = useEditor((s) => s.metadata);
  const objectives = useEditor((s) => s.objectives);
  const catalog = useEditor((s) => s.catalog);
  const designCost = useEditor((s) => s.analysis?.cost ?? null);
  const designConfidence = useEditor((s) => s.analysis?.confidence ?? null);
  const designEstimate = useEditor((s) => s.analysis?.estimate ?? null);
  const projectId = useRuns((s) => s.projectId);
  const sweep = useDecide((s) => s.sweep);
  const { run, report } = useDecideRun();
  const seedRuns = useSeedGroup(run?.groupId)?.runs ?? null;
  const [view, setView] = useState<"preview" | "markdown">("preview");

  const md = useMemo(() => {
    const doc = toDocument({ nodes, edges, workload, scenarios, objectives, metadata });
    const sweepAnalysis = sweep.projectId === projectId && sweep.rows.some((r) => r.throughput != null) ? analyzeSweep(sweep.rows, objectives) : null;
    return decisionReportMarkdown({ doc, catalog, objectives, run, report, review, designCost, designConfidence, designEstimate, sweep: sweepAnalysis, seedRuns });
  }, [nodes, edges, workload, scenarios, metadata, objectives, catalog, run, report, review, designCost, designConfidence, designEstimate, sweep, projectId, seedRuns]);

  const fileName = `${slugify(metadata.name || "architecture")}-decision-report.md`;
  const download = () => {
    const blob = new Blob([md], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = fileName;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(md);
      toast.success("Report copied as Markdown");
    } catch {
      toast.error("Copy failed", { description: "The browser blocked clipboard access. Use Download instead." });
    }
  };

  return (
    <div>
      <PageHeader
        kicker="report"
        title="Report"
        actions={
          <>
            <Button onClick={() => void copy()}>
              <Copy /> Copy
            </Button>
            <Button variant="primary" onClick={download}>
              <Download /> Download .md
            </Button>
          </>
        }
      />
      <div className="p-6">
        <div className="mx-auto max-w-4xl rounded-md border border-line bg-surface">
          <div className="flex items-center justify-between gap-2 border-b border-line px-4 py-2">
            <span className="flex items-center gap-2 font-mono text-xs text-ink-3">
              <FileText className="size-3.5" /> {fileName}
            </span>
            <div role="tablist" aria-label="Report view" className="flex rounded-md border border-line bg-surface-2 p-0.5">
              {(["preview", "markdown"] as const).map((v) => (
                <button
                  key={v}
                  type="button"
                  role="tab"
                  aria-selected={view === v}
                  onClick={() => setView(v)}
                  className={cn("h-6 rounded-sm px-2 font-mono text-xs", view === v ? "bg-surface text-ink ring-1 ring-line-strong" : "text-ink-3 hover:text-ink")}
                >
                  {v}
                </button>
              ))}
            </div>
          </div>
          <div className="px-6 py-5">
            {view === "preview" ? (
              <MarkdownPreview md={md} />
            ) : (
              <pre className="overflow-x-auto whitespace-pre-wrap break-words font-code text-xs leading-relaxed text-ink-2">{md}</pre>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

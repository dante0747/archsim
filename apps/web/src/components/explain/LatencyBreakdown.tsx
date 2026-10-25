"use client";

/** Where a successful request's time goes: one stacked bar and a table, from the traced requests. */
import { fmtMs } from "@/lib/format";
import type { LatencyShare } from "@/lib/types";

const COLORS = ["var(--series-1)", "var(--series-2)", "var(--series-3)", "var(--series-4)", "var(--series-5)", "var(--series-6)", "var(--series-7)"];

export function shareColor(row: LatencyShare, index: number): string {
  if (row.nodeId === null) return row.label === "Network" ? "var(--ink-3)" : "var(--surface-3)";
  return COLORS[index % COLORS.length];
}

export function LatencyBreakdown({ rows }: { rows: LatencyShare[] }) {
  if (!rows.length) return <p className="text-sm text-ink-3">No traced requests succeeded in this run.</p>;
  let ci = 0;
  const colored = rows.map((r) => ({ row: r, color: r.nodeId === null ? shareColor(r, 0) : shareColor(r, ci++) }));
  const total = rows.reduce((n, r) => n + r.meanMs, 0);
  return (
    <div>
      <div className="flex h-4 gap-0.5 overflow-hidden rounded-md" role="img" aria-label="Share of request time per component">
        {colored.map(({ row, color }) => (
          <span key={row.label} title={`${row.label}: ${fmtMs(row.meanMs)}`} style={{ width: `${Math.max(0.6, row.share * 100)}%`, background: color }} />
        ))}
      </div>
      <table className="mt-3 w-full text-[13px]">
        <thead>
          <tr className="text-left text-xs text-ink-3">
            <th className="py-1 font-medium">Where</th>
            <th className="py-1 text-right font-medium">Mean</th>
            <th className="py-1 text-right font-medium">Share</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {colored.map(({ row, color }) => (
            <tr key={row.label}>
              <td className="py-1.5">
                <span className="flex items-center gap-2 text-ink">
                  <span className="size-2.5 shrink-0 rounded-sm" style={{ background: color }} aria-hidden />
                  {row.label}
                </span>
              </td>
              <td className="tabular py-1.5 text-right text-ink-2">{fmtMs(row.meanMs)}</td>
              <td className="tabular py-1.5 text-right text-ink-2">{(row.share * 100).toFixed(row.share < 0.1 ? 1 : 0)}%</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t border-line-strong text-ink">
            <td className="py-1.5 font-medium">Typical request</td>
            <td className="tabular py-1.5 text-right font-medium">{fmtMs(total)}</td>
            <td />
          </tr>
        </tfoot>
      </table>
    </div>
  );
}

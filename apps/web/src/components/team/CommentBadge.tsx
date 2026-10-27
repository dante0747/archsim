"use client";

import { EdgeLabelRenderer } from "@xyflow/react";
import { MessageSquare } from "lucide-react";
import type { AnchorKind } from "@/lib/team-types";
import { showPart, useOpenCount } from "./hooks";

function label(n: number): string {
  return `${n} open comment ${n === 1 ? "thread" : "threads"}`;
}

const badgeClass =
  "nodrag nopan inline-flex items-center gap-0.5 rounded-full border border-accent/40 bg-accent-soft px-1.5 font-mono text-[11px] font-semibold leading-[18px] text-ink shadow-panel hover:border-accent [&_svg]:size-3";

function CountBadge({ kind, id, n, className, style }: { kind: AnchorKind; id: string; n: number; className: string; style?: React.CSSProperties }) {
  return (
    <button
      type="button"
      className={`${badgeClass} ${className}`}
      style={style}
      title={label(n)}
      aria-label={label(n)}
      data-testid={`comment-badge-${id}`}
      onClick={() => showPart(kind, id)}
    >
      <MessageSquare aria-hidden /> {n}
    </button>
  );
}

/** Open comment threads on a part, on the card's top edge near the right (clear of the
 * selection handles at the corners). */
export function NodeCommentBadge({ nodeId }: { nodeId: string }) {
  const n = useOpenCount("node", nodeId);
  return n ? <CountBadge kind="node" id={nodeId} n={n} className="absolute -top-2.5 right-5 z-20" /> : null;
}

/** Open comment threads on a link, just above its label. */
export function EdgeCommentBadge({ edgeId, x, y }: { edgeId: string; x: number; y: number }) {
  const n = useOpenCount("edge", edgeId);
  if (!n) return null;
  return (
    <EdgeLabelRenderer>
      <CountBadge
        kind="edge"
        id={edgeId}
        n={n}
        className="pointer-events-auto absolute"
        style={{ transform: `translate(-50%, -50%) translate(${x}px, ${y - 20}px)` }}
      />
    </EdgeLabelRenderer>
  );
}

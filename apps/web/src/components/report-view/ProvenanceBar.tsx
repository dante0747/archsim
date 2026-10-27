"use client";

import { RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge, Tip } from "@/components/ui/misc";
import { fmtNum, shortDigest } from "@/lib/format";
import type { Report } from "@/lib/types";

/** Where a result came from: seed, engine, digests and size. `onReproduce` adds "Reproduce" (the editor). */
export function ProvenanceBar({ report, onReproduce }: { report: Report; onReproduce?: () => void }) {
  const p = report.provenance;
  return (
    <div className="flex flex-wrap items-center gap-1.5 font-mono text-[11px] text-ink-2">
      <Badge tone="accent" className="font-mono">
        simulation-derived
      </Badge>
      <Tip content="Random seed: the same document + scenario + seed + engine version reproduces these results exactly.">
        <Badge className="font-mono">seed {p.seed}</Badge>
      </Tip>
      <Badge className="font-mono">engine {p.engineVersion}</Badge>
      <Tip content={`Input digest ${p.inputDigest}`}>
        <Badge className="font-mono">in {shortDigest(p.inputDigest)}</Badge>
      </Tip>
      <Tip content={`Result digest ${p.resultDigest}`}>
        <Badge className="font-mono">out {shortDigest(p.resultDigest)}</Badge>
      </Tip>
      <Badge className="font-mono">
        {fmtNum(p.requestsGenerated)} req · {fmtNum(report.window.simulatedS, 1)} s sim · {fmtNum(p.wallSeconds, 1)} s wall
      </Badge>
      {onReproduce && (
        <Button size="sm" variant="ghost" onClick={onReproduce} title="Re-run the stored snapshot and check the result digest matches">
          <RotateCcw /> Reproduce
        </Button>
      )}
    </div>
  );
}

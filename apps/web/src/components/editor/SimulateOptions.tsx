"use client";

/**
 * The Simulate menu's "how to run it" items: a quick check (sampled, estimated), enabled only
 * when the engine says the design qualifies (constant traffic, no faults, a long enough run;
 * analysis `quickCheck`), and a check with 5 seeds.
 */
import { Dices, FlaskConical } from "lucide-react";
import { MenuItem, MenuLabel } from "@/components/ui/overlays";
import { CHECK_SEEDS, type RunMode, quickCheckOf, seedsText } from "@/lib/seed-groups";
import { useEditor } from "@/store/editor";

export function SimulateOptionItems({ onRun }: { onRun: (opts: { seeds?: number; mode?: RunMode }) => void }) {
  const qc = useEditor((s) => quickCheckOf(s.analysis));
  const seed = useEditor((s) => s.workload.seed);
  const eligible = qc?.eligible === true;
  const reason = qc?.reason ?? "Checking whether the design qualifies…";
  return (
    <>
      <MenuLabel>How to run it</MenuLabel>
      <MenuItem disabled={!eligible} onSelect={() => onRun({ mode: "sampled" })} data-testid="quick-check-item" className="items-start">
        <FlaskConical className="mt-0.5" />
        <span className="min-w-0">
          <span className="block">Quick check (estimated)</span>
          {eligible && <span className="block text-xs text-ink-3">{reason}</span>}
        </span>
      </MenuItem>
      {!eligible && (
        <p data-testid="quick-check-reason" className="px-2 pb-1.5 pl-8 text-xs leading-snug text-ink-3">
          {reason}
        </p>
      )}
      <MenuItem onSelect={() => onRun({ seeds: CHECK_SEEDS })} data-testid="seeds-item" className="items-start">
        <Dices className="mt-0.5" />
        <span className="min-w-0">
          <span className="block">Check {CHECK_SEEDS} seeds</span>
          <span className="block text-xs text-ink-3">
            The same run with {seedsText(Array.from({ length: CHECK_SEEDS }, (_, i) => seed + i))}: how much of the answer is luck
          </span>
        </span>
      </MenuItem>
    </>
  );
}

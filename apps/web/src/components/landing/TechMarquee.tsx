"use client";

/** Two rows of real technology logos drifting in opposite directions (still for reduced motion). */
import { TechIcon } from "@/components/TechIcon";
import type { Chip } from "@/lib/tech-chips";

function Row({ chips, reverse }: { chips: Chip[]; reverse?: boolean }) {
  // The list is drawn twice so the loop is seamless at -50%.
  return (
    <div className="flex w-max gap-3" style={{ animation: `archsim-marquee ${chips.length * 2.6}s linear infinite`, animationDirection: reverse ? "reverse" : undefined }}>
      {[...chips, ...chips].map((c, i) => (
        <span
          key={`${c.key}-${i}`}
          className="flex shrink-0 items-center gap-2.5 rounded-xl border border-line bg-surface px-3.5 py-2.5 text-sm font-medium text-ink"
          aria-hidden={i >= chips.length}
        >
          <TechIcon technology={c.technology} kind={c.kind} size={20} />
          {c.name}
        </span>
      ))}
    </div>
  );
}

/** The chips come from the catalog (`techChips`): fetched by the app, read at build time by the website. */
export function TechMarquee({ chips }: { chips: Chip[] }) {
  if (chips.length === 0) return null;
  const half = Math.ceil(chips.length / 2);
  return (
    <div className="marquee-host relative space-y-3 overflow-hidden py-1 [mask-image:linear-gradient(90deg,transparent,#000_8%,#000_92%,transparent)]">
      <Row chips={chips.slice(0, half)} />
      <Row chips={chips.slice(half)} reverse />
    </div>
  );
}

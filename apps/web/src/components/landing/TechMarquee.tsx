"use client";

/** Two rows of real technology logos drifting in opposite directions (still for reduced motion). */
import { TechIcon } from "@/components/TechIcon";
import { kindOf, type Kind } from "@/lib/theme";
import type { Catalog } from "@/lib/types";

interface Chip {
  key: string;
  name: string;
  technology: string | null;
  kind: Kind;
}

/** Client technologies (they live on the client parts' technology field, not in presets). */
const CLIENT_TECH: [string, string][] = [
  ["ios", "iOS"],
  ["android", "Android"],
  ["react_native", "React Native"],
  ["flutter", "Flutter"],
  ["react", "React"],
  ["nextjs", "Next.js"],
  ["vue", "Vue"],
  ["angular", "Angular"],
  ["svelte", "Svelte"],
  ["graphql", "GraphQL"],
  ["rest", "REST / OpenAPI"],
];

export function techChips(catalog: Catalog): Chip[] {
  const presets = catalog.components.flatMap((c) =>
    (c.presets ?? []).map((p) => ({ key: `${c.type}:${p.id}`, name: p.name, technology: p.technology, kind: kindOf(c.type, c.category) })),
  );
  const clients = CLIENT_TECH.map(([technology, name]) => ({ key: `client:${technology}`, name, technology, kind: "traffic" as Kind }));
  return [...presets, ...clients];
}

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

export function TechMarquee({ catalog }: { catalog: Catalog }) {
  const chips = techChips(catalog);
  if (chips.length === 0) return null;
  const half = Math.ceil(chips.length / 2);
  return (
    <div className="marquee-host relative space-y-3 overflow-hidden py-1 [mask-image:linear-gradient(90deg,transparent,#000_8%,#000_92%,transparent)]">
      <Row chips={chips.slice(0, half)} />
      <Row chips={chips.slice(half)} reverse />
    </div>
  );
}

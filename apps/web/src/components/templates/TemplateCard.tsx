"use client";

/**
 * A template as a card: its design drawn like the editor on graph paper, with a glow and flowing
 * traffic in its category's colour, what you will see happen, and its key numbers. The whole card
 * opens the template as a new project. ``spotlight`` is the wide featured layout.
 */
import { ArrowRight, Boxes, Eye, Gauge, GitBranch, LoaderCircle, Play } from "lucide-react";
import { TechIcon } from "@/components/TechIcon";
import { TemplatePreview } from "@/components/templates/TemplatePreview";
import { CATEGORY_COLOR, type TemplateFacts, templateFacts, templateMeta } from "@/components/templates/templates-meta";
import { fmtNum } from "@/lib/format";
import { technologyLabel } from "@/lib/theme";
import type { ArchitectureDocument, ExampleSummary } from "@/lib/types";
import { cn } from "@/lib/utils";

const LEVEL_DOTS = { Beginner: 1, Intermediate: 2, Advanced: 3 } as const;

function compactRps(v: number): string {
  return v >= 1000 ? `${fmtNum(v / 1000, v % 1000 === 0 ? 0 : 1)}k` : fmtNum(v);
}

function Stats({ facts, scenarios }: { facts: TemplateFacts | null; scenarios: number }) {
  const items: [React.ReactNode, string, string][] = [
    [<Boxes key="b" className="size-3.5 text-ink-3" aria-hidden />, facts ? String(facts.parts) : "–", "parts"],
    [<GitBranch key="g" className="size-3.5 text-ink-3" aria-hidden />, String(scenarios), "scenarios"],
    [<Gauge key="p" className="size-3.5 text-ink-3" aria-hidden />, facts ? compactRps(facts.peakRps) : "–", "req/s peak"],
  ];
  return (
    <dl className="grid grid-cols-3 gap-2 text-xs">
      {items.map(([icon, v, label]) => (
        <div key={label} className="flex items-center gap-1.5 text-ink-2">
          {icon}
          <dd className="tabular font-semibold text-ink">{v}</dd>
          <dt>{label}</dt>
        </div>
      ))}
    </dl>
  );
}

export function TemplateCard({
  example,
  doc,
  busy,
  disabled,
  spotlight = false,
  onOpen,
  className,
}: {
  example: ExampleSummary;
  doc: ArchitectureDocument | undefined;
  busy: boolean;
  disabled: boolean;
  spotlight?: boolean;
  onOpen: () => void;
  className?: string;
}) {
  const meta = templateMeta(example);
  const hue = CATEGORY_COLOR[meta.category];
  const facts = doc ? templateFacts(doc) : null;

  const badges = (
    <div className="absolute inset-x-3 top-3 z-10 flex items-center gap-2">
      <span className="inline-flex items-center gap-1.5 rounded-full border border-line bg-surface/90 px-2.5 py-1 text-[11px] font-semibold text-ink backdrop-blur-sm">
        <span className="size-1.5 rounded-full" style={{ background: hue }} aria-hidden />
        {meta.category}
      </span>
      <span className="ml-auto inline-flex items-center gap-1.5 rounded-full border border-line bg-surface/90 px-2.5 py-1 text-[11px] font-medium text-ink-2 backdrop-blur-sm">
        <span className="flex gap-0.5" aria-hidden>
          {[1, 2, 3].map((i) => (
            <span key={i} className="h-2 w-1 rounded-full" style={{ background: i <= LEVEL_DOTS[meta.level] ? hue : "var(--line-strong)" }} />
          ))}
        </span>
        {meta.level}
      </span>
    </div>
  );

  const preview = (
    <div className={cn("relative overflow-hidden", spotlight ? "h-72 lg:h-full lg:min-h-[24rem]" : "h-56 border-b border-line")}>
      <div className="schematic-grid absolute inset-0" aria-hidden />
      <div
        className="absolute inset-0 opacity-75 transition-opacity duration-300 group-hover:opacity-100"
        style={{
          background:
            "radial-gradient(60% 70% at 15% 0%, color-mix(in srgb, var(--hue) 24%, transparent), transparent 70%), radial-gradient(50% 60% at 100% 100%, color-mix(in srgb, var(--hue) 16%, transparent), transparent 70%)",
        }}
        aria-hidden
      />
      <div className={cn("absolute inset-0", spotlight ? "px-10 pb-10 pt-16" : "px-4 pb-3 pt-11")}>
        {doc ? <TemplatePreview doc={doc} hue={hue} compact /> : <div className="h-full animate-pulse rounded-lg bg-surface-2/60" />}
      </div>
      {badges}
      {!spotlight && (
        <span
          className="accent-fill absolute bottom-3 right-3 z-10 inline-flex translate-y-2 items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold opacity-0 shadow-float transition-all duration-200 group-hover:translate-y-0 group-hover:opacity-100"
          aria-hidden
        >
          {busy ? <LoaderCircle className="size-3.5 animate-spin" /> : <Play className="size-3.5 fill-current" />} Use template
        </span>
      )}
    </div>
  );

  const frame = cn(
    "group relative h-full w-full overflow-hidden rounded-2xl border border-line bg-surface text-left",
    "transition-[transform,border-color,box-shadow] duration-200 hover:-translate-y-1 hover:border-[color-mix(in_srgb,var(--hue)_55%,transparent)]",
    "hover:shadow-[0_18px_40px_-18px_color-mix(in_srgb,var(--hue)_55%,transparent)] disabled:opacity-60",
    className,
  );

  if (spotlight) {
    return (
      <button
        type="button"
        onClick={onOpen}
        disabled={disabled}
        aria-label={`Open the ${example.name} template`}
        style={{ "--hue": hue } as React.CSSProperties}
        className={cn(frame, "grid lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]")}
      >
        <div className="border-b border-line lg:border-b-0 lg:border-r">{preview}</div>
        <div className="flex flex-col gap-4 p-6 lg:p-8">
          <p className="text-xs font-semibold" style={{ color: hue }}>
            Featured template
          </p>
          <h3 className="font-display text-[28px] font-semibold leading-tight tracking-[-0.035em] text-ink lg:text-[32px]">{example.name}</h3>
          <p className="flex gap-2 text-[15px] font-medium text-ink">
            <Eye className="mt-0.5 size-4 shrink-0" style={{ color: hue }} aria-hidden />
            <span>{meta.insight}</span>
          </p>
          <p className="text-sm leading-relaxed text-ink-2">{example.description}</p>
          {facts && facts.technologies.length > 0 && (
            <div>
              <p className="mb-2 text-xs font-medium text-ink-3">Built with</p>
              <ul className="flex flex-wrap gap-1.5">
                {facts.technologies.map((t) => (
                  <li key={t.technology} className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-surface-2 py-1 pl-1 pr-2.5 text-xs font-medium text-ink">
                    <TechIcon technology={t.technology} kind={t.kind} size={13} tile className="rounded-md" />
                    {technologyLabel(undefined, t.technology)}
                  </li>
                ))}
              </ul>
            </div>
          )}
          <div className="mt-auto space-y-4 border-t border-line pt-4">
            <Stats facts={facts} scenarios={example.scenarioCount} />
            <span className="accent-fill inline-flex h-11 items-center gap-2 rounded-xl px-5 text-sm font-semibold transition-transform group-hover:-translate-y-0.5">
              {busy ? <LoaderCircle className="size-4 animate-spin" /> : <Play className="size-4 fill-current" />} Use this template
              <ArrowRight className="size-4" />
            </span>
          </div>
        </div>
      </button>
    );
  }

  return (
    <button
      type="button"
      onClick={onOpen}
      disabled={disabled}
      aria-label={`Open the ${example.name} template`}
      style={{ "--hue": hue } as React.CSSProperties}
      className={cn(frame, "flex flex-col")}
    >
      {preview}
      <div className="flex flex-1 flex-col gap-3 p-5">
        <div className="flex items-start gap-2">
          <h3 className="min-w-0 flex-1 text-[17px] font-semibold tracking-[-0.015em] text-ink">{example.name}</h3>
          {busy ? (
            <LoaderCircle className="mt-1 size-4 shrink-0 animate-spin text-ink-3" />
          ) : (
            <ArrowRight className="mt-1 size-4 shrink-0 text-ink-3 transition-transform group-hover:translate-x-1 group-hover:text-[var(--hue)]" />
          )}
        </div>
        <p className="flex gap-2 text-[13px] font-medium text-ink">
          <Eye className="mt-0.5 size-4 shrink-0" style={{ color: hue }} aria-hidden />
          <span>{meta.insight}</span>
        </p>
        <p className="line-clamp-2 text-[13px] leading-relaxed text-ink-2" title={example.description ?? undefined}>
          {example.description}
        </p>
        <div className="mt-auto border-t border-line pt-3">
          <Stats facts={facts} scenarios={example.scenarioCount} />
        </div>
      </div>
    </button>
  );
}

"use client";

/** The templates gallery at /templates: search, category filters, a featured template and the rest. */
import { ArrowLeft, Plus, Search, Sparkles } from "lucide-react";
import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { Logo } from "@/components/brand";
import { Reveal } from "@/components/landing/Reveal";
import { NewProjectDialog } from "@/components/projects/ProjectsHome";
import { useProjectActions } from "@/components/projects/actions";
import { TemplateCard } from "@/components/templates/TemplateCard";
import { CATEGORY_COLOR, type TemplateCategory, orderTemplates, templateFacts, templateMeta } from "@/components/templates/templates-meta";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { api } from "@/lib/api";
import type { ArchitectureDocument, ExampleSummary } from "@/lib/types";
import { cn } from "@/lib/utils";

export function TemplatesGallery() {
  const [examples, setExamples] = useState<ExampleSummary[] | null>(null);
  const [docs, setDocs] = useState<Record<string, ArchitectureDocument>>({});
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState<TemplateCategory | "All">("All");
  const [newOpen, setNewOpen] = useState(false);
  const { busy, create } = useProjectActions();

  useEffect(() => {
    let alive = true;
    api
      .examples()
      .then(async (list) => {
        if (!alive) return;
        setExamples(orderTemplates(list));
        const loaded = await Promise.all(list.map((x) => api.example(x.id).catch(() => null)));
        if (!alive) return;
        setDocs(Object.fromEntries(list.flatMap((x, i) => (loaded[i] ? [[x.id, loaded[i]]] : []))));
      })
      .catch((e: unknown) => {
        if (!alive) return;
        setError(e instanceof Error ? e.message : String(e));
        setExamples([]);
      });
    return () => {
      alive = false;
    };
  }, []);

  const all = useMemo(() => examples ?? [], [examples]);
  const categories = useMemo(() => {
    const counts = new Map<TemplateCategory, number>();
    for (const ex of all) {
      const c = templateMeta(ex).category;
      counts.set(c, (counts.get(c) ?? 0) + 1);
    }
    return [...counts];
  }, [all]);
  const technologies = useMemo(() => {
    const set = new Set<string>();
    for (const d of Object.values(docs)) for (const t of templateFacts(d).technologies) set.add(t.technology);
    return set.size;
  }, [docs]);
  const scenarios = all.reduce((n, ex) => n + ex.scenarioCount, 0);

  const q = query.trim().toLowerCase();
  const shown = all.filter((ex) => {
    if (category !== "All" && templateMeta(ex).category !== category) return false;
    if (!q) return true;
    const hay = `${ex.name} ${ex.description ?? ""} ${ex.tags.join(" ")} ${templateMeta(ex).insight}`.toLowerCase();
    return q.split(/\s+/).every((w) => hay.includes(w));
  });
  const featured = category === "All" && !q ? shown[0] : undefined;
  const rest = featured ? shown.slice(1) : shown;

  return (
    <div className="min-h-full bg-page">
      <header className="sticky top-0 z-30 border-b border-line bg-page/95 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-7xl items-center gap-3 px-4 sm:px-6">
          <Logo />
          <Button variant="ghost" size="sm" asChild>
            <Link href="/">
              <ArrowLeft /> Home
            </Link>
          </Button>
          <Button variant="ghost" size="sm" asChild className="hidden sm:inline-flex">
            <Link href="/projects">Your projects</Link>
          </Button>
          <div className="ml-auto flex items-center gap-1.5">
            <ThemeToggle />
            <Button variant="primary" size="md" onClick={() => setNewOpen(true)} className="hidden sm:inline-flex">
              <Plus /> Start blank
            </Button>
          </div>
        </div>
      </header>

      {/* hero */}
      <section className="hero-band relative border-b border-line">
        <div className="mx-auto max-w-7xl px-4 py-14 sm:px-6 sm:py-20">
          <p className="inline-flex items-center gap-2 rounded-full border border-line bg-surface/80 px-3 py-1 text-[13px] font-medium text-ink-2 backdrop-blur-sm">
            <Sparkles className="size-3.5 text-accent" aria-hidden /> Templates
          </p>
          <h1 className="mt-5 max-w-3xl font-display text-[40px] font-semibold leading-[1.02] tracking-[-0.045em] text-band-ink sm:text-[60px]">
            Start from a system that <span className="text-gradient">already works</span>
          </h1>
          <p className="mt-5 max-w-2xl text-[17px] leading-relaxed text-ink-2">
            Real architectures with real technologies, each with scenarios ready to compare. Pick one, press Simulate, and watch it bend before you change a
            thing.
          </p>
          <dl className="mt-8 flex flex-wrap gap-x-10 gap-y-4">
            {[
              [all.length || "…", "templates"],
              [scenarios || "…", "scenarios to compare"],
              [technologies || "…", "technologies"],
            ].map(([n, label]) => (
              <div key={label as string}>
                <dd className="tabular font-display text-[34px] font-semibold leading-none tracking-[-0.04em] text-band-ink">{n}</dd>
                <dt className="mt-1.5 text-[13px] text-ink-2">{label}</dt>
              </div>
            ))}
          </dl>
        </div>
      </section>

      <main className="mx-auto max-w-7xl px-4 py-10 sm:px-6">
        {/* filters */}
        <div className="sticky top-14 z-20 -mx-4 mb-8 flex flex-wrap items-center gap-3 border-b border-line bg-page/95 px-4 py-3 backdrop-blur sm:-mx-6 sm:px-6">
          <div className="flex flex-wrap gap-1.5" role="tablist" aria-label="Categories">
            {(["All", ...categories.map(([c]) => c)] as const).map((c) => {
              const count = c === "All" ? all.length : (categories.find(([k]) => k === c)?.[1] ?? 0);
              const active = category === c;
              return (
                <button
                  key={c}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  onClick={() => setCategory(c)}
                  className={cn(
                    "inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-[13px] font-medium transition-colors",
                    active ? "border-ink bg-ink text-page" : "border-line bg-surface text-ink-2 hover:border-line-strong hover:text-ink",
                  )}
                >
                  {c !== "All" && <span className="size-1.5 rounded-full" style={{ background: CATEGORY_COLOR[c] }} aria-hidden />}
                  {c}
                  <span className={cn("tabular text-xs", active ? "text-page/70" : "text-ink-3")}>{count}</span>
                </button>
              );
            })}
          </div>
          <div className="relative ml-auto w-full sm:w-72">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-ink-3" aria-hidden />
            <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search kafka, sharding, retries…" aria-label="Search templates" className="pl-9" />
          </div>
        </div>

        {error && (
          <div role="alert" className="mb-8 rounded-xl bg-critical-soft p-4 text-sm text-ink">
            <p className="font-medium">Cannot reach the ArchSim API</p>
            <p className="mt-1 text-ink-2">{error}</p>
          </div>
        )}

        {examples === null ? (
          <div className="grid gap-5 md:grid-cols-2 lg:grid-cols-3">
            {[0, 1, 2, 3, 4, 5].map((i) => (
              <div key={i} className={cn("animate-pulse rounded-2xl bg-surface-2", i === 0 ? "h-[26rem] md:col-span-2 lg:col-span-3" : "h-[24rem]")} />
            ))}
          </div>
        ) : shown.length === 0 ? (
          <p className="py-16 text-center text-sm text-ink-3">No template matches “{query}”.</p>
        ) : (
          <div className="grid gap-5 md:grid-cols-2 lg:grid-cols-3">
            {featured && (
              <Reveal className="md:col-span-2 lg:col-span-3">
                <TemplateCard
                  example={featured}
                  doc={docs[featured.id]}
                  busy={busy === featured.id}
                  disabled={busy !== null}
                  spotlight
                  onOpen={() => void create({ exampleId: featured.id }, featured.id)}
                />
              </Reveal>
            )}
            {rest.map((ex, i) => (
              <Reveal key={ex.id} delay={(i % 3) * 60}>
                <TemplateCard
                  example={ex}
                  doc={docs[ex.id]}
                  busy={busy === ex.id}
                  disabled={busy !== null}
                  onOpen={() => void create({ exampleId: ex.id }, ex.id)}
                />
              </Reveal>
            ))}
          </div>
        )}

        <div className="mt-14 flex flex-col items-center gap-3 rounded-2xl border border-dashed border-line-strong px-6 py-10 text-center">
          <p className="text-[15px] font-semibold text-ink">Have something else in mind?</p>
          <p className="max-w-md text-[13px] text-ink-2">Start from a blank canvas and drag in the parts you run, or import a document you exported earlier.</p>
          <Button variant="primary" onClick={() => setNewOpen(true)}>
            <Plus /> Start blank
          </Button>
        </div>
      </main>

      <NewProjectDialog open={newOpen} onOpenChange={setNewOpen} examples={all} busy={busy} onCreate={(body, key) => void create(body, key)} />
    </div>
  );
}

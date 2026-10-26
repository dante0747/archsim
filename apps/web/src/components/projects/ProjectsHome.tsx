"use client";

import { ArrowRight, LoaderCircle, Play, Plus, Upload } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Logo, LogoMark } from "@/components/brand";
import { FeatureBento } from "@/components/landing/FeatureBento";
import { HeroDemo } from "@/components/landing/HeroDemo";
import { HowItWorks } from "@/components/landing/HowItWorks";
import { Reveal } from "@/components/landing/Reveal";
import { TechMarquee } from "@/components/landing/TechMarquee";
import { TemplateCard } from "@/components/templates/TemplateCard";
import { orderTemplates } from "@/components/templates/templates-meta";
import { type CreateBody, useProjectActions } from "@/components/projects/actions";
import { Button } from "@/components/ui/button";
import { Input, Label } from "@/components/ui/input";
import { Dialog, DialogContent } from "@/components/ui/overlays";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { api } from "@/lib/api";
import type { ArchitectureDocument, Catalog, ExampleSummary, ProjectSummary } from "@/lib/types";
import { cn } from "@/lib/utils";

export function ProjectsHome() {
  const [projects, setProjects] = useState<ProjectSummary[] | null>(null);
  const [examples, setExamples] = useState<ExampleSummary[]>([]);
  const [exampleDocs, setExampleDocs] = useState<Record<string, ArchitectureDocument>>({});
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [newOpen, setNewOpen] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const { busy, create, openTemplate, importFile } = useProjectActions();

  useEffect(() => {
    let alive = true;
    api
      .components()
      .then((c) => alive && setCatalog(c))
      .catch(() => undefined);
    Promise.all([api.listProjects(), api.examples()])
      .then(async ([p, e]) => {
        if (!alive) return;
        setProjects(p);
        setExamples(e);
        const docs = await Promise.all(e.map((x) => api.example(x.id).catch(() => null)));
        if (!alive) return;
        setExampleDocs(Object.fromEntries(e.flatMap((x, i) => (docs[i] ? [[x.id, docs[i]]] : []))));
      })
      .catch((e: unknown) => {
        if (!alive) return;
        setError(e instanceof Error ? e.message : String(e));
        setProjects([]);
      });
    return () => {
      alive = false;
    };
  }, []);

  const starter = examples.find((e) => e.id === "cache-aside-starter") ?? examples[0];
  const recent = projects ? [...projects].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)) : [];
  const latest = recent[0] ?? null;
  const presetCount = catalog ? catalog.components.reduce((n, c) => n + (c.presets?.length ?? 0), 0) : null;
  const presetTiers = catalog ? catalog.components.flatMap((c) => (c.presets ?? []).map((p) => p.tier ?? null)) : null;
  const tierCount = (t: string | null) => presetTiers?.filter((x) => x === t).length ?? 0;
  const clientTypes = catalog ? catalog.components.filter((c) => c.isTrafficSource).length : null;
  const startStarter = () => starter && void openTemplate(starter.id);

  const nav = [
    ["#how", "How it works"],
    ["#features", "Features"],
    ["/templates", "Templates"],
    ["/projects", projects && projects.length > 0 ? `Your projects · ${projects.length}` : "Your projects"],
  ];

  return (
    <div className="min-h-full overflow-x-clip bg-page">
      <header className="sticky top-0 z-30 border-b border-line bg-page">
        <div className="mx-auto flex h-14 max-w-7xl items-center gap-6 px-4 sm:px-6">
          <Logo />
          <nav className="hidden items-center gap-1 md:flex" aria-label="Sections">
            {nav.map(([href, label]) => (
              <a key={href} href={href} className="rounded-lg px-3 py-1.5 text-[13px] font-medium text-ink-2 transition-colors hover:bg-surface-2 hover:text-ink">
                {label}
              </a>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-1.5">
            <Button variant="ghost" size="sm" asChild className="hidden sm:inline-flex">
              <a href="/api/v1/docs" target="_blank" rel="noreferrer">
                API docs
              </a>
            </Button>
            <ThemeToggle />
            {starter && (
              <Button variant="primary" size="md" onClick={startStarter} disabled={busy !== null} className="hidden sm:inline-flex">
                Try it now <ArrowRight />
              </Button>
            )}
          </div>
        </div>
      </header>

      {/* ---------------------------------------------------------------- hero */}
      <section className="relative">
        <div className="schematic-grid absolute inset-0 opacity-70" aria-hidden />
        <div className="relative mx-auto max-w-6xl px-4 pt-16 text-center sm:px-6 sm:pt-24">
          <p className="pop-in mx-auto inline-flex items-center gap-2 rounded-full border border-line bg-surface py-1 pl-1 pr-3 text-[13px] text-ink-2">
            <span className="rounded-full bg-accent px-2 py-0.5 text-[11px] font-semibold text-[var(--accent-ink)]">New</span>
            Live dashboard · technology behaviour from vendor docs · the maths behind every number
          </p>
          <h1 className="mt-7 font-display text-[44px] font-semibold leading-[0.98] tracking-[-0.05em] text-ink sm:text-[64px] lg:text-[80px]">
            Crash-test your architecture
            <br />
            <span className="text-gradient">before your users do.</span>
          </h1>
          <p className="mx-auto mt-7 max-w-2xl text-[17px] leading-[1.6] text-ink-2 sm:text-lg">
            ArchSim is a flight simulator for distributed systems. Draw your stack with real technologies, throw traffic and failures at it, and get a
            straight yes or no, with the monthly bill.
          </p>
          <div className="mt-9 flex flex-wrap justify-center gap-3">
            {starter && (
              <Button variant="primary" size="xl" onClick={startStarter} disabled={busy !== null}>
                {busy === starter.id ? <LoaderCircle className="animate-spin" /> : <Play className="fill-current" />} Open the starter example
              </Button>
            )}
            <Button size="xl" onClick={() => setNewOpen(true)}>
              <Plus /> New project
            </Button>
            <input
              ref={fileRef}
              type="file"
              accept="application/json,.json"
              className="hidden"
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) void importFile(f);
                e.target.value = "";
              }}
            />
            <Button size="xl" variant="ghost" onClick={() => fileRef.current?.click()} disabled={busy !== null}>
              {busy === "import" ? <LoaderCircle className="animate-spin" /> : <Upload />} Import JSON
            </Button>
          </div>
          <p className="mt-5 text-[13px] text-ink-3">
            {latest ? (
              <Link href={`/projects/${latest.id}`} className="group inline-flex items-center gap-1 text-ink-2 hover:text-ink">
                Continue <span className="font-medium text-ink">{latest.name}</span>
                <ArrowRight className="size-3.5 transition-transform group-hover:translate-x-0.5" />
              </Link>
            ) : (
              "Open source · runs on your machine · no account, no telemetry"
            )}
          </p>
        </div>

        {/* product shot on a console band */}
        <div className="relative mt-14 sm:mt-20">
          <div className="hero-band absolute inset-x-0 bottom-0 top-[30%]" aria-hidden />
          <div className="relative mx-auto max-w-6xl px-4 pb-14 sm:px-6 sm:pb-20">
            <Reveal>
              <figure className="overflow-hidden rounded-2xl border border-line bg-surface shadow-float">
                <div className="flex items-center gap-3 border-b border-line px-4 py-2.5">
                  <LogoMark className="size-6" />
                  <span className="hidden truncate text-[13px] font-semibold text-ink sm:inline">Black Friday on Kubernetes</span>
                  <div className="mx-auto flex items-center gap-0.5 rounded-[9px] bg-[color:var(--seg)] p-[3px] text-[12px] font-medium">
                    {["Design", "Test", "Results"].map((w) => (
                      <span key={w} className={cn("rounded-[6px] px-2.5 py-1", w === "Test" ? "bg-[color:var(--seg-on)] text-ink" : "text-ink-3")}>
                        {w}
                      </span>
                    ))}
                  </div>
                  <span className="hidden items-center gap-2 rounded-[8px] border border-[color:var(--record-line)] bg-[color:var(--record-fill)] px-2.5 py-1 text-[12px] font-semibold text-ink sm:inline-flex">
                    <span className="size-2 rounded-full bg-[color:var(--record)]" /> Simulate
                  </span>
                </div>
                <HeroDemo />
              </figure>
            </Reveal>
          </div>
        </div>
      </section>

      <main>
        {error && (
          <div className="mx-auto max-w-7xl px-4 pt-10 sm:px-6">
            <div role="alert" className="rounded-xl bg-critical-soft p-4 text-sm text-ink">
              <p className="font-medium">Cannot reach the ArchSim API</p>
              <p className="mt-1 text-ink-2">{error}</p>
            </div>
          </div>
        )}

        {/* ---------------------------------------------------------- numbers */}
        <section aria-label="At a glance" className="border-b border-line">
          <dl className="mx-auto grid max-w-7xl grid-cols-2 gap-px px-4 py-12 sm:px-6 lg:grid-cols-4">
            {[
              [
                presetCount ?? "…",
                "technology profiles",
                presetTiers
                  ? `${tierCount("core")} core, ${tierCount("experimental")} experimental${tierCount(null) ? `, ${tierCount(null)} third-party APIs` : ""}`
                  : "PostgreSQL to Kafka to serverless",
              ],
              [examples.length || "…", "ready-made templates", "each with scenarios to compare"],
              [clientTypes ?? "…", "kinds of clients", "mobile, web, API consumers, generic"],
              ["0", "bytes leave your machine", "local, open source, no account"],
            ].map(([n, label, sub], i) => (
              <Reveal key={label as string} delay={i * 70}>
                <div className="px-2 py-3">
                  <dd className="tabular font-display text-[44px] font-semibold leading-none tracking-[-0.04em] text-ink">{n}</dd>
                  <dt className="mt-2 text-[15px] font-medium text-ink">{label}</dt>
                  <p className="mt-0.5 text-[13px] text-ink-3">{sub}</p>
                </div>
              </Reveal>
            ))}
          </dl>
        </section>

        {/* ------------------------------------------------------- tech wall */}
        {catalog && (
          <section aria-labelledby="tech-heading" className="py-20">
            <Reveal className="mx-auto max-w-3xl px-4 text-center sm:px-6">
              <p className="text-[13px] font-semibold text-accent">Real technologies</p>
              <h2 id="tech-heading" className="mt-3 font-display text-[36px] font-semibold leading-[1.05] tracking-[-0.04em] text-ink sm:text-[48px]">
                Model the stack you actually run
              </h2>
              <p className="mt-4 text-[17px] leading-relaxed text-ink-2">
                Pick a technology and it brings the mechanisms its documentation describes: PostgreSQL locks rows and waits for its log, Redis runs
                commands on one thread, Kafka keeps keys in order on one partition. Every behaviour links to the vendor&apos;s documentation and can be
                overridden. A core set (PostgreSQL, Redis, Kafka, SQS, Nginx and ALB-style load balancers, Node.js, Java and Go services) comes first;
                the others are marked experimental. Their numbers are illustrative, and no technology is validated against measurements yet.
              </p>
            </Reveal>
            <div className="mt-12">
              <TechMarquee catalog={catalog} />
            </div>
          </section>
        )}

        {/* ---------------------------------------------------- how it works */}
        <section id="how" aria-labelledby="how-heading" className="scroll-mt-16 border-t border-line bg-surface-2/40 py-20">
          <div className="mx-auto max-w-7xl px-4 sm:px-6">
            <Reveal className="max-w-2xl">
              <p className="text-[13px] font-semibold text-accent">How it works</p>
              <h2 id="how-heading" className="mt-3 font-display text-[36px] font-semibold leading-[1.05] tracking-[-0.04em] text-ink sm:text-[48px]">
                Sketch it. Shake it. Ship the one that holds.
              </h2>
            </Reveal>
            <div className="mt-12">
              <HowItWorks />
            </div>
          </div>
        </section>

        {/* -------------------------------------------------------- features */}
        <section id="features" aria-labelledby="features-heading" className="scroll-mt-16 py-20">
          <div className="mx-auto max-w-7xl px-4 sm:px-6">
            <Reveal className="max-w-2xl">
              <p className="text-[13px] font-semibold text-accent">Features</p>
              <h2 id="features-heading" className="mt-3 font-display text-[36px] font-semibold leading-[1.05] tracking-[-0.04em] text-ink sm:text-[48px]">
                Everything a design review argues about, measured
              </h2>
            </Reveal>
            <div className="mt-12">
              <FeatureBento />
            </div>
          </div>
        </section>

        {/* ------------------------------------------------------- templates */}
        <section id="templates" aria-labelledby="examples-heading" className="scroll-mt-16 border-t border-line bg-surface-2/40 py-20">
          <div className="mx-auto max-w-7xl px-4 sm:px-6">
            <Reveal className="flex flex-wrap items-end justify-between gap-4">
              <div className="max-w-2xl">
                <p className="text-[13px] font-semibold text-accent">Templates</p>
                <h2 id="examples-heading" className="mt-3 font-display text-[36px] font-semibold leading-[1.05] tracking-[-0.04em] text-ink sm:text-[48px]">
                  Start from a working system
                </h2>
                <p className="mt-3 text-[17px] text-ink-2">Each one comes with scenarios ready to compare. Open it, press Simulate.</p>
              </div>
            </Reveal>
            <div className="mt-10 grid gap-5 md:grid-cols-2 lg:grid-cols-3">
              {orderTemplates(examples)
                .slice(0, 3)
                .map((ex, i) => (
                  <Reveal key={ex.id} delay={i * 70}>
                    <TemplateCard
                      example={ex}
                      doc={exampleDocs[ex.id]}
                      busy={busy === ex.id}
                      disabled={busy !== null}
                      onOpen={() => void openTemplate(ex.id)}
                    />
                  </Reveal>
                ))}
              {examples.length === 0 && !error && [0, 1, 2].map((i) => <div key={i} className="h-[24rem] animate-pulse rounded-2xl bg-surface-2" />)}
            </div>
            {examples.length > 3 && (
              <div className="mt-8 flex justify-center">
                <Button size="lg" asChild>
                  <Link href="/templates">
                    Browse all {examples.length} templates <ArrowRight />
                  </Link>
                </Button>
              </div>
            )}
          </div>
        </section>

        {/* ------------------------------------------------------ final call */}
        <section className="px-4 pb-20 sm:px-6">
          <Reveal className="mx-auto max-w-7xl">
            <div className="hero-band relative overflow-hidden rounded-3xl px-6 py-16 text-center sm:px-12 sm:py-20">
              <h2 className="relative font-display text-[36px] font-semibold leading-[1.02] tracking-[-0.045em] text-band-ink sm:text-[56px]">
                Ready to break something?
              </h2>
              <p className="relative mx-auto mt-4 max-w-xl text-[17px] leading-relaxed text-band-ink opacity-80">
                Open the starter example and press Simulate. Your first answer is seconds away.
              </p>
              <div className="relative mt-8 flex flex-wrap justify-center gap-3">
                {starter && (
                  <button
                    type="button"
                    onClick={startStarter}
                    disabled={busy !== null}
                    className="accent-fill inline-flex h-12 items-center gap-2 rounded-xl px-6 text-[15px] font-semibold transition-transform hover:-translate-y-0.5 disabled:opacity-60"
                  >
                    <Play className="size-[18px] fill-current" /> Open the starter example
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => setNewOpen(true)}
                  className="inline-flex h-12 items-center gap-2 rounded-xl border border-band-ink/30 px-6 text-[15px] font-semibold text-band-ink transition-colors hover:bg-band-ink/10"
                >
                  <Plus className="size-[18px]" /> Start blank
                </button>
              </div>
            </div>
          </Reveal>
        </section>
      </main>

      <footer className="border-t border-line">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 gap-y-3 px-4 py-8 text-[13px] text-ink-3 sm:px-6">
          <Logo />
          <span>AGPL-3.0 · open source</span>
          <span>Runs on your machine · no account, no telemetry</span>
          <a href="/api/v1/docs" target="_blank" rel="noreferrer" className="hover:text-ink">
            API docs
          </a>
          <span className="sm:ml-auto">Estimates from your assumptions; calibrate before trusting absolute numbers.</span>
        </div>
      </footer>

      <NewProjectDialog open={newOpen} onOpenChange={setNewOpen} examples={examples} busy={busy} onCreate={(body, key) => void create(body, key)} />
    </div>
  );
}

export function NewProjectDialog({
  open,
  onOpenChange,
  examples,
  busy,
  onCreate,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  examples: ExampleSummary[];
  busy: string | null;
  onCreate: (body: CreateBody, key: string) => void;
}) {
  const [name, setName] = useState("");
  const [choice, setChoice] = useState<string>("blank");
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title="New project" description="Start blank or from one of the example architectures.">
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            onCreate(
              choice === "blank"
                ? { name: name.trim() || "Untitled architecture" }
                : {
                    exampleId: choice,
                    ...(name.trim() ? { name: name.trim() } : {}),
                  },
              "dialog",
            );
          }}
        >
          <div className="space-y-1.5">
            <Label htmlFor="project-name">Name</Label>
            <Input
              id="project-name"
              value={name}
              placeholder={choice === "blank" ? "Untitled architecture" : "Use the example's name"}
              onChange={(e) => setName(e.target.value)}
              maxLength={120}
              autoFocus
            />
          </div>
          <fieldset className="space-y-1.5">
            <legend className="microlabel mb-2">Start from</legend>
            {[
              {
                id: "blank",
                name: "Blank canvas",
                description: "An empty architecture.",
              },
              ...examples,
            ].map((o) => (
              <label
                key={o.id}
                className={cn(
                  "flex cursor-pointer gap-3 rounded-md border p-3 transition-colors",
                  choice === o.id ? "border-accent bg-accent-soft" : "border-line hover:bg-surface-2",
                )}
              >
                <input
                  type="radio"
                  name="start"
                  value={o.id}
                  checked={choice === o.id}
                  onChange={() => setChoice(o.id)}
                  className="mt-0.5 accent-[var(--accent)]"
                />
                <span>
                  <span className="block text-sm font-medium text-ink">{o.name}</span>
                  <span className="line-clamp-2 block text-xs text-ink-2">{o.description}</span>
                </span>
              </label>
            ))}
          </fieldset>
          <div className="flex justify-end gap-2">
            <Button onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" variant="primary" disabled={busy !== null}>
              {busy === "dialog" && <LoaderCircle className="animate-spin" />} Create
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

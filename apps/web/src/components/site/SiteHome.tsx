/**
 * The public website's home page (ADR-0078). The copy that states facts comes from the
 * repository at build time: the README's reasons, screenshots, quick start, license and
 * contributing sections, the accuracy page's data and the catalog's technologies. The landing
 * pieces (the hero's replay, how it works, the feature grid, the logo wall) are the app's own.
 */
import { Activity, ArrowRight, ArrowUpRight, Boxes, CircleCheck, Gauge, Lock, type LucideIcon, Scale, Sparkles, TriangleAlert } from "lucide-react";
import Link from "next/link";
import { FeatureBento } from "@/components/landing/FeatureBento";
import { HeroShot } from "@/components/landing/HeroShot";
import { HowItWorks } from "@/components/landing/HowItWorks";
import { Reveal } from "@/components/landing/Reveal";
import { TechMarquee } from "@/components/landing/TechMarquee";
import { Button } from "@/components/ui/button";
import { REPOSITORY_URL, repositoryUrl } from "@/lib/repository";
import type { AccuracyHighlights } from "@/lib/site/accuracy-highlights";
import type { ReadmeContent } from "@/lib/site/readme";
import type { Chip } from "@/lib/tech-chips";
import { cn } from "@/lib/utils";
import { CopyButton } from "./CopyButton";
import { GitHubMark } from "./GitHubMark";
import { Prose } from "./Prose";

export interface SiteHomeProps {
  readme: ReadmeContent;
  accuracy: AccuracyHighlights;
  chips: Chip[];
  /** The catalog's counts (`catalogStats`) and the templates'. */
  stats: { technologies: number; tiers: string; clientKinds: number; templates: number; scenarios: number };
  /** ArchSim's version, which the changelog's latest release names. */
  version: string;
  demoUrl: string | null;
}

/** One icon per reason of the README's "Why ArchSim", in its order. */
const WHY_ICONS: LucideIcon[] = [Boxes, Activity, CircleCheck, Scale, Lock];

function Head({ eyebrow, title, id, lead, center }: { eyebrow: string; title: string; id: string; lead?: React.ReactNode; center?: boolean }) {
  return (
    <Reveal className={cn("max-w-3xl", center && "mx-auto text-center")}>
      <p className="text-[13px] font-semibold text-accent">{eyebrow}</p>
      <h2 id={id} className="mt-3 font-display text-[34px] font-semibold leading-[1.05] tracking-[-0.04em] text-ink sm:text-[46px]">
        {title}
      </h2>
      {lead && <p className="mt-4 text-[17px] leading-relaxed text-ink-2">{lead}</p>}
    </Reveal>
  );
}

function Hero({ version, demoUrl }: Pick<SiteHomeProps, "version" | "demoUrl">) {
  return (
    <section aria-labelledby="hero-title" className="relative">
      <div className="schematic-grid absolute inset-0 opacity-70 [mask-image:linear-gradient(#000_60%,transparent)]" aria-hidden />
      <div className="relative mx-auto max-w-6xl px-4 pt-14 text-center sm:px-6 sm:pt-24">
        <Link
          href="/changelog/"
          className="pop-in mx-auto inline-flex max-w-full items-center gap-2 rounded-full border border-line bg-surface py-1 pl-1 pr-3 text-[13px] text-ink-2 transition-colors hover:text-ink"
        >
          <span className="rounded-full bg-accent px-2 py-0.5 text-[11px] font-semibold text-[var(--accent-ink)]">v{version}</span>
          <span className="truncate">Open source, out now: see what&apos;s in it</span>
          <ArrowRight className="size-3.5 shrink-0" aria-hidden />
        </Link>
        <h1 id="hero-title" className="mt-7 font-display text-[40px] font-semibold leading-[1] tracking-[-0.05em] text-ink min-[400px]:text-[44px] sm:text-[64px] lg:text-[80px]">
          Crash-test your architecture <br className="hidden sm:block" />
          <span className="text-gradient">before your users do.</span>
        </h1>
        <p className="mx-auto mt-7 max-w-2xl text-[17px] leading-[1.6] text-ink-2 sm:text-lg">
          ArchSim is a flight simulator for distributed systems. Draw your stack with real technologies, throw traffic and failures at it, and get a
          straight yes or no against your objectives, with the bottleneck, the breaking point and the monthly bill.
        </p>
        <div className="mt-9 flex flex-wrap justify-center gap-3">
          {demoUrl && (
            <Button variant="primary" size="xl" asChild>
              <a href={demoUrl}>
                Try it live <ArrowUpRight />
              </a>
            </Button>
          )}
          <Button variant={demoUrl ? "secondary" : "primary"} size="xl" asChild>
            <a href="#quick-start">
              Get started <ArrowRight />
            </a>
          </Button>
          <Button size="xl" asChild>
            <Link href="/docs/">Read the docs</Link>
          </Button>
          <Button size="xl" variant="ghost" asChild>
            <a href={REPOSITORY_URL}>
              <GitHubMark className="size-[18px]" /> View on GitHub
            </a>
          </Button>
        </div>
        <p className="mt-5 text-[13px] text-ink-3">Open source · run it on your own machine: no account, no telemetry</p>
      </div>
      <HeroShot />
    </section>
  );
}

function Stats({ stats }: Pick<SiteHomeProps, "stats">) {
  const items: [React.ReactNode, string, string][] = [
    [stats.technologies, "technology profiles", stats.tiers],
    [stats.templates, "ready-made templates", `${stats.scenarios} scenarios to compare`],
    [stats.clientKinds, "kinds of clients", "mobile, web, API consumers, generic"],
    ["0", "bytes leave your machine", "when you run it yourself: no account"],
  ];
  return (
    <section aria-label="At a glance" className="border-b border-line">
      <dl className="mx-auto grid max-w-7xl grid-cols-2 gap-x-4 gap-y-6 px-4 py-12 sm:px-6 lg:grid-cols-4">
        {items.map(([n, label, sub], i) => (
          <Reveal key={label} delay={i * 70}>
            <div className="flex flex-col-reverse px-2 py-3">
              <div>
                <dt className="mt-2 text-[15px] font-medium text-ink">{label}</dt>
                <p className="mt-0.5 text-[13px] text-ink-3">{sub}</p>
              </div>
              <dd className="tabular font-display text-[40px] font-semibold leading-none tracking-[-0.04em] text-ink sm:text-[44px]">{n}</dd>
            </div>
          </Reveal>
        ))}
      </dl>
    </section>
  );
}

function QuickStart({ readme }: Pick<SiteHomeProps, "readme">) {
  const { needs, commands, next } = readme.quickStart;
  return (
    <section id="quick-start" aria-labelledby="quick-start-title" className="scroll-mt-16 py-20 sm:py-24">
      <div className="mx-auto grid max-w-7xl items-center gap-10 px-4 sm:px-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)] lg:gap-14">
        <div>
          <Head eyebrow="Quick start" title="Up and running in one command" id="quick-start-title" />
          <Reveal delay={60}>
            <p className="mt-5 text-[17px] font-medium text-ink">{needs}</p>
            <Prose html={next} className="mt-3 text-[15px] text-ink-2" />
            <Link href="/docs/getting-started/" className="mt-5 inline-flex items-center gap-1.5 text-[14px] font-semibold text-accent hover:underline">
              Run from source, the CLI or the MCP server <ArrowRight className="size-4" aria-hidden />
            </Link>
          </Reveal>
        </div>
        <Reveal delay={120} className="min-w-0">
          <figure className="overflow-hidden rounded-2xl border border-line bg-[color:var(--lcd)] shadow-float">
            <figcaption className="flex items-center gap-2 border-b border-line px-4 py-2">
              <span className="flex gap-1.5" aria-hidden>
                <span className="size-2.5 rounded-full bg-[color:var(--status-critical)] opacity-80" />
                <span className="size-2.5 rounded-full bg-[color:var(--status-warning)] opacity-80" />
                <span className="size-2.5 rounded-full bg-[color:var(--status-good)] opacity-80" />
              </span>
              <span className="ml-2 text-xs font-medium text-ink-3">Terminal</span>
              <CopyButton text={commands} label="Copy the commands" className="ml-auto" />
            </figcaption>
            <pre className="whitespace-pre-wrap px-5 py-5 font-code text-[13px] leading-[1.8] text-ink [overflow-wrap:anywhere] sm:text-[14px]">
              {commands.split("\n").map((line, i) => {
                const at = line.search(/\s+#/);
                return (
                  <span key={i} className="block pl-[1.6em] -indent-[1.6em] before:inline-block before:w-[1.6em] before:indent-0 before:text-ink-3 before:content-['$']">
                    {at < 0 ? line : line.slice(0, at)}
                    {at >= 0 && <span className="text-ink-3">{line.slice(at)}</span>}
                    {i < commands.split("\n").length - 1 ? "\n" : ""}
                  </span>
                );
              })}
            </pre>
          </figure>
        </Reveal>
      </div>
    </section>
  );
}

function Why({ readme }: Pick<SiteHomeProps, "readme">) {
  return (
    <section id="why" aria-labelledby="why-title" className="scroll-mt-16 border-t border-line bg-surface-2/40 py-20 sm:py-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <Head eyebrow="Why ArchSim" title="An honest simulator for the decisions that are expensive to get wrong" id="why-title" />
        <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-6">
          {readme.why.map((reason, i) => {
            const Icon = WHY_ICONS[i] ?? Sparkles;
            return (
              <Reveal key={reason.title} delay={(i % 3) * 70} className={i < 2 ? "lg:col-span-3" : "lg:col-span-2"}>
                <article className="flex h-full flex-col rounded-2xl border border-line bg-surface p-6">
                  <span className="flex size-10 items-center justify-center rounded-xl bg-accent-soft text-accent">
                    <Icon className="size-5" aria-hidden />
                  </span>
                  <h3 className="mt-5 text-lg font-semibold tracking-[-0.02em] text-ink">{reason.title}</h3>
                  <p className="mt-2 text-[15px] leading-relaxed text-ink-2">{reason.text}</p>
                </article>
              </Reveal>
            );
          })}
        </div>
      </div>
    </section>
  );
}

function How({ readme }: Pick<SiteHomeProps, "readme">) {
  return (
    <section id="how" aria-labelledby="how-title" className="scroll-mt-16 py-20 sm:py-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <Head eyebrow="How it works" title="Sketch it. Shake it. Ship the one that holds." id="how-title" />
        <div className="mt-12">
          <HowItWorks />
        </div>
        <Reveal className="mt-6">
          <div className="grid gap-6 rounded-2xl border border-line bg-surface p-6 sm:p-8 lg:grid-cols-[13rem_minmax(0,1fr)]">
            <div>
              <p className="text-[13px] font-semibold text-accent">Under the hood</p>
              <p className="mt-1 text-lg font-semibold tracking-[-0.02em] text-ink">One engine, every request</p>
            </div>
            <div>
              <Prose html={readme.howItWorks} className="text-[15px] text-ink-2" />
              <div className="mt-4 flex flex-wrap gap-x-6 gap-y-2 text-[14px] font-semibold">
                <Link href="/docs/architecture/" className="inline-flex items-center gap-1.5 text-accent hover:underline">
                  Architecture <ArrowRight className="size-4" aria-hidden />
                </Link>
                <Link href="/docs/simulation-model/" className="inline-flex items-center gap-1.5 text-accent hover:underline">
                  Simulation model <ArrowRight className="size-4" aria-hidden />
                </Link>
              </div>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

function Tour({ readme }: Pick<SiteHomeProps, "readme">) {
  return (
    <section id="tour" aria-labelledby="tour-title" className="scroll-mt-16 border-t border-line bg-surface-2/40 py-20 sm:py-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <Head eyebrow="The product" title="From a sketch to a straight answer" id="tour-title" lead="Real screenshots of ArchSim, the same ones the README shows." />
        <div className="mt-12 grid gap-5 md:grid-cols-2">
          {readme.features.map((f, i) => (
            <Reveal key={f.title} delay={(i % 2) * 70}>
              <figure className="flex h-full flex-col overflow-hidden rounded-2xl border border-line bg-surface">
                <div className="border-b border-line bg-[color:var(--canvas)]">
                  {/* eslint-disable-next-line @next/next/no-img-element -- a static export serves images as they are, with no optimizer */}
                  <img src={f.image} alt={f.alt} width={800} height={500} loading="lazy" decoding="async" className="aspect-[8/5] h-auto w-full object-cover object-top" />
                </div>
                <figcaption className="p-5 sm:p-6">
                  <span className="block text-lg font-semibold tracking-[-0.02em] text-ink">{f.title}</span>
                  <span className="mt-1 block text-[15px] leading-relaxed text-ink-2">{f.caption}</span>
                </figcaption>
              </figure>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}

function Features({ readme }: Pick<SiteHomeProps, "readme">) {
  return (
    <section id="features" aria-labelledby="features-title" className="scroll-mt-16 py-20 sm:py-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <Head eyebrow="Features" title="Everything a design review argues about, measured" id="features-title" />
        <div className="mt-12">
          <FeatureBento />
        </div>
        <Reveal className="mt-6">
          <div className="grid gap-6 rounded-2xl border border-line bg-surface p-6 sm:p-8 lg:grid-cols-[13rem_minmax(0,1fr)]">
            <div>
              <p className="text-[13px] font-semibold text-accent">Beyond the canvas</p>
              <p className="mt-1 text-lg font-semibold tracking-[-0.02em] text-ink">Fits the way you already work</p>
            </div>
            <div>
              <Prose html={readme.beyond} className="text-[15px] text-ink-2" />
              <Link href="/docs/features/" className="mt-4 inline-flex items-center gap-1.5 text-[14px] font-semibold text-accent hover:underline">
                Every workspace and technology behaviour <ArrowRight className="size-4" aria-hidden />
              </Link>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

function Technologies({ chips, stats }: Pick<SiteHomeProps, "chips" | "stats">) {
  return (
    <section id="technologies" aria-labelledby="tech-title" className="scroll-mt-16 border-t border-line bg-surface-2/40 py-20 sm:py-24">
      <Head
        center
        eyebrow="Real technologies"
        title="Model the stack you actually run"
        id="tech-title"
        lead={`${stats.technologies} technology profiles bring the mechanisms their documentation describes, each linked to the vendor's docs and each a setting you can override. A core set comes first; the others are marked experimental.`}
      />
      <div className="mt-12">
        <TechMarquee chips={chips} />
      </div>
    </section>
  );
}

function AccuracySummary({ accuracy: a }: Pick<SiteHomeProps, "accuracy">) {
  const h = a.heldOut;
  const t = a.tunedOn;
  const cards: { value: string; label: string; note: string; tone: "good" | "warn" | "plain" }[] = [];
  if (h) {
    const notes = [
      h.over ? `${h.over} over-predicted capacity, by ${h.overRange}: the unsafe side.` : "",
      h.under ? `${h.under} under-predicted it, the safe side.` : "",
    ].filter(Boolean);
    cards.push({ value: `${h.within} of ${h.total}`, label: `held-out knees predicted within ${a.tolerance}`, note: notes.join(" ") || "Predicted before they were measured.", tone: h.within === h.total ? "good" : "warn" });
  }
  if (t) cards.push({ value: `${t.within} of ${t.total}`, label: `tuned-on knees within ${a.tolerance}`, note: `${t.range}, on experiments the model was developed with in view.`, tone: "plain" });
  cards.push({
    value: `${a.validated} of ${a.experiments}`,
    label: "experiments meet every proposed threshold",
    note: a.validated === 0 ? "So no technology counts as validated yet." : "Validated against the proposed bar.",
    tone: a.validated === 0 ? "warn" : "good",
  });
  cards.push({ value: String(a.runs), label: "runs, every one published", note: `${a.period}, on ${a.machine}.`, tone: "plain" });
  return (
    <section id="accuracy" aria-labelledby="accuracy-title" className="scroll-mt-16 py-20 sm:py-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <Head
          eyebrow="Accuracy"
          title="Measured, not claimed. Misses included."
          id="accuracy-title"
          lead={`In ${a.experiments} experiments, small synthetic services on real PostgreSQL, Redis and Kafka run on ${a.machine}, ArchSim was calibrated at low load and predicted where each would break; only then was the breaking point measured with k6. The held-out ones were sealed until a frozen engine had predicted them.`}
        />
        <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {cards.map((c, i) => (
            <Reveal key={c.label} delay={i * 70}>
              <div className="flex h-full flex-col rounded-2xl border border-line bg-surface p-6">
                <p className="tabular font-display text-[40px] font-semibold leading-none tracking-[-0.04em] text-ink">{c.value}</p>
                <p className="mt-3 text-[15px] font-medium text-ink">{c.label}</p>
                <p className={cn("mt-2 flex gap-2 text-[14px] leading-relaxed", c.tone === "warn" ? "text-ink" : "text-ink-2")}>
                  {c.tone === "warn" && <TriangleAlert className="mt-0.5 size-4 shrink-0 text-warning" aria-hidden />}
                  {c.tone === "good" && <CircleCheck className="mt-0.5 size-4 shrink-0 text-good" aria-hidden />}
                  <span>{c.note}</span>
                </p>
              </div>
            </Reveal>
          ))}
        </div>
        <Reveal className="mt-6">
          <div className="flex flex-col gap-5 rounded-2xl border border-line bg-warning-soft p-6 sm:flex-row sm:items-center">
            <Gauge className="hidden size-6 shrink-0 text-warning sm:block" aria-hidden />
            <p className="text-[15px] leading-relaxed text-ink">
              <strong>Results are estimates, not production guarantees.</strong> Every number comes from the model you drew and the assumptions you
              entered. Without calibration, trust comparisons between scenarios more than any single absolute value.
            </p>
            <div className="flex shrink-0 flex-wrap gap-2 sm:ml-auto">
              <Button variant="primary" size="lg" asChild>
                <Link href="/accuracy/">
                  See every run <ArrowRight />
                </Link>
              </Button>
              <Button size="lg" asChild>
                <Link href="/docs/calibration/">Calibrate yours</Link>
              </Button>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

function OpenSource({ readme }: Pick<SiteHomeProps, "readme">) {
  return (
    <section id="open-source" aria-labelledby="open-source-title" className="scroll-mt-16 border-t border-line bg-surface-2/40 py-20 sm:py-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <Head eyebrow="Open source" title="Yours to run, read and change" id="open-source-title" />
        <div className="mt-12 grid gap-5 lg:grid-cols-2">
          <Reveal>
            <article className="flex h-full flex-col rounded-2xl border border-line bg-surface p-6 sm:p-8">
              <h3 className="text-xl font-semibold tracking-[-0.02em] text-ink">License</h3>
              <Prose html={readme.license} className="mt-3 flex-1 text-[15px] text-ink-2" />
              <div className="mt-6 flex flex-wrap gap-2">
                <Button size="lg" asChild>
                  <a href={repositoryUrl("LICENSE")}>Read the AGPL-3.0</a>
                </Button>
                <Button size="lg" variant="soft" asChild>
                  <a href={readme.commercialContact}>Ask for a commercial license</a>
                </Button>
              </div>
            </article>
          </Reveal>
          <Reveal delay={70}>
            <article className="flex h-full flex-col rounded-2xl border border-line bg-surface p-6 sm:p-8">
              <h3 className="text-xl font-semibold tracking-[-0.02em] text-ink">Contributing</h3>
              <Prose html={readme.contributing} className="mt-3 flex-1 text-[15px] text-ink-2" />
              <div className="mt-6 flex flex-wrap gap-2">
                <Button size="lg" asChild>
                  <a href={`${REPOSITORY_URL}/discussions`}>Discussions</a>
                </Button>
                <Button size="lg" asChild>
                  <a href={`${REPOSITORY_URL}/issues`}>Issues</a>
                </Button>
                <Button size="lg" variant="ghost" asChild>
                  <a href={repositoryUrl("CONTRIBUTING.md")}>Contributing guide</a>
                </Button>
              </div>
            </article>
          </Reveal>
        </div>
      </div>
    </section>
  );
}

function FinalCall({ demoUrl }: Pick<SiteHomeProps, "demoUrl">) {
  return (
    <section aria-labelledby="final-title" className="px-4 py-20 sm:px-6">
      <Reveal className="mx-auto max-w-7xl">
        <div className="hero-band relative overflow-hidden rounded-3xl border border-line px-6 py-16 text-center sm:px-12 sm:py-20">
          <h2 id="final-title" className="relative font-display text-[34px] font-semibold leading-[1.02] tracking-[-0.045em] text-band-ink sm:text-[56px]">
            Ready to break something?
          </h2>
          <p className="relative mx-auto mt-4 max-w-xl text-[17px] leading-relaxed text-ink-2">
            Clone it, start it with Docker, open the starter example and press Simulate. Your first answer is seconds away.
          </p>
          <div className="relative mt-8 flex flex-wrap justify-center gap-3">
            {demoUrl && (
              <Button variant="primary" size="xl" asChild>
                <a href={demoUrl}>
                  Try it live <ArrowUpRight />
                </a>
              </Button>
            )}
            <Button variant={demoUrl ? "secondary" : "primary"} size="xl" asChild>
              <a href="#quick-start">
                Get started <ArrowRight />
              </a>
            </Button>
            <Button size="xl" asChild>
              <a href={REPOSITORY_URL}>
                <GitHubMark className="size-[18px]" /> Star it on GitHub
              </a>
            </Button>
          </div>
        </div>
      </Reveal>
    </section>
  );
}

export function SiteHome(props: SiteHomeProps) {
  return (
    <main id="main">
      <Hero version={props.version} demoUrl={props.demoUrl} />
      <Stats stats={props.stats} />
      <QuickStart readme={props.readme} />
      <Why readme={props.readme} />
      <How readme={props.readme} />
      <Tour readme={props.readme} />
      <Features readme={props.readme} />
      <Technologies chips={props.chips} stats={props.stats} />
      <AccuracySummary accuracy={props.accuracy} />
      <OpenSource readme={props.readme} />
      <FinalCall demoUrl={props.demoUrl} />
    </main>
  );
}

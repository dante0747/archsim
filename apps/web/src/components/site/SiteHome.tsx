/**
 * The public website's home page (ADR-0078, ADR-0097): one story from the problem to what to
 * test (ADR-0099), then how to run it. The narrative is the site's own; every fact on it comes from
 * the repository at build time: the screenshots and their descriptions and the quick start from
 * the README, the accuracy figures from the accuracy page's data, the technologies from the
 * engine's catalog.
 */
import { ArrowRight, ArrowUpRight, Check, Crosshair, Gauge, type LucideIcon, Receipt, Scale, Sigma, TriangleAlert, Unplug } from "lucide-react";
import Link from "next/link";
import { REPOSITORY_URL, repositoryUrl } from "@/lib/repository";
import type { ProductViews } from "@/lib/site/product-views";
import type { ReadmeContent } from "@/lib/site/readme";
import type { FeaturedTechnology } from "@/lib/site/technologies";
import { cn } from "@/lib/utils";
import { CopyButton } from "./CopyButton";
import { Cta, CtaGroup } from "./Cta";
import { GitHubMark } from "./GitHubMark";
import { ProductPicture, ScreenFrame } from "./ProductPicture";
import { ProductShowcase } from "./ProductShowcase";
import { Prose } from "./Prose";

export interface SiteHomeProps {
  views: ProductViews;
  quickStart: ReadmeContent["quickStart"];
  accuracy: ReadmeContent["accuracy"];
  /** The catalog's featured core technologies, and how many profiles it has by tier (`catalogStats`). */
  technologies: { featured: FeaturedTechnology[]; count: number; tiers: string };
  /** ArchSim's version, which the changelog's latest release names, and where its notes are. */
  version: string;
  releaseNotes: string;
  demoUrl: string | null;
}

const HERO_SIZES = "(min-width: 1264px) 1200px, (min-width: 1024px) calc(100vw - 4rem), calc(100vw - 3rem)";

function SectionHead({ eyebrow, title, id, lead, className }: { eyebrow: string; title: string; id: string; lead?: React.ReactNode; className?: string }) {
  return (
    <div className={cn("max-w-3xl", className)}>
      <p className="site-eyebrow">{eyebrow}</p>
      <h2 id={id} className="site-h2 mt-3">
        {title}
      </h2>
      {lead && <p className="site-lead mt-4">{lead}</p>}
    </div>
  );
}

function TextLink({ href, className, children }: { href: string; className?: string; children: React.ReactNode }) {
  const style = cn("group inline-flex min-h-11 items-center gap-1.5 text-[15px] font-semibold text-accent hover:underline", className);
  const arrow = <ArrowRight className="size-4 transition-transform group-hover:translate-x-0.5" aria-hidden />;
  return href.startsWith("/") ? (
    <Link href={href} className={style} prefetch={false}>
      {children}
      {arrow}
    </Link>
  ) : (
    <a href={href} className={style}>
      {children}
      {arrow}
    </a>
  );
}

/** "Try ArchSim" and GitHub, the page's only two actions. */
function Actions({ demoUrl, className }: { demoUrl: string | null; className?: string }) {
  return (
    <CtaGroup className={className}>
      <Cta href={demoUrl ?? "#quick-start"}>
        Try ArchSim
        {demoUrl ? <ArrowUpRight aria-hidden /> : <ArrowRight aria-hidden />}
      </Cta>
      <Cta href={REPOSITORY_URL} variant="secondary">
        <GitHubMark />
        View on GitHub
      </Cta>
    </CtaGroup>
  );
}

/** What running ArchSim yourself means, each with the qualification it needs. */
const TRUST = ["Open source (AGPL-3.0)", "Self-host with Docker", "No account, no telemetry when self-hosted"];

function Hero({ views, version, releaseNotes, demoUrl }: Pick<SiteHomeProps, "views" | "version" | "releaseNotes" | "demoUrl">) {
  return (
    <section aria-labelledby="hero-title" className="relative isolate overflow-hidden">
      <div className="schematic-grid absolute inset-0 -z-10 opacity-60 [mask-image:radial-gradient(70%_55%_at_50%_0%,#000_40%,transparent)]" aria-hidden />
      <div className="site-container pt-14 text-center sm:pt-20 lg:pt-24">
        <div className="flex flex-wrap items-center justify-center gap-x-3 gap-y-2 text-[14px]">
          <span className="text-ink-2">Open-source architecture experimentation tool</span>
          <Link
            href={releaseNotes}
            prefetch={false}
            aria-label={`v${version} release notes`}
            className="relative inline-flex h-8 items-center gap-1 rounded-full border border-line-strong bg-surface px-3 text-[13px] font-semibold text-ink transition-colors before:absolute before:inset-x-0 before:-inset-y-1.5 before:content-[''] hover:border-[color:var(--ink-3)]"
          >
            v{version}
            <ArrowRight className="size-3.5 text-ink-3" aria-hidden />
          </Link>
        </div>
        <h1 id="hero-title" className="site-h1 mx-auto mt-6 max-w-[15ch] sm:max-w-none">
          Crash-test your architecture <br className="hidden sm:block" />
          <span className="site-brand-text">before your users do.</span>
        </h1>
        <p className="site-lead mx-auto mt-6">Simulate distributed systems, uncover bottlenecks, and compare architectural decisions before writing production code.</p>
        <Actions demoUrl={demoUrl} className="mx-auto mt-10 max-w-sm justify-center sm:max-w-none" />
        <ul className="mx-auto mt-6 flex max-w-2xl flex-wrap justify-center gap-x-5 gap-y-2 text-[13px] text-ink-2">
          {TRUST.map((item) => (
            <li key={item} className="inline-flex items-center gap-1.5 whitespace-nowrap">
              <Check className="size-3.5 text-ink-3" aria-hidden />
              {item}
            </li>
          ))}
        </ul>
      </div>
      <figure className="site-container relative mt-12 pb-16 sm:mt-16 md:pb-24">
        <div className="absolute inset-x-[10%] top-[8%] bottom-[20%] -z-10 rounded-full bg-[radial-gradient(closest-side,color-mix(in_srgb,var(--brand-a)_22%,transparent),color-mix(in_srgb,var(--brand-b)_10%,transparent)_60%,transparent)] blur-2xl" aria-hidden />
        <ScreenFrame>
          <ProductPicture view={views.hero} sizes={HERO_SIZES} priority />
        </ScreenFrame>
      </figure>
    </section>
  );
}

const CONTRAST: { label: string; text: string; ours?: boolean }[] = [
  { label: "A diagram", text: "shows the structure you intend, not how it behaves under load or when a part fails." },
  { label: "A load test", text: "measures real behaviour, but it usually needs a running system, so it comes after the big decisions." },
  { label: "A simulation", text: "lets you try traffic, failures and cost on the design itself, while it is still cheap to change. Confirm with real measurements later.", ours: true },
];

function Problem() {
  return (
    <section id="why" aria-labelledby="why-title" className="site-section border-t border-line">
      <div className="site-container grid gap-10 lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)] lg:gap-16">
        <SectionHead eyebrow="Why simulate" title="Architecture diagrams show what you intend. Simulation shows what might happen." id="why-title" />
        <dl className="divide-y divide-line border-y border-line lg:mt-9">
          {CONTRAST.map((row) => (
            <div
              key={row.label}
              className={cn(
                "grid gap-1 py-5 sm:grid-cols-[9rem_minmax(0,1fr)] sm:gap-6",
                // the simulation's row: a bar of the logo's blue, drawn by CSS so the list holds only terms and definitions
                row.ours && "relative pl-4 before:absolute before:inset-y-5 before:left-0 before:w-0.5 before:rounded-full before:bg-[var(--brand-a)] before:content-[''] lg:pl-0 lg:before:-left-5",
              )}
            >
              <dt className={cn("text-[15px] font-semibold", row.ours ? "text-ink" : "text-ink-2")}>{row.label}</dt>
              <dd className={cn("text-[16px] leading-relaxed", row.ours ? "text-ink" : "text-ink-2")}>{row.text}</dd>
            </div>
          ))}
        </dl>
      </div>
    </section>
  );
}

const STEPS = [
  { title: "Design", text: "Draw your architecture with the technologies you run: PostgreSQL, Redis, Kafka, services on Kubernetes and the clients that call them." },
  { title: "Simulate", text: "Send realistic traffic, then take parts down, slow them and cap their capacity, and watch how every part copes." },
  {
    title: "Learn",
    text: "Compare scenarios side by side: which meet your objectives, where each one breaks, and what each costs a month, as hypotheses to confirm with a load test.",
  },
];

function HowItWorks() {
  return (
    <section id="how" aria-labelledby="how-title" className="site-section border-t border-line">
      <div className="site-container">
        <SectionHead eyebrow="How it works" title="Sketch it. Shake it. Learn what to test." id="how-title" />
        {/* the line through the steps' numbers is the list's own, drawn by CSS */}
        <ol className="relative mt-12 grid gap-8 before:absolute before:bottom-4 before:left-4 before:top-4 before:w-px before:bg-line-strong before:content-[''] md:grid-cols-3 md:gap-10 md:before:inset-x-4 md:before:bottom-auto md:before:h-px md:before:w-auto">
          {STEPS.map((step, i) => (
            <li key={step.title} className="relative grid grid-cols-[2rem_minmax(0,1fr)] gap-x-4 md:block">
              <span className="relative flex size-8 items-center justify-center rounded-full border border-line-strong bg-page font-readout text-[13px] font-semibold text-ink" aria-hidden>
                {i + 1}
              </span>
              <div>
                <h3 className="text-[20px] font-semibold tracking-[-0.02em] text-ink md:mt-6">{step.title}</h3>
                <p className="mt-2 max-w-sm text-[16px] leading-relaxed text-ink-2">{step.text}</p>
              </div>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}

const CAPABILITIES: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: Gauge, title: "Where does it break?", text: "Ramp the load to find the first part to saturate, the highest load that still meets your objectives, and what to fix first." },
  { icon: Unplug, title: "What if a part fails?", text: "Take down a cache, slow a database or add network latency mid-run, then watch the effect spread and the system recover, second by second." },
  { icon: Scale, title: "Which option is better?", text: "Simulate every scenario of a design and compare them on one cost-against-latency map, with the ones that meet your objectives marked." },
  { icon: Receipt, title: "What will it cost?", text: "Parts are sized for the peak and priced at AWS on-demand list prices, so each option comes with an estimated monthly bill, before discounts and data transfer." },
  { icon: Sigma, title: "Where do the numbers come from?", text: "See the queueing formulas behind a part's numbers beside what was simulated, and rerun any run from its seed and engine version for the same result." },
  { icon: Crosshair, title: "Does it match our system?", text: "Calibrate from Prometheus metrics or OpenTelemetry traces, replacing illustrative defaults with values measured on your own system." },
];

function Capabilities() {
  return (
    <section id="features" aria-labelledby="features-title" className="site-section border-t border-line">
      <div className="site-container">
        <SectionHead eyebrow="Product" title="Answer the questions every design review asks." id="features-title" />
        <div className="mt-12 grid gap-x-8 gap-y-8 sm:grid-cols-2 sm:gap-y-10 lg:grid-cols-3">
          {CAPABILITIES.map(({ icon: Icon, title, text }) => (
            <article key={title} className="border-t border-line pt-6">
              <h3 className="flex items-center gap-3 text-[18px] font-semibold tracking-[-0.02em] text-ink">
                <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-accent-soft text-accent" aria-hidden>
                  <Icon className="size-[18px]" />
                </span>
                {title}
              </h3>
              <p className="mt-3 text-[16px] leading-relaxed text-ink-2">{text}</p>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}

function Showcase({ views }: Pick<SiteHomeProps, "views">) {
  return (
    <section id="tour" aria-labelledby="tour-title" className="site-section border-t border-line">
      <div className="site-container">
        <SectionHead eyebrow="Screens" title="Real screens from a real run." id="tour-title" lead="ArchSim simulating its Black Friday template: an online shop's checkout on Kubernetes." className="mb-10" />
        <ProductShowcase views={views.showcase} />
      </div>
    </section>
  );
}

function Technologies({ technologies }: Pick<SiteHomeProps, "technologies">) {
  return (
    <section id="technologies" aria-labelledby="technologies-title" className="site-section border-t border-line">
      <div className="site-container">
        <SectionHead
          eyebrow="Technologies"
          title="Model the stack you actually run."
          id="technologies-title"
          lead="Each technology models the mechanisms its documentation describes, such as PostgreSQL's write-ahead log or Kafka's partitions. Every default says whether it is documented or illustrative, and every one is a setting you can override."
        />
        <ul className="mt-10 grid grid-cols-4 gap-px overflow-hidden rounded-2xl border border-line bg-line lg:grid-cols-8">
          {technologies.featured.map((tech) => (
            <li key={tech.key} className="flex flex-col items-center justify-center gap-2.5 bg-page px-1 py-5 text-[12px] font-medium text-ink min-[360px]:text-[13px] sm:gap-3 sm:py-6 sm:text-[14px]">
              <svg viewBox="0 0 24 24" className="size-7" fill={tech.logo.color ?? "currentColor"} aria-hidden>
                <path d={tech.logo.path} />
              </svg>
              {tech.name}
            </li>
          ))}
        </ul>
        <div className="mt-6 flex flex-col gap-x-6 gap-y-1 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-[15px] text-ink-2">
            {technologies.count} technology profiles: {technologies.tiers}. No technology is validated against measurements yet; core ones will be first.
          </p>
          <TextLink href="/docs/assumptions/#technology-tiers" className="shrink-0 whitespace-nowrap">
            See every technology profile
          </TextLink>
        </div>
      </div>
    </section>
  );
}

function Accuracy({ accuracy }: Pick<SiteHomeProps, "accuracy">) {
  return (
    <section id="accuracy" aria-labelledby="accuracy-title" className="site-section border-t border-line">
      <div className="site-container grid gap-10 lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)] lg:gap-16">
        <div>
          <p className="site-eyebrow">Accuracy</p>
          <h2 id="accuracy-title" className="site-h2 mt-3">
            Transparent by design.
          </h2>
          {/* the README's words (ADR-0097): the experiments, their table, what qualifies them, the caveat */}
          <p className="site-lead mt-4" dangerouslySetInnerHTML={{ __html: accuracy.intro }} />
        </div>
        <div className="min-w-0 lg:mt-9">
          <Prose html={accuracy.table} className="text-[15px] [&_td:first-child]:text-ink" />
          <p className="mt-5 text-[16px] leading-relaxed text-ink-2" dangerouslySetInnerHTML={{ __html: accuracy.notes }} />
          <div className="mt-6 flex gap-3 rounded-2xl border border-[color:color-mix(in_srgb,var(--status-warning)_35%,transparent)] bg-warning-soft p-5 text-[15px] leading-relaxed text-ink">
            <TriangleAlert className="mt-0.5 size-5 shrink-0 text-warning" aria-hidden />
            <p className="[&_strong]:font-semibold" dangerouslySetInnerHTML={{ __html: accuracy.caveat }} />
          </div>
          <ul className="mt-6 grid gap-x-8 sm:grid-cols-2">
            <li>
              <TextLink href="/accuracy/">Every run, in the accuracy report</TextLink>
            </li>
            <li>
              <TextLink href="/docs/validation/#reproduce">Rerun the benchmarks yourself</TextLink>
            </li>
            <li>
              <TextLink href="/docs/assumptions/">Every assumption, and its source</TextLink>
            </li>
            <li>
              <TextLink href="/docs/simulation-model/">What the model leaves out</TextLink>
            </li>
          </ul>
        </div>
      </div>
    </section>
  );
}

const WAYS_IN: { label: string; text: string; href: string }[] = [
  { label: "Command line", text: "The same engine and answers, without a server or database.", href: repositoryUrl("archsim-cli/README.md") },
  { label: "MCP server", text: "Give AI agents the engine as tools.", href: "/docs/agents/" },
  { label: "CI check", text: "Comment on a pull request whose design misses its objectives or regresses: a warning to read, not a gate.", href: "/docs/ci/" },
  { label: "Import and export", text: "From docker-compose, Kubernetes or Terraform; to Compose, Kubernetes or Mermaid.", href: "/docs/import/" },
];

function QuickStart({ quickStart }: Pick<SiteHomeProps, "quickStart">) {
  const { needs, commands, next } = quickStart;
  const lines = commands.split("\n");
  return (
    <section id="quick-start" aria-labelledby="quick-start-title" className="site-section border-t border-line">
      <div className="site-container grid gap-12 lg:grid-cols-[minmax(0,7fr)_minmax(0,5fr)] lg:gap-16">
        <div className="min-w-0">
          <SectionHead eyebrow="Quick start" title="Run ArchSim locally." id="quick-start-title" lead={needs} />
          <figure className="mt-8 overflow-hidden rounded-xl border border-line bg-[color:var(--lcd)]">
            <figcaption className="flex h-11 items-center justify-between border-b border-line pl-4">
              <span className="text-[13px] font-medium text-ink-3">Terminal</span>
              <CopyButton text={commands} label="Copy" className="h-11 rounded-none px-4 text-[13px] focus-visible:-outline-offset-2" />
            </figcaption>
            <pre tabIndex={0} aria-label="Commands" className="overflow-x-auto px-4 py-4 font-code focus-visible:-outline-offset-2 text-[13px] leading-[1.9] text-ink sm:px-5 sm:text-[14px]">
              {lines.map((line, i) => {
                const at = line.search(/\s+#/);
                return (
                  <span key={i} className="block before:mr-3 before:text-ink-3 before:content-['$']">
                    {at < 0 ? line : line.slice(0, at)}
                    {at >= 0 && <span className="text-ink-3">{line.slice(at)}</span>}
                    {i < lines.length - 1 ? "\n" : ""}
                  </span>
                );
              })}
            </pre>
          </figure>
          <p className="mt-4 text-[15px] leading-relaxed text-ink-2 [&_a]:text-accent [&_a]:underline [&_a]:underline-offset-2 [&_strong]:font-semibold [&_strong]:text-ink" dangerouslySetInnerHTML={{ __html: next }} />
          <TextLink href="/docs/getting-started/">Getting started guide</TextLink>
        </div>
        <div className="lg:pt-[4.5rem]">
          <h3 className="text-[15px] font-semibold text-ink">Other ways in</h3>
          <ul className="mt-3 divide-y divide-line border-y border-line">
            {WAYS_IN.map((way) => (
              <li key={way.label} className="py-4">
                {way.href.startsWith("/") ? (
                  <Link href={way.href} prefetch={false} className="text-[15px] font-semibold text-accent hover:underline">
                    {way.label}
                  </Link>
                ) : (
                  <a href={way.href} className="text-[15px] font-semibold text-accent hover:underline">
                    {way.label}
                  </a>
                )}
                <p className="mt-1 text-[15px] leading-relaxed text-ink-2">{way.text}</p>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  );
}

function FinalCall({ demoUrl }: Pick<SiteHomeProps, "demoUrl">) {
  return (
    <section
      aria-labelledby="final-title"
      className="site-section border-t border-line bg-[radial-gradient(50%_70%_at_50%_100%,color-mix(in_srgb,var(--brand-a)_12%,transparent),color-mix(in_srgb,var(--brand-b)_5%,transparent)_55%,transparent)]"
    >
      <div className="site-container text-center">
        <h2 id="final-title" className="site-h2 mx-auto max-w-[22ch]">
          Challenge your assumptions before you build.
        </h2>
        <p className="site-lead mx-auto mt-4">{demoUrl ? "Try it in the hosted sandbox, or run it on your own machine." : "Run it on your own machine, with Docker."}</p>
        <Actions demoUrl={demoUrl} className="mx-auto mt-8 max-w-sm justify-center sm:max-w-none" />
      </div>
    </section>
  );
}

export function SiteHome(props: SiteHomeProps) {
  return (
    <main id="main">
      <Hero views={props.views} version={props.version} releaseNotes={props.releaseNotes} demoUrl={props.demoUrl} />
      <Problem />
      <HowItWorks />
      <Capabilities />
      <Showcase views={props.views} />
      <Technologies technologies={props.technologies} />
      <Accuracy accuracy={props.accuracy} />
      <QuickStart quickStart={props.quickStart} />
      <FinalCall demoUrl={props.demoUrl} />
    </main>
  );
}


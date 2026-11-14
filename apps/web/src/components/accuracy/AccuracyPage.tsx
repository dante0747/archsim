/**
 * The public accuracy page at /accuracy: the benchmark results (docs/validation.md) in plain
 * language, good and bad alike, built at build time from src/data/accuracy.json.
 */
import { ArrowLeft, Check, Gauge, TriangleAlert, X } from "lucide-react";
import Link from "next/link";
import { Logo } from "@/components/brand";
import { KneeLegend, KneeScale } from "@/components/accuracy/KneeScale";
import { MobileNav } from "@/components/ui/MobileNav";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import {
  type Accuracy,
  BENCHMARK_WORKFLOW,
  CALIBRATION_DOC,
  type Experiment,
  JAVA_PARITY_DOC,
  VALIDATION_DOC,
  byKneeError,
  coverageAsClaimed,
  coverageSentence,
  cpuSentence,
  formatDate,
  formatTime,
  kneeSentence,
  lowLoadSentence,
  minus,
  p95Sentence,
  repeatSentence,
  spreadSentence,
  topStepText,
} from "@/lib/accuracy";
import { cn } from "@/lib/utils";

const navLink = "inline-flex h-7 items-center gap-1.5 rounded-md px-2.5 text-xs font-medium text-ink-2 transition-colors hover:bg-surface-2 hover:text-ink [&_svg]:size-4";

/** A file of the repository, named rather than linked (the repository is private). */
function RepoFile({ path, small }: { path: string; small?: boolean }) {
  return <code className={cn("font-code text-ink", small ? "text-[12px]" : "text-[13px]")}>{path}</code>;
}

/** A term of "How to read these numbers" with what it means. */
function Term({ term, children }: { term: string; children: React.ReactNode }) {
  return (
    <div className="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-6">
      <dt className="font-semibold text-ink">{term}</dt>
      <dd className="mt-1 text-ink-2 sm:mt-0">{children}</dd>
    </div>
  );
}

function Section({ id, title, children, lead }: { id: string; title: string; lead?: React.ReactNode; children: React.ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="scroll-mt-20 border-t border-line py-12 first:border-t-0 sm:py-14">
      <h2 id={`${id}-title`} className="font-display text-[26px] font-semibold tracking-[-0.03em] text-ink sm:text-[30px]">
        {title}
      </h2>
      {lead && <div className="mt-3 max-w-3xl text-[15px] leading-relaxed text-ink-2">{lead}</div>}
      <div className="mt-6">{children}</div>
    </section>
  );
}

function names(data: Accuracy, ids: string[]): string {
  const all = ids.map((id) => data.experiments.find((x) => x.id === id)?.name ?? id);
  return all.length <= 1 ? (all[0] ?? "") : `${all.slice(0, -1).join(", ")} and ${all[all.length - 1]}`;
}

function YesNo({ ok, children }: { ok: boolean; children: React.ReactNode }) {
  return (
    <li className="flex gap-2">
      {ok ? <Check className="mt-0.5 size-4 shrink-0 text-good" aria-hidden /> : <X className="mt-0.5 size-4 shrink-0 text-critical" aria-hidden />}
      <span>
        <span className="sr-only">{ok ? "Met: " : "Not met: "}</span>
        {children} <span className={cn("font-medium", ok ? "text-success-ink" : "text-danger-ink")}>{ok ? "yes" : "no"}</span>
      </span>
    </li>
  );
}

function ExperimentCard({ x, data }: { x: Experiment; data: Accuracy }) {
  const t = data.thresholds;
  const c = x.criteria;
  const low = lowLoadSentence(x);
  const env = x.environment;
  return (
    <article id={x.id} aria-labelledby={`${x.id}-name`} className="scroll-mt-20 rounded-2xl border border-line bg-surface p-5 shadow-[var(--shadow)] sm:p-6">
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
        <div className="min-w-0">
          <h3 id={`${x.id}-name`} className="text-[17px] font-semibold leading-snug tracking-tight text-ink">
            {x.name}
          </h3>
          <p className="mt-1 text-[13px] text-ink-3">{x.title}</p>
        </div>
        <div className="flex shrink-0 flex-wrap gap-1.5">
          <span className="inline-flex items-center rounded-full border border-line px-2 py-0.5 text-[12px] font-medium text-ink-2">
            {x.sample === "held-out" ? "Held out" : "Tuned on"}
          </span>
          <span
            className={cn(
              "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[12px] font-medium text-ink-2",
              x.tier === "validated" ? "bg-good-soft" : "bg-warning-soft",
            )}
          >
            {x.tier === "validated" ? <Check className="size-3.5 text-good" aria-hidden /> : <TriangleAlert className="size-3.5 text-warning" aria-hidden />}
            {x.tier === "validated" ? "Meets the proposed thresholds" : "Not validated"}
          </span>
        </div>
      </div>

      {x.caveat && (
        <p role="note" className="mt-4 flex gap-2 rounded-xl border border-line bg-warning-soft p-3 text-[13.5px] leading-relaxed text-ink">
          <TriangleAlert className="mt-0.5 size-4 shrink-0 text-warning" aria-hidden />
          <span>
            <span className="font-semibold">Measured with a benchmark flaw.</span> {x.caveat}
          </span>
        </p>
      )}

      <KneeScale x={x} />

      <dl className="mt-5 grid gap-x-8 gap-y-4 text-[14px] leading-relaxed md:grid-cols-2">
        <div>
          <dt className="text-[12px] font-medium uppercase tracking-wide text-ink-3">Capacity knee · error {minus(x.knee.text.error)}</dt>
          <dd className="mt-1 text-ink">{kneeSentence(x)}</dd>
        </div>
        <div>
          <dt className="text-[12px] font-medium uppercase tracking-wide text-ink-3">Run to run</dt>
          <dd className="mt-1 text-ink">
            {spreadSentence(x)} {repeatSentence(x)}
          </dd>
        </div>
        <div>
          <dt className="text-[12px] font-medium uppercase tracking-wide text-ink-3">Latency below the knee</dt>
          <dd className="mt-1 text-ink">{p95Sentence(x, t)}</dd>
        </div>
        {low && (
          <div>
            <dt className="text-[12px] font-medium uppercase tracking-wide text-ink-3">Light load</dt>
            <dd className="mt-1 text-ink">{low}</dd>
          </div>
        )}
      </dl>

      <div className="mt-5 rounded-xl bg-surface-2 p-4">
        <p className="text-[13px] font-medium text-ink">Proposed thresholds for &ldquo;validated&rdquo;</p>
        <ul className="mt-2 grid gap-1.5 text-[13px] text-ink-2 sm:grid-cols-2">
          <YesNo ok={c.knee.pass}>
            Median knee error within ±{Math.round(t.kneeError * 100)}% over 3+ runs ({c.knee.runs} so far):
          </YesNo>
          <YesNo ok={c.p95.pass}>
            p95 within a factor of {t.p95Factor} or {t.p95Ms} ms below the knee, in 2 of 3 runs:
          </YesNo>
          <YesNo ok={c.stable.pass}>No unstable step below the knee, in 2 of 3 runs:</YesNo>
          <YesNo ok={c.reproduced.pass}>
            Reproduced on a second machine ({c.reproduced.runs} run{c.reproduced.runs === 1 ? "" : "s"} under {c.reproduced.protocol}):
          </YesNo>
        </ul>
      </div>

      <p className="mt-4 text-[12.5px] leading-relaxed text-ink-3">
        Run of {formatDate(x.date)} (calibrated {formatTime(x.calibratedAt)}), protocol {x.protocol}, engine {x.engineVersion ?? "-"}. {env.text}.
        {x.sample === "held-out" && " Held out: predicted by a frozen engine that was not tuned on it, before it was first measured."}
        {x.inSample && " In-sample: the calibration protocol was revised after this experiment's earlier results had been seen."} The model inputs and
        how each was measured are in <RepoFile path={VALIDATION_DOC} small /> (section {x.id}) in the repository.
      </p>

      <details className="group mt-3">
        <summary className="cursor-pointer select-none text-[13px] font-medium text-ink-2 hover:text-ink">Every load step ({x.steps.length})</summary>
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-left text-[12.5px]">
            <caption className="sr-only">
              {x.name}: measured and predicted p95 latency and success at each offered load
            </caption>
            <thead className="text-ink-3">
              <tr className="border-b border-line align-bottom">
                <th scope="col" className="py-1.5 pr-2 font-medium sm:pr-3">
                  req/s
                </th>
                <th scope="col" className="py-1.5 pr-2 text-right font-medium sm:pr-3">
                  p95 meas. (ms)
                </th>
                <th scope="col" className="py-1.5 pr-2 text-right font-medium sm:pr-3">
                  p95 pred. (ms)
                </th>
                <th scope="col" className="py-1.5 pr-2 text-right font-medium sm:pr-3">
                  Error
                </th>
                <th scope="col" className="py-1.5 text-right font-medium">
                  Success
                </th>
              </tr>
            </thead>
            <tbody className="tabular text-ink">
              {x.steps.map((s) => (
                <tr key={s.offeredRps} className={cn("border-b border-line last:border-0", !s.atOrBelowKnee && "text-ink-3")}>
                  <td className="py-1.5 pr-2 sm:pr-3">
                    {s.text.offered}
                    {s.unstable && <span className="block font-sans text-[11px] text-ink-2 sm:ml-1 sm:inline">unstable</span>}
                  </td>
                  <td className="py-1.5 pr-2 text-right sm:pr-3">{s.text.measuredP95}</td>
                  <td className="py-1.5 pr-2 text-right sm:pr-3">{s.text.predictedP95}</td>
                  <td className="py-1.5 pr-2 text-right sm:pr-3">{minus(s.text.p95Error)}</td>
                  <td className="whitespace-nowrap py-1.5 text-right">
                    {s.text.measuredSuccess} / {s.text.predictedSuccess}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="mt-2 text-[12px] text-ink-3">Greyed rows are above the measured knee. Success is measured / predicted; &ldquo;-&rdquo; means no request succeeded.</p>
        </div>
      </details>
    </article>
  );
}

const th = "py-1.5 pr-2 font-medium sm:pr-3";
const num = "py-1.5 pr-2 text-right sm:pr-3";

/** One experiment's CPU (and memory, once measured): each part at the top step below both knees. */
function ResourcesCard({ x, memory }: { x: Experiment; memory: boolean }) {
  const r = x.resources;
  if (!r) return null;
  const top = topStepText(r);
  return (
    <article aria-labelledby={`${x.id}-resources`} className="min-w-0 rounded-2xl border border-line bg-surface p-5 shadow-[var(--shadow)] sm:p-6">
      <h3 id={`${x.id}-resources`} className="text-[15px] font-semibold leading-snug text-ink">
        {x.name}
      </h3>
      <p className="mt-1 text-[13px] text-ink-3">
        {top ? `Busy cores at ${top} req/s, the highest load step at or below both knees` : "No load step was at or below both knees"}
      </p>
      <div className="mt-3 overflow-x-auto">
        <table className="w-full min-w-[300px] text-left text-[13px]">
          <caption className="sr-only">{x.name}: busy cores measured and predicted for each part</caption>
          <thead className="text-ink-3">
            <tr className="border-b border-line align-bottom">
              <th scope="col" className={th}>
                Part
              </th>
              <th scope="col" className={cn(th, "text-right")}>
                Meas.
              </th>
              <th scope="col" className={cn(th, "text-right")}>
                Pred.
              </th>
              <th scope="col" className={cn(th, "text-right")}>
                Error
              </th>
              <th scope="col" className={cn(th, "text-right", memory ? "" : "pr-0 sm:pr-0")}>
                Typical error<span className="sr-only"> (median up to both knees)</span>
              </th>
              {memory && (
                <th scope="col" className="py-1.5 text-right font-medium">
                  Memory meas. / pred.
                </th>
              )}
            </tr>
          </thead>
          <tbody className="tabular text-ink">
            {r.parts.map((p) => (
              <tr key={p.container} className="border-b border-line last:border-0">
                <th scope="row" className="py-1.5 pr-2 font-sans font-normal sm:pr-3">
                  {p.name}
                </th>
                <td className={num}>{p.top?.text.measuredCores ?? "-"}</td>
                <td className={num}>{p.modelled.cores ? (p.top?.text.predictedCores ?? "-") : <span className="font-sans text-ink-3">not modelled</span>}</td>
                <td className={num}>{p.modelled.cores ? minus(p.top?.text.coresError ?? "-") : "-"}</td>
                <td className={cn(num, memory ? "" : "pr-0 sm:pr-0")}>{p.modelled.cores ? p.text.medianAbsCoresError : "-"}</td>
                {memory && (
                  <td className="whitespace-nowrap py-1.5 text-right">
                    {/* a prediction alone, with nothing measured, is not shown */}
                    {p.top?.measuredMemoryMiB != null
                      ? `${p.top.text.measuredMemory} / ${p.modelled.memory ? p.top.text.predictedMemory : "-"}`
                      : "-"}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <details className="group mt-3">
        <summary className="cursor-pointer select-none text-[13px] font-medium text-ink-2 hover:text-ink">Every load step ({r.steps.length})</summary>
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-left text-[12.5px]">
            <caption className="sr-only">{x.name}: busy cores measured and predicted for each part at each offered load</caption>
            <thead className="text-ink-3">
              <tr className="border-b border-line align-bottom">
                <th scope="col" className={th}>
                  req/s
                </th>
                {r.parts.map((p) => (
                  <th key={p.container} scope="col" className={cn(th, "text-right last:pr-0")}>
                    {p.name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="tabular text-ink">
              {r.steps.map((s) => (
                <tr key={s.offeredRps} className={cn("border-b border-line last:border-0", !s.atOrBelowKnee && "text-ink-3")}>
                  <td className="py-1.5 pr-2 sm:pr-3">{s.text.offered}</td>
                  {r.parts.map((p) => {
                    const c = s.parts[p.container];
                    return (
                      <td key={p.container} className={cn(num, "whitespace-nowrap last:pr-0")}>
                        {c ? `${c.text.measuredCores} / ${p.modelled.cores ? c.text.predictedCores : "-"}` : "-"}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
          <p className="mt-2 text-[12px] text-ink-3">Busy cores measured / predicted. Greyed rows are above the measured knee or the model&apos;s.</p>
        </div>
      </details>
    </article>
  );
}

function ResourcesSection({ data }: { data: Accuracy }) {
  const withRes = data.experiments.filter((x) => x.resources);
  if (!withRes.length) return null;
  const memory = withRes.some((x) => x.resources?.memoryMeasured);
  const measured = [...new Set(withRes.map((x) => x.resources?.measuredByText ?? ""))];
  const predicted = [...new Set(withRes.map((x) => x.resources?.predictedByText ?? ""))];
  const unmodelled = new Map<string, string>();
  for (const x of withRes) for (const p of x.resources?.parts ?? []) if (p.notModelled.cores) unmodelled.set(p.name, p.notModelled.cores);
  const sentence = cpuSentence(data.experiments);
  return (
    <Section
      id="resources"
      title="CPU and memory"
      lead={
        <>
          ArchSim also predicts how busy each part&apos;s CPU is and how much memory it holds, and picks instance sizes from those figures. Here they
          are against each container&apos;s own counters. {sentence}
        </>
      }
    >
      <div className="grid gap-5 md:grid-cols-2">
        {withRes.map((x) => (
          <ResourcesCard key={x.id} x={x} memory={memory} />
        ))}
      </div>
      <div className="mt-6 max-w-3xl space-y-2 text-[13px] leading-relaxed text-ink-2">
        <p>
          Busy cores are CPU seconds per second: 0.5 is half of one core busy on average. Error is (predicted − measured) ÷ measured; the typical
          error is the median over the load steps up to both knees, the measured one and the model&apos;s. Past the model&apos;s own knee its queue
          holds every request the real service would time out, so its CPU reads fully busy and its memory grows without bound; the knee error
          already counts that.
        </p>
        <p>
          <span className="font-medium text-ink">Measured:</span> {measured.join("; ")}.
        </p>
        <p>
          <span className="font-medium text-ink">Predicted:</span> {predicted.join("; ")}.
        </p>
        {unmodelled.size > 0 && (
          <p>
            <span className="font-medium text-ink">Not modelled:</span>{" "}
            {[...unmodelled].map(([name, why]) => `${name} (${why})`).join("; ")}.
          </p>
        )}
        {!memory && (
          <p>
            <span className="font-medium text-ink">Memory</span> is not on this page yet: these runs did not measure it. The benchmark runner now reads
            each container&apos;s working set, so the next run adds it beside ArchSim&apos;s prediction.
          </p>
        )}
        <p>
          What the gaps mean and where they come from is under &ldquo;Resources: CPU and memory&rdquo; in <RepoFile path={VALIDATION_DOC} small /> in
          the repository.
        </p>
      </div>
    </Section>
  );
}

function HistoryTable({ id, data }: { id: string; data: Accuracy }) {
  const runs = data.history[id] ?? [];
  const name = data.experiments.find((x) => x.id === id)?.name ?? id;
  return (
    <div>
      <h3 className="text-[15px] font-semibold text-ink">{name}</h3>
      <div className="mt-2 overflow-x-auto">
        <table className="w-full min-w-[320px] text-left text-[13px]">
          <caption className="sr-only">{name}: every kept run</caption>
          <thead className="text-ink-3">
            <tr className="border-b border-line">
              <th scope="col" className="py-1.5 pr-3 font-medium">
                Run
              </th>
              <th scope="col" className="py-1.5 pr-3 font-medium">
                Calibrated
              </th>
              <th scope="col" className="py-1.5 pr-3 text-right font-medium">
                Measured
              </th>
              <th scope="col" className="py-1.5 pr-3 text-right font-medium">
                Predicted
              </th>
              <th scope="col" className="py-1.5 text-right font-medium">
                Error
              </th>
            </tr>
          </thead>
          <tbody className="text-ink">
            {runs.map((r) => {
              const disturbed = r.checks?.disturbed ?? [];
              return (
                <tr key={r.dir} className="border-b border-line last:border-0">
                  <td className="py-1.5 pr-3">
                    {r.dir === "latest" ? "latest" : r.dir.replace("/", "")} <span className="text-ink-3">· {r.protocol}</span>
                  </td>
                  <td className="py-1.5 pr-3 text-ink-2">{formatTime(r.calibratedAt)}</td>
                  <td className="tabular py-1.5 pr-3 text-right">{r.text.measuredKnee}</td>
                  <td className="tabular py-1.5 pr-3 text-right">{r.text.predictedKnee}</td>
                  <td className="tabular py-1.5 text-right">
                    {disturbed.length ? (
                      <span className="text-ink-3" title={disturbedTitle(disturbed)}>
                        {minus(r.text.kneeError)} <span className="whitespace-nowrap">(not counted)</span>
                      </span>
                    ) : (
                      minus(r.text.kneeError)
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {runs.some((r) => r.checks?.disturbed?.length) && (
        <p className="mt-2 text-[12px] text-ink-3">
          Not counted: a disturbed run missed the objective at one load step and met it at a higher one. A stable stack does not get faster with more load, so its knee is not the stack&rsquo;s.
        </p>
      )}
    </div>
  );
}

function disturbedTitle(steps: number[]): string {
  const list = steps.map((v) => Math.round(v).toLocaleString("en-US")).join(", ");
  return `Missed the objective at ${list} req/s but met it at a higher load step`;
}

export function AccuracyPage({ data }: { data: Accuracy }) {
  const s = data.summary;
  const n = s.experiments;
  const obj = data.objective;
  const t = data.thresholds;
  const held = data.experiments.filter((x) => x.sample === "held-out");
  // the held-out experiments lead: they test ArchSim on stacks it was not tuned on
  const ordered = [...byKneeError(held), ...byKneeError(data.experiments.filter((x) => x.sample !== "held-out"))];
  const heldWithin = held.filter((x) => x.knee.withinTolerance).length;
  const lows = data.experiments.flatMap((x) => x.lowLoad).filter((l) => l.diffMs !== null);
  const lowSorted = [...lows].sort((a, b) => (a.diffMs ?? 0) - (b.diffMs ?? 0));
  const allAbove = lows.length > 0 && lows.every((l) => (l.diffMs ?? 0) > 0);
  const lowPct = lows.map((l) => l.text.p95Error).sort((a, b) => parseFloat(a) - parseFloat(b));
  const once = data.experiments.filter((x) => !x.spread).map((x) => x.id);
  const machines = s.machines.length;
  const protocolList = [...new Set(Object.values(data.history).flatMap((runs) => runs.map((r) => r.protocol)))].sort();
  const protocols = protocolList.length <= 1 ? (protocolList[0] ?? "-") : `${protocolList.slice(0, -1).join(", ")} and ${protocolList[protocolList.length - 1]}`;
  const cpu = cpuSentence(data.experiments);
  const coverage = coverageSentence(s);
  const allInSample = s.inSample.length === n;

  return (
    <div className="min-h-full overflow-x-clip bg-page [--meas:var(--series-1)] [--pred:#c26a00] dark:[--pred:#d07400]">
      <header className="sticky top-0 z-30 border-b border-line bg-page/95 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-7xl items-center gap-3 px-4 sm:px-6">
          <Logo />
          <Link href="/" className={navLink}>
            <ArrowLeft aria-hidden /> Home
          </Link>
          <Link href="/templates" className={cn(navLink, "hidden sm:inline-flex")}>
            Templates
          </Link>
          <div className="ml-auto flex items-center gap-1.5">
            <a href="#read" className={cn(navLink, "hidden sm:inline-flex")}>
              How to read these numbers
            </a>
            <ThemeToggle />
            <MobileNav
              className="sm:hidden"
              items={[
                { href: "/", label: "Home" },
                { href: "/templates", label: "Templates" },
                { href: "/projects", label: "Your projects" },
                { href: "#read", label: "How to read these numbers" },
              ]}
            />
          </div>
        </div>
      </header>

      <section className="hero-band relative border-b border-line">
        <div className="mx-auto max-w-5xl px-4 py-14 sm:px-6 sm:py-20">
          <p className="inline-flex items-center gap-2 rounded-full border border-line bg-surface/80 px-3 py-1 text-[13px] font-medium text-ink-2 backdrop-blur-sm">
            <Gauge className="size-3.5 text-accent" aria-hidden /> Accuracy
          </p>
          <h1 className="mt-5 max-w-3xl font-display text-[40px] font-semibold leading-[1.02] tracking-[-0.045em] text-band-ink sm:text-[60px]">
            How accurate is ArchSim?
          </h1>
          <p className="mt-5 max-w-2xl text-[17px] leading-relaxed text-ink-2">
            We run small real systems, ask ArchSim to predict how they behave under load <em>before</em> measuring them, and then measure. Here is every
            result, the good and the bad, and how far to trust them.
          </p>
          <dl className="mt-8 grid grid-cols-2 gap-x-8 gap-y-6 sm:flex sm:flex-wrap sm:gap-x-12">
            {[
              held.length
                ? [`${heldWithin} of ${held.length}`, `held-out knees predicted within ±${Math.round(t.kneeError * 100)}%`]
                : [`${s.kneeWithinTolerance.length} of ${n}`, `capacity knees predicted within ±${Math.round(t.kneeError * 100)}%`],
              [minus(s.kneeErrorRangeText), "range of the knee errors"],
              [`${s.validated.length} of ${n}`, "experiments meet every proposed threshold"],
              [formatDate(s.latestDate), `latest run, ${machines === 1 ? "on one machine" : `on ${machines} machines`}`],
            ].map(([value, label]) => (
              <div key={label} className="flex flex-col-reverse">
                <dt className="mt-1.5 max-w-[12rem] text-[13px] leading-snug text-ink-2">{label}</dt>
                <dd className="tabular whitespace-nowrap font-display text-[22px] font-semibold leading-none tracking-[-0.04em] text-band-ink min-[400px]:text-[26px] sm:text-[34px]">{value}</dd>
              </div>
            ))}
          </dl>
        </div>
      </section>

      <main className="mx-auto max-w-5xl px-4 sm:px-6">
        <Section id="summary" title="The short version">
          <ul className="max-w-3xl space-y-3 text-[15px] leading-relaxed text-ink">
            {ordered.map((x, i) => (
              <li key={x.id} className={cn("flex gap-3", held.length > 0 && i === held.length && "border-t border-line pt-3")}>
                {x.knee.withinTolerance ? <Check className="mt-1 size-4 shrink-0 text-good" aria-hidden /> : <X className="mt-1 size-4 shrink-0 text-critical" aria-hidden />}
                <span>
                  <a href={`#${x.id}`} className="font-semibold text-ink hover:underline">
                    {x.name}
                  </a>
                  {held.length > 0 && <span className="text-ink-2"> ({x.sample === "held-out" ? "held out" : "tuned on"})</span>}: {kneeSentence(x)}
                  {x.caveat && (
                    <span className="text-ink-2">
                      {" "}
                      But its measured knee was limited by a flaw in the benchmark, since fixed (
                      <a href={`#${x.id}`} className="underline">
                        see its card
                      </a>
                      ).
                    </span>
                  )}
                </span>
              </li>
            ))}
            {lows.length > 0 && (
              <li className="flex gap-3">
                <TriangleAlert className="mt-1 size-4 shrink-0 text-warning" aria-hidden />
                <span>
                  {allAbove
                    ? `At light load (at most ${t.lowLoadFraction === 0.5 ? "half" : `${t.lowLoadFraction}×`} the predicted knee), ArchSim predicted a higher p95 than measured at all ${lows.length} steps: by ${lowSorted[0].text.diff.replace(/^\+/, "")} to ${lowSorted[lowSorted.length - 1].text.diff.replace(/^\+/, "")}, or ${minus(lowPct[0])} to ${minus(lowPct[lowPct.length - 1])}.`
                    : `At light load (at most ${t.lowLoadFraction === 0.5 ? "half" : `${t.lowLoadFraction}×`} the predicted knee), ArchSim's p95 was off by ${minus(lowSorted[0].text.diff)} to ${minus(lowSorted[lowSorted.length - 1].text.diff)}, or ${minus(lowPct[0])} to ${minus(lowPct[lowPct.length - 1])}.`}
                </span>
              </li>
            )}
            {coverage && (
              <li className="flex gap-3">
                {coverageAsClaimed(s) ? <Check className="mt-1 size-4 shrink-0 text-good" aria-hidden /> : <TriangleAlert className="mt-1 size-4 shrink-0 text-warning" aria-hidden />}
                <span>{coverage}</span>
              </li>
            )}
            {cpu && (
              <li className="flex gap-3">
                <TriangleAlert className="mt-1 size-4 shrink-0 text-warning" aria-hidden />
                <span>
                  {cpu} See{" "}
                  <a href="#resources" className="font-medium text-ink hover:underline">
                    CPU and memory
                  </a>
                  .
                </span>
              </li>
            )}
            <li className="flex gap-3">
              {s.validated.length ? <Check className="mt-1 size-4 shrink-0 text-good" aria-hidden /> : <X className="mt-1 size-4 shrink-0 text-critical" aria-hidden />}
              <span>
                {s.validated.length === 0
                  ? `None of the ${n} experiments meets all the proposed thresholds yet, so no technology counts as validated.`
                  : `${s.validated.length} of ${n} experiments meet all the proposed thresholds (${names(data, s.validated)}).`}
              </span>
            </li>
          </ul>

          <div role="note" className="mt-8 max-w-3xl rounded-2xl border border-line bg-warning-soft p-5">
            <p className="flex items-center gap-2 text-[15px] font-semibold text-ink">
              <TriangleAlert className="size-4 text-warning" aria-hidden /> Knees move between runs
            </p>
            <p className="mt-2 text-[14px] leading-relaxed text-ink">
              The measured knee is not one fixed number: run the same experiment again on the same machine and it moves
              {s.spreadExceedsError.length
                ? ", sometimes by more than ArchSim's error."
                : ". Under the latest conditions it moved less than ArchSim's error, so the errors below are the model's, not noise."}
            </p>
            <ul className="mt-3 space-y-2 text-[14px] leading-relaxed text-ink">
              {data.experiments
                .filter((x) => x.spread)
                .map((x) => (
                  <li key={x.id}>
                    <span className="font-semibold">{x.name}:</span> {spreadSentence(x)}
                  </li>
                ))}
              {once.length > 0 && (
                <li>
                  {names(data, once)} {once.length === 1 ? "has" : "have"} run once, so {once.length === 1 ? "its" : "their"} spread is not known yet.
                </li>
              )}
            </ul>
            <p className="mt-3 text-[14px] leading-relaxed text-ink">
              Read these errors as results from {machines === 1 ? "one machine" : `${machines} machines`}
              {allInSample ? ", on experiments the model was tuned on," : ","} not as ArchSim&apos;s accuracy in general.
            </p>
          </div>
        </Section>

        <Section
          id="experiments"
          title="Each experiment"
          lead={
            <>
              The latest run of each experiment. The objective is p95 at most {obj.p95Ms} ms with at least {Math.round(obj.minSuccess * 100)}% of
              requests succeeding; the knee is the highest load that still meets it.
            </>
          }
        >
          <KneeLegend />
          <div className="mt-6 grid gap-5">
            {data.experiments.map((x) => (
              <ExperimentCard key={x.id} x={x} data={data} />
            ))}
          </div>
        </Section>

        <ResourcesSection data={data} />

        <Section id="read" title="How to read these numbers">
          <dl className="grid max-w-3xl gap-5 text-[15px] leading-relaxed">
            {[
              [
                "Knee",
                `The highest load that still meets the objective (p95 at most ${obj.p95Ms} ms, at least ${Math.round(obj.minSuccess * 100)}% success). k6 measures a ladder of load steps, three repeats each; the knee is interpolated between the last step that meets the objective and the first that does not. The predicted knee uses the same steps.`,
              ],
              [
                "Disturbed run",
                "A run that missed the objective at one load step and met it at a higher one. A stable system does not get faster with more load, so its knee is not the system's: it is listed, but not counted in the medians, the spread or the thresholds.",
              ],
              [
                "Knee error",
                "(predicted − measured) ÷ measured. Negative means ArchSim promised less capacity than the system had, which is the safe side; positive means it promised more.",
              ],
              [
                "Latency below the knee",
                `At each load step up to the measured knee, a prediction counts as close when it is within a factor of ${t.p95Factor} of the measured p95 (from half to double) or within ${t.p95Ms} ms of it, whichever is wider. The test is the same both ways: predicting half the measured latency misses as much as predicting double. The ${t.p95Ms} ms allowance is there because latencies of a millisecond or two make factors look large.`,
              ],
              [
                "Unstable step",
                "A load step where some repeats met the objective and others missed it, with p95s more than 2× apart. Its median is less trustworthy than other steps'.",
              ],
              [
                "Validated",
                `A proposed bar, not applied anywhere yet: over at least three counted runs, a median knee error within ±${Math.round(t.kneeError * 100)}%; latency close at every step below the knee, and no unstable step there, in two of three runs; and a run on a second machine. A technology is validated when every experiment that uses it is.`,
              ],
              [
                "In-sample",
                s.inSample.length
                  ? `${allInSample ? "Every experiment here" : names(data, s.inSample)} ${s.inSample.length === 1 ? "is" : "are"} in-sample: the calibration protocol was revised after their earlier results had been seen, so those numbers may flatter the model. ${allInSample ? "Held-out experiments, sealed until a frozen engine has predicted them, will test ArchSim on stacks it was not tuned on." : held.length ? `${names(data, held.map((x) => x.id))} ${held.length === 1 ? "is" : "are"} held out: registered before the engine was frozen, sealed until it had predicted them, and never used to tune it. They are the test of ArchSim on stacks it was not tuned on.` : "The others were first run under the current protocol."}`
                  : "Every experiment was first run under the current protocol, so none of them shaped it.",
              ],
              [
                "Predicted first",
                "Each experiment calibrates the model from a staircase of runs well below the knee, then ArchSim predicts every higher step and the predictions are saved before the first step is measured. Nothing measured afterwards feeds back into the model.",
              ],
            ].map(([term, text]) => (
              <Term key={term} term={term}>
                {text}
              </Term>
            ))}
            {data.javaEngine && (
              <Term term="Java engine">
                {data.javaEngine.text} The comparison is in <RepoFile path={JAVA_PARITY_DOC} small /> in the repository.
              </Term>
            )}
          </dl>
        </Section>

        <Section
          id="history"
          title="Every run so far"
          lead={
            <>
              {s.runs} runs {s.firstDate === s.latestDate ? `on ${formatDate(s.latestDate)}` : `between ${formatDate(s.firstDate)} and ${formatDate(s.latestDate)}`},
              under calibration protocols {protocols}
              {s.disturbedRuns ? `; ${s.disturbedRuns} of them were disturbed and are not counted` : ""}.{" "}
              What changed between protocols, and why, is under &ldquo;Protocol history&rdquo; in <RepoFile path={VALIDATION_DOC} /> in the repository.
              Knees are in req/s.
            </>
          }
        >
          <div className="grid gap-8 md:grid-cols-2">
            {data.experiments.map((x) => (
              <HistoryTable key={x.id} id={x.id} data={data} />
            ))}
          </div>
          <div className="mt-8 max-w-3xl text-[13px] leading-relaxed text-ink-2">
            <p className="font-medium text-ink">{machines === 1 ? `Machine (all ${s.runs} runs)` : "Machines"}</p>
            <ul className="mt-1 space-y-1">
              {s.machines.map((m) => (
                <li key={m}>{m}</li>
              ))}
            </ul>
            <p className="mt-3">
              Each experiment runs on Docker with its containers pinned to their own CPUs; the latest run&apos;s full environment is under its
              experiment above. The model inputs and every measured repeat are in <RepoFile path={VALIDATION_DOC} small /> and{" "}
              <RepoFile path="benchmarks/results/" small /> in the repository. A benchmark workflow (<RepoFile path={BENCHMARK_WORKFLOW} small />) is set
              to re-run the experiments every week and for every release and to open a pull request with the new numbers; this page changes only when a
              person merges one.
            </p>
          </div>
        </Section>

        <Section
          id="calibrate"
          title="How to calibrate your own design"
          lead={
            <>
              These experiments are small synthetic services on {machines === 1 ? "one machine" : `${machines} machines`}. A new design in ArchSim starts
              from illustrative defaults, not from your system. Before you trust its numbers for a decision:
            </>
          }
        >
          <ol className="grid max-w-3xl gap-4 text-[15px] leading-relaxed text-ink-2">
            {[
              [
                "Calibrate from your telemetry.",
                <>
                  Feed ArchSim your Prometheus metrics or an OpenTelemetry trace export (in the editor: Ctrl/⌘ K → Calibrate from telemetry, or{" "}
                  <code className="font-code text-[13px] text-ink">archsim calibrate</code>). Each fitted input is marked <em>measured</em>, with its source
                  and fit error.
                </>,
              ],
              [
                "Check the replay.",
                "Calibration replays the traffic it saw through the model and reports how close it comes to the latency and throughput your system actually had. A large replay error means the model is missing something about your system.",
              ],
              [
                "Look at what is still assumed.",
                "Results list every input as measured, assumed or illustrative. Numbers that rest on illustrative inputs are estimates of a system like yours, not of yours.",
              ],
              [
                "Measure your own knee.",
                <>
                  Load-test one step past the predicted knee in a staging environment, or run this page&apos;s benchmarks on your hardware (
                  <code className="font-code text-[13px] text-ink">./gradlew :archsim-benchmarks:run --args=&quot;all&quot;</code>).
                </>,
              ],
            ].map(([head, body], i) => (
              <li key={i} className="flex gap-4">
                <span className="tabular flex size-7 shrink-0 items-center justify-center rounded-full bg-accent-soft text-[13px] font-semibold text-accent">{i + 1}</span>
                <span>
                  <span className="font-semibold text-ink">{head}</span> {body}
                </span>
              </li>
            ))}
          </ol>
          <p className="mt-6 max-w-3xl text-[14px] leading-relaxed text-ink-2">
            In the repository, <RepoFile path={CALIBRATION_DOC} /> is the calibration guide (Prometheus and OpenTelemetry, what each fitted input
            means, the replay check), and <RepoFile path={VALIDATION_DOC} /> has the full method behind this page, the model inputs of every experiment
            and the raw data.
          </p>
        </Section>
      </main>

      <footer className="border-t border-line">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 gap-y-3 px-4 py-8 text-[13px] text-ink-3 sm:px-6">
          <Logo />
          <Link href="/" className="hover:text-ink">
            Home
          </Link>
          <Link href="/templates" className="hover:text-ink">
            Templates
          </Link>
          <Link href="/projects" className="hover:text-ink">
            Your projects
          </Link>
          <span className="sm:ml-auto">Every figure on this page comes from benchmarks/results in the repository.</span>
        </div>
      </footer>
    </div>
  );
}

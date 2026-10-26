"use client";

/**
 * Calibrate the design from telemetry: Prometheus (a server URL, or responses recorded with
 * `archsim calibrate --record`) or an OpenTelemetry trace export. The engine fits the inputs,
 * replays what was observed, and this dialog shows the changes before they are applied.
 */
import { Gauge, Upload } from "lucide-react";
import { Dialog as RDialog } from "radix-ui";
import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input, Label, Textarea } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/overlays";
import { type CalibrateResult, api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

type Source = "prometheus" | "otlp";

const MAPPING_EXAMPLE = `nodes:
  api:
    serviceTimeMs: {histogram: 'sum by (le) (rate(http_server_duration_seconds_bucket{job="api"}[5m]))', unit: s}
  cache:
    hitRatio: {ratio: {hits: 'sum(rate(redis_keyspace_hits_total[5m]))', misses: 'sum(rate(redis_keyspace_misses_total[5m]))'}}
workload:
  rps: {rate: 'sum(rate(http_requests_total{job="api"}[1m]))', trace: true}
replay:
  endToEnd: {histogram: 'sum by (le) (rate(http_server_duration_seconds_bucket{job="lb"}[5m]))', unit: s}`;

function lastDay(): string {
  const end = new Date();
  end.setSeconds(0, 0);
  const start = new Date(end.getTime() - 24 * 3600 * 1000);
  const iso = (d: Date) => d.toISOString().replace(/\.\d+Z$/, "Z");
  return `${iso(start)}/${iso(end)}`;
}

async function readJson(file: File): Promise<Record<string, unknown>> {
  return JSON.parse(await file.text()) as Record<string, unknown>;
}

const pct = (v: number | null | undefined) => (v == null ? "–" : `${v > 0 ? "+" : ""}${Math.round(v * 100)}%`);
const num = (v: number | null | undefined) => (v == null ? "–" : v >= 100 ? v.toFixed(0) : v >= 10 ? v.toFixed(1) : v.toFixed(2));

function describe(v: unknown): string {
  if (v == null) return "–";
  if (typeof v === "number") return num(v);
  if (typeof v === "object" && "kind" in (v as Record<string, unknown>)) {
    const d = v as Record<string, number | string | unknown[]>;
    if (d.kind === "lognormal") return `p50 ${num(d.median as number)} / p95 ${num(d.p95 as number)} ms`;
    if (d.kind === "constant") return `${num(d.value as number)} ms`;
    if (d.kind === "empirical") return `measured table (${(d.quantiles as unknown[]).length} points)`;
    return String(d.kind);
  }
  return String(v);
}

export function CalibrateDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [source, setSource] = useState<Source>("prometheus");
  const [url, setUrl] = useState("http://localhost:9090");
  const [range, setRange] = useState(lastDay);
  const [mapping, setMapping] = useState("");
  const [recorded, setRecorded] = useState<{ name: string; data: Record<string, unknown> } | null>(null);
  const [traces, setTraces] = useState<{ name: string; data: Record<string, unknown> } | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<CalibrateResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = async () => {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const document = useEditor.getState().toDocument();
      const res =
        source === "prometheus"
          ? await api.calibrate({
              document,
              source,
              mapping,
              prometheusUrl: recorded ? null : url,
              recorded: recorded?.data ?? null,
              range: recorded ? null : range.trim() || null, // a recording knows its own range
            })
          : await api.calibrate({ document, source, mapping: mapping.trim() || null, traces: traces?.data ?? null });
      setResult(res);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const apply = () => {
    if (!result) return;
    useEditor.getState().replaceDocument(result.document);
    toast.success(`Calibrated ${result.changes.length} input${result.changes.length === 1 ? "" : "s"} from telemetry`, {
      description: result.summary ?? "Measured inputs carry a badge in the inspector. Undo with Ctrl Z; save to keep it.",
    });
    onOpenChange(false);
    setResult(null);
  };

  const ready = source === "prometheus" ? mapping.trim().length > 0 && (recorded !== null || url.trim().length > 0) : traces !== null;

  return (
    <RDialog.Root open={open} onOpenChange={onOpenChange}>
      <RDialog.Portal>
        <RDialog.Overlay className="fixed inset-0 z-50 bg-black/55" />
        <RDialog.Content className="fixed left-1/2 top-1/2 z-50 flex max-h-[90vh] w-[min(820px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2 flex-col overflow-y-auto rounded-lg border border-line-strong bg-surface p-5 shadow-float">
          <div className="mb-3">
            <p className="microlabel">Calibrate</p>
            <RDialog.Title className="mt-1 text-[15px] font-semibold text-ink">Set inputs from telemetry</RDialog.Title>
            <RDialog.Description className="mt-1 text-xs leading-relaxed text-ink-2">
              Service times, hit ratios, link latency and traffic are fitted from what your system recorded, each marked measured with its source and fit
              quality, and the calibrated model is replayed against the observed latency. Queries go only to the server you name. Nothing changes until you
              apply it.
            </RDialog.Description>
          </div>
          <Tabs value={source} onValueChange={(v) => { setSource(v as Source); setResult(null); setError(null); }}>
            <TabsList>
              <TabsTrigger value="prometheus">Prometheus</TabsTrigger>
              <TabsTrigger value="otlp">OpenTelemetry traces</TabsTrigger>
            </TabsList>
            <TabsContent value="prometheus" className="mt-3 space-y-3">
              <div className="grid gap-3 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor="cal-url">Prometheus URL</Label>
                  <Input id="cal-url" value={url} onChange={(e) => setUrl(e.target.value)} disabled={recorded !== null} placeholder="http://localhost:9090" />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="cal-range">{recorded ? "Time range (the recording's own)" : "Time range (start/end, ISO 8601)"}</Label>
                  <Input
                    id="cal-range"
                    value={recorded ? recordedRange(recorded.data) : range}
                    onChange={(e) => setRange(e.target.value)}
                    disabled={recorded !== null}
                    placeholder="the mapping's range"
                    className="font-mono text-xs"
                  />
                </div>
              </div>
              <FilePick
                label={recorded ? `Recorded responses: ${recorded.name}` : "Or use recorded responses (archsim calibrate … --record)"}
                onFile={async (f) => setRecorded(f ? { name: f.name, data: await readJson(f) } : null)}
                active={recorded !== null}
              />
              <div className="space-y-1.5">
                <Label htmlFor="cal-map">Mapping (YAML): which query sets which input</Label>
                <Textarea id="cal-map" rows={8} value={mapping} onChange={(e) => setMapping(e.target.value)} placeholder={MAPPING_EXAMPLE} className="font-mono text-xs" />
              </div>
            </TabsContent>
            <TabsContent value="otlp" className="mt-3 space-y-3">
              <FilePick
                label={traces ? `Traces: ${traces.name}` : "Choose an OTLP JSON trace export (resourceSpans)"}
                onFile={async (f) => setTraces(f ? { name: f.name, data: await readJson(f) } : null)}
                active={traces !== null}
              />
              <div className="space-y-1.5">
                <Label htmlFor="cal-otlp-map">Mapping (optional YAML): service and database names to parts</Label>
                <Textarea id="cal-otlp-map" rows={4} value={mapping} onChange={(e) => setMapping(e.target.value)} placeholder={"services:\n  checkout-api: api\ndatabases:\n  orders: db"} className="font-mono text-xs" />
              </div>
            </TabsContent>
          </Tabs>
          <div className="mt-4 flex items-center gap-2">
            <Button variant="primary" onClick={() => void run()} disabled={!ready || busy}>
              <Gauge /> {busy ? "Calibrating…" : "Calibrate and replay"}
            </Button>
            {error && <p className="min-w-0 flex-1 text-xs text-danger-ink">{error}</p>}
          </div>
          {result && <CalibrationPreview result={result} onApply={apply} />}
        </RDialog.Content>
      </RDialog.Portal>
    </RDialog.Root>
  );
}

/** A recording's own window ("start/end"), or "" when it has none (the mapping's range applies). */
function recordedRange(data: Record<string, unknown>): string {
  const r = data.range as { start?: unknown; end?: unknown } | undefined;
  return r && typeof r.start === "string" && typeof r.end === "string" ? `${r.start}/${r.end}` : "";
}

function FilePick({ label, onFile, active }: { label: string; onFile: (f: File | null) => void; active: boolean }) {
  return (
    <label className={cn("flex cursor-pointer items-center gap-2 rounded-md border border-dashed px-3 py-2 text-xs", active ? "border-accent text-ink" : "border-line text-ink-2 hover:border-line-strong")}>
      <Upload className="size-3.5" />
      <span className="min-w-0 flex-1 truncate">{label}</span>
      <input type="file" accept="application/json,.json" className="sr-only" onChange={(e) => onFile(e.target.files?.[0] ?? null)} />
    </label>
  );
}

function CalibrationPreview({ result, onApply }: { result: CalibrateResult; onApply: () => void }) {
  const replay = result.calibration?.replay ?? [];
  return (
    <div className="mt-4 space-y-3 border-t border-line pt-4">
      {result.summary && <p className="text-[13px] font-medium text-ink">{result.summary}</p>}
      <div className="overflow-auto rounded-md border border-line">
        <table className="w-full text-xs">
          <thead className="bg-surface-2 text-left text-ink-3">
            <tr>
              <th className="px-3 py-1.5 font-medium">Input</th>
              <th className="px-2 py-1.5 font-medium">Before</th>
              <th className="px-2 py-1.5 font-medium">Measured</th>
              <th className="px-3 py-1.5 font-medium">Fit error</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {result.changes.map((c) => (
              <tr key={c.path}>
                <td className="px-3 py-1.5 text-ink">{c.label}</td>
                <td className="px-2 py-1.5 font-mono text-ink-3">{describe(c.before)}</td>
                <td className="px-2 py-1.5 font-mono text-ink">{describe(c.after)}</td>
                <td className="px-3 py-1.5 tabular text-ink-2">{c.fitError == null ? "–" : `${Math.round(c.fitError * 100)}%`}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {replay.length > 0 && (
        <div className="overflow-auto rounded-md border border-line">
          <table className="w-full text-xs">
            <thead className="bg-surface-2 text-left text-ink-3">
              <tr>
                <th className="px-3 py-1.5 font-medium">Replay check</th>
                <th className="px-2 py-1.5 font-medium">Metric</th>
                <th className="px-2 py-1.5 font-medium">Observed</th>
                <th className="px-2 py-1.5 font-medium">Simulated</th>
                <th className="px-3 py-1.5 font-medium">Error</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {replay.map((r, i) => (
                <tr key={i}>
                  <td className="px-3 py-1.5 text-ink">{r.label}</td>
                  <td className="px-2 py-1.5 text-ink-2">{r.metric}</td>
                  <td className="px-2 py-1.5 tabular">{num(r.observed)}</td>
                  <td className="px-2 py-1.5 tabular">{num(r.simulated)}</td>
                  <td className="px-3 py-1.5 tabular">{pct(r.error)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {result.notes.length > 0 && (
        <ul className="list-disc space-y-0.5 pl-5 text-xs text-ink-2">
          {result.notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      )}
      <div className="flex justify-end">
        <Button variant="primary" onClick={onApply} disabled={!result.changes.length}>
          Apply {result.changes.length} change{result.changes.length === 1 ? "" : "s"}
        </Button>
      </div>
    </div>
  );
}

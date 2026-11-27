"use client";

/**
 * Import a design from what a team already has: a docker-compose file, Kubernetes manifests or a
 * Terraform plan (`terraform show -json`). The engine reads it and says what it mapped, what it
 * guessed (marked assumed), what it ignored and which links to confirm; nothing changes until the
 * design is replaced (undoable).
 */
import { FileInput, Upload } from "lucide-react";
import { Dialog as RDialog } from "radix-ui";
import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Label, Textarea } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/overlays";
import { type ImportFormat, type ImportResult, api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

const FORMATS: { key: ImportFormat; label: string; accept: string; hint: string }[] = [
  { key: "compose", label: "docker-compose", accept: ".yml,.yaml", hint: "A docker-compose.yml: images become parts, depends_on and URLs become links." },
  { key: "k8s", label: "Kubernetes", accept: ".yml,.yaml", hint: "Manifests (Deployments, StatefulSets, Services, HPAs, Ingresses), several per file." },
  {
    key: "terraform",
    label: "Terraform",
    accept: ".json",
    hint: "The JSON of `terraform show -json plan.out` (or of a state): databases, caches, queues, functions, clusters.",
  },
];

/** The likely format of a file from its name and first lines. */
export function guessFormat(name: string, text: string): ImportFormat | null {
  if (/\.json$/i.test(name) || /^\s*\{/.test(text)) return "terraform";
  if (/^\s*services\s*:/m.test(text)) return "compose";
  if (/^\s*apiVersion\s*:/m.test(text) && /^\s*kind\s*:/m.test(text)) return "k8s";
  return null;
}

export function ImportDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [format, setFormat] = useState<ImportFormat>("compose");
  const [text, setText] = useState("");
  const [fileName, setFileName] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const current = FORMATS.find((f) => f.key === format) ?? FORMATS[0];

  const onFile = async (file: File | null) => {
    setResult(null);
    setError(null);
    if (!file) return;
    const body = await file.text();
    setText(body);
    setFileName(file.name);
    const guessed = guessFormat(file.name, body);
    if (guessed) setFormat(guessed);
  };

  const read = async () => {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await api.importDesign({ format, text }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const apply = () => {
    if (!result) return;
    const { document, report } = result;
    useEditor.getState().replaceDocument(document);
    const errors = report.issues.filter((i) => i.severity === "error").length;
    toast.success(`Imported ${document.architecture.nodes.length} parts from ${report.source}`, {
      description:
        (report.guessed.length ? `${report.guessed.length} guesses to check. ` : "") +
        (errors ? `${errors} problems to fix before a run. ` : "") +
        "Undo with Ctrl Z.",
    });
    onOpenChange(false);
    setResult(null);
  };

  return (
    <RDialog.Root open={open} onOpenChange={onOpenChange}>
      <RDialog.Portal>
        <RDialog.Overlay className="fixed inset-0 z-50 bg-black/55" />
        <RDialog.Content className="fixed left-1/2 top-1/2 z-50 flex max-h-[90vh] w-[min(820px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2 flex-col overflow-y-auto rounded-lg border border-line-strong bg-surface p-5 shadow-float">
          <div className="mb-3">
            <p className="microlabel">Import</p>
            <RDialog.Title className="mt-1 text-[15px] font-semibold text-ink">Start from what you already run</RDialog.Title>
            <RDialog.Description className="mt-1 text-xs leading-relaxed text-ink-2">
              Infrastructure files say what runs, rarely who calls whom or how fast it is. The import maps what it can, lists what it guessed and what
              it left out, and suggests links instead of inventing them. It replaces the current design when you apply it (undo with Ctrl Z).
            </RDialog.Description>
          </div>
          <Tabs value={format} onValueChange={(v) => { setFormat(v as ImportFormat); setResult(null); setError(null); }}>
            <TabsList>
              {FORMATS.map((f) => (
                <TabsTrigger key={f.key} value={f.key}>
                  {f.label}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
          <p className="mt-2 text-xs text-ink-2">{current.hint}</p>
          <label
            className={cn(
              "mt-3 flex cursor-pointer items-center gap-2 rounded-md border border-dashed px-3 py-2 text-xs",
              fileName ? "border-accent text-ink" : "border-line text-ink-2 hover:border-line-strong",
            )}
          >
            <Upload className="size-3.5" />
            <span className="min-w-0 flex-1 truncate">{fileName ? `File: ${fileName}` : `Choose a file (${current.accept}) or paste it below`}</span>
            <input type="file" accept={`${current.accept},.yml,.yaml,.json`} className="sr-only" onChange={(e) => void onFile(e.target.files?.[0] ?? null)} />
          </label>
          <div className="mt-3 space-y-1.5">
            <Label htmlFor="import-text">Content</Label>
            <Textarea
              id="import-text"
              rows={8}
              value={text}
              onChange={(e) => { setText(e.target.value); setFileName(null); setResult(null); }}
              placeholder={format === "terraform" ? '{"format_version": "1.2", "planned_values": {…}}' : "services:\n  api:\n    image: …"}
              className="font-mono text-xs"
            />
          </div>
          <div className="mt-4 flex items-center gap-2">
            <Button variant="primary" onClick={() => void read()} disabled={!text.trim() || busy}>
              <FileInput /> {busy ? "Reading…" : "Read it"}
            </Button>
            {error && <p className="min-w-0 flex-1 text-xs text-danger-ink">{error}</p>}
          </div>
          {result && <ImportPreview result={result} onApply={apply} />}
        </RDialog.Content>
      </RDialog.Portal>
    </RDialog.Root>
  );
}

function Section({ title, items, tone }: { title: string; items: string[]; tone?: "warn" }) {
  if (!items.length) return null;
  return (
    <details className="rounded-md border border-line" open={tone === "warn"}>
      <summary className="cursor-pointer px-3 py-1.5 text-xs font-medium text-ink">
        {title} <span className="tabular text-ink-3">({items.length})</span>
      </summary>
      <ul className={cn("list-disc space-y-0.5 border-t border-line py-2 pl-7 pr-3 text-xs", tone === "warn" ? "text-ink" : "text-ink-2")}>
        {items.map((t, i) => (
          <li key={i}>{t}</li>
        ))}
      </ul>
    </details>
  );
}

function ImportPreview({ result, onApply }: { result: ImportResult; onApply: () => void }) {
  const { document, report } = result;
  const errors = report.issues.filter((i) => i.severity === "error");
  const warnings = report.issues.filter((i) => i.severity !== "error");
  return (
    <div className="mt-4 space-y-2 border-t border-line pt-4">
      <p className="text-[13px] font-medium text-ink">
        {document.architecture.nodes.length} parts and {document.architecture.edges.length} links from {report.source}
        {report.guessed.length ? `; ${report.guessed.length} guesses to check` : ""}.
      </p>
      <Section title="Guessed (marked assumed): check these" items={report.guessed} tone="warn" />
      <Section
        title="Links to confirm (not added)"
        items={report.suggestions.map((s) => `${s.source} → ${s.target}: ${s.reason}`)}
        tone="warn"
      />
      <Section title="To fix before a run" items={errors.map((i) => i.message)} tone="warn" />
      <Section title="Mapped" items={report.mapped} />
      <Section title="Ignored" items={report.ignored} />
      <Section title="Warnings" items={warnings.map((i) => i.message)} />
      <div className="flex justify-end pt-1">
        <Button variant="primary" onClick={onApply}>
          Replace the design
        </Button>
      </div>
    </div>
  );
}

"use client";

/** Export the design as docker-compose, Kubernetes manifests or a Mermaid diagram. */
import { Check, Copy, Download } from "lucide-react";
import { Dialog as RDialog } from "radix-ui";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/overlays";
import { slugify } from "@/lib/document";
import { toDockerCompose } from "@/lib/export/compose";
import { toKubernetes } from "@/lib/export/k8s";
import { toMermaid } from "@/lib/export/mermaid";
import { withAutoSizes } from "@/store/sizes";
import { useEditor } from "@/store/editor";

type Format = "compose" | "k8s" | "mermaid";

const FORMATS: { key: Format; label: string; ext: string; mime: string; blurb: string }[] = [
  { key: "compose", label: "docker-compose", ext: "docker-compose.yml", mime: "application/yaml", blurb: "Run the stack locally: one container per component, replicas and dependencies from the model." },
  { key: "k8s", label: "Kubernetes", ext: "k8s.yaml", mime: "application/yaml", blurb: "Deployments, Services, HPAs from autoscaling, StatefulSets for stores and brokers, Knative for serverless." },
  { key: "mermaid", label: "Mermaid", ext: "mmd", mime: "text/plain", blurb: "A diagram for READMEs and design docs (GitHub, GitLab and Notion render it)." },
];

function downloadText(filename: string, text: string, mime: string) {
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function CodeView({ text }: { text: string }) {
  const lines = text.replace(/\n$/, "").split("\n");
  return (
    <div className="max-h-[52vh] overflow-auto rounded-md border border-line bg-surface-2">
      <pre className="grid min-w-max grid-cols-[auto_1fr] font-code text-xs leading-[18px]">
        {lines.map((l, i) => (
          <code key={i} className="contents">
            <span className="select-none border-r border-line px-2.5 text-right text-ink-3">{i + 1}</span>
            <span className={l.startsWith("#") || l.startsWith("%%") ? "px-3 text-ink-3" : l === "---" ? "px-3 text-accent" : "px-3 text-ink"}>{l || " "}</span>
          </code>
        ))}
      </pre>
    </div>
  );
}

export function ExportDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [format, setFormat] = useState<Format>("compose");
  const [copied, setCopied] = useState<Format | null>(null);
  const name = useEditor((s) => s.metadata.name);
  const outputs = useMemo(() => {
    if (!open) return null;
    const st = useEditor.getState();
    const doc = withAutoSizes(st.toDocument(), st.analysis);
    return { compose: toDockerCompose(doc, st.catalog), k8s: toKubernetes(doc, st.catalog), mermaid: toMermaid(doc, st.catalog) };
  }, [open]);
  const base = slugify(name || "architecture");

  const copy = async (f: Format) => {
    if (!outputs) return;
    try {
      await navigator.clipboard.writeText(outputs[f]);
      setCopied(f);
      setTimeout(() => setCopied(null), 1500);
    } catch {
      toast.error("Copy failed", { description: "The browser blocked clipboard access. Use Download instead." });
    }
  };

  return (
    <RDialog.Root open={open} onOpenChange={onOpenChange}>
      <RDialog.Portal>
        <RDialog.Overlay className="fixed inset-0 z-50 bg-black/55" />
        <RDialog.Content className="fixed left-1/2 top-1/2 z-50 flex max-h-[90vh] w-[min(920px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2 flex-col rounded-lg border border-line-strong bg-surface p-5 shadow-float">
          <div className="mb-3">
            <p className="microlabel">
              Export
            </p>
            <RDialog.Title className="mt-1 font-mono text-[15px] font-semibold text-ink">From model to infrastructure</RDialog.Title>
            <RDialog.Description className="mt-1 text-xs leading-relaxed text-ink-2">
              Starting-point scaffolding generated from the design. Images, ports, credentials, resources and storage are placeholders to review before
              use; capacity values come from the model&apos;s assumptions.
            </RDialog.Description>
          </div>
          <Tabs value={format} onValueChange={(v) => setFormat(v as Format)} className="flex min-h-0 flex-1 flex-col">
            <TabsList>
              {FORMATS.map((f) => (
                <TabsTrigger key={f.key} value={f.key}>
                  {f.label}
                </TabsTrigger>
              ))}
            </TabsList>
            {FORMATS.map((f) => (
              <TabsContent key={f.key} value={f.key} className="mt-3 flex min-h-0 flex-1 flex-col gap-3">
                <div className="flex items-center gap-2">
                  <p className="min-w-0 flex-1 text-xs text-ink-2">{f.blurb}</p>
                  <Button size="sm" onClick={() => void copy(f.key)} disabled={!outputs}>
                    {copied === f.key ? <Check /> : <Copy />}
                    {copied === f.key ? "Copied" : "Copy"}
                  </Button>
                  <Button size="sm" variant="primary" onClick={() => outputs && downloadText(`${base}.${f.ext}`, outputs[f.key], f.mime)} disabled={!outputs}>
                    <Download /> {base}.{f.ext}
                  </Button>
                </div>
                {outputs && <CodeView text={outputs[f.key]} />}
              </TabsContent>
            ))}
          </Tabs>
        </RDialog.Content>
      </RDialog.Portal>
    </RDialog.Root>
  );
}

"use client";

import {
  ArrowRight,
  Boxes,
  Copy,
  FileJson,
  FlaskConical,
  LoaderCircle,
  MoreHorizontal,
  Plus,
  Trash2,
  Upload,
} from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input, Label } from "@/components/ui/input";
import { Badge, Empty } from "@/components/ui/misc";
import {
  Dialog,
  DialogContent,
  Menu,
  MenuContent,
  MenuItem,
  MenuSeparator,
  MenuTrigger,
} from "@/components/ui/overlays";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { ApiError, api } from "@/lib/api";
import { fmtRelativeTime } from "@/lib/format";
import type { ExampleSummary, ProjectSummary } from "@/lib/types";
import { cn, readJsonFile } from "@/lib/utils";

export function ProjectsHome() {
  const router = useRouter();
  const [projects, setProjects] = useState<ProjectSummary[] | null>(null);
  const [examples, setExamples] = useState<ExampleSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [newOpen, setNewOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const refresh = useCallback(async () => {
    try {
      const [p, e] = await Promise.all([api.listProjects(), api.examples()]);
      setProjects(p);
      setExamples(e);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setProjects([]);
    }
  }, []);

  useEffect(() => {
    let alive = true;
    Promise.all([api.listProjects(), api.examples()])
      .then(([p, e]) => {
        if (!alive) return;
        setProjects(p);
        setExamples(e);
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

  const create = async (body: Parameters<typeof api.createProject>[0], key: string) => {
    setBusy(key);
    try {
      const p = await api.createProject(body);
      router.push(`/projects/${p.id}`);
    } catch (e) {
      toast.error("Could not create project", { description: e instanceof Error ? e.message : String(e) });
      setBusy(null);
    }
  };

  const onImport = async (file: File) => {
    setBusy("import");
    try {
      const doc = await readJsonFile(file);
      const p = await api.importProject(doc);
      toast.success(`Imported “${p.name}”`);
      router.push(`/projects/${p.id}`);
    } catch (e) {
      const msg =
        e instanceof ApiError
          ? e.message
          : e instanceof SyntaxError
            ? "The file is not valid JSON."
            : String(e);
      toast.error("Import failed", { description: msg });
      setBusy(null);
    }
  };

  const remove = async (p: ProjectSummary) => {
    if (!window.confirm(`Delete “${p.name}” and all its simulation runs? This cannot be undone.`)) return;
    try {
      await api.deleteProject(p.id);
      toast.success("Project deleted");
      void refresh();
    } catch (e) {
      toast.error("Delete failed", { description: e instanceof Error ? e.message : String(e) });
    }
  };

  const duplicate = async (p: ProjectSummary) => {
    try {
      await api.duplicateProject(p.id);
      void refresh();
    } catch (e) {
      toast.error("Duplicate failed", { description: e instanceof Error ? e.message : String(e) });
    }
  };

  return (
    <div className="min-h-full bg-page">
      <header className="border-b border-line bg-surface">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-3 px-4">
          <Logo />
          <div className="ml-auto flex items-center gap-1">
            <ThemeToggle />
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-4 py-8">
        <section className="mb-8 grid gap-6 md:grid-cols-[1.4fr_1fr] md:items-end">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight text-ink">Design it, load it, see where it breaks.</h1>
            <p className="mt-2 max-w-2xl text-sm leading-relaxed text-ink-2">
              Draw a distributed system, describe its traffic, and run a real discrete-event simulation to see
              queues, latency percentiles, throughput and bottlenecks. Results are estimates from the assumptions you
              enter, not production guarantees.
            </p>
          </div>
          <div className="flex flex-wrap gap-2 md:justify-end">
            <input
              ref={fileRef}
              type="file"
              accept="application/json,.json"
              className="hidden"
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) void onImport(f);
                e.target.value = "";
              }}
            />
            <Button onClick={() => fileRef.current?.click()} disabled={busy !== null}>
              {busy === "import" ? <LoaderCircle className="animate-spin" /> : <Upload />} Import JSON
            </Button>
            <Button variant="primary" onClick={() => setNewOpen(true)}>
              <Plus /> New project
            </Button>
          </div>
        </section>

        {error && (
          <div role="alert" className="mb-6 rounded-lg border border-line bg-critical-soft p-4 text-sm text-ink">
            <p className="font-medium">Cannot reach the ArchSim API</p>
            <p className="mt-1 text-ink-2">{error}</p>
          </div>
        )}

        <section aria-labelledby="examples-heading" className="mb-10">
          <h2 id="examples-heading" className="mb-3 text-sm font-semibold text-ink">
            Start from an example
          </h2>
          <div className="grid gap-3 md:grid-cols-3">
            {examples.map((ex) => (
              <button
                key={ex.id}
                onClick={() => void create({ exampleId: ex.id }, ex.id)}
                disabled={busy !== null}
                className="group flex flex-col items-start gap-2 rounded-xl border border-line bg-surface p-4 text-left transition-colors hover:border-accent disabled:opacity-60"
              >
                <div className="flex w-full items-center gap-2">
                  <FlaskConical className="size-4 text-ink-3" />
                  <span className="text-sm font-semibold text-ink">{ex.name}</span>
                  {busy === ex.id ? (
                    <LoaderCircle className="ml-auto size-4 animate-spin text-ink-3" />
                  ) : (
                    <ArrowRight className="ml-auto size-4 text-ink-3 opacity-0 transition-opacity group-hover:opacity-100" />
                  )}
                </div>
                <p className="line-clamp-3 text-xs leading-relaxed text-ink-2">{ex.description}</p>
                <div className="mt-auto flex flex-wrap gap-1 pt-1">
                  <Badge>{ex.scenarioCount} scenarios</Badge>
                  {ex.tags.slice(0, 3).map((t) => (
                    <Badge key={t}>{t}</Badge>
                  ))}
                </div>
              </button>
            ))}
            {examples.length === 0 && !error && projects === null && (
              <div className="col-span-3 h-28 animate-pulse rounded-xl bg-surface-2" />
            )}
          </div>
        </section>

        <section aria-labelledby="projects-heading">
          <h2 id="projects-heading" className="mb-3 text-sm font-semibold text-ink">
            Your projects
          </h2>
          {projects === null ? (
            <div className="space-y-2">
              {[0, 1, 2].map((i) => (
                <div key={i} className="h-16 animate-pulse rounded-lg bg-surface-2" />
              ))}
            </div>
          ) : projects.length === 0 ? (
            <div className="rounded-xl border border-dashed border-line-strong bg-surface">
              <Empty icon={<Boxes />} title="No projects yet">
                Pick an example above to explore a working architecture, or create a blank project and drag
                components from the palette.
              </Empty>
            </div>
          ) : (
            <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface">
              {projects.map((p) => (
                <li key={p.id} className="flex items-center gap-3 px-4 py-3 hover:bg-surface-2">
                  <FileJson className="size-4 shrink-0 text-ink-3" />
                  <Link href={`/projects/${p.id}`} className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium text-ink">{p.name}</p>
                    <p className="truncate text-xs text-ink-3">
                      {p.nodeCount} components · {p.scenarioCount} scenarios · updated {fmtRelativeTime(p.updatedAt)}
                    </p>
                  </Link>
                  <Menu>
                    <MenuTrigger asChild>
                      <Button variant="ghost" size="icon-sm" aria-label={`Actions for ${p.name}`}>
                        <MoreHorizontal />
                      </Button>
                    </MenuTrigger>
                    <MenuContent>
                      <MenuItem onSelect={() => router.push(`/projects/${p.id}`)}>
                        <ArrowRight /> Open
                      </MenuItem>
                      <MenuItem onSelect={() => void duplicate(p)}>
                        <Copy /> Duplicate
                      </MenuItem>
                      <MenuItem asChild>
                        <a href={`/api/v1/projects/${p.id}/export`} download>
                          <FileJson /> Export JSON
                        </a>
                      </MenuItem>
                      <MenuSeparator />
                      <MenuItem className="text-danger-ink" onSelect={() => void remove(p)}>
                        <Trash2 /> Delete
                      </MenuItem>
                    </MenuContent>
                  </Menu>
                </li>
              ))}
            </ul>
          )}
        </section>
      </main>

      <NewProjectDialog
        open={newOpen}
        onOpenChange={setNewOpen}
        examples={examples}
        busy={busy}
        onCreate={(body, key) => void create(body, key)}
      />
    </div>
  );
}

function NewProjectDialog({
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
  onCreate: (body: Parameters<typeof api.createProject>[0], key: string) => void;
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
                : { exampleId: choice, ...(name.trim() ? { name: name.trim() } : {}) },
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
            <legend className="mb-1.5 text-xs font-medium text-ink-2">Start from</legend>
            {[{ id: "blank", name: "Blank canvas", description: "An empty architecture." }, ...examples].map((o) => (
              <label
                key={o.id}
                className={cn(
                  "flex cursor-pointer gap-3 rounded-lg border p-3",
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

export function Logo({ className }: { className?: string }) {
  return (
    <Link href="/" className={cn("flex items-center gap-2 text-ink", className)} aria-label="ArchSim home">
      <svg viewBox="0 0 24 24" className="size-6" aria-hidden="true">
        <rect x="2" y="9" width="6" height="6" rx="1.5" fill="var(--series-1)" />
        <rect x="16" y="3" width="6" height="6" rx="1.5" fill="var(--series-3)" />
        <rect x="16" y="15" width="6" height="6" rx="1.5" fill="var(--series-2)" />
        <path d="M8 12h4m0 0V6h4m-4 6v6h4" stroke="var(--ink-3)" strokeWidth="1.5" fill="none" />
      </svg>
      <span className="text-sm font-semibold tracking-tight">ArchSim</span>
    </Link>
  );
}

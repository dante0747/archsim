"use client";

import { ArrowRight, Boxes, Copy, FileJson, LoaderCircle, MoreHorizontal, Play, Plus, Trash2, Upload } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { Logo } from "@/components/brand";
import { HeroRecorder } from "@/components/projects/HeroRecorder";
import { MiniDiagram } from "@/components/projects/MiniDiagram";
import { TechIcon } from "@/components/TechIcon";
import { Button } from "@/components/ui/button";
import { Input, Label } from "@/components/ui/input";
import { Empty } from "@/components/ui/misc";
import { Dialog, DialogContent, Menu, MenuContent, MenuItem, MenuSeparator, MenuTrigger } from "@/components/ui/overlays";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { ApiError, api } from "@/lib/api";
import { fmtRelativeTime } from "@/lib/format";
import { FAMILIES, familyOf, kindOf } from "@/lib/theme";
import type { ArchitectureDocument, Catalog, ExampleSummary, ProjectSummary } from "@/lib/types";
import { cn, readJsonFile } from "@/lib/utils";

const WORKSPACES = [
  {
    key: "build",
    n: "01",
    title: "Design",
    text: "Drag in PostgreSQL, Redis, Kafka, Kubernetes services and more, and connect them.",
  },
  {
    key: "stress",
    n: "02",
    title: "Test",
    text: "Send traffic, break things on purpose, and replay what happened second by second.",
  },
  {
    key: "decide",
    n: "03",
    title: "Results",
    text: "Get a clear yes or no, the monthly cost, and the load where it breaks.",
  },
];

export function ProjectsHome() {
  const router = useRouter();
  const [projects, setProjects] = useState<ProjectSummary[] | null>(null);
  const [examples, setExamples] = useState<ExampleSummary[]>([]);
  const [exampleDocs, setExampleDocs] = useState<Record<string, ArchitectureDocument>>({});
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [newOpen, setNewOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const refresh = useCallback(async () => {
    try {
      setProjects(await api.listProjects());
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setProjects([]);
    }
  }, []);

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

  const create = async (body: Parameters<typeof api.createProject>[0], key: string) => {
    setBusy(key);
    try {
      const p = await api.createProject(body);
      router.push(`/projects/${p.id}`);
    } catch (e) {
      toast.error("Could not create project", {
        description: e instanceof Error ? e.message : String(e),
      });
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
      const msg = e instanceof ApiError ? e.message : e instanceof SyntaxError ? "The file is not valid JSON." : String(e);
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
      toast.error("Delete failed", {
        description: e instanceof Error ? e.message : String(e),
      });
    }
  };

  const duplicate = async (p: ProjectSummary) => {
    try {
      await api.duplicateProject(p.id);
      void refresh();
    } catch (e) {
      toast.error("Duplicate failed", {
        description: e instanceof Error ? e.message : String(e),
      });
    }
  };

  const starter = examples.find((e) => e.id === "cache-aside-starter") ?? examples[0];

  return (
    <div className="min-h-full bg-page">
      <header className="sticky top-0 z-30 border-b border-line bg-page/85 backdrop-blur">
        <div className="mx-auto flex h-12 max-w-7xl items-center gap-3 px-4">
          <Logo />
          <span className="microlabel hidden sm:inline">architecture simulator</span>
          <div className="ml-auto flex items-center gap-1">
            <Button variant="ghost" size="sm" asChild className="font-mono text-[11px]">
              <a href="/api/v1/docs" target="_blank" rel="noreferrer">
                API
              </a>
            </Button>
            <ThemeToggle />
          </div>
        </div>
      </header>

      <section className="schematic-grid relative overflow-hidden border-b border-line">
        <div className="aurora" aria-hidden />
        <div className="relative mx-auto grid max-w-7xl items-center gap-10 px-4 py-16 lg:grid-cols-[0.85fr_1.15fr]">
          <div>
            <p className="mb-6 inline-flex items-center gap-2 rounded-full border border-line-strong bg-surface/60 px-3 py-1 text-[12px] font-medium text-ink-2 backdrop-blur">
              <span className="size-1.5 rounded-full bg-accent shadow-[0_0_10px_var(--accent)]" aria-hidden />
              Open source · runs on your machine · no account
            </p>
            <h1 className="font-display text-[3.4rem] font-extrabold leading-[0.95] tracking-[-0.045em] text-ink sm:text-[4.8rem]">
              A flight simulator
              <br />
              <span className="whitespace-nowrap">
                <span className="text-gradient">for your architecture</span>
                <span className="caret ml-1 inline-block h-[0.7em] w-[3px] translate-y-[0.04em] rounded-full bg-accent" aria-hidden />
              </span>
            </h1>
            <p className="mt-6 max-w-lg text-[17px] leading-[1.55] text-ink-2">
              Draw your system, send traffic through it, and see where it breaks, before your users do.
            </p>
            <div className="mt-7 flex flex-wrap gap-2">
              {starter && (
                <Button
                  variant="primary"
                  size="lg"
                  onClick={() => void create({ exampleId: starter.id }, starter.id)}
                  disabled={busy !== null}
                >
                  {busy === starter.id ? <LoaderCircle className="animate-spin" /> : <Play className="fill-current" />} Open the starter
                  example
                </Button>
              )}
              <Button size="lg" onClick={() => setNewOpen(true)}>
                <Plus /> New project
              </Button>
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
              <Button size="lg" variant="ghost" onClick={() => fileRef.current?.click()} disabled={busy !== null}>
                {busy === "import" ? <LoaderCircle className="animate-spin" /> : <Upload />} Import JSON
              </Button>
            </div>
          </div>
          <figure className="overflow-hidden rounded-2xl border border-line-strong bg-surface shadow-[0_30px_80px_-30px_var(--accent)]" aria-hidden>
            <div className="flex items-center gap-2 border-b border-line bg-surface-2 px-3 py-2">
              <span className="flex gap-1.5">
                {[0, 1, 2].map((i) => (
                  <span key={i} className="size-2.5 rounded-full border border-line-strong" />
                ))}
              </span>
              <span className="ml-2 truncate font-mono text-[11px] text-ink-2">replay · cache outage test</span>
              <span className="ml-auto flex shrink-0 items-center gap-1.5 font-mono text-[10px] text-ink-3">
                <span className="size-1.5 rounded-full bg-[var(--status-critical)] pulse-soft" /> rec
              </span>
              <span className="microlabel shrink-0">illustration</span>
            </div>
            <div className="schematic-grid px-4 pb-3 pt-5">
              <HeroRecorder />
            </div>
          </figure>
        </div>
      </section>

      <main className="mx-auto max-w-7xl space-y-14 px-4 py-12">
        {error && (
          <div role="alert" className="rounded-lg border border-line bg-critical-soft p-4 text-sm text-ink">
            <p className="font-medium">Cannot reach the ArchSim API</p>
            <p className="mt-1 text-ink-2">{error}</p>
          </div>
        )}

        <section aria-labelledby="ws-heading">
          <div className="mb-6">
            <p className="microlabel">
              <span className="mr-1 opacity-60" aria-hidden>
                {"//"}
              </span>
              how it works
            </p>
            <h2 id="ws-heading" className="mt-1.5 font-display text-[2.4rem] leading-[1.05] tracking-[-0.035em] text-ink">
              Three workspaces, <span className="text-ink-3">one canvas</span>
            </h2>
          </div>
          <ol className="grid gap-px overflow-hidden rounded-xl border border-line bg-line shadow-panel lg:grid-cols-3">
            {WORKSPACES.map((w) => (
              <li key={w.key} className="surface-sheen relative bg-surface p-6">
                <div className="mb-3 flex items-baseline gap-3">
                  <span className="font-mono text-xs text-accent">{w.n}</span>
                  <span className="font-display text-[28px] leading-none text-ink">{w.title}</span>
                </div>
                <p className="text-[14px] leading-relaxed text-ink-2">{w.text}</p>
              </li>
            ))}
          </ol>
        </section>

        {catalog && <TechWall catalog={catalog} />}

        <section aria-labelledby="examples-heading">
          <div className="mb-4 flex items-end justify-between">
            <div>
              <p className="microlabel">
                <span className="mr-1 opacity-60" aria-hidden>
                  {"//"}
                </span>
                templates
              </p>
              <h2 id="examples-heading" className="mt-1.5 font-display text-[2.4rem] leading-[1.05] tracking-[-0.035em] text-ink">
                Start from an <span className="text-ink-3">example</span>
              </h2>
              <p className="mt-2 text-sm text-ink-2">Each one comes with scenarios ready to compare.</p>
            </div>
          </div>
          <div className="grid gap-4 md:grid-cols-3">
            {examples.map((ex) => {
              const doc = exampleDocs[ex.id];
              const meta = [
                doc ? `${doc.architecture.nodes.length} nodes` : null,
                doc ? `${doc.architecture.edges.length} links` : null,
                `${ex.scenarioCount} scenarios`,
              ]
                .filter(Boolean)
                .join(" · ");
              return (
                <button
                  key={ex.id}
                  onClick={() => void create({ exampleId: ex.id }, ex.id)}
                  disabled={busy !== null}
                  className="group flex flex-col overflow-hidden rounded-xl border border-line bg-surface text-left shadow-panel transition-[border-color,transform,box-shadow] duration-200 hover:-translate-y-0.5 hover:border-line-strong hover:shadow-float disabled:opacity-60"
                >
                  <div className="schematic-grid h-40 border-b border-line p-3">
                    {doc ? <MiniDiagram doc={doc} /> : <div className="h-full animate-pulse rounded-md bg-surface-2/60" />}
                  </div>
                  <div className="flex flex-1 flex-col gap-2 p-4">
                    <div className="flex items-center gap-2">
                      <span className="text-[15px] font-semibold text-ink">{ex.name}</span>
                      {busy === ex.id ? (
                        <LoaderCircle className="ml-auto size-4 shrink-0 animate-spin text-ink-3" />
                      ) : (
                        <ArrowRight className="ml-auto size-4 shrink-0 text-ink-3 transition-transform group-hover:translate-x-0.5 group-hover:text-accent" />
                      )}
                    </div>
                    <p className="line-clamp-2 text-[13px] leading-relaxed text-ink-2" title={ex.description ?? undefined}>{ex.description}</p>
                    <p className="mt-auto pt-1 font-mono text-[11px] text-ink-3">{meta}</p>
                  </div>
                </button>
              );
            })}
            {examples.length === 0 &&
              !error &&
              [0, 1, 2].map((i) => <div key={i} className="h-72 animate-pulse rounded-lg bg-surface-2" />)}
          </div>
        </section>

        <section aria-labelledby="projects-heading">
          <div className="mb-4 flex items-end justify-between gap-3">
            <div>
              <p className="microlabel">
                <span className="mr-1 opacity-60" aria-hidden>
                  {"//"}
                </span>
                your projects{projects && projects.length > 0 ? ` · ${projects.length}` : ""}
              </p>
              <h2 id="projects-heading" className="mt-1.5 font-display text-[2.4rem] leading-[1.05] tracking-[-0.035em] text-ink">
                Pick up where you <span className="text-ink-3">left off</span>
              </h2>
              <p className="mt-2 text-sm text-ink-2">Saved locally in the ArchSim database.</p>
            </div>
            <Button size="sm" onClick={() => setNewOpen(true)}>
              <Plus /> New project
            </Button>
          </div>
          {projects === null ? (
            <div className="space-y-2">
              {[0, 1, 2].map((i) => (
                <div key={i} className="h-16 animate-pulse rounded-lg bg-surface-2" />
              ))}
            </div>
          ) : projects.length === 0 ? (
            <div className="rounded-lg border border-dashed border-line-strong bg-surface">
              <Empty icon={<Boxes />} title="No projects yet">
                Open an example above to explore a working architecture, or create a blank project and add components from the rail.
              </Empty>
            </div>
          ) : (
            <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface shadow-panel">
              {projects.map((p) => (
                <li key={p.id} className="flex items-center gap-3 px-4 py-3 transition-colors hover:bg-surface-2">
                  <span className="flex size-9 shrink-0 items-center justify-center rounded-md border border-line bg-surface-2 text-ink-3">
                    <FileJson className="size-4" />
                  </span>
                  <Link href={`/projects/${p.id}`} className="min-w-0 flex-1">
                    <p className="truncate text-sm font-semibold text-ink">{p.name}</p>
                    <p className="truncate font-mono text-[11px] text-ink-3">
                      {p.nodeCount} components · {p.scenarioCount} scenarios · rev {p.revision} · {fmtRelativeTime(p.updatedAt)}
                    </p>
                  </Link>
                  <Button variant="ghost" size="sm" asChild className="hidden sm:inline-flex">
                    <Link href={`/projects/${p.id}`}>
                      Open <ArrowRight />
                    </Link>
                  </Button>
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

      <footer className="border-t border-line">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-3 px-4 py-6 font-mono text-[11px] text-ink-3">
          <span>archsim · AGPL-3.0</span>
          <span>·</span>
          <span>estimates from your assumptions; calibrate before trusting absolute numbers</span>
        </div>
      </footer>

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

/** Every technology profile the engine ships, grouped by family (read from the live catalog). */
function TechWall({ catalog }: { catalog: Catalog }) {
  const items = catalog.components.flatMap((c) =>
    (c.presets ?? []).map((p) => ({ ...p, kind: kindOf(c.type, c.category), type: c.displayName })),
  );
  if (items.length === 0) return null;
  const groups = FAMILIES.map((f) => ({ f, items: items.filter((i) => familyOf(i.kind).key === f.key) })).filter((g) => g.items.length);
  return (
    <section aria-labelledby="tech-heading">
      <div className="mb-4 flex items-end justify-between gap-4">
        <div>
          <p className="microlabel">
            <span className="mr-1 opacity-60" aria-hidden>
              {"//"}
            </span>
            {items.length} technology profiles
          </p>
          <h2 id="tech-heading" className="mt-1.5 font-display text-[2.4rem] leading-[1.05] tracking-[-0.035em] text-ink">
            Model the stack you <span className="text-ink-3">actually run</span>
          </h2>
        </div>
      </div>
      <div className="grid gap-px overflow-hidden rounded-xl border border-line bg-line shadow-panel sm:grid-cols-2 lg:grid-cols-3">
        {groups.map(({ f, items: list }) => (
          <div key={f.key} className="surface-sheen bg-surface p-5">
            <p className="mb-3 text-[13px] font-semibold text-ink">{f.label}</p>
            <ul className="flex flex-wrap gap-1.5">
              {list.map((p) => (
                <li
                  key={`${p.type}-${p.id}`}
                  className="flex items-center gap-1.5 rounded-md border border-line bg-surface-2/50 px-2 py-1"
                  title={p.description}
                >
                  <TechIcon technology={p.technology} kind={p.kind} size={14} />
                  <span className="text-[12.5px] text-ink">{p.name}</span>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </section>
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

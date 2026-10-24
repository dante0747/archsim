"use client";

import {
  ArrowRight,
  Boxes,
  Cable,
  Copy,
  FileJson,
  GitCompareArrows,
  LoaderCircle,
  MoreHorizontal,
  MousePointerClick,
  Play,
  Plus,
  Trash2,
  Upload,
} from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { Logo } from "@/components/brand";
import { type DiagramEdge, type DiagramNode, MiniDiagram, SchematicDiagram } from "@/components/projects/MiniDiagram";
import { Button } from "@/components/ui/button";
import { Input, Label } from "@/components/ui/input";
import { Empty } from "@/components/ui/misc";
import { Dialog, DialogContent, Menu, MenuContent, MenuItem, MenuSeparator, MenuTrigger } from "@/components/ui/overlays";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { ApiError, api } from "@/lib/api";
import { fmtRelativeTime } from "@/lib/format";
import type { ArchitectureDocument, ExampleSummary, ProjectSummary } from "@/lib/types";
import { cn, readJsonFile } from "@/lib/utils";

const HERO_NODES: DiagramNode[] = [
  { id: "clients", kind: "traffic", label: "Clients", x: 0, y: 96 },
  { id: "lb", kind: "network", label: "Balancer", x: 148, y: 96 },
  {
    id: "api",
    kind: "compute",
    label: "API",
    x: 296,
    y: 96,
    layers: 2,
    note: { text: "91% busy", color: "var(--status-serious)" },
  },
  { id: "cache", kind: "cache", label: "Redis", x: 452, y: 8 },
  { id: "db", kind: "database", label: "Postgres", x: 600, y: 118 },
];

const HERO_EDGES: DiagramEdge[] = [
  { id: "e1", source: "clients", target: "lb", types: ["read", "write"] },
  { id: "e2", source: "lb", target: "api", types: ["read", "write"] },
  { id: "e3", source: "api", target: "cache", types: ["read"] },
  { id: "e4", source: "api", target: "db", types: ["write"] },
  { id: "e5", source: "cache", target: "db", types: ["read"], miss: true },
];

/** Decorative illustration of the starter design (not live data). */
function HeroDiagram() {
  return (
    <SchematicDiagram
      nodes={HERO_NODES}
      edges={HERO_EDGES}
      nodeW={120}
      nodeH={56}
      unit={0.6}
      font={14}
      text="full"
      strokeWidth={1.4}
      className="h-auto w-full"
    />
  );
}

const HOW = [
  {
    icon: MousePointerClick,
    title: "Draw",
    text: "Place clients, balancers, services, caches and databases on a canvas, then wire them up.",
  },
  {
    icon: Cable,
    title: "Describe",
    text: "Set replicas, queues, service times, hit ratios, and how much traffic arrives.",
  },
  {
    icon: Play,
    title: "Simulate",
    text: "A discrete-event engine moves every request through queues and workers.",
  },
  {
    icon: GitCompareArrows,
    title: "Compare",
    text: "See bottlenecks and latency, then test alternatives side by side.",
  },
];

export function ProjectsHome() {
  const router = useRouter();
  const [projects, setProjects] = useState<ProjectSummary[] | null>(null);
  const [examples, setExamples] = useState<ExampleSummary[]>([]);
  const [exampleDocs, setExampleDocs] = useState<Record<string, ArchitectureDocument>>({});
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
        <div className="mx-auto flex h-12 max-w-6xl items-center gap-3 px-4">
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
        <div className="mx-auto grid max-w-6xl items-center gap-10 px-4 py-14 lg:grid-cols-[1fr_1.05fr]">
          <div>
            <p className="microlabel mb-5">
              <span className="mr-1 opacity-60" aria-hidden>
                {"//"}
              </span>
              open-source · discrete-event · local-first
            </p>
            <h1 className="text-4xl font-semibold leading-[1.12] tracking-tight text-ink sm:text-[2.85rem]">
              Draw your system.
              <br />
              Push traffic through it.
              <br />
              See where it breaks.
              <span className="caret ml-1.5 inline-block h-[0.82em] w-[0.42em] translate-y-[0.06em] bg-accent" aria-hidden />
            </h1>
            <p className="mt-5 max-w-xl text-[15px] leading-relaxed text-ink-2">
              ArchSim simulates every request through your load balancers, services, caches and databases, then explains the bottleneck.
              Results are estimates driven by the assumptions you enter, not production guarantees.
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
            <p className="mt-5 font-mono text-[11px] text-ink-3">runs locally · no account · no telemetry</p>
          </div>
          <figure className="overflow-hidden rounded-lg border border-line-strong bg-surface shadow-float" aria-hidden>
            <div className="flex items-center gap-2 border-b border-line bg-surface-2 px-3 py-2">
              <span className="flex gap-1.5">
                {[0, 1, 2].map((i) => (
                  <span key={i} className="size-2.5 rounded-full border border-line-strong" />
                ))}
              </span>
              <span className="ml-2 truncate font-mono text-[11px] text-ink-2">cache-aside-starter.archsim.json</span>
              <span className="microlabel ml-auto shrink-0">illustration</span>
            </div>
            <div className="schematic-grid px-4 py-6">
              <HeroDiagram />
            </div>
            <figcaption className="flex items-center gap-3 border-t border-line bg-surface-2 px-3 py-1.5 font-mono text-[10.5px] text-ink-3">
              <span>400 req/s · 90% reads · 60 s</span>
              <span className="ml-auto">● read ■ write</span>
              <span>seed 42</span>
            </figcaption>
          </figure>
        </div>
      </section>

      <main className="mx-auto max-w-6xl space-y-14 px-4 py-12">
        {error && (
          <div role="alert" className="rounded-lg border border-line bg-critical-soft p-4 text-sm text-ink">
            <p className="font-medium">Cannot reach the ArchSim API</p>
            <p className="mt-1 text-ink-2">{error}</p>
          </div>
        )}

        <section aria-labelledby="how-heading">
          <h2 id="how-heading" className="microlabel mb-4">
            <span className="mr-1 opacity-60" aria-hidden>
              {"//"}
            </span>
            How it works
          </h2>
          <ol className="grid gap-px overflow-hidden rounded-lg border border-line bg-line sm:grid-cols-2 lg:grid-cols-4">
            {HOW.map((h, i) => (
              <li key={h.title} className="bg-surface p-4">
                <div className="mb-3 flex items-center justify-between">
                  <span className="font-mono text-xs text-ink-3">{String(i + 1).padStart(2, "0")}</span>
                  <h.icon className="size-4 text-ink-3" aria-hidden />
                </div>
                <p className="font-mono text-[13px] font-semibold text-ink">{h.title}</p>
                <p className="mt-1 text-[13px] leading-relaxed text-ink-2">{h.text}</p>
              </li>
            ))}
          </ol>
        </section>

        <section aria-labelledby="examples-heading">
          <div className="mb-4 flex items-end justify-between">
            <div>
              <h2 id="examples-heading" className="microlabel">
                <span className="mr-1 opacity-60" aria-hidden>
                  {"//"}
                </span>
                Start from an example
              </h2>
              <p className="mt-1 text-sm text-ink-2">Each one comes with scenarios ready to compare.</p>
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
                  className="group flex flex-col overflow-hidden rounded-lg border border-line bg-surface text-left transition-colors hover:border-line-strong disabled:opacity-60"
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
                    <p className="line-clamp-3 text-[13px] leading-relaxed text-ink-2">{ex.description}</p>
                    <div className="mt-auto space-y-0.5 pt-1 font-mono text-[11px] text-ink-3">
                      <p>{meta}</p>
                      {ex.tags.length > 0 && (
                        <p className="truncate">
                          {ex.tags
                            .slice(0, 3)
                            .map((t) => `#${t}`)
                            .join(" ")}
                        </p>
                      )}
                    </div>
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
              <h2 id="projects-heading" className="microlabel">
                <span className="mr-1 opacity-60" aria-hidden>
                  {"//"}
                </span>
                Your projects
                {projects && projects.length > 0 ? ` · ${projects.length}` : ""}
              </h2>
              <p className="mt-1 text-sm text-ink-2">Saved locally in the ArchSim database.</p>
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
            <ul className="divide-y divide-line overflow-hidden rounded-lg border border-line bg-surface">
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
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-3 px-4 py-6 font-mono text-[11px] text-ink-3">
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

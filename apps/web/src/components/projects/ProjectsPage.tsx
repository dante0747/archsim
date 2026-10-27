"use client";

/** Your saved projects: search, open, duplicate, export and delete. Lives at /projects. */
import { ArrowLeft, ArrowRight, Boxes, Copy, FileJson, LoaderCircle, MoreHorizontal, Plus, Search, Trash2, Upload } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { Logo } from "@/components/brand";
import { NewProjectDialog } from "@/components/projects/ProjectsHome";
import { useProjectActions } from "@/components/projects/actions";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Empty } from "@/components/ui/misc";
import { Menu, MenuContent, MenuItem, MenuSeparator, MenuTrigger } from "@/components/ui/overlays";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { api } from "@/lib/api";
import { fmtRelativeTime } from "@/lib/format";
import type { ExampleSummary, ProjectSummary } from "@/lib/types";
import { MobileNav } from "@/components/ui/MobileNav";
import { UserMenu } from "@/components/auth/UserMenu";
import { signInPage } from "@/lib/session";
import { authSettled, useAuthState } from "@/store/auth";

export function ProjectsPage() {
  const router = useRouter();
  const [projects, setProjects] = useState<ProjectSummary[] | null>(null);
  const [examples, setExamples] = useState<ExampleSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [newOpen, setNewOpen] = useState(false);
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
  const { busy, create, importFile, remove, duplicate } = useProjectActions(() => void refresh());
  const { status: authStatus, workspaceId, workspace } = useAuthState();

  useEffect(() => {
    let alive = true;
    api
      .examples()
      .then((e) => alive && setExamples(e))
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, []);

  // The current workspace's projects; signed out on a server with sign-in: to the sign-in page.
  useEffect(() => {
    if (authStatus === "signed-out") {
      window.location.replace(signInPage("/projects"));
      return;
    }
    if (!authSettled(authStatus)) return;
    let alive = true;
    api
      .listProjects()
      .then((p) => alive && setProjects(p))
      .catch((e: unknown) => {
        if (!alive) return;
        setError(e instanceof Error ? e.message : String(e));
        setProjects([]);
      });
    return () => {
      alive = false;
    };
  }, [authStatus, workspaceId]);
  const readOnly = workspace?.role === "viewer";

  const q = query.trim().toLowerCase();
  const list = (projects ?? []).filter((p) => !q || p.name.toLowerCase().includes(q)).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));

  return (
    <div className="min-h-full bg-page">
      <header className="sticky top-0 z-30 border-b border-line bg-page">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-4 px-4 sm:px-6">
          <Logo />
          <Button variant="ghost" size="sm" asChild>
            <Link href="/">
              <ArrowLeft /> Home
            </Link>
          </Button>
          <Button variant="ghost" size="sm" asChild className="hidden sm:inline-flex">
            <Link href="/templates">Templates</Link>
          </Button>
          <div className="ml-auto flex items-center gap-1.5">
            <ThemeToggle />
            <UserMenu />
            <MobileNav className="sm:hidden" items={[{ href: "/templates", label: "Templates" }, { href: "/api/v1/docs", label: "API docs", external: true }]} />
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-4 py-12 sm:px-6">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="font-display text-[36px] font-semibold leading-[1.05] tracking-[-0.04em] text-ink sm:text-[44px]">Your projects</h1>
            <p className="mt-2 text-[15px] text-ink-2">
              {workspace && workspace.id !== "default"
                ? `${projects ? `${projects.length} ` : ""}in ${workspace.name}${readOnly ? ". You can open them; editors change them." : "."}`
                : projects
                  ? `${projects.length} saved in your ArchSim database.`
                  : "Saved in your ArchSim database."}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
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
            <Button onClick={() => fileRef.current?.click()} disabled={busy !== null || readOnly}>
              {busy === "import" ? <LoaderCircle className="animate-spin" /> : <Upload />} Import JSON
            </Button>
            <Button variant="primary" onClick={() => setNewOpen(true)} disabled={readOnly}>
              <Plus /> New project
            </Button>
          </div>
        </div>

        {error && (
          <div role="alert" className="mt-8 rounded-xl bg-critical-soft p-4 text-sm text-ink">
            <p className="font-medium">Cannot reach the ArchSim API</p>
            <p className="mt-1 text-ink-2">{error}</p>
          </div>
        )}

        {projects && projects.length > 6 && (
          <div className="relative mt-8 max-w-sm">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-ink-3" aria-hidden />
            <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search projects" aria-label="Search projects" className="pl-9" />
          </div>
        )}

        <div className="mt-6">
          {projects === null ? (
            <div className="space-y-2">
              {[0, 1, 2].map((i) => (
                <div key={i} className="h-16 animate-pulse rounded-xl bg-surface-2" />
              ))}
            </div>
          ) : projects.length === 0 ? (
            <div className="rounded-2xl bg-surface-2/60">
              <Empty icon={<Boxes />} title="No projects yet">
                Open a template from the home page to explore a working architecture, or create a blank project and drag in parts.
              </Empty>
            </div>
          ) : list.length === 0 ? (
            <p className="py-10 text-center text-sm text-ink-3">No project matches “{query}”.</p>
          ) : (
            <ul className="grid gap-3 md:grid-cols-2">
              {list.map((p) => (
                <li key={p.id} className="group flex items-center gap-3 rounded-2xl border border-line bg-surface p-4 transition-colors hover:border-line-strong">
                  <span className="fam-tile flex size-10 shrink-0 items-center justify-center rounded-[10px] bg-[color:var(--fam-compute)]">
                    <FileJson className="size-5" strokeWidth={2} />
                  </span>
                  <Link href={`/projects/${p.id}`} className="min-w-0 flex-1">
                    <p className="truncate text-[15px] font-semibold text-ink">{p.name}</p>
                    <p className="truncate text-[13px] text-ink-3">
                      {p.nodeCount} parts · {p.scenarioCount} scenarios · edited {fmtRelativeTime(p.updatedAt)}
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
                      <MenuItem onSelect={() => void duplicate(p)} disabled={readOnly}>
                        <Copy /> Duplicate
                      </MenuItem>
                      <MenuItem asChild>
                        <a href={`/api/v1/projects/${p.id}/export`} download>
                          <FileJson /> Export JSON
                        </a>
                      </MenuItem>
                      <MenuSeparator />
                      <MenuItem className="text-danger-ink" onSelect={() => void remove(p)} disabled={readOnly}>
                        <Trash2 /> Delete
                      </MenuItem>
                    </MenuContent>
                  </Menu>
                </li>
              ))}
            </ul>
          )}
        </div>
      </main>

      <NewProjectDialog open={newOpen} onOpenChange={setNewOpen} examples={examples} busy={busy} onCreate={(body, key) => void create(body, key)} />
    </div>
  );
}

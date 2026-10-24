"use client";

/**
 * Technology library: a rail of families (Clients, Edge, Compute, Data, Cache, Queues, External) and
 * a searchable flyout of technology presets (PostgreSQL, MongoDB, Kafka, Kubernetes service...).
 * Drag onto the canvas or click to add.
 */
import { useReactFlow } from "@xyflow/react";
import { Library, Search, X } from "lucide-react";
import { useCallback, useMemo, useState } from "react";
import { NODE_W } from "@/components/editor/ArchNodeView";
import {
  DND_MIME,
  dndPayload,
  familyOfItem,
  findPreset,
  type LibraryItem,
  libraryItems,
  matchesItem,
  parseDndPayload,
} from "@/components/build/presets";
import { SHAPE_OF, ShapeGlyph } from "@/components/shapes";
import { Kbd, Tip } from "@/components/ui/misc";
import { FAMILIES, type Family, kindOf } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";

export { DND_MIME, parseDndPayload };

/** Add a component (optionally from a technology preset) in a free spot of the visible canvas. */
export function useAddComponent() {
  const rf = useReactFlow();
  const addNode = useEditor((s) => s.addNode);
  const addPresetNode = useEditor((s) => s.addPresetNode);
  return useCallback(
    (type: string, presetId?: string | null) => {
      const el = document.querySelector(".react-flow");
      const rect = el?.getBoundingClientRect();
      const center = rect
        ? { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }
        : { x: window.innerWidth / 2, y: window.innerHeight / 2 };
      const p = rf.screenToFlowPosition(center);
      const taken = useEditor.getState().nodes.map((n) => n.position);
      const isFree = (x: number, y: number) => taken.every((q) => Math.abs(q.x - x) > 250 || Math.abs(q.y - y) > 150);
      let x = Math.round(p.x - NODE_W / 2);
      let y = Math.round(p.y - 60);
      for (let i = 0; i < 40 && !isFree(x, y); i++) {
        x += 320;
        if (i % 4 === 3) {
          x = Math.round(p.x - NODE_W / 2);
          y += 190;
        }
      }
      const preset = findPreset(useEditor.getState().catalog, type, presetId);
      if (preset) addPresetNode(type, { x, y }, preset);
      else addNode(type, { x, y });
      window.setTimeout(() => void rf.fitView({ padding: 0.2, maxZoom: 1, duration: 250 }), 40);
    },
    [rf, addNode, addPresetNode],
  );
}

function dragProps(type: string, presetId?: string | null) {
  return {
    draggable: true,
    onDragStart: (e: React.DragEvent) => {
      e.dataTransfer.setData(DND_MIME, dndPayload(type, presetId));
      e.dataTransfer.effectAllowed = "move";
    },
  };
}

/** Representative component type of a family (for dragging straight from the rail). */
function familyType(f: Family, items: LibraryItem[]): string | null {
  return items.find((i) => !i.preset && f.kinds.includes(kindOf(i.type, i.comp.category)))?.type ?? null;
}

/** Slim rail of technology families; opens the library at a family, drag adds the generic component. */
export function ComponentRail() {
  const catalog = useEditor((s) => s.catalog);
  const libraryOpen = useUi((s) => s.libraryOpen);
  const setLibraryOpen = useUi((s) => s.setLibraryOpen);
  const [family, setFamily] = useState<string | null>(null);
  const items = useMemo(() => libraryItems(catalog), [catalog]);
  const families = FAMILIES.filter((f) => items.some((i) => familyOfItem(i).key === f.key));
  const counts = (f: Family) => items.filter((i) => familyOfItem(i).key === f.key && i.preset).length;

  return (
    <aside className="relative z-20 flex h-full w-[72px] shrink-0 flex-col items-center gap-0.5 border-r border-line bg-surface py-2.5" aria-label="Technology library">
      <span className="microlabel mb-1.5 !text-[9px]">add</span>
      {families.map((f) => {
        const type = familyType(f, items);
        const active = libraryOpen && family === f.key;
        const n = counts(f);
        return (
          <Tip
            key={f.key}
            side="right"
            content={
              <span className="block max-w-56">
                <span className="block font-semibold">{f.label}</span>
                <span className="block text-ink-2">{f.blurb}</span>
                <span className="mt-1 block text-ink-3">
                  Click for {n ? `${n} technologies` : "options"}; drag to add a generic one.
                </span>
              </span>
            }
          >
            <button
              type="button"
              {...(type ? dragProps(type) : {})}
              onClick={() => {
                if (active) setLibraryOpen(false);
                else {
                  setFamily(f.key);
                  setLibraryOpen(true);
                }
              }}
              aria-expanded={active}
              className={cn(
                "group relative flex w-[62px] cursor-pointer flex-col items-center gap-1 rounded-md py-2 transition-colors hover:bg-surface-2",
                active && "bg-accent-soft",
              )}
              aria-label={`${f.label} technologies`}
            >
              <ShapeGlyph
                shape={SHAPE_OF[f.kinds[0]]}
                w={34}
                h={22}
                className={cn("transition-colors group-hover:text-accent", active ? "text-accent" : "text-ink-2")}
              />
              <span className={cn("w-full truncate text-center font-mono text-[10px] group-hover:text-ink", active ? "text-ink" : "text-ink-2")}>
                {f.short}
              </span>
              {n > 0 && (
                <span className="absolute right-1 top-1 font-mono text-[8.5px] leading-none text-ink-3" aria-hidden>
                  {n}
                </span>
              )}
            </button>
          </Tip>
        );
      })}
      <div className="mt-auto">
        <Tip side="right" content="Browse and search every technology">
          <button
            type="button"
            onClick={() => {
              setFamily(null);
              setLibraryOpen(!libraryOpen || family !== null);
            }}
            aria-expanded={libraryOpen && family === null}
            className="flex w-[62px] flex-col items-center gap-1 rounded-md py-1.5 text-ink-3 hover:bg-surface-2 hover:text-ink"
          >
            <Library className="size-4" />
            <span className="font-mono text-[10px]">library</span>
          </button>
        </Tip>
      </div>
      {libraryOpen && <ComponentLibrary family={family} onFamily={setFamily} onClose={() => setLibraryOpen(false)} />}
    </aside>
  );
}

/** Searchable technology library (flyout next to the rail). */
export function ComponentLibrary({
  family,
  onFamily,
  onClose,
}: {
  family: string | null;
  onFamily: (f: string | null) => void;
  onClose: () => void;
}) {
  const catalog = useEditor((s) => s.catalog);
  const add = useAddComponent();
  const [q, setQ] = useState("");
  const items = useMemo(() => libraryItems(catalog), [catalog]);
  const visible = useMemo(
    () => items.filter((i) => (!family || familyOfItem(i).key === family) && matchesItem(i, q)),
    [items, family, q],
  );
  const sections = useMemo(() => {
    const out: { family: Family; groups: { name: string; items: LibraryItem[] }[] }[] = [];
    for (const f of FAMILIES) {
      const inFamily = visible.filter((i) => familyOfItem(i).key === f.key);
      if (!inFamily.length) continue;
      const groups: { name: string; items: LibraryItem[] }[] = [];
      for (const it of inFamily) {
        const g = groups.find((x) => x.name === it.group);
        if (g) g.items.push(it);
        else groups.push({ name: it.group, items: [it] });
      }
      // generic entries last
      groups.sort((a, b) => Number(a.name === "Generic") - Number(b.name === "Generic"));
      out.push({ family: f, groups });
    }
    return out;
  }, [visible]);
  const familiesPresent = FAMILIES.filter((f) => items.some((i) => familyOfItem(i).key === f.key));
  const pick = (it: LibraryItem) => {
    add(it.type, it.preset?.id ?? null);
    onClose();
  };

  return (
    <div
      className="absolute left-full top-2 ml-2 flex max-h-[calc(100%-16px)] w-[360px] flex-col rounded-lg border border-line-strong bg-surface shadow-float"
      role="dialog"
      aria-label="Technology library"
    >
      <div className="border-b border-line p-3">
        <div className="flex items-center gap-2">
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
            <input
              autoFocus
              value={q}
              onChange={(e) => setQ(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && visible[0]) {
                  e.preventDefault();
                  pick(visible[0]);
                } else if (e.key === "Escape") onClose();
              }}
              placeholder="postgres, nosql, kafka, k8s, serverless…"
              aria-label="Search technologies"
              className="h-8 w-full rounded-md border border-line bg-surface-2 pl-8 pr-2 font-mono text-[12px] text-ink placeholder:text-ink-3 focus-visible:border-accent focus-visible:outline-none"
            />
          </div>
          <button type="button" onClick={onClose} className="rounded-md p-1.5 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Close library">
            <X className="size-4" />
          </button>
        </div>
        <div className="mt-2 flex flex-wrap gap-1" role="tablist" aria-label="Families">
          {[{ key: null as string | null, short: "all" }, ...familiesPresent.map((f) => ({ key: f.key as string | null, short: f.short.toLowerCase() }))].map((f) => (
            <button
              key={f.key ?? "all"}
              type="button"
              role="tab"
              aria-selected={family === f.key}
              onClick={() => onFamily(f.key)}
              className={cn(
                "rounded-sm border px-1.5 py-0.5 font-mono text-[10.5px] transition-colors",
                family === f.key ? "border-accent/50 bg-accent-soft text-ink" : "border-line text-ink-3 hover:border-line-strong hover:text-ink",
              )}
            >
              {f.short}
            </button>
          ))}
        </div>
      </div>
      <div className="flex-1 space-y-4 overflow-y-auto p-3">
        {sections.map(({ family: f, groups }) => (
          <section key={f.key} aria-label={f.label}>
            <h3 className="microlabel">
              <span className="mr-1 opacity-60" aria-hidden>
                {"//"}
              </span>
              {f.label}
            </h3>
            <p className="mb-2 mt-0.5 text-[11px] text-ink-3">{f.blurb}</p>
            {groups.map((g) => (
              <div key={g.name} className="mb-2.5">
                {(groups.length > 1 || g.name !== "Generic") && (
                  <p className="mb-1 font-mono text-[10px] lowercase text-ink-3">{g.name}</p>
                )}
                <ul className="space-y-1">
                  {g.items.map((it) => (
                    <li key={it.key}>
                      <button
                        type="button"
                        {...dragProps(it.type, it.preset?.id)}
                        onClick={() => pick(it)}
                        title={it.description}
                        className="group flex w-full cursor-grab items-start gap-2.5 rounded-md border border-line p-2 text-left transition-colors hover:border-line-strong hover:bg-surface-2 active:cursor-grabbing"
                      >
                        <ShapeGlyph
                          shape={SHAPE_OF[kindOf(it.type, it.comp.category)]}
                          w={30}
                          h={20}
                          className="mt-0.5 shrink-0 text-ink-2 group-hover:text-accent"
                        />
                        <span className="min-w-0 flex-1">
                          <span className="flex items-center gap-2">
                            <span className="truncate font-mono text-[12px] font-medium text-ink">{it.name}</span>
                            <span className="ml-auto shrink-0 rounded-sm border border-line px-1 font-mono text-[9.5px] text-ink-3">
                              {it.technology}
                            </span>
                          </span>
                          <span className="mt-0.5 line-clamp-2 block text-[11px] leading-4 text-ink-2">{it.description}</span>
                          {it.tags.length > 0 && (
                            <span className="mt-0.5 block truncate font-mono text-[9.5px] text-ink-3">
                              {it.tags.map((t) => `#${t}`).join(" ")}
                            </span>
                          )}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </section>
        ))}
        {sections.length === 0 && <p className="px-1 py-6 text-center font-mono text-xs text-ink-3">no technology matches “{q}”</p>}
      </div>
      <p className="border-t border-line p-3 text-[11px] leading-4 text-ink-3">
        Drag onto the canvas or click to add. Preset numbers are editable assumptions, not vendor benchmarks.{" "}
        <Kbd>Enter</Kbd> adds the first match.
      </p>
    </div>
  );
}

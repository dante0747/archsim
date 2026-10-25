"use client";

/**
 * Parts library (Design workspace): a searchable panel of technologies with their logos, grouped
 * by family (Clients, Edge, Compute, Data, Cache, Queues, External). Drag onto the canvas or click.
 */
import { useReactFlow } from "@xyflow/react";
import { PanelLeftClose, PanelLeftOpen, Search, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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
import { TechIcon } from "@/components/TechIcon";
import { Tip } from "@/components/ui/misc";
import { FAMILIES, KIND_COLOR, kindOf } from "@/lib/theme";
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

const FIND_PARTS = "archsim:find-parts";

/** Open the Design workspace with the parts search focused (command palette, shortcuts). */
export function findParts() {
  useUi.getState().setMode("build");
  window.setTimeout(() => window.dispatchEvent(new Event(FIND_PARTS)), 60);
}

/** Short tile label: "Generic database" reads as "Any database"; client parts use their own names. */
function tileName(it: LibraryItem): string {
  if (it.preset) return it.preset.name;
  if (it.comp.isTrafficSource) return it.type === "traffic_source" ? "Clients" : it.comp.displayName;
  const base = it.comp.displayName.split(" / ")[0].replace(/ \(.*\)$/, "");
  return `Any ${base.toLowerCase()}`;
}

function Tile({ it, onPick }: { it: LibraryItem; onPick: (it: LibraryItem) => void }) {
  return (
    <button
      type="button"
      {...dragProps(it.type, it.preset?.id)}
      onClick={() => onPick(it)}
      title={`${tileName(it)}: ${it.description}`}
      className="group flex h-[72px] w-full cursor-grab flex-col items-center justify-center gap-1.5 rounded-xl border border-line bg-surface-2/40 px-1.5 text-center transition-all hover:-translate-y-px hover:border-accent/60 hover:bg-surface-2 hover:shadow-[0_6px_18px_-8px_var(--accent)] active:cursor-grabbing"
    >
      <TechIcon technology={it.preset || it.comp.isTrafficSource ? it.technology : null} kind={kindOf(it.type, it.comp.category)} size={22} />
      <span className="line-clamp-2 text-[11.5px] leading-[1.2] text-ink-2 group-hover:text-ink">{tileName(it)}</span>
    </button>
  );
}

function readOpen(): boolean {
  try {
    return localStorage.getItem("archsim-parts") !== "0";
  } catch {
    return true;
  }
}

/** Always-visible parts library on the left of the Design workspace (collapsible). */
export function PartsPanel() {
  const catalog = useEditor((s) => s.catalog);
  const add = useAddComponent();
  const [open, setOpenState] = useState(readOpen);
  const [q, setQ] = useState("");
  const listRef = useRef<HTMLDivElement>(null);
  const setOpen = (v: boolean) => {
    setOpenState(v);
    try {
      localStorage.setItem("archsim-parts", v ? "1" : "0");
    } catch {
      /* storage unavailable */
    }
  };
  const items = useMemo(() => libraryItems(catalog), [catalog]);
  const visible = useMemo(() => items.filter((i) => matchesItem(i, q)), [items, q]);
  const sections = useMemo(
    () =>
      FAMILIES.map((f) => ({
        family: f,
        // Clients first, then technologies with logos, the generic part last.
        items: visible.filter((i) => familyOfItem(i).key === f.key).sort((a, b) => Number(!a.preset) - Number(!b.preset)),
      })).filter((s) => s.items.length),
    [visible],
  );
  const familiesPresent = FAMILIES.filter((f) => items.some((i) => familyOfItem(i).key === f.key));
  const pick = (it: LibraryItem) => add(it.type, it.preset?.id ?? null);

  useEffect(() => {
    const onFind = () => {
      setOpenState(true);
      window.setTimeout(() => document.getElementById("parts-search")?.focus(), 30);
    };
    window.addEventListener(FIND_PARTS, onFind);
    return () => window.removeEventListener(FIND_PARTS, onFind);
  }, []);

  if (!open) {
    return (
      <aside data-tour="rail" className="island flex h-full w-[52px] shrink-0 flex-col items-center gap-1 py-2" aria-label="Parts">
        <Tip side="right" content="Show parts">
          <button type="button" onClick={() => setOpen(true)} className="mb-1 rounded-md p-2 text-ink-2 hover:bg-surface-2 hover:text-ink" aria-label="Show parts">
            <PanelLeftOpen className="size-4" />
          </button>
        </Tip>
        {familiesPresent.map((f) => (
          <Tip key={f.key} side="right" content={f.label}>
            <button type="button" onClick={() => setOpen(true)} className="rounded-md p-2 hover:bg-surface-2" aria-label={f.label}>
              <TechIcon kind={f.kinds[0]} size={18} />
            </button>
          </Tip>
        ))}
      </aside>
    );
  }

  return (
    <aside data-tour="rail" className="island flex h-full w-[232px] shrink-0 flex-col" aria-label="Parts">
      <div className="flex items-center gap-1.5 border-b border-line p-2.5">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
          <input
            id="parts-search"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              listRef.current?.scrollTo({ top: 0 });
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" && visible[0]) {
                e.preventDefault();
                pick(visible[0]);
              } else if (e.key === "Escape") setQ("");
            }}
            placeholder="Search parts"
            aria-label="Search parts"
            className="h-8 w-full rounded-md border border-line bg-surface-2/70 pl-8 pr-7 text-[13px] text-ink placeholder:text-ink-3 focus-visible:border-accent focus-visible:outline-none"
          />
          {q && (
            <button type="button" onClick={() => setQ("")} className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded p-0.5 text-ink-3 hover:text-ink" aria-label="Clear search">
              <X className="size-3.5" />
            </button>
          )}
        </div>
        <Tip content="Hide parts">
          <button type="button" onClick={() => setOpen(false)} className="rounded-md p-1.5 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Hide parts">
            <PanelLeftClose className="size-4" />
          </button>
        </Tip>
      </div>
      <div ref={listRef} className="flex-1 overflow-y-auto px-2.5 pb-3">
        {sections.map(({ family: f, items: list }) => (
          <section key={f.key} aria-label={f.label}>
            <h3 className="sticky top-0 z-10 flex items-center gap-2 bg-surface pb-1.5 pt-3 text-[12px] font-semibold text-ink">
              <span className="size-2 rounded-full" style={{ background: KIND_COLOR[f.kinds[0]], boxShadow: `0 0 8px ${KIND_COLOR[f.kinds[0]]}` }} aria-hidden />
              {f.label}
            </h3>
            <ul className="grid grid-cols-2 gap-1.5">
              {list.map((it) => (
                <li key={it.key}>
                  <Tile it={it} onPick={pick} />
                </li>
              ))}
            </ul>
          </section>
        ))}
        {sections.length === 0 && <p className="py-8 text-center text-[13px] text-ink-3">Nothing matches “{q}”</p>}
      </div>
      <p className="border-t border-line px-3 py-2 text-[11.5px] text-ink-3">Drag onto the canvas, or click</p>
    </aside>
  );
}

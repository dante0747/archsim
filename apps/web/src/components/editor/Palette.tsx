"use client";

import { useReactFlow } from "@xyflow/react";
import { Library, Search, X } from "lucide-react";
import { useCallback, useMemo, useState } from "react";
import { NODE_W } from "@/components/editor/ArchNodeView";
import { SHAPE_OF, ShapeGlyph } from "@/components/shapes";
import { Input } from "@/components/ui/input";
import { Kbd, Tip } from "@/components/ui/misc";
import { KIND_LABEL, kindOf, shortName } from "@/lib/theme";
import type { ComponentType } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";

export const DND_MIME = "application/x-archsim-component";

/** Add a component next to the existing ones, in the visible part of the canvas. */
export function useAddComponent() {
  const rf = useReactFlow();
  const addNode = useEditor((s) => s.addNode);
  return useCallback(
    (type: string) => {
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
      addNode(type, { x, y });
      window.setTimeout(() => void rf.fitView({ padding: 0.2, maxZoom: 1, duration: 250 }), 40);
    },
    [rf, addNode],
  );
}

function dragProps(type: string) {
  return {
    draggable: true,
    onDragStart: (e: React.DragEvent) => {
      e.dataTransfer.setData(DND_MIME, type);
      e.dataTransfer.effectAllowed = "move";
    },
  };
}

/** Slim rail of component shapes: drag onto the canvas or click to add. */
export function ComponentRail() {
  const catalog = useEditor((s) => s.catalog);
  const add = useAddComponent();
  const libraryOpen = useUi((s) => s.libraryOpen);
  const setLibraryOpen = useUi((s) => s.setLibraryOpen);
  return (
    <aside className="relative z-20 flex h-full w-[72px] shrink-0 flex-col items-center gap-0.5 border-r border-line bg-surface py-2.5" aria-label="Components">
      <span className="microlabel mb-1.5 !text-[9px]">add</span>
      {(catalog?.components ?? []).map((c) => {
        const kind = kindOf(c.type, c.category);
        return (
          <Tip
            key={c.type}
            side="right"
            content={
              <span className="block max-w-56">
                <span className="block font-semibold">{c.displayName}</span>
                <span className="block text-ink-2">{c.description}</span>
                <span className="mt-1 block text-ink-3">Drag onto the canvas, or click to add.</span>
              </span>
            }
          >
            <button
              type="button"
              {...dragProps(c.type)}
              onClick={() => add(c.type)}
              className="group flex w-[62px] cursor-grab flex-col items-center gap-1 rounded-md py-2 transition-colors hover:bg-surface-2 active:cursor-grabbing"
              aria-label={`Add ${c.displayName}`}
            >
              <ShapeGlyph shape={SHAPE_OF[kind]} w={34} h={22} className="text-ink-2 transition-colors group-hover:text-accent" />
              <span className="w-full truncate text-center font-mono text-[10px] text-ink-2 group-hover:text-ink">
                {shortName(c.type, c.displayName)}
              </span>
            </button>
          </Tip>
        );
      })}
      <div className="mt-auto">
        <Tip side="right" content="Browse all components with descriptions">
          <button
            type="button"
            onClick={() => setLibraryOpen(!libraryOpen)}
            aria-expanded={libraryOpen}
            className="flex w-[62px] flex-col items-center gap-1 rounded-md py-1.5 text-ink-3 hover:bg-surface-2 hover:text-ink"
          >
            <Library className="size-4" />
            <span className="font-mono text-[10px]">library</span>
          </button>
        </Tip>
      </div>
      {libraryOpen && <ComponentLibrary onClose={() => setLibraryOpen(false)} />}
    </aside>
  );
}

/** Searchable component library with descriptions (flyout next to the rail). */
function ComponentLibrary({ onClose }: { onClose: () => void }) {
  const catalog = useEditor((s) => s.catalog);
  const add = useAddComponent();
  const [q, setQ] = useState("");
  const groups = useMemo(() => {
    const term = q.trim().toLowerCase();
    const items = (catalog?.components ?? []).filter(
      (c) => !term || `${c.displayName} ${c.description} ${c.type}`.toLowerCase().includes(term),
    );
    const byKind = new Map<string, ComponentType[]>();
    for (const c of items) {
      const k = kindOf(c.type, c.category);
      byKind.set(k, [...(byKind.get(k) ?? []), c]);
    }
    return [...byKind.entries()];
  }, [catalog, q]);
  return (
    <div className="absolute left-full top-2 ml-2 flex max-h-[calc(100%-16px)] w-80 flex-col rounded-lg border border-line-strong bg-surface shadow-float" role="dialog" aria-label="Component library">
      <div className="flex items-center gap-2 border-b border-line p-3">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
          <Input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search components" aria-label="Search components" className="pl-8" />
        </div>
        <button type="button" onClick={onClose} className="rounded-md p-1.5 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Close library">
          <X className="size-4" />
        </button>
      </div>
      <div className="flex-1 space-y-4 overflow-y-auto p-3">
        {groups.map(([kind, items]) => (
          <section key={kind}>
            <h3 className="microlabel mb-2">
              <span className="mr-1 opacity-60" aria-hidden>
            {"//"}
          </span>
              {KIND_LABEL[kind as ReturnType<typeof kindOf>]}
            </h3>
            <ul className="space-y-1.5">
              {items.map((c) => (
                <li key={c.type}>
                  <button
                    type="button"
                    {...dragProps(c.type)}
                    onClick={() => {
                      add(c.type);
                      onClose();
                    }}
                    className="group flex w-full cursor-grab items-start gap-2.5 rounded-md border border-line p-2.5 text-left transition-colors hover:border-line-strong hover:bg-surface-2"
                  >
                    <ShapeGlyph shape={SHAPE_OF[kindOf(c.type, c.category)]} w={30} h={20} className="mt-0.5 shrink-0 text-ink-2 group-hover:text-accent" />
                    <span className="min-w-0">
                      <span className="block font-mono text-[12px] font-medium text-ink">{c.displayName}</span>
                      <span className="line-clamp-2 block text-[11.5px] leading-4 text-ink-2">{c.description}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ))}
        {groups.length === 0 && <p className="text-xs text-ink-3">No component matches “{q}”.</p>}
      </div>
      <p className="border-t border-line p-3 text-[11px] leading-4 text-ink-3">
        Link components by dragging from a right-side handle to another component. <Kbd>Shift</Kbd>+drag box-selects.
      </p>
    </div>
  );
}

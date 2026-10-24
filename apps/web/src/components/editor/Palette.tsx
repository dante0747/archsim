"use client";

import { useReactFlow } from "@xyflow/react";
import { Plus, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { DND_MIME } from "@/components/editor/Canvas";
import { ComponentIcon } from "@/components/editor/icons";
import { Input } from "@/components/ui/input";
import { Kbd } from "@/components/ui/misc";
import type { ComponentType } from "@/lib/types";
import { useEditor } from "@/store/editor";

const CATEGORY_ORDER = ["traffic", "network", "compute", "storage"];
const CATEGORY_LABEL: Record<string, string> = {
  traffic: "Traffic",
  network: "Network",
  compute: "Compute",
  storage: "Storage",
};

export function Palette() {
  const catalog = useEditor((s) => s.catalog);
  const addNode = useEditor((s) => s.addNode);
  const rf = useReactFlow();
  const [q, setQ] = useState("");

  const groups = useMemo(() => {
    const term = q.trim().toLowerCase();
    const items = (catalog?.components ?? []).filter(
      (c) =>
        !term ||
        c.displayName.toLowerCase().includes(term) ||
        c.description.toLowerCase().includes(term) ||
        c.type.includes(term),
    );
    const byCat = new Map<string, ComponentType[]>();
    for (const c of items) {
      const list = byCat.get(c.category) ?? [];
      list.push(c);
      byCat.set(c.category, list);
    }
    return [...byCat.entries()].sort(
      ([a], [b]) =>
        (CATEGORY_ORDER.indexOf(a) + 1 || 99) - (CATEGORY_ORDER.indexOf(b) + 1 || 99),
    );
  }, [catalog, q]);

  const addAtCenter = (type: string) => {
    const el = document.querySelector(".react-flow");
    const rect = el?.getBoundingClientRect();
    const center = rect
      ? { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }
      : { x: window.innerWidth / 2, y: window.innerHeight / 2 };
    const p = rf.screenToFlowPosition(center);
    // Start at the viewport center and step right/down until the spot is free.
    const taken = useEditor.getState().nodes.map((n) => n.position);
    const isFree = (x: number, y: number) => taken.every((q) => Math.abs(q.x - x) > 240 || Math.abs(q.y - y) > 130);
    let x = Math.round(p.x - 124);
    let y = Math.round(p.y - 50);
    for (let i = 0; i < 40 && !isFree(x, y); i++) {
      x += 320;
      if (i % 4 === 3) {
        x = Math.round(p.x - 124);
        y += 170;
      }
    }
    addNode(type, { x, y });
    window.setTimeout(() => void rf.fitView({ padding: 0.2, maxZoom: 1, duration: 200 }), 30);
  };

  return (
    <aside className="flex h-full w-60 shrink-0 flex-col border-r border-line bg-surface" aria-label="Component palette">
      <div className="border-b border-line p-3">
        <h2 className="mb-2 text-xs font-semibold text-ink">Components</h2>
        <div className="relative">
          <Search className="pointer-events-none absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search components"
            aria-label="Search components"
            className="pl-7"
          />
        </div>
      </div>
      <div className="flex-1 space-y-4 overflow-y-auto p-3">
        {groups.map(([cat, items]) => (
          <section key={cat}>
            <h3 className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-ink-3">
              {CATEGORY_LABEL[cat] ?? cat}
            </h3>
            <ul className="space-y-1.5">
              {items.map((c) => {
                return (
                  <li key={c.type}>
                    <div
                      draggable
                      onDragStart={(e) => {
                        e.dataTransfer.setData(DND_MIME, c.type);
                        e.dataTransfer.effectAllowed = "move";
                      }}
                      className="group flex cursor-grab items-start gap-2 rounded-lg border border-line bg-surface p-2 hover:border-accent active:cursor-grabbing"
                      title={c.description}
                    >
                      <div className="flex size-7 shrink-0 items-center justify-center rounded-md bg-surface-2 text-ink-2">
                        <ComponentIcon name={c.icon} className="size-3.5" />
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-xs font-medium text-ink">{c.displayName}</p>
                        <p className="line-clamp-2 text-[11px] leading-4 text-ink-3">{c.description}</p>
                      </div>
                      <button
                        type="button"
                        onClick={() => addAtCenter(c.type)}
                        className="rounded p-0.5 text-ink-3 opacity-0 hover:bg-surface-2 hover:text-ink focus-visible:opacity-100 group-hover:opacity-100"
                        aria-label={`Add ${c.displayName} to canvas`}
                      >
                        <Plus className="size-3.5" />
                      </button>
                    </div>
                  </li>
                );
              })}
            </ul>
          </section>
        ))}
        {groups.length === 0 && <p className="text-xs text-ink-3">No component matches “{q}”.</p>}
      </div>
      <div className="space-y-1 border-t border-line p-3 text-[11px] leading-4 text-ink-3">
        <p>Drag onto the canvas, then drag from a right handle to a left handle to link.</p>
        <p>
          <Kbd>Shift</Kbd>+drag to box-select · <Kbd>Del</Kbd> to delete
        </p>
      </div>
    </aside>
  );
}

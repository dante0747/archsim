"use client";

/**
 * Chaos pads: the design's one-click failure experiments (lib/faults.ts chaosPresets) as drum pads
 * on the canvas. Hitting a pad plans that failure, replacing the planned ones so each run tests
 * one thing, and simulates at once; the pad stays lit while its failure is planned. Undo (Ctrl Z)
 * takes it back.
 */
import { Ban, ChevronDown, Flame, Snail, Timer, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { useEditorActions } from "@/components/editor/actions";
import { type ChaosPreset, chaosPresets, presetActive } from "@/lib/faults";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

const KIND: Record<ChaosPreset["kind"], { color: string; Icon: typeof Ban }> = {
  outage: { color: "var(--status-critical)", Icon: Ban },
  slowdown: { color: "var(--status-warning)", Icon: Snail },
  latency: { color: "var(--fam-database)", Icon: Timer },
  spike: { color: "var(--accent-2)", Icon: Flame },
};

const STORE = "archsim-chaos-open";
/** Below this canvas width the rack starts folded, so it doesn't cover the canvas toolbar. */
const NARROW_PX = 720;

function readOpen(): boolean {
  try {
    return localStorage.getItem(STORE) !== "0";
  } catch {
    return true;
  }
}

export function ChaosPads() {
  const nodes = useEditor((s) => s.nodes);
  const edges = useEditor((s) => s.edges);
  const catalog = useEditor((s) => s.catalog);
  const workload = useEditor((s) => s.workload);
  const setWorkload = useEditor((s) => s.setWorkload);
  const { run } = useEditorActions();
  const [stored, setStored] = useState(readOpen);
  // On a narrow canvas the rack stays folded until it is opened here.
  const [narrow, setNarrow] = useState(false);
  const [forced, setForced] = useState(false);
  const probe = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    const host = probe.current?.parentElement;
    if (!host || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(() => setNarrow(host.clientWidth < NARROW_PX));
    ro.observe(host);
    return () => ro.disconnect();
  }, []);
  const presets = useMemo(() => chaosPresets(nodes, edges, catalog, workload), [nodes, edges, catalog, workload]);
  const open = stored && (!narrow || forced);
  const setOpen = (v: boolean) => {
    setForced(v);
    setStored(v);
    try {
      localStorage.setItem(STORE, v ? "1" : "0");
    } catch {
      /* storage unavailable */
    }
  };
  // a zero-size marker that lets the rack measure the canvas it sits on
  const marker = <span ref={probe} className="hidden" aria-hidden />;
  if (!presets.length) return marker;

  // A pad is lit while its experiment is planned (also after a save and reload).
  const lit = (p: ChaosPreset) => presetActive(p, workload);
  const planned = (workload.faults ?? []).length > 0;

  const hit = (p: ChaosPreset) => {
    setWorkload(p.apply(useEditor.getState().workload), `chaos:${p.id}`);
    toast.message(p.title, { description: "Planned and simulating. Ctrl Z takes it back." });
    void run();
  };

  if (!open) {
    return (
      <>
      {marker}
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="surface-glass absolute bottom-3 right-3 z-10 flex h-8 items-center gap-2 rounded-lg px-2.5 text-xs text-ink-2 hover:text-ink"
        aria-label="Show chaos pads"
      >
        <Flame className="size-3.5" style={{ color: "var(--status-critical)" }} aria-hidden />
        Chaos
        <span className="text-ink-3">{presets.length}</span>
      </button>
      </>
    );
  }

  return (
    <>
    {marker}
    <section aria-label="Chaos pads" className="surface-glass absolute bottom-3 right-3 z-10 w-[268px] rounded-[14px] p-2">
      <header className="mb-2 flex items-center gap-2 text-xs">
        <span className="font-semibold text-ink">Chaos</span>
        <span className="text-ink-3">hit a pad</span>
        <span className="ml-auto flex items-center gap-0.5">
          {planned && (
            <button
              type="button"
              onClick={() => setWorkload({ faults: [] }, "chaos:clear")}
              className="flex items-center gap-1 rounded-md px-1.5 py-0.5 text-ink-3 hover:bg-surface-2 hover:text-ink"
              title="Remove the planned failures"
            >
              <X className="size-3" aria-hidden /> Clear
            </button>
          )}
          <button type="button" onClick={() => setOpen(false)} className="rounded-md p-0.5 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Hide chaos pads">
            <ChevronDown className="size-4" />
          </button>
        </span>
      </header>
      <div className="grid grid-cols-3 gap-1.5">
        {presets.map((p) => {
          const { color, Icon } = KIND[p.kind];
          const on = lit(p);
          return (
            <button
              key={p.id}
              type="button"
              onClick={() => hit(p)}
              title={p.description}
              aria-pressed={on}
              className={cn(
                "flex h-[64px] flex-col justify-between overflow-hidden rounded-[10px] border px-2 pb-1.5 pt-2.5 text-left transition-[filter,transform] active:scale-[0.97]",
                on ? "border-transparent text-ink" : "border-line bg-surface-2 text-ink-2 hover:text-ink hover:brightness-110",
              )}
              style={{
                boxShadow: `inset 0 3px 0 ${color}`,
                background: on ? `color-mix(in srgb, ${color} 22%, var(--surface-2))` : undefined,
              }}
            >
              <Icon className="size-3.5 shrink-0" style={{ color }} aria-hidden />
              <span className="line-clamp-2 text-[11px] font-medium leading-[13px]">{p.title}</span>
            </button>
          );
        })}
      </div>
    </section>
    </>
  );
}

"use client";

/**
 * Guided tour of the editor: a spotlight on one region at a time (parts rail, canvas, side panel,
 * Simulate button, workspaces) with a short plain-language card. It opens once on a first visit
 * and again from the ? button. Regions are found by their `data-tour="…"` attribute.
 */
import { X } from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useUi } from "@/store/ui";

const SEEN_KEY = "archsim-tour-v1";

type Side = "right" | "left" | "bottom" | "inside";

interface TourStep {
  anchor: string;
  side: Side;
  title: string;
  body: string;
}

export const TOUR_STEPS: TourStep[] = [
  {
    anchor: "rail",
    side: "right",
    title: "Add parts",
    body: "Find a technology here (PostgreSQL, Redis, Kafka…) and drag it onto the canvas.",
  },
  {
    anchor: "canvas",
    side: "inside",
    title: "Connect and tune",
    body: "Drag from the dot on a part's right edge to the next part. Click a part to change its settings.",
  },
  {
    anchor: "panel",
    side: "left",
    title: "Set the traffic",
    body: "Choose how many requests per second hit your system.",
  },
  {
    anchor: "simulate",
    side: "bottom",
    title: "Press Simulate",
    body: "Traffic flows through your design. Parts that struggle turn red.",
  },
  {
    anchor: "workspaces",
    side: "bottom",
    title: "Then test and decide",
    body: "Test: break things and replay. Results: a clear yes or no, the cost, and where it breaks.",
  },
];

export function hasSeenTour(): boolean {
  try {
    return localStorage.getItem(SEEN_KEY) === "1";
  } catch {
    return true; // storage blocked: never nag
  }
}

function markTourSeen() {
  try {
    localStorage.setItem(SEEN_KEY, "1");
  } catch {
    /* storage unavailable */
  }
}

/** Open the tour on the Design workspace, where every region it points at is on screen. */
export function startTour() {
  const ui = useUi.getState();
  const switching = ui.mode !== "build";
  if (switching) ui.setMode("build");
  window.setTimeout(() => useUi.getState().setTourOpen(true), switching ? 150 : 0);
}

function anchorRect(anchor: string): DOMRect | null {
  const el = document.querySelector(`[data-tour="${anchor}"]`);
  if (!el) return null;
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0 ? r : null;
}

const CARD_W = 330;
const GAP = 14;
const MARGIN = 12;
const PAD = 6;

function cardPosition(r: DOMRect | null, side: Side, h: number): { left: number; top: number } {
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  let left: number;
  let top: number;
  if (!r) {
    left = vw / 2 - CARD_W / 2;
    top = vh / 2 - h / 2;
  } else if (side === "right") {
    left = r.right + PAD + GAP;
    top = r.top + 24;
  } else if (side === "left") {
    left = r.left - PAD - GAP - CARD_W;
    top = r.top + 24;
  } else if (side === "bottom") {
    left = r.right - CARD_W;
    top = r.bottom + PAD + GAP;
  } else {
    // Inside a large region (the canvas): top-left corner, where a fitted diagram leaves room.
    left = r.left + 20;
    top = r.top + 20;
  }
  return {
    left: Math.min(Math.max(MARGIN, left), vw - CARD_W - MARGIN),
    top: Math.min(Math.max(MARGIN, top), vh - h - MARGIN),
  };
}

function TourOverlay({ onClose }: { onClose: () => void }) {
  const [i, setI] = useState(0);
  const [rect, setRect] = useState<DOMRect | null>(null);
  const [cardH, setCardH] = useState(200);
  const cardRef = useRef<HTMLDivElement>(null);
  const step = TOUR_STEPS[i];
  const last = i === TOUR_STEPS.length - 1;

  useLayoutEffect(() => {
    const measure = () => {
      setRect(anchorRect(step.anchor));
      if (cardRef.current) setCardH(cardRef.current.offsetHeight);
    };
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [step]);

  const next = useCallback(() => (last ? onClose() : setI((v) => v + 1)), [last, onClose]);
  const back = useCallback(() => setI((v) => Math.max(0, v - 1)), []);

  // Capture phase, so the editor's own shortcuts stay quiet while the tour is up.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowRight" || e.key === "Enter") next();
      else if (e.key === "ArrowLeft") back();
      else return;
      e.preventDefault();
      e.stopPropagation();
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [next, back, onClose]);

  const pos = cardPosition(rect, step.side, cardH);
  return (
    <div className="fixed inset-0 z-[70]" role="dialog" aria-modal="true" aria-labelledby="tour-title" aria-describedby="tour-body">
      {/* Blocks clicks on the editor while the tour is showing. */}
      <div className="absolute inset-0" style={rect ? undefined : { background: "var(--tour-shade)" }} />
      {rect && (
        <div
          className="pointer-events-none absolute rounded-xl transition-[left,top,width,height] duration-300 ease-out motion-reduce:transition-none"
          style={{
            left: rect.left - PAD,
            top: rect.top - PAD,
            width: rect.width + PAD * 2,
            height: rect.height + PAD * 2,
            outline: "2px solid var(--accent)",
            boxShadow: "0 0 0 9999px var(--tour-shade, rgba(2, 3, 6, 0.55)), 0 0 24px color-mix(in oklab, var(--accent) 35%, transparent)",
          }}
          aria-hidden
        />
      )}
      <div
        ref={cardRef}
        className="surface-glass absolute rounded-xl p-4 transition-[left,top] duration-300 ease-out motion-reduce:transition-none"
        style={{ width: CARD_W, left: pos.left, top: pos.top }}
      >
        <div className="flex items-center gap-2">
          <span className="microlabel">
            step {i + 1} of {TOUR_STEPS.length}
          </span>
          <button type="button" onClick={onClose} className="ml-auto rounded-md p-1 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Close the tour">
            <X className="size-3.5" />
          </button>
        </div>
        <h2 id="tour-title" className="mt-1 font-display text-[26px] leading-[1.1] text-ink">
          {step.title}
        </h2>
        <p id="tour-body" className="mt-2 text-[13px] leading-relaxed text-ink-2">
          {step.body}
        </p>
        <div className="mt-4 flex items-center gap-2">
          <div className="flex items-center gap-1" aria-hidden>
            {TOUR_STEPS.map((s, k) => (
              <span key={s.anchor} className={cn("h-1.5 rounded-full transition-all", k === i ? "w-4 bg-accent" : "w-1.5 bg-line-strong")} />
            ))}
          </div>
          <div className="ml-auto flex gap-1.5">
            {i > 0 ? (
              <Button size="sm" variant="ghost" onClick={back}>
                Back
              </Button>
            ) : (
              <Button size="sm" variant="ghost" onClick={onClose}>
                Skip
              </Button>
            )}
            <Button size="sm" variant="primary" onClick={next} autoFocus>
              {last ? "Got it" : "Next"}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Mount once in the editor shell. */
export function GuidedTour() {
  const open = useUi((s) => s.tourOpen);
  const setOpen = useUi((s) => s.setTourOpen);
  const close = useCallback(() => {
    markTourSeen();
    setOpen(false);
  }, [setOpen]);
  return open ? <TourOverlay onClose={close} /> : null;
}

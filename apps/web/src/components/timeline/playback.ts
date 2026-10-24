"use client";

/**
 * Flight-recorder transport: play/pause, stepping, jumping and the frame loop that advances the
 * playhead. All state lives in the UI store (`playhead`, `playing`, `speed`) so the canvas, the
 * dock and the clock stay in sync.
 */
import { useEffect } from "react";
import { bucketIndex } from "@/lib/recorder";
import { prefersReducedMotion, useUi } from "@/store/ui";

export const SPEEDS = [1, 2, 4, 8, 16] as const;

/** Last instant of the recording that still falls inside its final bucket. */
export function lastInstant(endS: number): number {
  return Math.max(0, endS - 1e-3);
}

export function togglePlay(endS: number): void {
  const ui = useUi.getState();
  if (ui.playing) {
    ui.setPlaying(false);
    return;
  }
  if (ui.playhead == null || ui.playhead >= lastInstant(endS)) ui.setPlayhead(0);
  ui.setPlaying(true);
}

export function stepBucket(dir: -1 | 1, endS: number, bucketS: number): void {
  const ui = useUi.getState();
  ui.setPlaying(false);
  const b = bucketS > 0 ? bucketS : 1;
  const cur = ui.playhead ?? (dir > 0 ? -b : endS);
  const i = bucketIndex(Math.max(0, cur), b) + dir;
  const t = Math.min(lastInstant(endS), Math.max(0, i * b));
  ui.setPlayhead(cur < 0 && dir > 0 ? 0 : t);
}

export function jumpTo(t: number, endS: number): void {
  const ui = useUi.getState();
  ui.setPlaying(false);
  ui.setPlayhead(Math.min(lastInstant(endS), Math.max(0, t)));
}

export function showSummary(): void {
  const ui = useUi.getState();
  ui.setPlaying(false);
  ui.setPlayhead(null);
}

/**
 * Advance the playhead while `playing`: smooth (requestAnimationFrame, `speed` simulated seconds per
 * real second) or, with reduced motion, in whole buckets at the same average pace.
 */
export function usePlaybackLoop(endS: number, bucketS: number): void {
  const playing = useUi((s) => s.playing);
  const speed = useUi((s) => s.speed);
  useEffect(() => {
    if (!playing || endS <= 0) return;
    const ui = useUi.getState();
    const end = lastInstant(endS);
    if (prefersReducedMotion()) {
      const b = bucketS > 0 ? bucketS : 1;
      const id = window.setInterval(
        () => {
          const s = useUi.getState();
          const next = (s.playhead ?? 0) + b;
          if (next >= end) {
            s.setPlayhead(end);
            s.setPlaying(false);
          } else {
            s.setPlayhead(next);
          }
        },
        Math.max(60, (b / speed) * 1000),
      );
      return () => window.clearInterval(id);
    }
    let raf = 0;
    let last = performance.now();
    const tick = (now: number) => {
      const dt = Math.min(0.1, Math.max(0, (now - last) / 1000));
      last = now;
      const s = useUi.getState();
      const next = (s.playhead ?? 0) + dt * s.speed;
      if (next >= end) {
        s.setPlayhead(end);
        s.setPlaying(false);
        return;
      }
      s.setPlayhead(next);
      raf = requestAnimationFrame(tick);
    };
    if (ui.playhead == null) ui.setPlayhead(0);
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [playing, speed, endS, bucketS]);
}

/**
 * Transport keys while the Stress workspace is open: Space play/pause, ←/→ step one bucket,
 * Home/End jump. Ignores typing, the open command palette, modifier chords, and events from inside
 * `[data-no-transport-keys]` (the experiment tracks use arrows for their own handles).
 */
export function useTransportKeys(enabled: boolean, endS: number, bucketS: number): void {
  useEffect(() => {
    if (!enabled) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey) return;
      if (useUi.getState().paletteOpen) return;
      const t = e.target;
      if (t instanceof HTMLElement) {
        const tag = t.tagName;
        if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || t.isContentEditable) return;
        if (t.closest("[data-no-transport-keys], [role='dialog'], [role='menu'], [role='slider'], .react-flow__node, .react-flow__edge")) return;
      }
      if (e.key === " " || e.code === "Space") {
        if (t instanceof HTMLElement && (t.tagName === "BUTTON" || t.getAttribute("role") === "button")) return;
        e.preventDefault();
        togglePlay(endS);
      } else if (e.key === "ArrowLeft") {
        e.preventDefault();
        stepBucket(-1, endS, bucketS);
      } else if (e.key === "ArrowRight") {
        e.preventDefault();
        stepBucket(1, endS, bucketS);
      } else if (e.key === "Home") {
        e.preventDefault();
        jumpTo(0, endS);
      } else if (e.key === "End") {
        e.preventDefault();
        jumpTo(endS, endS);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [enabled, endS, bucketS]);
}

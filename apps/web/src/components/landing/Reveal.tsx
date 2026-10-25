"use client";

import { useEffect, useRef, useState } from "react";
import { useReducedMotion } from "@/components/projects/MiniDiagram";
import { cn } from "@/lib/utils";

/** Fades and lifts its content into place the first time it scrolls into view. */
export function Reveal({ children, className, delay = 0 }: { children: React.ReactNode; className?: string; delay?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const reduced = useReducedMotion();
  const [seen, setSeen] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el || seen) return;
    const io = new IntersectionObserver(
      ([e]) => {
        if (e.isIntersecting) {
          setSeen(true);
          io.disconnect();
        }
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.12 },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [seen]);
  const shown = reduced || seen;
  return (
    <div
      ref={ref}
      className={cn("transition-[opacity,transform] duration-700 ease-[cubic-bezier(.2,.8,.2,1)]", shown ? "translate-y-0 opacity-100" : "translate-y-6 opacity-0", className)}
      style={{ transitionDelay: shown ? `${delay}ms` : undefined }}
    >
      {children}
    </div>
  );
}

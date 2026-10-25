"use client";

/** What a technology does differently, each behaviour with the documentation it comes from. */
import { ExternalLink } from "lucide-react";
import type { TechTrait } from "@/lib/types";
import { cn } from "@/lib/utils";

export function TechTraits({ traits, className, dense = false }: { traits: TechTrait[]; className?: string; dense?: boolean }) {
  if (!traits.length) return null;
  return (
    <ul className={cn("space-y-2", className)}>
      {traits.map((t) => (
        <li key={t.id} className={cn("rounded-md border border-line bg-surface", dense ? "px-2.5 py-2" : "px-3 py-2.5")}>
          <div className="flex items-start gap-2">
            <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-accent" aria-hidden />
            <div className="min-w-0 flex-1">
              <p className="text-[13px] font-medium text-ink">{t.title}</p>
              <p className={cn("mt-0.5 leading-relaxed text-ink-2", dense ? "text-xs" : "text-[13px]")}>{t.detail}</p>
              {t.source && (
                <a
                  href={t.source}
                  target="_blank"
                  rel="noreferrer"
                  className="mt-1 inline-flex items-center gap-1 text-xs text-accent hover:underline"
                  aria-label={`Documentation for ${t.title}`}
                >
                  Docs <ExternalLink className="size-3" />
                </a>
              )}
            </div>
          </div>
        </li>
      ))}
    </ul>
  );
}

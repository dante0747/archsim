import Link from "next/link";
import { cn } from "@/lib/utils";

/** The ArchSim mark: one request fanning out to two services, on the brand gradient. */
export function LogoMark({ className }: { className?: string }) {
  return (
    <span className={cn("brand-bg inline-flex size-7 items-center justify-center rounded-lg shadow-panel", className)} aria-hidden>
      <svg viewBox="0 0 24 24" className="size-[70%]" fill="none">
        <path d="M7 12h4.5M11.5 12c2 0 2.2-5 5-5M11.5 12c2 0 2.2 5 5 5" stroke="var(--accent-ink)" strokeWidth="1.8" strokeLinecap="round" />
        <circle cx="5.5" cy="12" r="2.4" fill="var(--accent-ink)" />
        <circle cx="18.5" cy="7" r="2.2" fill="var(--accent-ink)" />
        <circle cx="18.5" cy="17" r="2.2" fill="var(--accent-ink)" />
      </svg>
    </span>
  );
}

export function Logo({ className }: { className?: string }) {
  return (
    <Link href="/" className={cn("flex items-center gap-2 text-ink", className)} aria-label="ArchSim home">
      <LogoMark />
      <span className="font-mono text-sm font-semibold tracking-tight">
        arch<span className="brand-text">sim</span>
      </span>
    </Link>
  );
}

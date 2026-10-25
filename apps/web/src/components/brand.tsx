import Link from "next/link";
import { cn } from "@/lib/utils";

/** The ArchSim mark: one node fanning out to two, drawn as a tiny schematic. */
export function LogoMark({ className }: { className?: string }) {
  return (
    <span
      className={cn("brand-tile inline-flex size-7 items-center justify-center rounded-md", className)}
      aria-hidden
    >
      <svg viewBox="0 0 24 24" className="size-[78%]" fill="none">
        <rect x="2.5" y="9.5" width="5" height="5" rx="1" fill="currentColor" />
        <path
          d="M7.5 12h3.5M11 12V8.2a1.2 1.2 0 0 1 1.2-1.2H15M11 12v3.8a1.2 1.2 0 0 0 1.2 1.2H15"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinecap="round"
        />
        <rect x="15" y="4.6" width="6.2" height="4.8" rx="1" stroke="currentColor" strokeWidth="1.4" />
        <rect x="15" y="14.6" width="6.2" height="4.8" rx="1" stroke="currentColor" strokeWidth="1.4" />
      </svg>
    </span>
  );
}

export function Logo({ className }: { className?: string }) {
  return (
    <Link href="/" className={cn("flex items-center gap-2 text-ink", className)} aria-label="ArchSim home">
      <LogoMark />
      <span className="font-mono text-sm font-semibold tracking-tight">
        archsim
        <span className="caret text-accent" aria-hidden>
          _
        </span>
      </span>
    </Link>
  );
}

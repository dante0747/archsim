import Link from "next/link";
import { cn } from "@/lib/utils";

/** The ArchSim mark: four family tiles (compute, data, cache, clients), like parts on a canvas. */
export function LogoMark({ className }: { className?: string }) {
  return (
    <span className={cn("inline-flex size-7 shrink-0", className)} aria-hidden>
      <svg viewBox="0 0 26 26" className="size-full">
        <rect x="1" y="1" width="11" height="11" rx="3" fill="var(--fam-compute)" />
        <rect x="14" y="1" width="11" height="11" rx="3" fill="var(--fam-database)" />
        <rect x="1" y="14" width="11" height="11" rx="3" fill="var(--fam-cache)" />
        <rect x="14" y="14" width="11" height="11" rx="3" fill="var(--fam-traffic)" />
      </svg>
    </span>
  );
}

export function Logo({ className }: { className?: string }) {
  return (
    <Link href="/" className={cn("flex items-center gap-2.5 text-ink", className)} aria-label="ArchSim home">
      <LogoMark className="size-6" />
      <span className="text-[15px] font-semibold tracking-tight">archsim</span>
    </Link>
  );
}

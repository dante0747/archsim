import Link from "next/link";
import { LOGO_BLUE, LOGO_PATH_A, LOGO_PATH_S, LOGO_VIEWBOX, LOGO_VIOLET } from "@/lib/logo";
import { cn } from "@/lib/utils";

/** The ArchSim mark: an interlocking blue A and violet S. */
export function LogoMark({ className }: { className?: string }) {
  return (
    <span className={cn("inline-flex size-7 shrink-0", className)} aria-hidden>
      <svg viewBox={LOGO_VIEWBOX} className="size-full">
        <path fill={LOGO_BLUE} fillRule="evenodd" d={LOGO_PATH_A} />
        <path fill={LOGO_VIOLET} fillRule="evenodd" d={LOGO_PATH_S} />
      </svg>
    </span>
  );
}

export function Logo({ className }: { className?: string }) {
  return (
    <Link href="/" className={cn("flex items-center gap-2 text-ink", className)} aria-label="ArchSim home">
      <LogoMark className="size-7" />
      <span className="text-[16px] font-bold tracking-tight">ArchSim</span>
    </Link>
  );
}

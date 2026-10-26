import { cn } from "@/lib/utils";

/**
 * Utilisation as a studio meter: 12 segments lit up to the level, green, then yellow, then red by
 * position (like an audio level meter), so a part near saturation reads at a glance.
 */
export function Meter({ level, className }: { level: number; className?: string }) {
  const lit = Math.round(Math.min(1, Math.max(0, level)) * 12);
  return (
    <div className={cn("flex gap-[2px] pt-1.5", className)} aria-hidden>
      {Array.from({ length: 12 }, (_, i) => (
        <span
          key={i}
          className="h-[5px] flex-1 rounded-[1.5px] transition-colors duration-300"
          style={{
            background: i < lit ? (i < 7 ? "var(--status-good)" : i < 10 ? "var(--status-warning)" : "var(--status-critical)") : "var(--surface-3)",
          }}
        />
      ))}
    </div>
  );
}

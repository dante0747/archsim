/** Bordered card with a mono title row. */
import { cn } from "@/lib/utils";

export function Panel({
  title,
  subtitle,
  actions,
  children,
  className,
  bodyClassName,
}: {
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  bodyClassName?: string;
}) {
  return (
    <section className={cn("flex min-w-0 flex-col rounded-md border border-line bg-surface", className)}>
      <div className="flex min-h-10 items-center justify-between gap-3 border-b border-line px-4 py-2">
        <div className="min-w-0">
          <h3 className="truncate font-mono text-xs font-semibold text-ink">{title}</h3>
          {subtitle && <p className="truncate font-mono text-[11px] text-ink-3">{subtitle}</p>}
        </div>
        {actions}
      </div>
      <div className={cn("min-w-0 flex-1 p-4", bodyClassName)}>{children}</div>
    </section>
  );
}

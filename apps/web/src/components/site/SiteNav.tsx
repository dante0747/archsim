"use client";

/** The website's main navigation, marking the section the reader is in. */
import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import { SITE_NAV } from "./site-nav-items";

export function SiteNav({ className }: { className?: string }) {
  const path = usePathname() ?? "";
  const here = (section: string | null) => section !== null && `${path.replace(/\/?$/, "/")}`.startsWith(section);
  return (
    <nav aria-label="Main" className={cn("items-center gap-1", className)}>
      {SITE_NAV.map((item) => (
        <Link
          key={item.href}
          href={item.href}
          aria-current={here(item.section) ? "page" : undefined}
          className="rounded-lg px-3 py-1.5 text-[13px] font-medium text-ink-2 transition-colors hover:bg-surface-2 hover:text-ink aria-[current=page]:text-ink aria-[current=page]:bg-surface-2"
        >
          {item.label}
        </Link>
      ))}
    </nav>
  );
}

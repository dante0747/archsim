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
          prefetch={false}
          aria-current={here(item.section) ? "page" : undefined}
          className="inline-flex h-9 items-center rounded-lg px-3 text-[14px] font-medium text-ink-2 transition-colors before:absolute before:inset-x-0 before:-inset-y-1 before:content-[''] hover:text-ink aria-[current=page]:bg-surface-2 aria-[current=page]:text-ink relative"
        >
          {item.label}
        </Link>
      ))}
    </nav>
  );
}

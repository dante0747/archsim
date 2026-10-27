"use client";

/**
 * The page menu on narrow screens: a button that opens the links the header hides below its
 * breakpoint (pass `className` with the matching `md:hidden` or `sm:hidden`).
 */
import { Menu as MenuIcon } from "lucide-react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Menu, MenuContent, MenuItem, MenuTrigger } from "@/components/ui/overlays";

export interface MobileNavItem {
  label: string;
  /** A page path ("/templates"), an anchor ("#how") or an external URL. */
  href?: string;
  /** Opens in a new tab (API docs). */
  external?: boolean;
  onSelect?: () => void;
}

export function MobileNav({ items, className }: { items: MobileNavItem[]; className?: string }) {
  return (
    <Menu>
      <MenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label="Menu" className={className}>
          <MenuIcon />
        </Button>
      </MenuTrigger>
      <MenuContent align="end" className="w-56">
        {items.map((it) =>
          it.href ? (
            <MenuItem key={it.label} asChild>
              {it.external ? (
                <a href={it.href} target="_blank" rel="noreferrer">
                  {it.label}
                </a>
              ) : it.href.startsWith("/") ? (
                <Link href={it.href}>{it.label}</Link>
              ) : (
                <a href={it.href}>{it.label}</a>
              )}
            </MenuItem>
          ) : (
            <MenuItem key={it.label} onSelect={it.onSelect}>
              {it.label}
            </MenuItem>
          ),
        )}
      </MenuContent>
    </Menu>
  );
}

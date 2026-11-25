"use client";

/**
 * The website's menu below 768 pixels: a button that opens the main sections, GitHub and the call
 * to action in a sheet from the top. The sheet is a modal dialog: focus stays inside it while it is
 * open, Escape or its close button shuts it and gives focus back to the menu button, and following
 * one of its links closes it.
 */
import { ArrowRight, ArrowUpRight, Menu as MenuIcon, X } from "lucide-react";
import Link from "next/link";
import { Dialog } from "radix-ui";
import { useRef, useState } from "react";
import { LogoMark } from "@/components/brand";
import { REPOSITORY_URL } from "@/lib/repository";
import { cn } from "@/lib/utils";
import { Cta } from "./Cta";
import { GitHubMark } from "./GitHubMark";
import { SITE_NAV, tryHref } from "./site-nav-items";

const ICON_BUTTON = "inline-flex size-11 items-center justify-center rounded-lg text-ink-2 transition-colors hover:bg-surface-2 hover:text-ink [&_svg]:size-5";
const ROW = "flex min-h-14 items-center justify-between border-b border-line text-[17px] font-medium text-ink transition-colors hover:text-accent";

export function SiteMobileNav({ demoUrl, className }: { demoUrl: string | null; className?: string }) {
  const [open, setOpen] = useState(false);
  // Following a link closes the menu without handing focus back to its button: the browser starts
  // the next Tab from the section the link went to.
  const following = useRef(false);
  const followLink = () => {
    following.current = true;
    setOpen(false);
  };
  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger aria-label="Open menu" className={cn(ICON_BUTTON, className)}>
        <MenuIcon aria-hidden />
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/50" />
        <Dialog.Content
          aria-describedby={undefined}
          onCloseAutoFocus={(e) => {
            if (following.current) e.preventDefault();
            following.current = false;
          }}
          className="fixed inset-x-0 top-0 z-50 max-h-dvh overflow-y-auto border-b border-line bg-page pb-8 shadow-float">
          <div className="site-container flex h-14 items-center justify-between">
            <span className="flex items-center gap-2 text-ink" aria-hidden>
              <LogoMark className="size-7" />
              <span className="text-[16px] font-bold tracking-tight">ArchSim</span>
            </span>
            <Dialog.Title className="sr-only">Menu</Dialog.Title>
            <Dialog.Close aria-label="Close menu" className={cn(ICON_BUTTON, "-mr-2.5")}>
              <X aria-hidden />
            </Dialog.Close>
          </div>
          <nav aria-label="Main" className="site-container">
            <ul className="border-t border-line">
              {SITE_NAV.map((item) => (
                <li key={item.href}>
                  <Link href={item.href} prefetch={false} onClick={followLink} className={ROW}>
                    {item.label}
                  </Link>
                </li>
              ))}
              <li>
                <a href={REPOSITORY_URL} onClick={followLink} className={ROW}>
                  GitHub
                  <GitHubMark className="size-5 text-ink-3" />
                </a>
              </li>
            </ul>
          </nav>
          <div className="site-container mt-6">
            <Cta href={tryHref(demoUrl)} onClick={followLink} className="w-full">
              Try ArchSim
              {demoUrl ? <ArrowUpRight aria-hidden /> : <ArrowRight aria-hidden />}
            </Cta>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

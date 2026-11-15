/**
 * The public website's header (ADR-0078): the logo, the site's sections, the repository, the
 * theme picker the app uses, and "Try it live" when the build names a hosted demo.
 */
import { ArrowRight, ArrowUpRight } from "lucide-react";
import Link from "next/link";
import { Logo } from "@/components/brand";
import { Button } from "@/components/ui/button";
import { MobileNav } from "@/components/ui/MobileNav";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { REPOSITORY_URL } from "@/lib/repository";
import { GitHubMark } from "./GitHubMark";
import { SiteNav } from "./SiteNav";
import { SITE_NAV } from "./site-nav-items";

export function SiteHeader({ demoUrl }: { demoUrl: string | null }) {
  return (
    <header className="sticky top-0 z-40 border-b border-line bg-page/90 backdrop-blur-md">
      <div className="mx-auto flex h-14 max-w-7xl items-center gap-4 px-4 sm:px-6">
        <Logo />
        <SiteNav className="hidden md:flex" />
        <div className="ml-auto flex items-center gap-1.5">
          <Button variant="ghost" size="md" asChild className="hidden sm:inline-flex">
            <a href={REPOSITORY_URL}>
              <GitHubMark /> GitHub
            </a>
          </Button>
          <ThemeToggle />
          <MobileNav
            className="md:hidden"
            items={[
              ...SITE_NAV.map(({ href, label }) => ({ href, label })),
              { href: REPOSITORY_URL, label: "GitHub", external: true },
              ...(demoUrl ? [{ href: demoUrl, label: "Try it live", external: true }] : []),
            ]}
          />
          {demoUrl ? (
            <Button variant="primary" size="md" asChild className="hidden sm:inline-flex">
              <a href={demoUrl}>
                Try it live <ArrowUpRight />
              </a>
            </Button>
          ) : (
            <Button variant="primary" size="md" asChild className="hidden sm:inline-flex">
              <Link href="/#quick-start">
                Get started <ArrowRight />
              </Link>
            </Button>
          )}
        </div>
      </div>
    </header>
  );
}

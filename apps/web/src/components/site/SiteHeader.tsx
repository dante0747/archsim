/**
 * The public website's header (ADR-0078, ADR-0097): the logo, three sections, GitHub, the theme
 * picker the app uses, and the one call to action, "Try ArchSim": the hosted demo when the build
 * names one, else the quick start. Below 768 pixels the sections, GitHub and the call to action
 * move into the menu. Every control is a 44-pixel touch target in a 56-pixel bar.
 */
import { ArrowRight, ArrowUpRight } from "lucide-react";
import { Logo } from "@/components/brand";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { REPOSITORY_URL } from "@/lib/repository";
import { Cta } from "./Cta";
import { GitHubMark } from "./GitHubMark";
import { SiteMobileNav } from "./SiteMobileNav";
import { SiteNav } from "./SiteNav";
import { tryHref } from "./site-nav-items";

export function SiteHeader({ demoUrl }: { demoUrl: string | null }) {
  return (
    <header className="sticky top-0 z-40 border-b border-line bg-page/85 backdrop-blur-md">
      <div className="site-container flex h-14 items-center gap-2 md:gap-6">
        <Logo className="h-11 shrink-0 rounded-lg" />
        <SiteNav className="hidden md:flex" />
        <div className="ml-auto flex items-center gap-1">
          <a
            href={REPOSITORY_URL}
            className="hidden h-11 items-center gap-2 rounded-lg px-3 text-[14px] font-medium text-ink-2 transition-colors hover:text-ink md:inline-flex"
          >
            <GitHubMark />
            GitHub
          </a>
          <ThemeToggle className="size-11 [&_svg]:size-[18px]" />
          <Cta href={tryHref(demoUrl)} size="md" className="ml-2 hidden md:inline-flex">
            Try ArchSim
            {demoUrl ? <ArrowUpRight aria-hidden /> : <ArrowRight aria-hidden />}
          </Cta>
          <SiteMobileNav demoUrl={demoUrl} className="-mr-2.5 md:hidden" />
        </div>
      </div>
    </header>
  );
}

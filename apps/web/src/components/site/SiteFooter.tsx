/**
 * The public website's footer (ADR-0078, ADR-0097): the product, the documentation, the community
 * and the legal links in four groups, the copyright and what the site does not collect. The
 * license and the contributor agreement are linked, not explained, here: their own documents say
 * what they mean.
 */
import Link from "next/link";
import { Logo } from "@/components/brand";
import { REPOSITORY_URL, repositoryUrl } from "@/lib/repository";

interface FooterLink {
  label: string;
  href: string;
}

function groups(commercialContact: string): { title: string; links: FooterLink[] }[] {
  return [
    {
      title: "Product",
      links: [
        { label: "Capabilities", href: "/#features" },
        { label: "Screens", href: "/#tour" },
        { label: "Quick start", href: "/#quick-start" },
        { label: "Accuracy", href: "/accuracy/" },
        { label: "Changelog", href: "/changelog/" },
      ],
    },
    {
      title: "Documentation",
      links: [
        { label: "Getting started", href: "/docs/getting-started/" },
        { label: "Simulation model", href: "/docs/simulation-model/" },
        { label: "Calibration", href: "/docs/calibration/" },
        { label: "Decision records", href: "/docs/adr/" },
        { label: "All docs", href: "/docs/" },
      ],
    },
    {
      title: "Community",
      links: [
        { label: "GitHub", href: REPOSITORY_URL },
        { label: "Discussions", href: `${REPOSITORY_URL}/discussions` },
        { label: "Issues", href: `${REPOSITORY_URL}/issues` },
        { label: "Contributing", href: repositoryUrl("CONTRIBUTING.md") },
        { label: "Code of conduct", href: repositoryUrl("CODE_OF_CONDUCT.md") },
      ],
    },
    {
      title: "Legal",
      links: [
        { label: "AGPL-3.0-only", href: repositoryUrl("LICENSE") },
        { label: "Ask for a commercial license", href: commercialContact },
        { label: "Contributor agreement", href: repositoryUrl("CLA.md") },
        { label: "Security policy", href: repositoryUrl("SECURITY.md") },
        { label: "Privacy", href: "/privacy/" },
      ],
    },
  ];
}

const LINK = "inline-flex min-h-10 items-center text-ink-2 transition-colors hover:text-ink md:min-h-8";

/**
 * `copyright` is NOTICE's copyright line ("Copyright 2026 …"); `commercialContact` the README's
 * mailto: link for a commercial license.
 */
export function SiteFooter({ copyright, commercialContact }: { copyright: string; commercialContact: string }) {
  return (
    <footer className="border-t border-line">
      <div className="site-container grid gap-x-8 gap-y-10 py-12 md:py-16 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,3fr)]">
        <div>
          <Logo className="h-11 w-fit" />
          <p className="mt-3 max-w-xs text-[14px] leading-relaxed text-ink-2">Architecture simulation for distributed systems. Open source, local-first, honest about what it knows.</p>
        </div>
        <div className="grid grid-cols-2 gap-x-6 gap-y-8 sm:grid-cols-4">
          {groups(commercialContact).map((group) => (
            <nav key={group.title} aria-label={group.title}>
              <p className="text-[13px] font-semibold text-ink">{group.title}</p>
              <ul className="mt-2 text-[14px]">
                {group.links.map((l) => (
                  <li key={l.label}>
                    {l.href.startsWith("/") ? (
                      <Link href={l.href} className={LINK} prefetch={false}>
                        {l.label}
                      </Link>
                    ) : (
                      <a href={l.href} className={LINK}>
                        {l.label}
                      </a>
                    )}
                  </li>
                ))}
              </ul>
            </nav>
          ))}
        </div>
      </div>
      <div className="border-t border-line">
        <div className="site-container flex flex-col gap-2 py-6 text-[13px] text-ink-3 sm:flex-row sm:items-center sm:justify-between">
          <p>{copyright.replace(/^Copyright\s+/, "© ")}</p>
          <p>No analytics, no cookies, no third-party scripts.</p>
        </div>
      </div>
    </footer>
  );
}

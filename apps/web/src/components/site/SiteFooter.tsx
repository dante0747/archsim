/** The public website's footer (ADR-0078): the site, the docs, the community, and the license. */
import Link from "next/link";
import { Logo } from "@/components/brand";
import { REPOSITORY_URL, repositoryUrl } from "@/lib/repository";

const COLUMNS: { title: string; links: { label: string; href: string }[] }[] = [
  {
    title: "Product",
    links: [
      { label: "Quick start", href: "/#quick-start" },
      { label: "Features", href: "/#features" },
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
      { label: "Security policy", href: repositoryUrl("SECURITY.md") },
      { label: "Code of conduct", href: repositoryUrl("CODE_OF_CONDUCT.md") },
    ],
  },
];

/** `copyright` is NOTICE's copyright line ("Copyright 2026 …"). */
export function SiteFooter({ copyright }: { copyright: string }) {
  return (
    <footer className="border-t border-line bg-page">
      <div className="mx-auto grid max-w-7xl gap-10 px-4 py-14 sm:px-6 md:grid-cols-[minmax(0,1.4fr)_repeat(3,minmax(0,1fr))]">
        <div>
          <Logo />
          <p className="mt-4 max-w-xs text-[14px] leading-relaxed text-ink-2">
            A flight simulator for distributed systems. Open source, local-first, honest about what it knows.
          </p>
        </div>
        {COLUMNS.map((col) => (
          <nav key={col.title} aria-label={col.title}>
            <p className="text-[13px] font-semibold text-ink">{col.title}</p>
            <ul className="mt-3 space-y-2 text-[14px]">
              {col.links.map((l) => (
                <li key={l.label}>
                  {l.href.startsWith("/") ? (
                    <Link href={l.href} className="text-ink-2 hover:text-ink">
                      {l.label}
                    </Link>
                  ) : (
                    <a href={l.href} className="text-ink-2 hover:text-ink">
                      {l.label}
                    </a>
                  )}
                </li>
              ))}
            </ul>
          </nav>
        ))}
      </div>
      <div className="border-t border-line">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-6 text-[13px] text-ink-3 sm:px-6">
          <span>{copyright.replace(/^Copyright\s+/, "© ")}</span>
          <span>
            Licensed under the{" "}
            <a href={repositoryUrl("LICENSE")} className="underline decoration-line-strong underline-offset-2 hover:text-ink">
              AGPL-3.0-only
            </a>
          </span>
          <Link href="/privacy/" className="underline decoration-line-strong underline-offset-2 hover:text-ink">
            Privacy
          </Link>
          <span className="sm:ml-auto">No analytics, no cookies, no third-party scripts.</span>
        </div>
      </div>
    </footer>
  );
}

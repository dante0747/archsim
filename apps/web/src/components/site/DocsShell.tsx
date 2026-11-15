/**
 * A documentation page of the public website (ADR-0078): the docs index's navigation beside the
 * document (a menu above it on narrow screens), the document's own contents on wide ones, and a
 * link to its source on GitHub. Server-rendered; it needs no script.
 */
import { ArrowUpRight, ChevronDown, ChevronRight, FilePenLine } from "lucide-react";
import Link from "next/link";
import { formatDate } from "@/lib/accuracy";
import { REPOSITORY_URL, repositoryUrl } from "@/lib/repository";
import type { Doc } from "@/lib/site/content";
import type { NavSection } from "@/lib/site/nav";
import { cn } from "@/lib/utils";
import { Prose } from "./Prose";

const withoutHash = (href: string) => href.replace(/#.*$/, "");

/** The nav item the page is, or failing that the one whose page holds it (/docs/adr/ for a record). */
function current(nav: NavSection[], route: string): string | null {
  const items = nav.flatMap((s) => s.items).filter((i) => !i.external);
  if (items.some((i) => withoutHash(i.href) === route)) return route;
  const parent = items.map((i) => withoutHash(i.href)).filter((h) => h !== "/docs/" && route.startsWith(h));
  return parent.sort((a, b) => b.length - a.length)[0] ?? null;
}

function DocsNav({ nav, route }: { nav: NavSection[]; route: string }) {
  const here = current(nav, route);
  return (
    <nav aria-label="Documentation" className="space-y-6 text-[14px]">
      <Link
        href="/docs/"
        aria-current={route === "/docs/" ? "page" : undefined}
        className="block rounded-md px-2.5 py-1.5 font-semibold text-ink hover:bg-surface-2 aria-[current=page]:bg-accent-soft aria-[current=page]:text-accent"
      >
        Overview
      </Link>
      {nav.map((section) => (
        <div key={section.title}>
          <p className="px-2.5 text-[12px] font-semibold uppercase tracking-[0.06em] text-ink-3">{section.title}</p>
          <ul className="mt-2 space-y-0.5">
            {section.items.map((item) => {
              const exact = !item.external && withoutHash(item.href) === route;
              const within = !exact && !item.external && withoutHash(item.href) === here;
              const className = cn(
                "flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-ink-2 transition-colors hover:bg-surface-2 hover:text-ink",
                (exact || within) && "bg-accent-soft font-medium text-accent hover:bg-accent-soft hover:text-accent",
              );
              return (
                <li key={item.href}>
                  {item.external ? (
                    <a href={item.href} className={className}>
                      {item.title}
                      <ArrowUpRight className="size-3.5 shrink-0 text-ink-3" aria-label="(on GitHub)" />
                    </a>
                  ) : (
                    <Link href={item.href} aria-current={exact ? "page" : undefined} className={className}>
                      {item.title}
                    </Link>
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </nav>
  );
}

function Toc({ doc }: { doc: Doc }) {
  if (doc.headings.length < 2) return null;
  return (
    <aside className="hidden xl:block">
      <nav aria-label="On this page" className="sticky top-14 max-h-[calc(100dvh-3.5rem)] overflow-y-auto py-10 pl-2 text-[13px]">
        <p className="font-semibold text-ink">On this page</p>
        <ul className="mt-3 space-y-1.5 border-l border-line">
          {doc.headings.map((h) => (
            <li key={h.id}>
              <a href={`#${h.id}`} className={cn("-ml-px block border-l border-transparent py-0.5 text-ink-3 hover:border-ink-3 hover:text-ink", h.depth === 3 ? "pl-6" : "pl-3")}>
                {h.text}
              </a>
            </li>
          ))}
        </ul>
      </nav>
    </aside>
  );
}

const STATUS_TONE: Record<string, string> = {
  accepted: "bg-good-soft text-success-ink",
  proposed: "bg-accent-soft text-accent",
  rejected: "bg-critical-soft text-danger-ink",
  deprecated: "bg-warning-soft text-ink",
};

function Status({ status }: { status: string }) {
  const word = status.split(" ")[0].toLowerCase();
  return <span className={cn("rounded-full px-2.5 py-0.5 text-[12px] font-semibold", STATUS_TONE[word] ?? "bg-surface-2 text-ink-2")}>{status[0].toUpperCase() + status.slice(1)}</span>;
}

function Crumbs({ nav, route }: { nav: NavSection[]; route: string }) {
  if (route === "/docs/") return null;
  const here = current(nav, route);
  const section = nav.find((s) => s.items.some((i) => withoutHash(i.href) === here));
  const parent = here && here !== route ? section?.items.find((i) => withoutHash(i.href) === here) : null;
  return (
    <nav aria-label="Breadcrumb" className="mb-4 flex flex-wrap items-center gap-1 text-[13px] text-ink-3">
      <Link href="/docs/" className="hover:text-ink">
        Docs
      </Link>
      {section && (
        <>
          <ChevronRight className="size-3.5" aria-hidden />
          <span>{section.title}</span>
        </>
      )}
      {parent && (
        <>
          <ChevronRight className="size-3.5" aria-hidden />
          <Link href={parent.href} className="hover:text-ink">
            {parent.title}
          </Link>
        </>
      )}
    </nav>
  );
}

export function DocsShell({ nav, doc, route }: { nav: NavSection[]; doc: Doc; route: string }) {
  const source = repositoryUrl(doc.path);
  const { status } = doc.frontMatter;
  // a record's date, when it is one (the records' template says YYYY-MM-DD)
  const date = /^\d{4}-\d{2}-\d{2}$/.test(doc.frontMatter.date ?? "") ? doc.frontMatter.date : null;
  return (
    <div className="mx-auto max-w-[88rem] px-4 sm:px-6 lg:grid lg:grid-cols-[15.5rem_minmax(0,1fr)] lg:gap-10 xl:grid-cols-[15.5rem_minmax(0,1fr)_14rem]">
      <aside className="hidden lg:block">
        <div className="sticky top-14 max-h-[calc(100dvh-3.5rem)] overflow-y-auto py-10 pr-2">
          <DocsNav nav={nav} route={route} />
        </div>
      </aside>
      <main id="main" className="min-w-0 py-8 sm:py-10">
        <details className="group mb-8 rounded-xl border border-line bg-surface lg:hidden">
          <summary className="flex cursor-pointer list-none items-center justify-between px-4 py-3 text-[14px] font-semibold text-ink [&::-webkit-details-marker]:hidden">
            Browse the docs
            <ChevronDown className="size-4 text-ink-3 transition-transform group-open:rotate-180" aria-hidden />
          </summary>
          <div className="border-t border-line px-2 py-4">
            <DocsNav nav={nav} route={route} />
          </div>
        </details>
        <article className="mx-auto max-w-3xl">
          <header className="mb-8 border-b border-line pb-6">
            <Crumbs nav={nav} route={route} />
            {(status || date) && (
              <p className="mb-3 flex flex-wrap items-center gap-2 text-[13px] text-ink-3">
                {status && <Status status={status} />}
                {date && <span>{formatDate(date)}</span>}
              </p>
            )}
            <h1 className="font-display text-[32px] font-semibold leading-[1.1] tracking-[-0.035em] text-ink sm:text-[40px]">{doc.title}</h1>
            <p className="mt-4 flex flex-wrap gap-x-5 gap-y-2 text-[13px]">
              <a href={source} className="inline-flex items-center gap-1.5 text-ink-2 hover:text-ink">
                <FilePenLine className="size-4" aria-hidden /> View on GitHub
              </a>
              <a href={`${REPOSITORY_URL}/issues/new/choose`} className="inline-flex items-center gap-1.5 text-ink-2 hover:text-ink">
                Report a problem with this page
              </a>
            </p>
          </header>
          <Prose html={doc.html} />
        </article>
      </main>
      <Toc doc={doc} />
    </div>
  );
}

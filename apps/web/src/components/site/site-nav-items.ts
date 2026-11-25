/** The website's main sections; `section` is the path whose pages light the item up. */
export const SITE_NAV: { href: string; label: string; section: string | null }[] = [
  { href: "/#features", label: "Product", section: null },
  { href: "/accuracy/", label: "Accuracy", section: "/accuracy/" },
  { href: "/docs/", label: "Docs", section: "/docs/" },
];

/** Where "Try ArchSim" goes: the hosted demo when the build names one, else the quick start. */
export function tryHref(demoUrl: string | null): string {
  return demoUrl ?? "/#quick-start";
}

import type { Metadata } from "next";
import Link from "next/link";
import { Button } from "@/components/ui/button";

// served for every missing path: it names no canonical page and asks not to be indexed
export const metadata: Metadata = { title: "Page not found", alternates: { canonical: null }, robots: { index: false } };

/** GitHub Pages serves this as 404.html for any path the site does not have. */
export default function NotFound() {
  return (
    <main id="main" className="relative">
      <div className="schematic-grid absolute inset-0 opacity-60 [mask-image:linear-gradient(#000_40%,transparent)]" aria-hidden />
      <div className="relative mx-auto max-w-2xl px-4 py-28 text-center sm:px-6">
        <p className="tabular font-readout text-[15px] font-semibold text-accent">404</p>
        <h1 className="mt-3 font-display text-[40px] font-semibold leading-[1.05] tracking-[-0.04em] text-ink sm:text-[52px]">This page didn&apos;t hold up</h1>
        <p className="mt-4 text-[17px] leading-relaxed text-ink-2">There is nothing at this address. It may have moved when the docs changed.</p>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <Button variant="primary" size="lg" asChild>
            <Link href="/">Home</Link>
          </Button>
          <Button size="lg" asChild>
            <Link href="/docs/">Documentation</Link>
          </Button>
        </div>
      </div>
    </main>
  );
}

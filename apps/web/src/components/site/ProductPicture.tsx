import type { ProductView } from "@/lib/site/product-views";
import { cn } from "@/lib/utils";

/**
 * One of the app's screens, as the home page shows it (ADR-0097): the whole screen from 768 pixels
 * up, a phone's crop below where the view has one, each at the widths a browser picks from and
 * with its size reserved, so nothing moves while it loads.
 */
export function ProductPicture({ view, sizes, priority = false, className }: { view: ProductView; sizes: string; priority?: boolean; className?: string }) {
  return (
    <picture>
      {view.crop && (
        <source media="(max-width: 767px)" srcSet={view.crop.srcSet} sizes="calc(100vw - 2rem)" width={view.crop.width} height={view.crop.height} />
      )}
      <img
        src={view.full.src}
        srcSet={view.full.srcSet}
        sizes={sizes}
        width={view.full.width}
        height={view.full.height}
        alt={view.alt}
        loading={priority ? undefined : "lazy"}
        fetchPriority={priority ? "high" : undefined}
        // the first screen paints with the page's first frame, not in one after the scripts have run
        decoding={priority ? "sync" : "async"}
        className={cn("block h-auto w-full", className)}
      />
    </picture>
  );
}

/** The frame every screen sits in: a hairline, the canvas behind it, one soft shadow. */
export function ScreenFrame({ className, children }: { className?: string; children: React.ReactNode }) {
  return <div className={cn("overflow-hidden rounded-xl border border-line-strong bg-canvas shadow-float md:rounded-2xl", className)}>{children}</div>;
}

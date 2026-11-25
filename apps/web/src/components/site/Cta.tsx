/**
 * The website's calls to action (ADR-0097): one primary, filled in the brand's blue, and one
 * secondary beside it, never more. Every one is 48 pixels tall (40 in the header, with a 44-pixel
 * touch target), labels centred, icons 18 pixels. `CtaGroup` stacks a group at full width on a
 * phone, 12 pixels apart, and sets it side by side from 640 pixels up.
 */
import Link from "next/link";
import { cn } from "@/lib/utils";

export type CtaVariant = "primary" | "secondary";

const VARIANTS: Record<CtaVariant, string> = {
  primary: "bg-[var(--site-cta)] text-white shadow-[inset_0_1px_0_rgb(255_255_255/0.14)] hover:bg-[var(--site-cta-hover)]",
  secondary: "border border-line-strong bg-surface text-ink hover:border-[color:var(--ink-3)] hover:bg-surface-2",
};

const SIZES = {
  lg: "h-12 gap-2 rounded-xl px-6 text-[15px] leading-none [&_svg]:size-[18px]",
  // a 40-pixel button whose touch target reaches 44 pixels
  md: "relative h-10 gap-1.5 rounded-lg px-4 text-[14px] leading-none before:absolute before:inset-x-0 before:-inset-y-0.5 before:content-[''] [&_svg]:size-4",
};

export function Cta({
  href,
  variant = "primary",
  size = "lg",
  className,
  children,
  ...rest
}: {
  /** A page ("/docs/"), an anchor on this page ("#quick-start") or another site. */
  href: string;
  variant?: CtaVariant;
  size?: keyof typeof SIZES;
  className?: string;
  children: React.ReactNode;
} & Omit<React.AnchorHTMLAttributes<HTMLAnchorElement>, "href" | "className" | "children">) {
  const style = cn(
    "inline-flex shrink-0 items-center justify-center whitespace-nowrap font-semibold tracking-[-0.01em] transition-[background-color,border-color,color] duration-150 [&_svg]:shrink-0",
    VARIANTS[variant],
    SIZES[size],
    className,
  );
  return href.startsWith("/") ? (
    <Link href={href} className={style} data-cta={variant} prefetch={false} {...rest}>
      {children}
    </Link>
  ) : (
    <a href={href} className={style} data-cta={variant} {...rest}>
      {children}
    </a>
  );
}

/** A primary call to action and at most one secondary, laid out the same everywhere. */
export function CtaGroup({ className, children }: { className?: string; children: React.ReactNode }) {
  return <div className={cn("flex w-full flex-col gap-3 sm:w-auto sm:flex-row sm:items-center [&>*]:w-full sm:[&>*]:w-auto", className)}>{children}</div>;
}

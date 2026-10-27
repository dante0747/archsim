import Link from "next/link";
import { LogoMark } from "@/components/brand";
import { SavedTheme } from "@/components/share/SavedTheme";

/** A share link that never existed, expired or was revoked: all three look the same. */
export default function ShareNotFound() {
  return (
    <main className="flex min-h-full flex-col items-center justify-center gap-4 bg-page px-6 py-16 text-center">
      <SavedTheme />
      <LogoMark className="size-9" />
      <h1 className="font-display text-[26px] font-semibold tracking-[-0.03em] text-ink">This link does not work any more</h1>
      <p className="max-w-sm text-[15px] leading-relaxed text-ink-2">
        Shared results stop working when they expire or when whoever shared them revokes the link. Ask them for a new one.
      </p>
      <Link href="/" className="text-[13px] font-medium text-ink-2 underline decoration-dotted underline-offset-2 hover:text-ink">
        Go to ArchSim
      </Link>
    </main>
  );
}

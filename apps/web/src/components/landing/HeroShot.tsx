/**
 * The hero's product shot on a console band: a window with the editor's top bar around the
 * scripted replay (HeroDemo). The app's home and the public website (ADR-0078) both show it.
 */
import { LogoMark } from "@/components/brand";
import { HeroDemo } from "@/components/landing/HeroDemo";
import { Reveal } from "@/components/landing/Reveal";
import { cn } from "@/lib/utils";

export function HeroShot() {
  return (
    <div className="relative mt-14 sm:mt-20">
      <div className="hero-band absolute inset-x-0 bottom-0 top-[30%]" aria-hidden />
      <div className="relative mx-auto max-w-6xl px-4 pb-14 sm:px-6 sm:pb-20">
        <Reveal>
          <figure className="overflow-hidden rounded-2xl border border-line bg-surface shadow-float">
            <div className="flex items-center gap-3 border-b border-line px-4 py-2.5">
              <LogoMark className="size-6" />
              <span className="hidden truncate text-[13px] font-semibold text-ink sm:inline">Black Friday on Kubernetes</span>
              <div className="mx-auto flex items-center gap-0.5 rounded-[9px] bg-[color:var(--seg)] p-[3px] text-[12px] font-medium">
                {["Design", "Test", "Results"].map((w) => (
                  <span key={w} className={cn("rounded-[6px] px-2.5 py-1", w === "Test" ? "bg-[color:var(--seg-on)] text-ink" : "text-ink-3")}>
                    {w}
                  </span>
                ))}
              </div>
              <span className="hidden items-center gap-2 rounded-[8px] border border-[color:var(--record-line)] bg-[color:var(--record-fill)] px-2.5 py-1 text-[12px] font-semibold text-ink sm:inline-flex">
                <span className="size-2 rounded-full bg-[color:var(--record)]" /> Simulate
              </span>
            </div>
            <HeroDemo />
          </figure>
        </Reveal>
      </div>
    </div>
  );
}

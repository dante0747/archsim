"use client";

/**
 * The home page's product views (ADR-0097): three of the app's screens. From 768 pixels up they
 * are tabs, one large screen at a time, switched by click or the arrow keys, never on their own;
 * every screen has the same proportions, so switching moves nothing. On a phone the three are a
 * plain list instead, each cropped to the part that stays readable: no tab panels without tabs.
 * Only the list or the tabs is shown, so only its pictures load.
 */
import { Tabs } from "radix-ui";
import type { ShowcaseView } from "@/lib/site/product-views";
import { ProductPicture, ScreenFrame } from "./ProductPicture";

const SIZES = "(min-width: 1264px) 1200px, (min-width: 1024px) calc(100vw - 4rem), calc(100vw - 3rem)";

function Screen({ view }: { view: ShowcaseView }) {
  return (
    <figure>
      <ScreenFrame>
        <ProductPicture view={view} sizes={SIZES} />
      </ScreenFrame>
      <figcaption className="mt-4 max-w-2xl text-[15px] leading-relaxed text-ink-2 md:min-h-[3.25rem]">
        <span className="font-semibold text-ink">{view.title}.</span> {view.caption}
      </figcaption>
    </figure>
  );
}

export function ProductShowcase({ views }: { views: ShowcaseView[] }) {
  return (
    <>
      <div className="grid gap-12 md:hidden">
        {views.map((v) => (
          <Screen key={v.name} view={v} />
        ))}
      </div>
      <Tabs.Root defaultValue={views[0]?.name} className="hidden md:block">
        <Tabs.List aria-label="Product views" className="mb-6 flex w-fit gap-1 rounded-xl border border-line bg-surface-2/60 p-1">
          {views.map((v) => (
            <Tabs.Trigger
              key={v.name}
              value={v.name}
              className="h-11 rounded-lg px-5 text-[14px] font-semibold text-ink-2 transition-colors hover:text-ink data-[state=active]:bg-surface data-[state=active]:text-ink data-[state=active]:shadow-[0_1px_2px_rgb(0_0_0/0.18)] data-[state=active]:ring-1 data-[state=active]:ring-line-strong"
            >
              {v.tab}
            </Tabs.Trigger>
          ))}
        </Tabs.List>
        {views.map((v) => (
          <Tabs.Content key={v.name} value={v.name}>
            <Screen view={v} />
          </Tabs.Content>
        ))}
      </Tabs.Root>
    </>
  );
}

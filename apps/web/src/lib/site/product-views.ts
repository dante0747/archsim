/**
 * The app screens the website's home page shows (ADR-0097): which README screenshot each one is,
 * the widths the capture run (e2e/capture.spec.ts) writes it at for the site, and, where part of
 * the screen stays readable on a phone, that crop. The page takes each screen's title, caption and
 * alt text from the README, so the two describe the same picture in the same words.
 *
 * Crops are in the screen's CSS pixels, 1440 × 900; the capture run takes two device pixels per
 * CSS pixel, so a crop is written at its own width and at twice it.
 */
import type { Feature } from "./readme";

export const SCREEN = { width: 1440, height: 900 } as const;

/** The widths of the whole screen a browser picks from, by the room the picture takes and the display's density. */
export const FULL_WIDTHS = [960, 1440, 1920] as const;

export interface Crop {
  x: number;
  y: number;
  width: number;
  height: number;
}

export const PRODUCT_VIEWS: Record<string, { crop: Crop | null }> = {
  // the canvas mid-run: the checkout service, its cache and payments, with live request rates
  "editor-results": { crop: { x: 450, y: 92, width: 408, height: 340 } },
  // the inspector: PostgreSQL's behaviour, and how the database did in the last run
  build: { crop: { x: 1120, y: 131, width: 320, height: 320 } },
  // the verdict, the objectives it missed, the bottleneck and the run's figures, every line whole
  scorecard: { crop: { x: 227, y: 119, width: 953, height: 342 } },
  // the cost-against-latency map says what it says only whole
  tradeoffs: { crop: null },
};

const HERO = "editor-results";
const SHOWCASE: { name: string; tab: string }[] = [
  { name: "build", tab: "Design" },
  { name: "scorecard", tab: "Results" },
  { name: "tradeoffs", tab: "Compare" },
];

/** A file the capture run writes for a view, by its path under docs/. */
export interface Rendition {
  path: string;
  width: number;
  crop: boolean;
}

export function renditions(name: string): Rendition[] {
  const view = PRODUCT_VIEWS[name];
  if (!view) throw new Error(`no product view named "${name}"`);
  const full = FULL_WIDTHS.map((width) => ({ path: `images/site/${name}-${width}.webp`, width, crop: false }));
  const crop = view.crop ? [view.crop.width, view.crop.width * 2].map((width) => ({ path: `images/site/${name}-crop-${width}.webp`, width, crop: true })) : [];
  return [...full, ...crop];
}

/** An image as the page writes it: a fallback, the candidates, and the size it is drawn at. */
export interface Picture {
  src: string;
  srcSet: string;
  width: number;
  height: number;
}

export interface ProductView {
  name: string;
  title: string;
  caption: string;
  alt: string;
  full: Picture;
  /** What a phone shows instead of the whole screen, or null when it shows the whole screen. */
  crop: Picture | null;
}

export interface ShowcaseView extends ProductView {
  tab: string;
}

export interface ProductViews {
  hero: ProductView;
  showcase: ShowcaseView[];
}

const served = (r: Rendition) => `/docs-assets/${r.path}`;

function picture(candidates: Rendition[], fallback: Rendition, width: number, height: number): Picture {
  return { src: served(fallback), srcSet: candidates.map((r) => `${served(r)} ${r.width}w`).join(", "), width, height };
}

/**
 * The hero's screen and the showcase's, from the README's feature grid and the files under docs/
 * (`assets`, as the repository lists them). A screen the README no longer shows, or a rendition
 * that was never captured, fails the build, naming it.
 */
export function productViews(features: Feature[], assets: readonly string[]): ProductViews {
  const captured = new Set(assets);
  const view = (name: string): ProductView => {
    const feature = features.find((f) => f.image.endsWith(`/${name}.jpg`));
    if (!feature) throw new Error(`README.md's Features table has no ${name}.jpg, which the website's home page shows (docs/website.md)`);
    const files = renditions(name);
    const missing = files.find((r) => !captured.has(`docs/${r.path}`));
    if (missing) throw new Error(`docs/${missing.path} is missing; the website's home page shows it and npm run e2e:capture writes it (docs/website.md)`);
    const full = files.filter((r) => !r.crop);
    const crop = files.filter((r) => r.crop);
    const area = PRODUCT_VIEWS[name].crop;
    return {
      name,
      title: feature.title,
      caption: feature.caption,
      alt: feature.alt,
      full: picture(full, full[1], SCREEN.width, SCREEN.height),
      crop: area ? picture(crop, crop[crop.length - 1], area.width, area.height) : null,
    };
  };
  return { hero: view(HERO), showcase: SHOWCASE.map(({ name, tab }) => ({ ...view(name), tab })) };
}

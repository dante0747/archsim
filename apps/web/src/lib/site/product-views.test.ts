import { describe, expect, it } from "vitest";
import { PRODUCT_VIEWS, productViews, renditions } from "./product-views";
import type { Feature } from "./readme";

const feature = (name: string, title: string): Feature => ({
  image: `/docs-assets/images/${name}.jpg`,
  alt: `The ${name} screen`,
  title,
  caption: `What ${name} shows.`,
});

const features: Feature[] = [
  feature("build", "Design with real technologies"),
  feature("editor-results", "Test with traffic and failures"),
  feature("scorecard", "Get a straight answer"),
  feature("tradeoffs", "Compare the options"),
  feature("traces", "Follow single requests"),
];

/** Every rendition the capture run writes for the views, as the repository lists docs/. */
const everyRendition = Object.keys(PRODUCT_VIEWS).flatMap((name) => renditions(name).map((r) => `docs/${r.path}`));

describe("productViews", () => {
  it("leads with the Test workspace and shows design, results and comparison, in the README's words", () => {
    const views = productViews(features, everyRendition);

    expect(views.hero).toMatchObject({ name: "editor-results", title: "Test with traffic and failures", alt: "The editor-results screen", caption: "What editor-results shows." });
    expect(views.showcase.map((v) => [v.tab, v.name, v.title])).toEqual([
      ["Design", "build", "Design with real technologies"],
      ["Results", "scorecard", "Get a straight answer"],
      ["Compare", "tradeoffs", "Compare the options"],
    ]);
  });

  it("serves each screen at the widths a browser picks from, and a phone's crop where one stays readable", () => {
    const { hero, showcase } = productViews(features, everyRendition);

    expect(hero.full).toEqual({
      src: "/docs-assets/images/site/editor-results-1440.webp",
      srcSet: "/docs-assets/images/site/editor-results-960.webp 960w, /docs-assets/images/site/editor-results-1440.webp 1440w, /docs-assets/images/site/editor-results-1920.webp 1920w",
      width: 1440,
      height: 900,
    });
    const crop = PRODUCT_VIEWS["editor-results"].crop!;
    expect(hero.crop).toEqual({
      src: `/docs-assets/images/site/editor-results-crop-${crop.width * 2}.webp`,
      srcSet: `/docs-assets/images/site/editor-results-crop-${crop.width}.webp ${crop.width}w, /docs-assets/images/site/editor-results-crop-${crop.width * 2}.webp ${crop.width * 2}w`,
      width: crop.width,
      height: crop.height,
    });
    expect(showcase.find((v) => v.name === "tradeoffs")?.crop).toBeNull();
  });

  it("fails the build when the README no longer shows a screen the home page does", () => {
    expect(() => productViews(features.filter((f) => !f.image.endsWith("/scorecard.jpg")), everyRendition)).toThrow(/README.md's Features table has no scorecard.jpg/);
  });

  it("fails the build when a rendition is missing, naming the run that makes it", () => {
    const missing = "docs/images/site/build-960.webp";

    expect(() => productViews(features, everyRendition.filter((p) => p !== missing))).toThrow(/docs\/images\/site\/build-960\.webp.*npm run e2e:capture/);
  });
});

describe("renditions", () => {
  it("names every file the capture run writes for a view, the crop at one and two pixels per point", () => {
    const crop = PRODUCT_VIEWS.build.crop!;

    expect(renditions("build").map((r) => r.path)).toEqual([
      "images/site/build-960.webp",
      "images/site/build-1440.webp",
      "images/site/build-1920.webp",
      `images/site/build-crop-${crop.width}.webp`,
      `images/site/build-crop-${crop.width * 2}.webp`,
    ]);
  });

  it("keeps every crop inside the 1440 × 900 screen it is cut from", () => {
    for (const [name, view] of Object.entries(PRODUCT_VIEWS)) {
      if (!view.crop) continue;
      const { x, y, width, height } = view.crop;
      expect(x >= 0 && y >= 0 && x + width <= 1440 && y + height <= 900, name).toBe(true);
    }
  });
});

---
status: proposed
date: 2026-11-25
decision-makers: Majid Abarghooei (owner)
---

# 0098. Give the website a stylesheet of its own, built from the files it renders

## Context and Problem Statement

archsim.io is a static export of the web app
([ADR-0078](0078-build-the-website-as-a-static-export-of-the-web-app.md)), and its layout loaded
the app's stylesheet, `globals.css`, with the site's `site.css` beside it. Tailwind builds
`globals.css` from every file of the web app (the editor, the results, the settings pages, the
tests), and the file imports React Flow's styles for the editor's canvas, which the site never
shows. So every page of the site waited for three stylesheets (the app's, the site's and the
fonts') before it could paint: 123,249 bytes, 22,479 gzipped, of which the app's was 115,024.
After the home page's redesign
([ADR-0097](0097-give-the-websites-home-page-its-own-narrative-and-take-its-facts-from-the-repository.md)),
Lighthouse on a throttled phone put the home page's first contentful paint at 4.6 seconds and its
largest contentful paint at 4.9, against the redesign brief's 2.5. Profiling showed the first
paint held back by that stylesheet and by Next.js's payload, not by the page's own content. How
can the site's pages wait for less CSS without changing the app's look, and without a second copy
of its design tokens?

## Decision Drivers

- Less CSS before the first paint, on every page of the site.
- The app's stylesheet and look stay as they are.
- One copy of the design tokens and the theme, for the app and the site.
- A component the site starts to use cannot silently lose its styles.

## Considered Options

- (a) Keep the app's stylesheet on the site
- (b) A stylesheet of the site's own, on a design system both stylesheets import
- (c) Inline the stylesheet in every page (Next.js's `experimental.inlineCss`)

## Decision Outcome

Chosen option: (b), because it takes off the site what the site never uses, keeps one source of
the tokens, and leaves the app's stylesheet as it was. (a) keeps every page waiting for the app's
classes and React Flow's styles. (c) saves the stylesheet's request, but sends all of its bytes
again with every page, where a stylesheet is fetched once for the whole site, and the bytes would
still be the app's; it can be weighed again on top of (b).

- **The design system, shared.** `apps/web/src/app/theme.css` holds what both stylesheets need:
  the tokens of both themes, Tailwind's theme built on them (`@theme` and the `dark` variant), the
  base styles and the helper classes. It is the first part of the former `globals.css`, moved word
  for word, and the reduced-motion rule.
- **The app's stylesheet.** `globals.css` imports Tailwind, which still reads classes from every
  file of the web app, React Flow's styles and `theme.css`, then keeps the app's own rules: React
  Flow's theming and the motion of the canvas and the landing pieces. Its build holds the same
  rules as before. Only the reduced-motion rule moved, ahead of the app's own rules; its
  declarations are `!important` and none of the rules it now precedes has one, so its place
  changes nothing.
- **The site's stylesheet.** `site.css` imports Tailwind with `source(none)` and names the files
  Tailwind reads classes from: the site's routes (`*.site.tsx`), `src/components/site/`,
  `src/components/accuracy/` and the six of the app's components the site renders (the logo, the
  button, the tooltip and menu pieces, the accuracy page's menu and the theme picker), tests
  excluded. It imports `theme.css`, not React Flow, and keeps the site's own layer and the
  documents' styles. The site's layout loads it alone.
- **A test keeps the list true.** `src/app/site-styles.test.ts` compiles each stylesheet the
  site's routes import the way the build does, with Tailwind's compiler and scanner
  (`@tailwindcss/node` and `@tailwindcss/oxide`, already installed with Tailwind and now named as
  development dependencies), and follows the routes' imports. It fails, naming the file, when the
  site renders a component Tailwind does not read, when Tailwind reads a file the site does not
  render, and when React Flow's styles come back.

### Consequences

- Good, because a page of the site waits for one stylesheet of 60,377 bytes (12,330 gzipped)
  instead of three of 123,249 (22,479): 51% fewer bytes, 45% fewer gzipped. Tailwind's utility
  classes fall from 79,176 bytes to 36,374, and React Flow's 16,941 are gone.
- Good, because the pages paint sooner. On a throttled phone the home page's first contentful
  paint comes at 3.6 seconds instead of 4.6 and its largest at 4.1 instead of 4.9; the getting
  started guide's at 3.3 instead of 3.6 and 3.9 instead of 4.2.
- Good, because the app's stylesheet keeps its rules, and the site's pages are drawn exactly as
  before.
- Bad, because the site's list of the app's components is kept by hand. The test names a missing
  or stale entry, and [the website's guide](../website.md) says where to add one.
- Bad, because `theme.css` carries five helper classes only the app uses now (the hero gradient,
  the brand and family tiles, the live dot and the caret: about 600 bytes on the site), so that the
  app's rules keep their order.
- Neutral, because the home page's largest contentful paint still misses the brief's 2.5 seconds.
  What a phone waits for now is mostly Next.js's: the home page's HTML is 165 KB (44 KB gzipped),
  over half of it the data React hydrates the page from, and it loads 743 KB of JavaScript (235 KB
  gzipped). This decision does not change those.

### Confirmation

`site-styles.test.ts`, above. The app's stylesheet, built before and after, holds the same 231
blocks, all in the same order but the reduced-motion rule. On all 133 pages of the export, at 375
and 1,440 pixels wide in both themes, every element's computed style (with its `::before`,
`::after` and `::marker`) and its box were the same before and after: 366,196 elements in 532
page views. Screenshots of the five main pages' first screens, each menu open, the keyboard's
focus ring and each of the home page's product tabs, 194 in all, were identical to the pixel.
`npm run e2e:site` passes.

## More Information

Measured with Lighthouse 13.6.0 on the mobile profile with DevTools throttling, the export served
locally with gzip as GitHub Pages serves it: seven runs of each page, before and after alternated,
and the median of each. On the measuring machine a security product's web filter handed Chrome the
files uncompressed (Lighthouse reported transfer sizes equal to the raw sizes), so the throttled
network carried the raw bytes, before and after alike; on GitHub Pages the stylesheet travels
gzipped. How the site is built: [the website's guide](../website.md).

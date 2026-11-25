---
status: proposed
date: 2026-11-25
decision-makers: Majid Abarghooei (owner)
---

# 0097. Give the website's home page its own narrative, and take its facts from the repository

## Context and Problem Statement

[ADR-0078](0078-build-the-website-as-a-static-export-of-the-web-app.md) built archsim.io's home page
from the app's landing components and the README's sections. It grew into eleven sections and
about 1,940 words: four equally loud calls to action, a scripted replay as the hero (not a real
run, and too small to read on a phone), a band of counts, the README's five reasons, an
explanation of the engine, eight screenshots at 800 × 500, nine feature tiles, a 44-logo marquee
drawn twice, the accuracy figures, and the license and contributing text. A visitor met a README
before learning what ArchSim is for. How should the home page say what ArchSim does, simply,
without ever saying something the repository does not?

## Decision Drivers

- A visitor can tell what ArchSim does, and what to do next, within seconds: one primary action.
- The page never states a fact the repository does not: numbers, commands, technologies and
  screenshots come from it at build time (ADR-0078's driver).
- The product shown is the product: real screens, readable on a phone.
- No new dependencies, nothing from another origin, no analytics.
- WCAG 2.2 AA, and no slower than before.

## Considered Options

- (a) Keep rendering the README's sections, and shorten the README
- (b) A narrative of the site's own, with every fact read from the repository at build time
- (c) A separate marketing site, written by hand or in a content system

## Decision Outcome

Chosen option: (b), because it lets the home page tell one short story (the problem, how it
works, what you can find out, the product, the technologies, the accuracy, how to run it) while
every number, command, technology and screenshot still comes from the repository. (a) would bend
the README, which serves contributors on GitHub, to a visitor's first minute; (c) would copy the
facts and let them drift.

- **What the site writes.** The headings, the problem, the three steps, the six capabilities and
  the calls to action are in `apps/web/src/components/site/SiteHome.tsx`. They name no figure,
  version or command.
- **What the repository says.** The README gives each screenshot's title, caption and alt text,
  the quick start's commands and the first step after them, the accuracy statement and the
  commercial license's contact (`src/lib/site/readme.ts`); the engine's catalog the featured
  technologies, which must be core profiles, and the count by tier (`src/lib/site/technologies.ts`);
  `package.json` the version, and the changelog the anchor of its release notes. The accuracy
  statement is the README's "How accurate is it?" word for word: the experiments, the table of
  median errors, what qualifies them (the engine, the comparison with bottleneck arithmetic, the
  latency criteria, why nothing is validated yet) and the caveat. A home page that counted its own
  figures from `src/data/accuracy.json` showed only the favourable ones and mixed latest runs with
  the README's medians; the full figures stay on `/accuracy`. A missing section, screenshot,
  rendition or technology fails the build, naming it. The site no longer reads the README's "Why
  ArchSim", "How it works", "Contributing" and "License" sections; the footer links the license,
  the contributor agreement and the contributing guide instead of repeating them.
- **Real screens.** The capture run (`npm run e2e:capture`) shoots at two device pixels per CSS
  pixel and also writes the website's renditions of four of the README's screenshots as WebP under
  `docs/images/site/`: each screen at 960, 1,440 and 1,920 pixels, and for three of them a crop
  that stays readable on a phone (`src/lib/site/product-views.ts` names the crops). The hero shows
  the Test workspace mid-run; three tabs show design, results and the comparison of options. The
  link check now reads `srcset`.
- **The app keeps its landing pieces.** The site stops using the hero's scripted replay, the
  feature grid, the how-it-works vignettes, the logo marquee and the reveal-on-scroll wrapper; the
  app's projects home still does.
- **One system.** `src/app/site.css` adds the site's type scale, measure and spacing to the app's
  tokens; `Cta` and `CtaGroup` are its only buttons: one primary and at most one secondary, 48
  pixels tall, stacked at full width on a phone and side by side from 640 pixels.

### Consequences

- Good, because the page says what ArchSim does in one sentence under the headline, has one
  primary action, and is about 60% shorter in words (about 1,940 before, about 750 after).
- Good, because the screens are real, sharp and readable on a phone, where the replay was
  illustrative and shrank to unreadable.
- Good, because the page sends less JavaScript (the replay's 30-frames-a-second loop and the icon
  library are gone) and axe-core finds no violations where it found three.
- Bad, because the narrative can drift from the product as features change; the page keeps numbers
  out of it, and a change to what ArchSim does should look at `SiteHome.tsx` as well as the README.
- Bad, because about 0.9 MB of WebP renditions live in the repository and must be recaptured when
  the screens change, with the README's images, by the same run.
- Bad, because on a phone the page's largest element is now an image rather than text, which a
  throttled lab run paints a little later.
- Bad, because the accuracy statement now depends on the README section's shape (a paragraph, the
  table, a paragraph and a quote); changing that shape fails the site's build until it follows.

### Confirmation

Vitest covers the README reading, the product views, the featured technologies and the home
page's structure (`SiteHome.test.tsx`: one primary action, the order of the story, three product
views, the accuracy caveat). `e2e-site/home.spec.ts` checks, in a browser, that the page never
scrolls sideways at eleven widths from 320 to 1,920 pixels, that the calls to action are drawn
48 pixels tall and 12 apart, stacked on a phone and side by side from a tablet, that the header
stays one bar, that the phone's menu traps focus and closes with Escape, that the tabs switch by
arrow key without moving the page, and that the quick start copies. `npm run build:site` fails on
a missing rendition or a broken link.

## More Information

How the site is built, and what the home page reads: [docs/website.md](../website.md).
Supersedes the "Home" paragraph of [ADR-0078](0078-build-the-website-as-a-static-export-of-the-web-app.md);
the rest of that decision stands.

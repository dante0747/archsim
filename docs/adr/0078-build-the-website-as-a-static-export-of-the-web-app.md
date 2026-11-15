---
status: accepted
date: 2026-11-15
decision-makers: Majid Abarghooei (owner)
---

# 0078. Build archsim.io as a static export of the web app, from the repository's own docs

## Context and Problem Statement

ArchSim is public as of 1.0.0 and needs a website at `https://archsim.io`, served by GitHub Pages:
static files, no server, no response headers. The web app (`apps/web`) already has the landing
pieces (the hero's replay, how it works, the feature grid, the logo wall) and a static accuracy
page, and the documentation is Markdown under `docs/`, linked the way GitHub reads it. How is the
site built so that it looks like the app and never says something the app, the docs or the
measured numbers don't?

## Decision Drivers

- One source for each thing: the app's components and theme, the docs as written, the accuracy
  data, the catalog and the templates.
- GitHub Pages: plain files, a `CNAME`, a `404.html`; no headers, rewrites or server.
- No analytics and nothing from another origin: fonts, scripts and images come from the site.
- The app's build, its Docker image ([ADR-0066](0066-ship-the-web-image-without-an-image-optimizer.md)),
  its tests and its end-to-end tests stay as they are.
- Few new dependencies, permissive licenses only, no telemetry.

## Considered Options

- (a) A second, static build of `apps/web`: `ARCHSIM_SITE=1` switches `next.config.ts` to
  `output: "export"` with `pageExtensions: ["site.tsx", "site.ts"]`, so only `*.site.tsx` files are
  its routes, and the app's build sees none of them
- (b) A separate Next.js app (`apps/site`) importing the web app's components across folders
- (c) A documentation generator (Docusaurus, Astro Starlight, VitePress) with a landing page of its own
- (d) GitHub's Jekyll over `docs/`

## Decision Outcome

Chosen option: (a), because the site is the app's own components, tokens and theme script, built by
the same toolchain, and nothing is copied. (b) needs a second package, lock file and Tailwind set-up
that would see the app's sources from outside; (c) and (d) would restyle the docs in another look
and leave the landing pieces behind.

- **Routes.** `src/app/layout.site.tsx`, `page.site.tsx` (home), `docs/page.site.tsx` and
  `docs/[...slug]/page.site.tsx` (every Markdown file under `docs/`, all 77 decision records
  included, a few hundred milliseconds each), `accuracy/page.site.tsx` (the app's `AccuracyPage`,
  which now takes the page's header and footer), `changelog/page.site.tsx` (`CHANGELOG.md`),
  `not-found.site.tsx` (`404.html`), and route handlers for `docs-assets/[...path]` (the images
  under `docs/`), `sitemap.xml`, `robots.txt` and `CNAME`. Next.js 16.4 with Turbopack exports all
  of these; it ignores custom page extensions for `sitemap.ts`-style metadata files, hence handlers.
- **Docs.** `src/lib/site/` renders Markdown at build time with unified (remark-parse, remark-gfm,
  remark-rehype, rehype-slug, rehype-highlight, rehype-stringify; MIT, highlight.js BSD-3-Clause).
  Raw HTML is dropped. A link to a document becomes its page (`docs/x.md` → `/docs/x/`,
  `CHANGELOG.md` → `/changelog/`), an image under `docs/` is served from `/docs-assets/`, and any
  other path links to the file or folder on GitHub at main. Headings keep GitHub's anchors. The
  navigation is `docs/README.md`'s sections and tables. Mermaid blocks show their source with a
  link to GitHub's drawing: drawing them would need a headless browser in the build or a large
  script on the page.
- **Home.** The facts come from the repository: the README's reasons, screenshots, quick start,
  how it works, license and contributing sections; the accuracy page's data; the engine's generated
  catalog (`src/test/fixtures/catalog.json`, which archsim-engine's `GeneratedFileTests` keeps
  current); `examples/`. A README section the home page reads that is missing fails the build.
- **Look and privacy.** The app's tokens, light and dark, and its theme script (dark by default);
  Geist served by `next/font` from the site; a content security policy in a meta tag allowing only
  the site's origin. "Try it live" appears only when the build sets `ARCHSIM_SITE_DEMO_URL` (the
  workflow passes the repository variable of that name).
- **Build.** `npm run build:site` (`scripts/build-site.mjs`) runs the build, then checks the export.
  Next.js keeps a static export's build in `.next`, so the script sets the app's `.next` aside and
  puts it back. The site's build skips type checking: the app's typed routes don't exist there, and
  `npm run typecheck` checks the site's files with the app's. On Windows, Next.js 16.4 writes the
  router's prefetch files into folders (`path.relative` joins with backslashes); the script names
  them as the router asks, and does nothing to a Linux build.
- **Deployment.** `.github/workflows/site.yml` builds on pull requests and pushes to main that
  touch what the site reads, and deploys main with `actions/upload-pages-artifact` and
  `actions/deploy-pages`. The repository's Pages source must be GitHub Actions, with `archsim.io`
  as its custom domain.

### Consequences

- Good, because the docs, the changelog and every number on the site are main's, rendered at
  build time, and a broken link, a missing anchor or a link to a file that no longer exists fails
  the build of the pull request that causes it.
- Good, because a change to a landing component shows in the app and on the site alike.
- Bad, because `*.site.tsx` is a convention a reader must learn: those files are routes in one
  build and ignored in the other.
- Bad, because the home page now depends on the README's section headings; renaming one fails the
  site's build, with the section named, until the site follows.
- Bad, because a change to `docs/` runs the site's workflow but no CI job, so the site's checks,
  not CI's, catch a docs change that breaks it.

### Confirmation

Vitest covers the link rewriting, the Markdown rendering, the README and navigation reading, the
accuracy highlights and the site's components. `scripts/site-check.mjs` checks every link, anchor,
image and script of the export, every link to the repository and every sitemap entry, and refuses
resources from another origin. `npm run e2e:site` opens every page at 375 and 1440 px wide in both
themes and fails on sideways scrolling or a browser error. The app's `npm run build` lists the same
routes as before.

## More Information

How to build and preview the site: [docs/website.md](../website.md). Related: the accuracy page's
data ([ADR-0004](0004-prove-accuracy-on-sealed-held-out-experiments.md)), the license the site
states ([ADR-0067](0067-license-under-agpl-3.0-with-a-commercial-license.md)).

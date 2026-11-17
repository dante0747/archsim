# The website

[archsim.io](https://archsim.io) is a static export of the web app, built from this repository on
every change to main and served by GitHub Pages
([ADR-0078](adr/0078-build-the-website-as-a-static-export-of-the-web-app.md)). It has no content
of its own to keep up to date: it shows what the repository says.

| Page | Built from |
| --- | --- |
| Home (`/`) | The app's landing components (`apps/web/src/components/landing/`), the README's reasons, screenshots, quick start, how it works, license and contributing sections, the accuracy data, the engine's catalog and `examples/` |
| Docs (`/docs/...`) | Every Markdown file under `docs/`, the decision records included; the navigation is [the docs index](README.md) |
| Accuracy (`/accuracy/`) | The app's accuracy page, from `apps/web/src/data/accuracy.json` |
| Changelog (`/changelog/`) | [CHANGELOG.md](../CHANGELOG.md) |

## How it is built

The site's pages are the files named `*.site.tsx` and `*.site.ts` under `apps/web/src/app/`.
`ARCHSIM_SITE=1` switches `next.config.ts` to a static export of those files only; the app's own
build never sees them. The Markdown is rendered at build time (`apps/web/src/lib/site/`): a link to
another document becomes a link to its page, an image under `docs/` is served by the site, and a
link to any other file goes to it on GitHub, at main. Write links as GitHub reads them, relative to
the document, and both GitHub and the site follow them.

The home page reads the README's sections by their headings: "Why ArchSim", "Features" (the
screenshot table and the list after it), "Quick start", "How it works", "Contributing" and
"License". Rename or remove one and the site's build fails, naming it.

## Building and previewing it

From `apps/web`:

```bash
npm ci
npm run build:site     # the site in out/, every link checked
npm run serve:site     # out/ as GitHub Pages serves it: http://127.0.0.1:4000
npm run dev:site       # or edit with live reload: http://localhost:3001
npx playwright install chromium   # once
npm run e2e:site       # every page on a phone and a desktop, light and dark (after build:site)
```

`npm run build:site` fails on a broken link, a missing anchor, a link to a repository file that does
not exist, a sitemap entry without a page, or anything loaded from another origin. It sets the
app's own build (`.next`) aside while it runs and puts it back, so `npm run start` and the
end-to-end tests still find it. `SITE_SCREENSHOTS=1 npm run e2e:site` also saves full-page
screenshots of the main pages under `apps/web/test-results/site/`. `npm run dev:site` and the app's
`npm run dev` share `.next/dev`, so run one at a time.

Set `ARCHSIM_SITE_DEMO_URL` to an https URL to show "Try it live" links to a hosted demo; without
it they are hidden.

## What search engines see

- **Each page** has its title, a description, its canonical URL on archsim.io and a link preview
  (`apps/web/src/lib/site/site.ts`). A document's description is its first paragraph of prose, less
  a closing line that leads into a list with a colon, cut at about 155 characters; to change what a
  search result says about a page, change that paragraph.
- **Structured data** (schema.org JSON-LD, `apps/web/src/lib/site/structured-data.ts`): the home
  page describes ArchSim as free software at the version in `apps/web/package.json`, and each
  document is an article with the breadcrumbs that lead to it.
- **`sitemap.xml`** lists every page, each with the date of the last commit to the file it is made
  from. It needs the repository's history: a shallow clone leaves the dates out, so the site's
  workflow checks out the full history (commits and trees, without old file contents).
- **IndexNow.** After each deploy, the workflow sends the sitemap's pages to IndexNow
  (`apps/web/scripts/indexnow.mjs`), which passes them to Bing and the other search engines that
  take part. Its key is public by design: the site serves it at `/<key>.txt` to prove the pings
  are the site's own. A ping IndexNow refuses is a warning in the run, never a failed deploy.

## Deploying it

[`.github/workflows/site.yml`](../.github/workflows/site.yml) builds and checks the site on every
pull request that changes what it reads (`apps/web/`, `docs/`, `examples/`, the README, the
changelog and `NOTICE`), and on a push to main it also deploys it with GitHub's Pages actions. A run
by hand from the Actions tab on main rebuilds and deploys. CI's `Website (build, links, browsers)`
job runs the same build and browser checks whenever a change touches what the site reads or
deletes a file, and on every CI run started by hand, so a broken link fails before a change reaches
main, not when main deploys ([ci.md](ci.md#which-jobs-a-change-runs)). The repository variable
`ARCHSIM_SITE_DEMO_URL` turns on the "Try it live" links.

The repository's settings, once: Pages' source is **GitHub Actions**, and its custom domain is
`archsim.io` with HTTPS enforced. The export carries a `CNAME` file naming it. The domain's DNS
points `archsim.io` at GitHub Pages' addresses (A and AAAA records) and `www.archsim.io` at
`dante0747.github.io` (a CNAME record), as GitHub's
[custom domain guide](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site)
lists them.

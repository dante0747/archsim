# The website

[archsim.io](https://archsim.io) is a static export of the web app, built from this repository on
every change to main and served by GitHub Pages
([ADR-0078](adr/0078-build-the-website-as-a-static-export-of-the-web-app.md)). Its facts are the
repository's: the home page tells its own short story, but every number, command, technology and
screenshot on it is read from the repository at build time
([ADR-0097](adr/0097-give-the-websites-home-page-its-own-narrative-and-take-its-facts-from-the-repository.md)).

| Page | Built from |
| --- | --- |
| Home (`/`) | The site's own components (`apps/web/src/components/site/`), the README's screenshots, quick start and accuracy statement, the renditions under `docs/images/site/` and the engine's catalog |
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

The home page reads the README's sections by their headings: "Features" (the screenshot table:
each screen's title, caption and alt text), "Quick start" (what you need, the commands and the
first sentence after them), "How accurate is it?" (the paragraph leading into the table, the table,
and the paragraph after it and the quoted caveat, each up to its first link) and "License" (who to
ask for a commercial license). The accuracy statement is the README's, word for word, so the home
page says what the README says about the experiments, the comparison with bottleneck arithmetic,
the criteria and the caveat; update it there with each campaign. Rename or remove
one and the site's build fails, naming it. Its narrative (the headings, the problem, the steps and
the capabilities) is written in `SiteHome.tsx` and names no figure, version or command; when what
ArchSim does changes, look at it as well as the README.

### The product screens

The home page shows four of the README's screenshots, as sharp renditions under
`docs/images/site/`: the Test workspace in the hero, and the Design workspace, the Results answer
and the Compare map in its tabs. `apps/web/src/lib/site/product-views.ts` names them, the widths
each is served at, and the crop a phone shows where part of the screen stays readable. The capture
run writes them with the README's images, at two device pixels per CSS pixel:

```bash
npm run build && npm run e2e:capture    # needs PostgreSQL, the API's jar and ffmpeg (e2e/capture.spec.ts)
```

A rendition that is missing fails `npm run build:site`, naming the file. The featured technologies
are core profiles of the engine's catalog (`apps/web/src/lib/site/technologies.ts`); one that is
gone or no longer core fails the build too.

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

`npm run build:site` fails on a broken link or image (each candidate of a `srcset` too), a missing
anchor, a link to a repository file that does not exist, a sitemap entry without a page, or anything
loaded from another origin. It sets the
app's own build (`.next`) aside while it runs and puts it back, so `npm run start` and the
end-to-end tests still find it. `SITE_SCREENSHOTS=1 npm run e2e:site` also saves full-page
screenshots of the main pages under `apps/web/test-results/site/`. `npm run dev:site` and the app's
`npm run dev` share `.next/dev`, so run one at a time.

Set `ARCHSIM_SITE_DEMO_URL` to an https URL to send "Try ArchSim" to a hosted demo; without it,
"Try ArchSim" goes to the home page's quick start. `e2e-site/home.spec.ts` checks the home page at
eleven widths from 320 to 1,920 pixels: no sideways scrolling, the calls to action as drawn, the
header, the phone's menu, the product tabs, the copy button and the images.

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
`ARCHSIM_SITE_DEMO_URL` points "Try ArchSim" at the hosted demo.

The repository's settings, once: Pages' source is **GitHub Actions**, and its custom domain is
`archsim.io` with HTTPS enforced. The export carries a `CNAME` file naming it. The domain's DNS
points `archsim.io` at GitHub Pages' addresses (A and AAAA records) and `www.archsim.io` at
`dante0747.github.io` (a CNAME record), as GitHub's
[custom domain guide](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site)
lists them.

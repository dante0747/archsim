---
status: proposed
date: 2026-11-25
decision-makers: Majid Abarghooei (owner)
---

# 0098. Serve the website at a second address, archsim.coderator.dev, from Cloudflare Pages

## Context and Problem Statement

archsim.io was registered in November 2026, and some company networks block every domain
registered in the last weeks or months: people on them cannot open the site or its docs. GitHub
Pages, which serves the site
([ADR-0078](0078-build-the-website-as-a-static-export-of-the-web-app.md)), gives a site one custom
domain. The owner's older domain, coderator.dev, is not new, and its DNS stays at its registrar.
How can the site also be reached at archsim.coderator.dev, without a second copy of it to keep in
step?

## Decision Drivers

- Both addresses serve the same pages, from the same build, deployed by the same run.
- archsim.io stays the site's address for search engines: the second address must not compete
  with it.
- The pages stay static: no server or proxy code between a reader and the files.
- Nothing new to pay for, and no secret beyond a deploy token scoped to one task.

## Considered Options

- Cloudflare Pages serves the build's files at archsim.coderator.dev, deployed by the site's
  workflow
- A second GitHub Pages site, from a second repository
- A Cloudflare Worker at archsim.coderator.dev that fetches each page from archsim.io
- A redirect from archsim.coderator.dev to archsim.io

## Decision Outcome

Chosen option: "Cloudflare Pages serves the build's files", because it serves the very files
GitHub Pages serves, from one build and one run, with no new repository and no code in the path of
a request. Pages takes a custom subdomain whose DNS is elsewhere through a CNAME record, so
coderator.dev's DNS, mail forwarding included, stays where it is. Cloudflare already holds
archsim.io's DNS and stands in front of the demo
([ADR-0080](0080-host-a-public-server-from-a-compose-kit.md)), so it is not a new provider, and
its free plan covers a static site of this size.

- The site workflow's `mirror` job downloads the build job's Pages artifact and deploys it with
  Wrangler, at an exact version, to the Pages project `archsim`, whose custom domain is
  archsim.coderator.dev. It runs on main only, from the environment `cloudflare-pages`, whose
  secret is an API token that may edit Cloudflare Pages and nothing else, and it is skipped until
  the repository variable `CLOUDFLARE_ACCOUNT_ID` is set.
- Every page keeps archsim.io as its canonical URL, and the sitemap and `robots.txt` name
  archsim.io, so search engines read the pages at archsim.coderator.dev as copies.
- A link in the docs to archsim.io itself becomes a path on the site, so a reader stays at the
  address they opened.
- The privacy page names both hosts.

### Consequences

- Good, because readers on a network that blocks new domains can read the site and the docs.
- Good, because the second address cannot drift: it serves the artifact archsim.io serves,
  deployed by the same run.
- Bad, because a second host sees readers' requests, under Cloudflare's privacy policy; the
  privacy page says so.
- Bad, because a deploy needs a Cloudflare token in GitHub, and Wrangler is bumped by hand.
- Neutral, because "Try ArchSim" still opens the demo at app.archsim.io, which such networks block
  too: the demo gets no second address here.

### Confirmation

`links.test.ts` covers the rewriting of links to the site itself, and `site-check.mjs` still
checks every link of the export. After a deploy, both addresses serve the same `index.html` and the
same canonical URLs.

## Pros and Cons of the Options

### A second GitHub Pages site, from a second repository

- Good, because the site stays on GitHub alone.
- Bad, because it needs a second public repository, and either a token that may push to it or a
  second build on a schedule, for a copy of what this repository already builds.

### A Cloudflare Worker that fetches each page from archsim.io

- Good, because nothing is deployed twice.
- Bad, because a Worker takes only host names whose DNS is on Cloudflare: coderator.dev's DNS
  would have to move.
- Bad, because it is code in the path of every request, which must rewrite the host GitHub Pages
  expects and the redirects it sends back.

### A redirect from archsim.coderator.dev to archsim.io

- Bad, because the browser follows it to archsim.io, which those networks block.

## More Information

How the site is deployed and how the second address is set up: [docs/website.md](../website.md).
Related: [ADR-0078](0078-build-the-website-as-a-static-export-of-the-web-app.md) (the site, on
GitHub Pages), [ADR-0080](0080-host-a-public-server-from-a-compose-kit.md) (Cloudflare in front of
the demo).

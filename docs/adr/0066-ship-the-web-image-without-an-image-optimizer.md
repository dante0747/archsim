---
status: accepted
date: 2026-11-12
decision-makers: Majid Abarghooei (owner)
---

# 0066. Ship the web image without Next.js's image optimizer, and so without sharp and libvips

## Context and Problem Statement

The web image is Next.js's standalone output. Next.js's image optimizer, which serves
`next/image` at `/_next/image`, loads sharp, and Next.js traces sharp into the standalone output
with its prebuilt native binaries: in the Alpine image, `@img/sharp-libvips-linuxmusl-x64`, under
the GNU Lesser General Public License 3.0 or later. The stage 6 license audit found it there.
The app shows no image through `next/image`: its icons are files Next.js serves as they are, and
its one generated image, a shared run's preview (`opengraph-image.tsx`), is drawn by `next/og`,
which renders with Satori and WebAssembly and never loads sharp. So the image carries a native
LGPL library that it never runs. Should it?

## Decision Drivers

- Ship only what runs: a smaller image, and no license to honour for unused code.
- No npm change: sharp is an optional dependency of `next`, and the native binaries the build
  itself needs (Tailwind's, Lightning CSS's) are optional dependencies too.
- A check that fails when a Next.js release brings sharp back.

## Considered Options

- Turn the optimizer off and leave sharp out of the standalone output, in `next.config.ts`
- Keep sharp, and give notice of libvips's license (as `NOTICE` did)
- Install without optional dependencies in the image build

## Decision Outcome

Chosen option: "turn the optimizer off and leave sharp out", because it ships nothing the app
does not run and changes only configuration.

- `next.config.ts` sets `images: { unoptimized: true }`: an image through `next/image` would be
  served as it is, and `/_next/image` answers 404 without loading sharp.
- It sets `outputFileTracingExcludes: { "*": ["node_modules/sharp/**", "node_modules/@img/**"] }`.
  The key is `"*"`, not the documented `"/*"`: only `"*"` also matches `next-server`, the
  server's own trace, which is the one that pulls in sharp.
- `apps/web/scripts/check-standalone.mjs` fails when sharp, `@img` or a libvips file is
  anywhere in the standalone output; the Dockerfile runs it after the build, and so does CI's web
  job.

### Consequences

- Good, because the web image carries no LGPL code and is smaller, and `NOTICE` names no
  library the app does not use.
- Good, because a Next.js release that traces sharp in again fails the build, not the release.
- Bad, because an image added through `next/image` later is served at its own size: a page that
  needs resized images has to bring the optimizer back, with sharp, its notice, and this ADR
  superseded.

### Confirmation

`check-standalone.mjs` passes on the standalone output and fails on one built without the two
settings (it then lists `sharp` and `@img`). The end-to-end test `e2e/images.spec.ts` asks
`/_next/image` for the app's icon and gets 404, where it got 200 before, and gets the icon itself
with 200.

## Pros and Cons of the Options

### Keep sharp, and give notice of libvips's license

- Good, because `next/image` would work fully if a page used it.
- Bad, because the image ships a native library nothing runs, and the LGPL's terms with it.

### Install without optional dependencies in the image build

- Good, because nothing optional lands in the image at all.
- Bad, because the build's own native tools are optional dependencies: `npm ci --omit=optional`
  leaves out Tailwind's and Lightning CSS's binaries, and the build fails.

## More Information

- [`NOTICE`](../../NOTICE) lists what the web image ships, and no longer sharp or libvips.
- Next.js's documentation of `images.unoptimized` and of `outputFileTracingExcludes` (output
  file tracing, for the standalone output).
- The stage 6 license audit found sharp's libvips in the image; the same audit's follow-up names
  in `NOTICE` the MPL-2.0 code Next.js bundles for `next/og`, which the image does run.

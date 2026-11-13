---
status: accepted
date: 2026-11-13
decision-makers: Majid Abarghooei (owner)
---

# 0067. License ArchSim under the AGPL-3.0, and offer a commercial license

## Context and Problem Statement

ArchSim's `LICENSE` carried the Apache License 2.0 from the first commit, and it was to be
published under it. Before publishing, the owner asked for a license under which companies that
make money with ArchSim pay for it, while the project stays open. No license the Open Source
Initiative approves forbids commercial use, so the question is which license best serves both:
wide use by engineers, at work too, and a reason for companies that build on ArchSim to buy a
license.

## Decision Drivers

- Engineers can try and use ArchSim at work without a contract: that is how a developer tool
  spreads.
- A company that builds ArchSim into its product, or runs a changed ArchSim as a service, either
  shares its source or pays.
- A competitor can't take ArchSim and sell a closed copy.
- A license the Open Source Initiative approves, which developers trust and which open-source
  programs and listings ask for.
- A standard license that lawyers know, not a custom one.

## Considered Options

- Apache License 2.0, as before
- MIT License
- PolyForm Noncommercial 1.0.0, with a commercial license
- Functional Source License 1.1 or Business Source License 1.1
- GNU AGPL-3.0, with a commercial license

## Decision Outcome

Chosen option: "GNU AGPL-3.0, with a commercial license", because it is the only option that
stays open source and still gives a company that embeds or hosts ArchSim a reason to pay.

- `LICENSE` holds the AGPL-3.0's text as the Free Software Foundation publishes it, and the
  license identifier is `AGPL-3.0-only`: version 3, not "or any later version", so a later
  version of the license, whose terms nobody can foresee, never applies.
- The copyright holder also offers ArchSim under a commercial license. A lawyer drafts its terms
  before the first one is sold.
- Every commit in the history carries the AGPL-3.0. The history was rewritten on 13 Nov 2026,
  before the repository was published, so that no earlier commit offers ArchSim under the Apache
  License 2.0.
- Third-party code stays under its own licenses, each compatible with the AGPL-3.0. Where a
  library offers a choice, ArchSim takes the one compatible with the GPL: Logback under the
  LGPL-2.1, the Jakarta Annotations API under the GPL-2.0 with the Classpath Exception, and elkjs
  under the GPL-3.0-or-later (`NOTICE`).
- A contribution from outside the project is merged only under a Contributor License Agreement,
  which lets the copyright holder license it both ways.

### Consequences

- Good, because anyone may use ArchSim for any purpose, at work included, and study, change and
  share it.
- Good, because a company that ships ArchSim inside a product, or offers a changed ArchSim over a
  network, must publish its source under the AGPL-3.0 or buy the commercial license. Many
  companies' policies rule the AGPL out, so they buy.
- Bad, because a company that only runs ArchSim, unchanged, for its own use owes nothing: the
  revenue comes from embedding, hosting and support, not from every commercial user.
- Bad, because the AGPL-3.0 puts some companies off using ArchSim at all.
- Bad, because outside contributions need a signed Contributor License Agreement, which is not
  written yet: until it is, outside pull requests can't be merged (`CONTRIBUTING.md`).
- Bad, because the commercial license needs a lawyer to draft it.

### Confirmation

`LICENSE` is the AGPL-3.0's text, unchanged. `NOTICE`, `README.md`, `CONTRIBUTING.md`, the web
app's `package.json` and its footer name the license. A new dependency's license is checked for
compatibility with the AGPL-3.0 when it is added, as `NOTICE` lists it.

## Pros and Cons of the Options

### Apache License 2.0, as before

- Good, because it is the most widely accepted license in the Java world, with a patent grant.
- Bad, because anyone, competitors included, may sell ArchSim, or a closed product built on it,
  and owe nothing.

### MIT License

- Good, because it is the shortest and simplest license.
- Bad, because, like the Apache License, it lets anyone sell ArchSim closed, and it has no
  explicit patent grant.

### PolyForm Noncommercial 1.0.0, with a commercial license

- Good, because every commercial use needs a paid license.
- Bad, because an engineer can't even try ArchSim at work without a contract, which keeps a
  developer tool from spreading.
- Bad, because it is not an open-source license, so the project couldn't call itself open source.

### Functional Source License 1.1 or Business Source License 1.1

- Good, because companies may use ArchSim but not sell a competing product, and each version
  becomes Apache-2.0 after two years (FSL) or up to four (BUSL).
- Bad, because neither is an open-source license, and the revenue comes only from stopping
  competitors.

## More Information

- The GNU AGPL-3.0: https://www.gnu.org/licenses/agpl-3.0.html
- `NOTICE` lists the third-party software the images bundle, under their own licenses.

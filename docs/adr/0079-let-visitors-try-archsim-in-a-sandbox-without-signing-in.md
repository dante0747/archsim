---
status: accepted
date: 2026-11-16
decision-makers: Majid Abarghooei (owner)
---

# 0079. Let visitors try ArchSim in a sandbox for a day, without signing in

## Context and Problem Statement

A public demo runs at `https://app.archsim.io` in hosted mode (`ARCHSIM_AUTH_MODE=oauth`). Today a
visitor must sign in with GitHub or Google before trying anything, which loses most of the people
who only want to look. The ground work for something smaller exists: the workspace kind `sandbox`
with its own limits (`WorkspaceLimits`: one run at once, 20 an hour, 200,000 requests and 20 s a
run, five projects), calibration that never calls out from a sandbox, and the web app's
`WorkspaceKind`. Nothing creates a sandbox for a visitor. How does an anonymous visitor get one,
without opening the server to abuse, and what may they do in it?

## Decision Drivers

- One click, no account: the demo's first minute.
- Nothing new for anyone who signs in: sessions, CSRF, tokens and roles work as
  [ADR-0065](0065-move-sign-in-to-spring-security-after-the-port.md) has them.
- Bounded cost: an anonymous visitor must not be able to fill the database or the workers.
- Anonymous content is not published under the demo's name.
- Off by default; a server that does not ask for it is unchanged.

## Considered Options

- (a) A guest account with an expiry, its own sandbox and a normal browser session, started by
  `POST /auth/sandbox`; a guest is marked by `users.expires_at`.
- (b) The same, with no new column: a guest is "a member of a sandbox workspace", and the expiry is
  the workspace's `created_at` plus a day.
- (c) No account: a sandbox reached through a secret link or a signed cookie, outside the session
  machinery.
- (d) A shared, read-only demo workspace that everyone sees.

## Decision Outcome

Chosen option: (a), because it reuses the session cookie, the CSRF filter, workspace roles and the
sandbox's limits as they are, and marks a guest explicitly, so no rule has to guess who is one.

- **The setting.** `ARCHSIM_SANDBOXES=true` (`SignInProperties.sandboxes`, default `false`) offers
  sandboxes; it means something only with `ARCHSIM_AUTH_MODE=oauth`, and is ignored without. Its
  lifetime (24 hours), the cap (500 guests) and the limit per address (5 an hour) are constants, as
  the sandbox's other limits are; they can become settings when an operator needs another value.
- **Starting one.** `POST /api/v1/auth/sandbox` makes, in one transaction, a guest account (name
  "Guest", no identity, no email, `users.expires_at` a day ahead), a sandbox workspace it owns, and
  a browser session that ends when the sandbox does. It answers `201` with what `GET /auth/me`
  would say, and sets the session and CSRF cookies, as a sign-in's callback does. Where sandboxes
  are not offered it answers `404`; to a browser signed in already, `409`.
- **CSRF and login CSRF.** A start signs no one in yet, but it sets a session cookie, so another
  site could otherwise make a visitor's browser start one and sign it out of its real account. The
  sign-in start is protected by its state cookie; this start is protected by the CSRF filter it
  already has: `SandboxOffer` adds `POST /auth/sandbox` to the requests the filter checks, so it
  must send `X-ArchSim-CSRF` equal to the `__Host-archsim_csrf` cookie, which is `SameSite=Strict`
  and which only this site's pages can read. A signed-out browser gets that cookie from
  `GET /auth/config`, which also returns the token as `csrfToken` where sandboxes are offered.
- **Abuse.** Starts are counted per address with the existing counters (`ip:<address>:sandboxes`,
  5 an hour, `429` with `Retry-After`; off with the other rate limits when
  `ARCHSIM_RATE_LIMITS=false`), before the admission's transaction begins, so a start holds one
  database connection at a time (the counters take a transaction of their own) and a flood of
  starts cannot starve the pool. The server keeps at most 500 guests, expired ones not yet
  deleted included; at the cap a start answers `503` with `Retry-After` until the first guest
  goes. Admissions take turns on an advisory lock, so two cannot both take the last place. Each
  sandbox keeps its kind's limits, and never calls out to Prometheus.
- **Expiry.** `SandboxPruning`, in the API role, runs every minute, like the rate limits' pruning:
  each expired guest is deleted in a transaction of its own, and one that fails is logged and
  left for the next prune without holding up the others; their sandbox goes first (the existing
  cascades take its memberships, projects, runs, share links, comments and history), then the
  account (and its sessions). A run in progress is not cancelled separately: as when a team
  workspace is deleted, its worker finds the row gone at its next heartbeat and stops it. It
  prunes whatever the setting says now, so sandboxes made before it was turned off still go.
- **What a guest may do.** Anything an owner may do in their sandbox, within its limits. Not what
  takes an account of one's own: a guest is never a `Person` (`Principal.requirePerson` refuses
  them as it refuses API tokens), so they make no team workspaces, API tokens or invitations, and
  accept no invitation (so cannot be invited). **Share links are refused** (`Principal.requireAccount`
  in `ShareLinks`, `403`): a share page is public, and would put an anonymous visitor's text on
  the demo's address with no one accountable for it; it would also die with the sandbox a day
  later. "Open a copy" of someone else's share link into a sandbox is allowed.
- **What the web app reads.** `GET /auth/config` adds `sandboxes` and `csrfToken`; `GET /auth/me`'s
  `user` adds `guest` and `expiresAt`. The sign-in page offers "Try it without signing in" where
  sandboxes are on; in a sandbox a quiet strip under the header says when the sandbox and its
  designs are deleted, with "Sign in for a workspace of your own". Signing in from a sandbox
  signs in as usual and starts a personal workspace; the sandbox stays behind until it expires,
  and no message promises otherwise (a refused guest is told "sign in first").

**Status (27 Nov 2026):** renaming a workspace, changing a member's role, removing a member and
revoking an invitation take a person, as making a workspace or an invitation does, so a guest no
longer renames the sandbox either.

### Consequences

- Good, because a visitor tries the real app, with the same API and UI, in one click.
- Good, because guests go through the same session, CSRF and role checks as everyone; the new
  rules are two methods on `Principal` and one CSRF matcher.
- Good, because a guest is explicit in the schema (`users.expires_at`), so pruning, the cap and
  the `Person` check are each one query or one field.
- Bad, because it adds a migration (V2), so a worker of an older build refuses a database the new
  API migrated, as for any schema change.
- Bad, because the cap is a denial of service in itself: someone with many addresses can keep all
  500 places taken for a day. The limit per address and the five-project cap bound the cost of
  that, not its nuisance; sign-in still works.
- Bad, because work done in a sandbox is lost when it expires. Moving a sandbox's projects into the
  personal workspace at sign-in is left as a follow-up (below).

### Confirmation

`SandboxTests`, `GuestTests`, `SandboxPruningTests`, `SandboxesOffTests` and `SandboxOfferTests`
(archsim-app) run the application on PostgreSQL; the black-box suite's `SandboxTests` checks the HTTP contract
against a started API with the hosted profile, which now sets `ARCHSIM_SANDBOXES=true`. The web
app's `sandbox.test.tsx` and `store/auth.test.ts` check the sign-in page, the strip and the store.

## Pros and Cons of the Options

### (b) No new column

- Good, because the schema stays at V1.
- Bad, because "a member of a sandbox" is not "a guest": tests and older data put signed-in people
  in sandboxes, and they would lose their tokens and team workspaces. Every rule would infer
  guest-ness from memberships, a join on every check.

### (c) No account

- Good, because no rows exist until the visitor saves something.
- Bad, because every endpoint resolves a `Principal` from a session or a token; a third kind of
  credential would touch the security chain, the resolvers and every repository that records who
  did what.

### (d) A shared demo workspace

- Good, because it costs nothing per visitor.
- Bad, because visitors could not simulate their own design, which is the point, or would edit
  each other's.

## More Information

- Follow-up: at sign-in from a sandbox, offer to move its projects (and their runs) into the new
  personal workspace, which needs the guest's session to be read at the OAuth callback.
- [SECURITY.md](../../SECURITY.md) (hosted mode: sandboxes), [docs/api.md](../api.md) (sign-in and
  limits), [ADR-0065](0065-move-sign-in-to-spring-security-after-the-port.md) (sessions and CSRF).

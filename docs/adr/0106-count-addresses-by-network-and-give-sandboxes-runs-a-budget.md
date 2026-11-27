---
status: proposed
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0106. Count addresses by network, and give sandboxes' runs a budget and the back of the queue

## Context and Problem Statement

Sandboxes let anyone try ArchSim without signing in
([ADR-0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md)). Their abuse
controls count per address: five sandbox starts an hour, and the limits on sign-ins and share
pages. An address was counted whole, and the hosting kit serves IPv6, where a subscriber is given
at least a /64, so a limit per address did not limit an IPv6 subscriber. Sandboxes' runs had no
limit but each sandbox's 20 an hour, however many sandboxes an address held; and workers pick runs
fairly per workspace, so a signed-in person's run waited a round behind every sandbox's.

How should the limits count a visitor, and how should sandboxes' runs share the workers with
everyone else?

## Decision Drivers

- A limit per address counts at least the /64 every subscriber is given, not each address in it.
- Sandboxes stay as easy to start; the cap of 500 guests and the five projects stay.
- A sandbox's queued run never takes a slot a signed-in person's queued run could take.
- Visitors' runs fit in what the hosting kit's worker runs in an hour.
- No new moving part: the existing counters and the queue's one query.

## Considered Options

- Count IPv6 by /64; budget sandboxes' runs per address and for all sandboxes; queue sandboxes
  last.
- The same, with looser counters per /56 and per /48 besides each /64's.
- Count IPv6 by /56 or /48 instead.
- Reserve worker slots for signed-in workspaces.

## Decision Outcome

Chosen option: "count IPv6 by /64, budget sandboxes' runs, queue them last", because it closes
the gaps with the counters and the query that exist, and its numbers follow from limits the
hosting kit already has.

- **Every limit per address counts an IPv6 address by its /64** (`Principal.countedAddress`,
  `Visitor.countedAddress`), the least an end site should be given
  ([RFC 6177](https://www.rfc-editor.org/rfc/rfc6177)); an IPv4 address counts by itself, also
  when it comes mapped into IPv6. Sandbox starts, sign-ins, share pages and sandboxes' runs all
  key on it.
- **Sandboxes' runs have two budgets** besides each sandbox's 20 an hour, both rate limits
  charged with the run's others, all or none. 40 an hour per address, two sandboxes' own hours:
  two people behind one address each get their sandbox's whole hour, and an address holding many
  sandboxes runs no more than two could. 300 an hour for all sandboxes together: at a sandbox's
  20 s a run, at most 100 minutes of simulation an hour, within the 120 that the hosting kit's
  worker (two runs at once) has, so visitors' runs do not pile up there; they can take 83% of it
  while no one signed in runs anything. A worker with one slot has 60 minutes an hour, so there
  sandboxes' runs can queue for longer than an hour.
- **Workers take every other workspace's queued runs before a sandbox's**: the claim's query
  orders sandboxes' runs last, then each workspace's by position, as before, so a sandbox's run
  takes a slot only when no other workspace's queued run can (one at its cap cannot). This amends
  [ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md)'s claim, which ported the Python
  worker's exactly. A run already started is never stopped, so a signed-in person's run can still
  wait for a sandbox's run in progress, up to its 20 s.
- **A run's charges lock their counters in one order of kinds**, at most one key of each: the
  person's, the address's, all sandboxes', the workspace's (`RateLimits.enforceAll`), so two
  charges cannot deadlock.

This revisits ADR-0079's "someone with many addresses can keep all 500 places taken", which
remains. A guest lives 24 hours, so one counted address holds up to 5 × 24 = 120 places, and five
counted addresses (five IPv4 addresses, or five /64s) keep all 500 taken. Counting by /64 makes a
/64 count as one address, as an IPv4 address does, but it does not make addresses scarce: home
connections are commonly delegated a /56 to a /60, RFC 6177 holds that an end site should get "at
least one /64, and in most cases significantly more", and tunnel brokers give out /48s for free.
What the run budgets add is that whoever holds the places cannot also take the workers.

### Consequences

- Good, because a subscriber's /64 counts once for every limit per address, as an IPv4 address
  does.
- Good, because a sandbox's queued run never takes a slot another workspace's queued run could
  take, and visitors' runs fit in the default worker's hour.
- Bad, because the gap above remains: five counted addresses keep all 500 places taken, and a
  /56 holds 256 of them, a /48 65,536.
- Bad, because the budget for all sandboxes can itself be spent: 300 / 40 = 7.5, so eight counted
  addresses, with two sandboxes each, can spend it at the start of an hour, and every other
  visitor's runs are then refused with `429` until the hour ends, where before they waited in the
  queue. The refusal tells them to sign in, and signed-in people are not affected. A higher ratio
  of the budget for all to the one per address would make this take more addresses, but only by
  giving an address less than two sandboxes' hours, or by letting visitors queue more than the
  default worker runs.
- Bad, because people behind one address or /64 (a household, an office) share its budgets, as
  people behind one IPv4 NAT always did.
- Bad, because a sandbox's queued run waits as long as signed-in people keep every slot busy.

### Confirmation

`VisitorTests` and `PrincipalTests` (an IPv6 address counts by its /64, an IPv4 address by itself,
mapped or not); `RunChargesTests` (a sandbox's run is refused once its address has started 40 in
the hour, or all sandboxes 300, and a run refused by the budget for all counts nothing against
the person; a team's run counts against neither budget; the charges' order); `RunClaimsTests` (a
sandbox's run queued before a team's is taken after it, and is still taken while every other
workspace is at its cap); the black-box `SandboxTests` (a sixth sandbox start in an hour from one
/64 is refused). The cases for the new behaviour fail against the code before it.

## Pros and Cons of the Options

### Looser counters per /56 and per /48 besides each /64's

- Good, because a visitor with a /56 or a /48 would count as a few addresses, not 256 or 65,536.
- Bad, because the gap it narrows stays open: five IPv4 addresses keep all 500 places taken
  already, and a /48 counter loose enough for a company or a campus behind one /48 still lets a
  single free /48 hold several addresses' worth of places.
- Bad, because every limit per address would charge three counters, with numbers for the /56 and
  the /48 that no measurement supports.

### Count IPv6 by /56 or /48 instead

- Good, because providers that hand out a /56 or a /48 count once too.
- Bad, because everyone behind one /56 or /48 (an office, a campus, a provider that numbers its
  customers from one) would share one address's budgets.

### Reserve worker slots for signed-in workspaces

- Good, because a signed-in person's run would never wait for a sandbox's run in progress.
- Bad, because a reserved slot idles while no one signed in runs anything, which on the default
  worker halves what visitors get.

## More Information

- [ADR-0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md): sandboxes, whose
  trade-off this revisits.
- [ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md): the claim, which this amends.
- [RFC 6177](https://www.rfc-editor.org/rfc/rfc6177): IPv6 address assignment to end sites.
- [docs/api.md](../api.md), "Limits", and [SECURITY.md](../../SECURITY.md) list the numbers.

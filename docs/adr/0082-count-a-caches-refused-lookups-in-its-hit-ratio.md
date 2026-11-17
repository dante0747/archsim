---
status: accepted
date: 2026-11-17
decision-makers: Majid Abarghooei (owner)
---

# 0082. Count a cache's refused lookups in its hit ratio, and advise only the fallback a caller lacks

## Context and Problem Statement

A cache's hit ratio was its hits over its hits and misses: the lookups it answered. A lookup it
refused, because it was down or its queue turned it away, was neither, so it vanished from the
ratio. The site critic's review of the live demo (16 Nov 2026) ran the starter's "Lose Redis
Cache for 15 s": the finding said "hit ratio 80%, 50 reads/s missed" while PostgreSQL's arrivals
rose from 112 to 188 req/s and the cache refused 10,426 calls. The suggested fix then told the
user to turn on "Carry on if this call fails" and "Reads only on a cache miss", both already set.
What should a cache's hit ratio count, and what may the advice after an outage ask for?

## Decision Drivers

- A figure agrees with the run around it: the reads that reached the data store are the reads the
  cache did not serve.
- Advice never asks for what the design already does.
- One place counts a refused lookup, once, whoever made it (a service, another cache's miss link,
  a read-on-miss link).
- The benchmarks compare a predicted hit ratio with a measured one like with like.

## Considered Options

- (a) The hit ratio is hits over all lookups the cache received, refused ones included
  (`refusedLookups`, a new counter); the finding says what became of the refused reads; the
  advice asks for what the callers lack
- (b) Keep the hit ratio over answered lookups, and report refused lookups beside it
- (c) Only fix the advice

## Decision Outcome

Chosen option: (a).

- **The counter.** The cache counts, as `refusedLookups`, each read it refused: while it was
  down (the call's outage, through `RuntimeComponent.refusedWhileDown`) or turned away by its
  queue (full, or waited past `queueTimeoutMs`). A read counts once, at its first refusal, however
  often its caller retries it, and not at all when the cache answers it on a later attempt: the
  request carries when each cache first refused it, and the cache takes its count back, in the
  same bucket, when it answers it, so nothing grows with the run. The mark is the request's, not
  the caller's: two parts that look the same cache up for one request, both refused, count once,
  and one answered takes back the other's refusal (no template has two callers of one cache on
  one request's path). A write's invalidation is not a lookup, nor is a write-back, which its
  call names as one (`Calls.Errand.WRITE_BACK`); a read its data store refused behind a
  read-through cache is a miss the cache answered. A lookup its caller gave up on but the cache
  answered later counts as the hit or miss it found.
- **The hit ratio** is hits over hits, misses and refused lookups, in the summary, in each bucket
  (0 in a bucket whose every lookup was refused) and in the explained formula, whose note says a
  refused lookup costs a data-store read where its caller carries on and reads on a miss, and
  fails the read otherwise. The cache's own `hitRatio` setting keeps its meaning: the share of
  the lookups it answers that hit.
- **The finding** names the refused reads and what became of them: "it refused 99 reads/s, which
  API Servers read from PostgreSQL too", "which failed", or, with callers of both kinds, which
  were read and which failed. Its evidence adds `refusedLookupRps` when there are any.
- **The advice after a cache's outage** asks each caller (a service whose link carries reads) only
  for what it lacks: "Carry on if this call fails" on the links named, a "Reads only on a cache
  miss" link for the callers named, both when no caller does either, or, when every caller does
  both, capacity of the named callers and data stores for the cache's reads, or a replicated
  cache. The design review's advice on a cache without a fallback does the same for the part.
- **The benchmarks** read a simulated cache's hit ratio over the lookups it answered, as the
  harness measures it at the service, where the cache refused any.
- **Engine 1.2.1**, because a result changes for the same design and seed.

### Consequences

- Good, because the hit ratio, the reads reaching the data store and the refusals agree: the
  starter's outage reads 57% and 149 reads/s at PostgreSQL, where it read 80% and 50.
- Good, because advice the design already follows is not given.
- Bad, because a run's hit ratio no longer equals the frozen Python engine's where the cache
  refused lookups, so the parity tests carry a structured departure (`CacheDepartures`), and the
  hit ratio a person typed and the one a run reports differ during an outage, by design.
- Neutral: a run whose cache refuses nothing reports exactly what it did.

### Confirmation

`HitRatioTests`, `RefusedLookupsTests` (every refusal of a cache that only gets lookups is one;
write-backs and writes are none; a read its caller retries counts once, and one the cache answers
on a retry counts as answered; a queue's refusals count; a read-through cache whose store is down
counts none), `CacheOutageTests` (the starter's outage: the ratio, the formula, the finding's
wording with a fallback, without one and with callers of both kinds, the advice),
`CacheCallersTests` (who falls back, which the finding and the advice share),
`CacheFallbackTests`, `CacheDependencyAdviceTests` and the benchmark's `SimulationsTests`.

## Pros and Cons of the Options

### (b) Keep the ratio, report refusals beside it

- Good, because the ratio stays what Python's was and what the harness measures.
- Bad, because the headline figure still says 80% while two reads in five were not served: the
  contradiction the review found, now with a footnote.

### (c) Only fix the advice

- Good, because nothing about the figures changes.
- Bad, because the hit ratio still contradicts the data store's load.

## More Information

[ADR-0081](0081-price-an-unset-api-at-its-preset-or-leave-it-out-of-the-month.md) fixed the
review's first finding; [docs/simulation-model.md](../simulation-model.md) describes the cache.

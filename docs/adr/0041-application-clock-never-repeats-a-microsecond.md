---
status: proposed
date: 2026-11-06
decision-makers: Majid Abarghooei (owner)
---

# 0041. The application's clock never tells the same microsecond twice

## Context and Problem Statement

The API stores when each row was made (`created_at`, to the microsecond, `Times`), and lists
sign-offs, comments, runs, shares, tokens, invitations and members in that order. Their ids are
random, as the Python API made them, so the time is the only record of which came first. In
production (Linux containers) `Clock.systemUTC()` has sub-microsecond resolution, but on the
owner's Windows laptop it moved only about once a millisecond when measured (2026-11-06). Two
requests in quick succession then get the same time, and a list puts them in either order:
`SignoffsTests` and `ReviewTests` failed this way in full local runs. The Python API's queries were
the same. How should the app keep rows in the order they were made?

## Decision Drivers

- A list shows rows in the order they were made, whatever the host clock's resolution.
- The schema stays as the Python workers share it ([ADR-0032](0032-flyway-takes-over-from-alembic.md)),
  and the HTTP contract stays FastAPI's ([ADR-0034](0034-keep-the-fastapi-http-contract-in-spring-mvc.md)).
- A list comes back in the same order on every call.

## Considered Options

- An application clock that never repeats a microsecond, with the id as the last sort key
- A second clock, only for the times rows are stamped with
- Stamp the times in the database (`clock_timestamp()`)
- A sequence column on each table listed by time, as `audit_events` has
- Break ties by id alone
- Make the tests tolerate ties

## Decision Outcome

Chosen option: "an application clock that never repeats a microsecond, with the id as the last
sort key", because it keeps the rows one server makes in order for every table at once, with no
migration. The id puts rows that two servers stored at the same instant in the same order on
every call.

- The app's `Clock` bean is a `StrictlyIncreasingClock` over the system clock in UTC. Each reading
  is the system's time to the microsecond, or a microsecond after the last reading, whichever is
  later. On Linux it moves a reading on only when it falls in the same microsecond as the last
  one, or after the clock is set back.
- Every list and every one-row pick ordered by a time also sorts by `id` after it, in the same
  direction, as comments already did. Newest first: sign-offs, runs, share links, API tokens, open
  invitations, a workspace's projects and the latest unedited copy of an example. Oldest first: the
  person a verified email belongs to. Memberships have no id, so a workspace's members sort by
  `user_id` after the time and a user's workspaces by `workspace_id`, the other half of the
  membership's key.

### Consequences

- Good, because the rows a server makes one after another are listed in that order on any host.
- Good, because nothing stored changes form, and no table changes.
- Bad, because after the system clock is set back, the app's clock moves on only a microsecond per
  reading, ahead of the system clock, until the system clock catches up. For that long:
  - rate limits, whose fixed windows come from the clock, count every hit in one window, and
    their `Retry-After` stops counting down, so a key over its limit stays refused;
  - the trusted proxies' DNS lookups are not renewed;
  - sessions, tokens and invitations expire early, which is the safe direction.

  chrony, as Linux distributions usually set it up, slews the clock and steps it only at boot,
  so such a hold is rare and short.
- Bad, because rows two servers make at the same instant are ordered by id, not by when they were
  made.
- Neutral: times the database writes (`now()`) or the Python workers write are not covered.

### Confirmation

`StrictlyIncreasingClockTests`, `ClockConfigurationTests` (10,000 readings in a row, sorted and
distinct), and a same-instant test for each list and pick: `SignoffsTests`, `RunsTests`,
`SharesTests`, `TokensTests`, `InvitationsTests`, `MembersTests`, `ProjectRepositoryTests`,
`AccessRepositoryTests` and `AccountRepositoryTests`. Each stores three rows at the same instant
through `SameInstant.storeThree` (the middle id first, then the highest, then the lowest) and
checks they come back by id. Three, because two rows stored lowest first can pass without a
tie-break: PostgreSQL reads a `(parent, time)` index backwards for a newest-first list, which
returns the last row stored first.

## Pros and Cons of the Options

### A second clock, only for the times rows are stamped with

- Good, because expiry checks and rate limits would keep the system's time.
- Bad, because one operation often stamps a row and works out an expiry from the same `now`
  (`InvitationLinks.issue`, sessions, tokens): two clocks would give it two meanings of now.

### Stamp the times in the database (`clock_timestamp()`)

- Good, because every server would share one clock.
- Bad, because each insert would have to read its time back to answer with it, and two rows can
  still get the same microsecond.

### A sequence column on each table listed by time

- Good, because the order would hold across servers too.
- Bad, because every table listed by time would need a migration of the schema the workers
  share, and the times would still tie.

### Break ties by id alone

- Good, because it is one sort key per query.
- Bad, because the ids are random: among equal times, newest first would be wrong half the time.

### Make the tests tolerate ties

- Good, because the product would not change.
- Bad, because people would still see two items made in the same millisecond in either order,
  changing from one call to the next.

## More Information

- [ADR-0034](0034-keep-the-fastapi-http-contract-in-spring-mvc.md): the HTTP contract, whose
  lists are newest or oldest first.

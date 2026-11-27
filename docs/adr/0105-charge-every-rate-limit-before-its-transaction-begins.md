---
status: proposed
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0105. Charge every rate limit before its transaction begins, and size the connection pool explicitly

## Context and Problem Statement

A rate limit's hit is counted in a transaction of its own, so it stays counted when the request
then fails, and a refused hit costs nothing (`RateLimits`, ported from the Python API). Sandbox
starts charge their limit before the admission's transaction begins, so a start holds one
connection at a time ([ADR-0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md)).
Five other writes charge from inside their own transaction: a comment, a sign-off, a share link, an
invitation and a team workspace. Each of those requests holds one connection and waits for a
second. The pool is Hikari's default of ten, requests run on virtual threads without a cap, and a
connection is awaited for 10 s, so ten such requests at once hold every connection, wait for one
more, and fail after 10 s, with every other request, the health check included, waiting behind
them.

How should a limit be charged so that no request ever needs two connections, and how large should
the pool be?

## Decision Drivers

- No request holds more than one connection at a time, whatever it does.
- A hit stays counted when the request then fails, and a refused one costs nothing, as today.
- A request refused for another reason (403, 404, 409, 422) costs nothing, as today: the checks
  come before the charge.
- A later change that charges inside a transaction fails in the tests, not in production.

## Considered Options

- Charge before the transaction begins, and refuse a charge made inside one.
- Charge in the caller's transaction.
- Keep the charge where it is and make the pool larger.

## Decision Outcome

Chosen option: "charge before the transaction begins, and refuse a charge made inside one",
because it keeps every property of the counters and makes the rule one the code checks.

- **`RateLimits.enforce` and `enforceAll` run only outside a transaction**
  (`@Transactional(propagation = NEVER)`): a call from inside one throws
  `IllegalTransactionStateException`, so a test of any endpoint that charges from inside a
  transaction fails at once. The counter's own transaction is then the request's only one.
- **The five writes run their checks, then the charge, then the writes in one transaction.** Four
  write in a `@Transactional` method of the class that writes the record and its line in the
  history (`CommentRecords.add`, `SignoffRecords.add`, `ShareRecords.add`,
  `InvitationLinks.issue`); a team workspace writes in a transaction written out with
  `TransactionOperations`, as `RunClaims` does, because its writes are a private method. Sandbox
  starts already worked this way, with `@Transactional` on `GuestAdmissions.admit`. The writes stay
  atomic; the checks read without a transaction, which changes nothing at PostgreSQL's
  read-committed isolation, where a read takes no row lock either way.
- **The pool is ten connections, Hikari's default, now set in `application.yml`** for the API and
  the worker alike, so that it is a decision rather than an accident. With one connection per
  request at a time, the pool bounds how many requests use the database at once, not the number
  of threads. A worker uses a connection only for a moment (a claim, a heartbeat for all its runs,
  a run's progress at most once a second, an outcome), so ten serve its 32 runs at most. The bound
  that matters is PostgreSQL's 100 connections: the hosting kit's API and worker hold 20 of them,
  which leaves room for more workers and the nightly dump.
  `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` changes it, as [deployment.md](../deployment.md)
  says.

### Consequences

- Good, because in a burst of writes each request waits for a connection, up to the 10 s
  connection timeout, and none holds one while it waits for another, so the burst no longer
  stops every other request, the health check included.
- Good, because the counters behave as before for every caller; only a charge made from inside a
  transaction, which no caller makes now, is refused instead of counted.
- Bad, because the five writes read and check outside the transaction that writes. At read
  committed this loses no guarantee they had, but a reader must know why the transaction starts
  where it does; each method's Javadoc says so.

### Confirmation

`ChargedWritesTests` posts each of the five writes many times at once to an API whose pool holds
two connections and whose connection timeout is short: before the change every request failed with
500, after it each is created. `RateLimitsTests` checks that `enforce` and `enforceAll` called
from inside a transaction are refused, so a hit is always counted in a transaction of its own.

## Pros and Cons of the Options

### Charge in the caller's transaction

- Good, because it is a one-line change, and one connection by construction.
- Bad, because a hit no longer counts when the request then fails, which the counters promise, and
  the counter's row stays locked until the caller's transaction ends.

### Make the pool larger

- Good, because nothing in the code changes.
- Bad, because any pool size is reached by that many requests at once: it moves the threshold, not
  the deadlock.

## More Information

- [ADR-0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md): sandbox starts,
  which charged before their transaction first.
- [ADR-0008](0008-jdbcclient-and-flyway.md): the data access this works within.

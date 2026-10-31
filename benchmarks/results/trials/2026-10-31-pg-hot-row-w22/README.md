# W22 trial: PostgreSQL's own statistics in calibration (31 Oct 2026)

Development data for accuracy plan W22, not benchmark results: calibration passes only, nothing
measured at the load ladder, and `run.py` and `report.py` never read `results/trials/`. The
same trial wrote `2026-10-31-pg-mixed-w22/` and `2026-10-31-kafka-w22/`.

- `calibration.json`: `run.py calibrate` (protocol v5) with PostgreSQL's statistics in every
  pass (`postgres`: pg_stat_statements since the pass began, and pg_stat_wal, pg_stat_database
  and each PostgreSQL process's CPU over the pass).
- `diagnostics.json`: extra passes above the staircase (hot:1 at 500, 700 and 850 req/s, and
  write_async:1 at 700), to see the flush and the lock under more load.
- `environment.json`: the machine, and the host's CPU and the other sessions' Python jobs just
  before the trial (W21's and W24's re-simulations had finished; the host was at 7% to 22%).
  The host was not sampled during the passes.

## What it shows

Hot-row (every request updates row 1), per pass, PostgreSQL's own figures:

| Rate (req/s) | WAL flush (write + sync), ms | Commits per flush | UPDATE in the executor, ms (pg_stat_statements) | Backend active per UPDATE, its flush included, ms | PostgreSQL CPU per UPDATE, ms |
| --- | --- | --- | --- | --- | --- |
| 50 | 1.63 | 0.99 | 0.099 | 1.80 | 0.66 |
| 196 | 1.06 | 1.01 | 0.055 | 1.16 | 0.32 |
| 329 | 0.84 | 0.97 | 0.047 | 0.92 | 0.25 |
| 424 (top) | 0.79 | 1.00 | 0.045 to 0.060 | 0.86 to 0.90 | 0.22 to 0.23 |
| 500 | 0.79 | 1.00 | 0.068 | 0.89 | 0.24 |
| 850 | 0.76 | 0.99 | 0.210 (lock waits) | 1.03 | 0.21 |
| 424, synchronous_commit off | none waited for | - | 0.031 to 0.037 | 0.07 to 0.08 | 0.19 to 0.21 |

- The WAL flush gets faster with load and levels off near 0.77 ms from about 400 req/s; at
  50 req/s it took twice as long. A flush measured at the top of a low staircase is too long
  for the load near the knee.
- Writes to one row never share a flush (one commit per flush), as the model has it.
- The UPDATE itself takes 0.03 to 0.06 ms in the executor; a backend is active for 0.07 to
  0.08 ms per UPDATE without the flush; PostgreSQL's CPU per UPDATE is 0.19 to 0.23 ms. The
  backends' CPU is nearly all of PostgreSQL's (the WAL writer and the other processes use
  under 0.01 ms per query). So most of PostgreSQL's CPU per query is spent outside the
  statement (waking up, reading the query, sending the reply) and, for an UPDATE, outside
  the row's lock.
- PostgreSQL's flush (0.79 to 0.80 ms per flush at 424 req/s) agrees with the wait Node
  measured for it (synchronous_commit on minus off: 0.73 from the medians, 0.79 from the
  means).

pg-mixed (staircase to 1,024 req/s; this trial's passes were slower than the baseline
campaign's): SELECT 0.04 ms and UPDATE 0.07 ms in the executor, PostgreSQL's CPU 0.29 ms per
query at 1,024 req/s and 0.17 at 2,500; flushes 2.4 to 5.8 ms at 50 req/s, 1.4 at 448, 1.1 to
1.3 at 1,024 and 0.94 at 2,500, about one commit per flush. The diagnostic pass at 3,500 req/s
stalled (p95 61 ms; flushes of 3.4 ms) and is not used.

Kafka (staircase to 664 req/s): the consumer's INSERTs share flushes as load rises (1.0 commits
per flush at 237 req/s, 1.2 at 664, 1.5 at 1,200 and 1,500), and the flush grows with them
(0.94 ms at 664 req/s, 1.17 to 1.24 at 1,200 to 1,500). The producer's UPDATE pass at 664 req/s
flushed in 0.79 to 0.98 ms with 1.0 to 1.06 commits per flush.

How `run.py` uses this (W22): a query's time in PostgreSQL is its CPU per query; the rest of the
round trip the service measured is the service-to-database link; the flush is PostgreSQL's own
when the pass recorded it, else fitted in the model to the wait the service measured. On these
passes the two flush figures differ by 3% to 16% (PostgreSQL's is the longer: hot-row 0.79
against 0.73 ms, Kafka 0.91 against 0.88, pg-mixed 1.27 against 1.10).

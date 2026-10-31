# W21 trials: Node.js garbage collection, calibration only (31 Oct 2026)

Development data, not benchmark runs: `run.py` and `report.py` never read `results/trials/`
except `run.py`'s development checks, which take the GC per request from these trials for kept
runs whose service did not report it (`run.GC_TRIALS`, `run.trial_gc`). Nothing here is on
/accuracy.

Each `2026-10-31-<experiment>-w21/` holds a protocol v5 calibration (`calibration.json`) of the
benchmark stack with `benchmarks/service/server.js` reporting V8's garbage collections in its
`/stats` (`gc`: collections and main-thread pause by kind, and when each pause other than a
scavenge started), and `environment.json` with the host's CPU load and the other jobs running.
pg-mixed and cache-aside also have `diagnostic.json`: one 30 s pass (after 10 s of warm-up) at
each of the baseline run's 0.5x, 0.75x and 0.9x predicted-knee steps (2,475, 3,712 and 4,455
req/s; 2,425, 3,638 and 4,850 req/s), to see whether garbage collection explains the latency
tail there. They ran back to back from 14:59 to 15:49 with the benchmark lock held throughout
and the host at 2% to 23% CPU before each stack (51% after Kafka's calibration).

What they show:

- **GC pauses are a small share of the event loop's time**: 0.1% to 0.45% in every pass.
  Scavenges are frequent and short and get rarer and longer as load rises (pg-mixed: about
  0.6 ms every 550 requests at 1,931 req/s, 2.2 ms every 3,600 at 3,712); a mark-compact cycle
  (an incremental-marking step and the final pause about 10 ms after it, so one stall) takes 4
  to 16 ms every 7,500 (Kafka's producer) to 58,000 requests.
- **pg-mixed's tail did not appear**: at 3,712 req/s the trial measured p95 1.74 ms (the baseline
  run's step: 8.67 ms; engine 0.14.0 predicted 1.80). At 4,455 req/s the stack was overloaded
  (3,919 req/s served, k6 dropped 16,077 requests) with Node 89% busy and PostgreSQL saturating
  its two CPUs, which are hyperthread siblings of one physical core: 1.0 core busy, CPU per
  query up from 0.162 ms at 3,712 req/s to 0.257 ms, the service's reads waiting 34 ms on
  average. pg-mixed's knee looks set by PostgreSQL's CPU, not by Node's garbage collection.
- **cache-aside's CPU per request kept falling above the staircase**: 0.22 to 0.25 ms at the top
  (1,182 req/s), 0.170 ms at 4,850 req/s (p95 5.4 ms, the baseline step 4.8 ms).
- pg-hot-row: no mark-compact landed in a top pass (about 9,000 requests each at 299 req/s), so
  its model has no GC pause; one cycle came every 6,500 requests lower on the staircase.

A first attempt the same morning, with the host at 100% CPU from other sessions' jobs, was
discarded: Node's CPU per request read 0.8 ms (four times its usual cost) and the service
saturated at 2,475 req/s.

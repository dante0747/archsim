# k6 at the second held-out set's rates (21 Nov 2026)

[ADR-0090](../../../../docs/adr/0090-register-a-second-held-out-set-before-the-next-model-change.md)
asks for k6 to be checked, before the second held-out set's first calibration, at the rates two
of its experiments reach with k6 on CPUs 6 and 7 alone: python-wide's ladder is expected to pass
4,000 req/s and cache-miss's knee to lie above 3,000, where protocol v7 had checked k6 only up to
4,400 req/s. What it shows decides whether the two run as registered or with other CPUs for k6.

## How

`k6_check.py` sends cache-ttl's own traffic (Zipf 1.1 over 1,000,000 keys, every request a
cached read) to cache-ttl's stack, started afresh by `smoke cache-ttl --keep`, at 3,000, 4,000
and 5,000 req/s, twice each, with the virtual users protocol v7 gives each rate and its 5 s
warm-up and 20 s window. It reads what the harness reads of a measured window: k6's busy cores
from its container's cgroup, 2 s into the window to 2 s before its end (`K6Cpu`), and from k6's
summary the requests sent, dropped and failed and the latency (`K6Measured`).

cache-ttl's stack stands in for chain's, which ADR-0090 named: both give k6 CPUs 6 and 7, but
chain's stack holds about 3,400 req/s, so at the rates checked its own queue, not k6, would drop
requests; cache-ttl's holds about 5,000. cache-ttl is spent (the first held-out set's), and
cache-miss's own traffic was not sent, since it is sealed. The machine was idle, on mains power
and Windows' Best performance, Engine 1.6.0 frozen (tag `engine-1.6.0`).

## What it showed

`k6-check.json` holds every step:

| Rate (req/s) | Virtual users | k6's busy cores | Dropped | p95 |
| --- | --- | --- | --- | --- |
| 3,000 | 1,050 | 1.19, 0.91 | 0.4%, 0 | 72 ms, 6.5 ms |
| 4,000 | 1,050 | 0.97, 0.92 | 0, 0 | 4.0 ms, 1.3 ms |
| 5,000 | 1,300 | 1.15, 1.05 | 0, 0 | 36 ms, 3.5 ms |

k6 used about one of its two CPUs at every rate and dropped no request once the stack was warm;
the only drops, and the highest p95, came in the first run on the freshly started stack, which
a campaign's settling takes before anything is measured. So python-wide and cache-miss run as
registered, with k6 on CPUs 6 and 7.

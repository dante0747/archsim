# W26 trial, first attempt: cache-aside on a throttled laptop (1 Nov 2026, 02:13-02:22)

Development data, not a benchmark run, and **not used**: kept as the record of why the trial was
stopped. Nothing here is predicted or measured, and `run.py` and `report.py` never read
`results/trials/`.

A protocol v6 calibration (`W26-trial.py` at commit `2d986ff`), taken with the benchmark lock
held and the host at 14% CPU over the minute before. It read the Node.js service's CPU per
request at 0.49 ms at 676 req/s and 0.44 ms at 1,278 (the kept runs: 0.26 to 0.30 ms at 750 to
980 req/s and 0.19 to 0.23 at 1,200 to 1,600; W24's trial on the same stack on 31 Oct: 0.24 to
0.25 at 1,212), with p50 1.35 ms and p95 12.7 ms at 1,278 req/s (kept: about 0.4 and 1.2 ms).
The p95 stop (10 ms) ended the staircase at 1,278 req/s.

Right after it, Windows' counters showed why: with one thread spinning, the busy CPUs ran at 74%
to 77% of the 2.8 GHz base clock (about 2.1 GHz), where this i7-1165G7 boosts to about 4.1 to
4.7 GHz. The power mode was Best performance on mains power; the cause (heat or a power limit
after hours of load) could not be read. Every CPU cost on the machine was about twice its usual,
so the trial was stopped before PostgreSQL's stack calibrated, and `W26-trial.py` now waits until
a spinning thread runs above 120% of the base clock (`cpu_boost`).

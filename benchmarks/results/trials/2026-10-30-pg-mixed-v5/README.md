# Protocol v5 trial: pg-mixed, calibration and prediction only (30 Oct 2026)

A development check of W20's calibration on the real stack, not a benchmark run: nothing was
measured beyond the calibration passes, and these files are not results (`run.py` and
`report.py` never read `results/trials/`).

- `calibration.json`: the staircase rose to 50, 358, 806 and 1,193 req/s; the service's CPU per
  request fell from 0.84 ms at 50 req/s to 0.23 ms at 1,193 (net of idle). The first pass at
  1,193 req/s was disturbed twice (p95 64 ms, then 46 ms, with PostgreSQL using 2.6 times its
  usual CPU), so this file's first replicate set rests on it. The code that wrote it took each
  input's numbers as medians one by one; W20 now takes each input whole from its median
  replicate and replaces a top pass still disturbed after its retry.
- `predictions.json`: predicted with engine 0.14.0 from the replicate sets above, with inputs
  recomputed by the current rule. Knee 4,375 req/s (range 1,412 to 4,475 req/s; the low end is
  the disturbed set). pg-mixed's measured knee was 2,430 req/s in run 5 (1,895 to 3,162 over runs
  2 to 5).

What it shows: measured where the cost settles, Node's CPU per request (about 0.23 ms) no longer
binds before the real knee, so engine 0.14.0 would over-predict pg-mixed by about 80%. The real
knee there comes from tail latency, which the model does not reproduce yet (accuracy plan W21 to
W24: garbage-collection pauses, PostgreSQL's two CPUs being one physical core, the WAL flush
under load).

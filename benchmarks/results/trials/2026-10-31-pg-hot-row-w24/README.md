# W24 trial: pg-hot-row, calibration only (31 Oct 2026)

A development check for accuracy plan W24 (sizing inputs), not a benchmark run: calibration
only, nothing predicted or measured, and `run.py` and `report.py` never read `results/trials/`
(these numbers are never on /accuracy). Calibrated with protocol v5 as `run.py` does, at commit
`6dcf2cb` (clean tree), with each container's working set read at rest and in every staircase
pass (`ResourceSampler`), which no kept run has. The host was quiet (mean CPU load 26.0% in
the minute before the stack started; other sessions' trial scripts were only waiting for the
lock); `environment.json` records it.

What it is for: the kept runs' calibrations have no memory readings, so the memory fit
(`run.memory_inputs`, W17's `fit_memory`: memory at idle plus per req/s, non-negative least
squares over rest and every staircase pass) is tried here and compared with the memory the kept
runs measured (`benchmarks/tests/test_sizing_inputs.py`).

- Staircase 50, 214 and 317 req/s, right after pg-mixed's calibration on the same stack (as
  in a run). The API's working set: 59 to 62 MiB at rest and up to 214 req/s (the heap pg-mixed
  left), then 32 to 35 MiB once a collection shrank it.
- Fit: 43.8 MiB flat (memory did not grow with the rate over the range).
- Against the latest kept run: 44 MiB against 48 to 66 measured (-9% to -33%); the defaults
  predicted 258 to 320 MiB (+386% to +444%). Kept run 7 measured 187 MiB at its top step: hot-row
  runs on the process pg-mixed's ladder (up to 7,425 req/s) has just used, which no fit of
  hot-row's own traffic can see.

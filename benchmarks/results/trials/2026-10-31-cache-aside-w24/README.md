# W24 trial: cache-aside, calibration only (31 Oct 2026)

A development check for accuracy plan W24 (sizing inputs), not a benchmark run: calibration
only, nothing predicted or measured, and `run.py` and `report.py` never read `results/trials/`
(these numbers are never on /accuracy). Calibrated with protocol v5 as `run.py` does, at commit
`6dcf2cb` (clean tree), with each container's working set read at rest and in every staircase
pass (`ResourceSampler`), which no kept run has. The host was quiet (mean CPU load 9.6% in
the minute before the stack started; other sessions' trial scripts were only waiting for the
lock); `environment.json` records it.

What it is for: the kept runs' calibrations have no memory readings, so the memory fit
(`run.memory_inputs`, W17's `fit_memory`: memory at idle plus per req/s, non-negative least
squares over rest and every staircase pass) is tried here and compared with the memory the kept
runs measured (`benchmarks/tests/test_sizing_inputs.py`).

- Staircase 50, 384, 783 and 1,212 req/s. The API's working set: 65 to 67 MiB at rest and at
  50 req/s (the warm-up's heap, not collected yet), 37 to 48 MiB on the way up, 67 to 89 MiB on
  the way down.
- Fit: 63.8 MiB flat: the high readings at rest pin the line, so memory per req/s comes out 0.
- Against the latest kept run: 64 MiB against 63 to 182 measured (+1% to -65%); the defaults
  predicted 290 to 920 MiB (+185% to +407%).
- Redis's CPU per command at 1,212 req/s net of idle: 0.0431 ms (INFO commandstats' own time:
  0.0035 ms), against 0.032 to 0.038 ms in the kept v5 runs.

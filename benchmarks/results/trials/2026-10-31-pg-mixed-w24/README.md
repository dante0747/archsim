# W24 trial: pg-mixed, calibration only (31 Oct 2026)

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

- Staircase 50, 445, 898 and 1,386 req/s. The API's working set: 22.8 MiB at rest, 25.5 to
  55.9 MiB on the way up, and 64 to 91 MiB on the way down (V8 keeps the heap it grew until a
  collection shrinks it; at 50 req/s it was back at 27 MiB).
- Fit: 35.3 MiB + 0.0196 MiB per req/s (largest error at and above the median rate 48%).
- Against the latest kept run (steps at or below both knees): 60, 84 and 108 MiB at 1,238, 2,475
  and 3,712 req/s against 45, 70 and 145 measured (+32%, +19%, -25%); ArchSim's defaults
  predicted 264 to 278 MiB (+91% to +485%).

What the four trials show: the fit is far closer than ArchSim's defaults at every step, but low
at the top step (-23% to -65%). The staircase stops at 35% of the service's core, a third to a
sixth of the knee, and V8's heap grows faster than the rate beyond it; the heap also lags the
rate (it grows during a pass and is collected lazily), so passes 30 s long read the heap of the
passes before them. A line through the staircase cannot see either.

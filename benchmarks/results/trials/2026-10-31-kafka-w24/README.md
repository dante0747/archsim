# W24 trial: kafka, calibration only (31 Oct 2026)

A development check for accuracy plan W24 (sizing inputs), not a benchmark run: calibration
only, nothing predicted or measured, and `run.py` and `report.py` never read `results/trials/`
(these numbers are never on /accuracy). Calibrated with protocol v5 as `run.py` does, at commit
`6dcf2cb` (clean tree), with each container's working set read at rest and in every staircase
pass (`ResourceSampler`), which no kept run has. The host was quiet (mean CPU load 12.7% in
the minute before the stack started; other sessions' trial scripts were only waiting for the
lock); `environment.json` records it.

What it is for: the kept runs' calibrations have no memory readings, so the memory fit
(`run.memory_inputs`, W17's `fit_memory`: memory at idle plus per req/s, non-negative least
squares over rest and every staircase pass) is tried here and compared with the memory the kept
runs measured (`benchmarks/tests/test_sizing_inputs.py`).

- Staircase 50, 218, 391 and 526 req/s (one disturbed pass run again). The producer's working
  set: 28 MiB at rest, 29 to 59 MiB on the way up, 65 to 90 MiB on the way down.
- Fit: 47.5 MiB + 0.0474 MiB per req/s (largest error at and above the median rate 47%).
- Against the latest kept run: 72 to 137 MiB against 48 to 179 measured (+51%, -1%, -30%, -23%);
  the defaults predicted 264 to 292 MiB (+59% to +452%).

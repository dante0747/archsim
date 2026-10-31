<!-- archsim-check -->
### ❌ ArchSim check fails: Cache-aside web API (starter)

p95 goes from 90.8 ms to 264 ms (objective 400 ms); availability misses its objective; cost -$99 a month; success rate 100.00% → 71.99%.

| Metric | Base | This change | Change | |
| --- | --- | --- | --- | --- |
| Latency p95 | 90.8 ms | 264 ms | +191% | regressed |
| Success rate | 100.00% | 71.99% | -28.0 pp | regressed |
| Monthly cost | $334 | $234 | -30% |  |

| Objective | Target | Result | |
| --- | --- | --- | --- |
| Latency p95 | ≤ 400 ms | 264 ms | met |
| Availability | ≥ 99.00% | 71.99% | missed |

Bottleneck: API Servers is the primary bottleneck.
**Low confidence**: 31 of 35 inputs are illustrative defaults. To raise it: Calibrate from telemetry, or replace the illustrative and assumed inputs of API Servers, Clients → Load Balancer and 1 more with measured values.
Fails on: regression. These are ArchSim's estimates under the design's assumptions (engine 0.15.0-w22, result `6f115d28853f`), not measurements. [Full report](https://example.com/run/1)

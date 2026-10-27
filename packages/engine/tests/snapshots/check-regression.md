<!-- archsim-check -->
### ❌ ArchSim check fails: Cache-aside web API (starter)

p95 goes from 104 ms to 275 ms (objective 400 ms); availability misses its objective; cost -$109 a month; success rate 100.00% → 70.23%.

| Metric | Base | This change | Change | |
| --- | --- | --- | --- | --- |
| Latency p95 | 104 ms | 275 ms | +164% | regressed |
| Success rate | 100.00% | 70.23% | -29.8 pp | regressed |
| Monthly cost | $311 | $202 | -35% |  |

| Objective | Target | Result | |
| --- | --- | --- | --- |
| Latency p95 | ≤ 400 ms | 275 ms | met |
| Availability | ≥ 99.00% | 70.23% | missed |

Bottleneck: API Servers is the primary bottleneck.
Fails on: regression. These are ArchSim's estimates under the design's assumptions (engine 0.10.0, result `90e5b06c9e02`), not measurements. [Full report](https://example.com/run/1)

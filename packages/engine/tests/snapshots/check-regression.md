<!-- archsim-check -->
### ❌ ArchSim check fails: Cache-aside web API (starter)

p95 goes from 102 ms to 286 ms (objective 400 ms); availability misses its objective; cost -$109 a month; success rate 100.00% → 70.70%.

| Metric | Base | This change | Change | |
| --- | --- | --- | --- | --- |
| Latency p95 | 102 ms | 286 ms | +180% | regressed |
| Success rate | 100.00% | 70.70% | -29.3 pp | regressed |
| Monthly cost | $311 | $202 | -35% |  |

| Objective | Target | Result | |
| --- | --- | --- | --- |
| Latency p95 | ≤ 400 ms | 286 ms | met |
| Availability | ≥ 99.00% | 70.70% | missed |

Bottleneck: API Servers is the primary bottleneck.
Fails on: regression. These are ArchSim's estimates under the design's assumptions (engine 0.9.0, result `f9d461c5096c`), not measurements. [Full report](https://example.com/run/1)

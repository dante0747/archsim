// k6's Zipf sampler (benchmarks/k6/load.js, its rejection-inversion part, which draws every skewed key)
// against the exact distribution: for each keyspace and skew, 400,000 samples counted in bins
// of keys, and the largest gap from the exact share in standard errors. Run by K6ZipfTests:
//   node zipf-check.js <load.js>
const src = require("fs").readFileSync(process.argv[2], "utf8");
const part = src.slice(src.indexOf("const helper1"), src.indexOf("function sampleKey"));
const out = [];
for (const [KEYS, SKEW] of [[1000000, 1.1], [1000000, 0.8], [1000000, 1.0], [100000, 0.99]]) {
  const sample = new Function("KEYS", "SKEW", part + "; return sampleRejectionInversion;")(KEYS, SKEW);
  let total = 0;
  for (let k = 1; k <= KEYS; k++) total += Math.pow(k, -SKEW);
  const bins = [[1, 1], [2, 10], [11, 1000], [1001, 100000], [100001, KEYS]].filter(([a, b]) => a <= b);
  const exact = bins.map(([a, b]) => { let s = 0; for (let k = a; k <= b; k++) s += Math.pow(k, -SKEW); return s / total; });
  const n = 400000, counts = bins.map(() => 0);
  for (let i = 0; i < n; i++) { const k = sample(); counts[bins.findIndex(([a, b]) => k >= a && k <= b)]++; }
  out.push(Math.max(...bins.map((_, j) => Math.abs(counts[j] / n - exact[j]) / Math.sqrt(exact[j] * (1 - exact[j]) / n))));
}
console.log(JSON.stringify(out));

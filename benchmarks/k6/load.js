// Open-loop load for ArchSim's accuracy benchmarks: a constant arrival rate (like ArchSim's
// clients), a warm-up that is not measured, then the measured window. Keys follow the same Zipf
// distribution ArchSim samples (probability of rank k proportional to 1 / k^s; s = 0 is uniform).
//
// Environment: TARGET (base URL), MIX ("read:0.9,write:0.1"; endpoints read, write, cached,
// event, hot), RATE (req/s), WARMUP_S, DURATION_S, KEYS, SKEW, TIMEOUT_MS, OUT (file name).
import exec from "k6/execution";
import http from "k6/http";

const TARGET = __ENV.TARGET || "http://api:8080";
const RATE = Number(__ENV.RATE || 50);
const WARMUP_S = Number(__ENV.WARMUP_S || 10);
const DURATION_S = Number(__ENV.DURATION_S || 30);
const KEYS = Number(__ENV.KEYS || 100000);
const SKEW = Number(__ENV.SKEW || 0);
const TIMEOUT_MS = Number(__ENV.TIMEOUT_MS || 2000);
const MAX_VUS = Number(__ENV.MAX_VUS || 3000);
const MIX = (__ENV.MIX || "read:1").split(",").map((p) => {
  const [name, share] = p.split(":");
  return { name, share: Number(share) };
});

// cumulative Zipf weights over ranks 1..KEYS
const CDF = new Float64Array(KEYS);
let total = 0;
for (let k = 1; k <= KEYS; k++) {
  total += SKEW === 0 ? 1 : Math.pow(k, -SKEW);
  CDF[k - 1] = total;
}

function sampleKey() {
  const x = Math.random() * total;
  let lo = 0;
  let hi = KEYS - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (CDF[mid] < x) lo = mid + 1;
    else hi = mid;
  }
  return lo + 1;
}

function pick() {
  let x = Math.random();
  for (const m of MIX) {
    if (x < m.share) return m.name;
    x -= m.share;
  }
  return MIX[MIX.length - 1].name;
}

const arrival = (startTime, duration) => ({
  executor: "constant-arrival-rate",
  rate: RATE,
  timeUnit: "1s",
  duration: `${duration}s`,
  startTime: `${startTime}s`,
  preAllocatedVUs: Math.min(MAX_VUS, Math.ceil(RATE * 0.05) + 50),
  maxVUs: MAX_VUS,
  gracefulStop: `${Math.ceil(TIMEOUT_MS / 1000) + 1}s`,
});

export const options = {
  discardResponseBodies: true,
  scenarios: WARMUP_S > 0 ? { warm: arrival(0, WARMUP_S), measure: arrival(WARMUP_S, DURATION_S) } : { measure: arrival(0, DURATION_S) },
  summaryTrendStats: ["avg", "min", "med", "p(95)", "p(99)", "max", "count"],
  // thresholds that always pass, only to get per-scenario sub-metrics in the summary
  thresholds: {
    "http_req_duration{scenario:measure,expected_response:true}": ["max>=0"],
    "http_reqs{scenario:measure}": ["count>=0"],
    "http_req_failed{scenario:measure}": ["rate>=0"],
    "dropped_iterations{scenario:measure}": ["count>=0"],
  },
};

const PATHS = {
  read: () => ["GET", `/read?id=${sampleKey()}`],
  write: () => ["POST", `/write?id=${sampleKey()}`],
  write_async: () => ["POST", `/write?id=${sampleKey()}&async=1`],
  hot: () => ["POST", "/write?id=1"],
  cached: () => ["GET", `/cached?id=${sampleKey()}`],
  event: () => ["POST", `/event?id=${sampleKey()}`],
};

export default function () {
  const [method, path] = PATHS[pick()]();
  http.request(method, `${TARGET}${path}`, null, {
    timeout: `${TIMEOUT_MS}ms`,
    headers: { "X-Bench-Phase": exec.scenario.name },
  });
}

export function handleSummary(data) {
  return { [`/out/${__ENV.OUT || "summary"}.json`]: JSON.stringify(data.metrics) };
}

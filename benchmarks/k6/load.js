// Open-loop load for ArchSim's accuracy benchmarks: a constant arrival rate (like ArchSim's
// clients), a warm-up that is not measured, then the measured window. Keys follow the same Zipf
// distribution ArchSim samples (probability of rank k proportional to 1 / k^s; s = 0 is uniform).
//
// Environment: TARGET (base URL), MIX ("read:0.9,write:0.1"; endpoints read, write, cached,
// event, hot), RATE (req/s), WARMUP_S, DURATION_S, KEYS, SKEW, TIMEOUT_MS, VUS (virtual users,
// all of them ready before the first request), OUT (file name).
import exec from "k6/execution";
import http from "k6/http";

const TARGET = __ENV.TARGET || "http://api:8080";
const RATE = Number(__ENV.RATE || 50);
const WARMUP_S = Number(__ENV.WARMUP_S || 10);
const DURATION_S = Number(__ENV.DURATION_S || 30);
const KEYS = Number(__ENV.KEYS || 100000);
const SKEW = Number(__ENV.SKEW || 0);
const TIMEOUT_MS = Number(__ENV.TIMEOUT_MS || 2000);
// The arrival rate stays open-loop only while a virtual user is free to send the next request:
// with all of them waiting for answers k6 drops the request instead (dropped_iterations), and the
// latencies it reports are held down. So every virtual user is started before the first request
// (k6 starts one at a time once the test runs, dropping requests meanwhile). The harness says
// how many (its Traffic and Protocol): enough for at least a quarter of a second of arrivals to
// wait at once, and no more than k6's garbage collector carries without a tail of its own
// (compose.yaml relaxes it).
if (!__ENV.VUS) throw new Error("VUS: how many virtual users k6 holds ready (the harness's Traffic)");
const VUS = Number(__ENV.VUS);
const MIX = (__ENV.MIX || "read:1").split(",").map((p) => {
  const [name, share] = p.split(":");
  return { name, share: Number(share) };
});

// Keys are drawn without a table, so that a virtual user costs no memory of its own for them:
// a uniform key directly, a skewed one by rejection-inversion, which draws the Zipf distribution
// exactly (Hormann and Derflinger 1996, as in Apache Commons RNG).
const helper1 = (x) => (Math.abs(x) > 1e-8 ? Math.log1p(x) / x : 1 - x * (0.5 - x * (1 / 3 - 0.25 * x)));
const helper2 = (x) => (Math.abs(x) > 1e-8 ? Math.expm1(x) / x : 1 + x * 0.5 * (1 + (x / 3) * (1 + 0.25 * x)));
const zH = (x) => Math.exp(-SKEW * Math.log(x));
const zHIntegral = (x) => {
  const logX = Math.log(x);
  return helper2((1 - SKEW) * logX) * logX;
};
const zHIntegralInverse = (x) => {
  const t = Math.max(-1, x * (1 - SKEW));
  return Math.exp(helper1(t) * x);
};
const Z_X1 = zHIntegral(1.5) - 1;
const Z_N = zHIntegral(KEYS + 0.5);
const Z_S = 2 - zHIntegralInverse(zHIntegral(2.5) - zH(2));

function sampleRejectionInversion() {
  for (;;) {
    const u = Z_N + Math.random() * (Z_X1 - Z_N);
    const x = zHIntegralInverse(u);
    const k = Math.min(KEYS, Math.max(1, Math.floor(x + 0.5)));
    if (k - x <= Z_S || u >= zHIntegral(k + 0.5) - zH(k)) return k;
  }
}

function sampleKey() {
  return SKEW === 0 ? 1 + Math.floor(Math.random() * KEYS) : sampleRejectionInversion();
}

function pick() {
  let x = Math.random();
  for (const m of MIX) {
    if (x < m.share) return m.name;
    x -= m.share;
  }
  return MIX[MIX.length - 1].name;
}

// The warm-up stops at once (gracefulStop 0) and hands its virtual users, with their connections,
// to the measured window, which starts in the same instant: k6 plans the users of scenarios that
// overlap side by side, so a warm-up left to finish its requests would double them. The requests
// the warm-up still had in flight are cut off and counted to it, not to the window.
const arrival = (startTime, duration, gracefulStop) => ({
  executor: "constant-arrival-rate",
  rate: RATE,
  timeUnit: "1s",
  duration: `${duration}s`,
  startTime: `${startTime}s`,
  preAllocatedVUs: VUS,
  maxVUs: VUS,
  gracefulStop: `${gracefulStop}s`,
});
const MEASURED_STOP_S = Math.ceil(TIMEOUT_MS / 1000) + 1;

export const options = {
  discardResponseBodies: true,
  scenarios:
    WARMUP_S > 0
      ? { warm: arrival(0, WARMUP_S, 0), measure: arrival(WARMUP_S, DURATION_S, MEASURED_STOP_S) }
      : { measure: arrival(0, DURATION_S, MEASURED_STOP_S) },
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

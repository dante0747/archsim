// ArchSim benchmark service: a small Node.js HTTP API (ROLE=api) and a Kafka consumer
// (ROLE=consumer). It does the minimum each benchmark needs and records the timings that
// calibrate the ArchSim model: CPU per request on the event loop, and the time of each
// PostgreSQL, Redis and Kafka call as seen from the service. GET /stats returns them,
// POST /reset clears them. No framework, so the event loop does nothing we did not write.
"use strict";

const http = require("node:http");
const { performance } = require("node:perf_hooks");
const { Pool } = require("pg");

const ROLE = process.env.ROLE || "api";
const PORT = Number(process.env.PORT || 8080);
const POOL = Number(process.env.PG_POOL || 10);
const TTL_S = Number(process.env.CACHE_TTL_S || 30);
const TOPIC = process.env.KAFKA_TOPIC || "events";
const PARTITIONS = Number(process.env.KAFKA_PARTITIONS || 6);
const CONSUMERS = Number(process.env.KAFKA_CONSUMERS || 3);

const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: POOL });
// Same database, commits without waiting for the WAL flush: calibration only (write time
// without the flush; the difference is the flush).
const asyncPool = new Pool({ connectionString: process.env.DATABASE_URL, max: 2, options: "-c synchronous_commit=off" });

// ---- timings --------------------------------------------------------------------------------
const MAX_SAMPLES = 200000;
let samples = {};
let counters = {};
let requests = 0;
let consumed = 0;
let produced = 0;
let cpu0 = process.cpuUsage();
let wall0 = performance.now();

function record(name, ms) {
  const a = (samples[name] ||= []);
  if (a.length < MAX_SAMPLES) a.push(ms);
}

// counts per load phase (k6 sends X-Bench-Phase: warm or measure)
function count(name) {
  counters[name] = (counters[name] || 0) + 1;
}

function quantile(sorted, q) {
  if (!sorted.length) return null;
  const i = Math.min(sorted.length - 1, Math.max(0, Math.ceil(q * sorted.length) - 1));
  return sorted[i];
}

function stats() {
  const cpu = process.cpuUsage(cpu0);
  const cpuMs = (cpu.user + cpu.system) / 1000;
  const out = {
    role: ROLE,
    requests,
    produced,
    consumed,
    wallMs: performance.now() - wall0,
    cpuMs,
    cpuMsPerRequest: requests ? cpuMs / requests : null,
    cpuMsPerMessage: consumed ? cpuMs / consumed : null,
    counters,
    timings: {},
  };
  for (const [name, a] of Object.entries(samples)) {
    const s = [...a].sort((x, y) => x - y);
    out.timings[name] = {
      count: s.length,
      mean: s.reduce((t, v) => t + v, 0) / s.length,
      median: quantile(s, 0.5),
      p95: quantile(s, 0.95),
      p99: quantile(s, 0.99),
    };
  }
  return out;
}

function reset() {
  samples = {};
  counters = {};
  requests = 0;
  consumed = 0;
  produced = 0;
  cpu0 = process.cpuUsage();
  wall0 = performance.now();
}

// Time of one awaited dependency call; ``io`` (per request) adds up the waits so the handler can
// record its own time (handler minus dependency calls).
async function timed(name, fn, io) {
  const t = performance.now();
  try {
    return await fn();
  } finally {
    const ms = performance.now() - t;
    record(name, ms);
    if (io) io.ms += ms;
  }
}

// ---- dependencies -----------------------------------------------------------------------------
let redis = null;
async function connectRedis() {
  if (!process.env.REDIS_URL) return;
  const { createClient } = require("redis");
  redis = createClient({ url: process.env.REDIS_URL });
  redis.on("error", (e) => console.error("redis", e.message));
  await redis.connect();
}

let kafka = null;
let producer = null;
async function connectKafka() {
  if (!process.env.KAFKA_BROKERS) return;
  const { Kafka, logLevel } = require("kafkajs");
  kafka = new Kafka({ clientId: `bench-${ROLE}`, brokers: process.env.KAFKA_BROKERS.split(","), logLevel: logLevel.WARN });
  const admin = kafka.admin();
  await admin.connect();
  await admin.createTopics({ topics: [{ topic: TOPIC, numPartitions: PARTITIONS, replicationFactor: 1 }] }).catch(() => {});
  await admin.disconnect();
  if (ROLE === "api") {
    producer = kafka.producer({ allowAutoTopicCreation: false });
    await producer.connect();
  }
}

// ---- API --------------------------------------------------------------------------------------
async function route(req, url, io) {
  const id = Number(url.searchParams.get("id") || 1);
  const phase = req.headers["x-bench-phase"] || "none";
  switch (url.pathname) {
    case "/read": {
      const r = await timed("db_read", () => pool.query("SELECT value FROM items WHERE id = $1", [id]), io);
      return { value: r.rows[0]?.value ?? null };
    }
    case "/write": {
      const async = url.searchParams.get("async") === "1";
      await timed(async ? "db_write_async" : "db_write", () =>
        (async ? asyncPool : pool).query("UPDATE items SET value = value + 1 WHERE id = $1", [id]), io,
      );
      return { ok: true };
    }
    case "/cached": {
      const key = `item:${id}`;
      const hit = await timed("redis_get", () => redis.get(key), io);
      if (hit !== null) {
        count(`cache_hit:${phase}`);
        return { value: hit, cached: true };
      }
      count(`cache_miss:${phase}`);
      const r = await timed("db_read", () => pool.query("SELECT value FROM items WHERE id = $1", [id]), io);
      const value = String(r.rows[0]?.value ?? "");
      await timed("redis_set", () => redis.set(key, value, { EX: TTL_S }), io);
      return { value, cached: false };
    }
    case "/event": {
      await timed("kafka_send", () =>
        producer.send({ topic: TOPIC, acks: -1, messages: [{ key: String(id), value: JSON.stringify({ id, at: Date.now(), phase }) }] }), io,
      );
      produced++;
      count(`produced:${phase}`);
      return { ok: true };
    }
    default:
      return null;
  }
}

function serve(handler) {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, "http://x");
    if (url.pathname === "/stats") return send(res, 200, stats());
    if (url.pathname === "/reset") {
      reset();
      return send(res, 200, { ok: true });
    }
    if (url.pathname === "/health") return send(res, 200, { ok: true });
    const t = performance.now();
    const io = { ms: 0 };
    handler(req, url, io).then(
      (body) => {
        if (body === null) return send(res, 404, { error: "not found" });
        requests++;
        const ms = performance.now() - t;
        record("handler", ms);
        record("own", ms - io.ms);
        send(res, 200, body);
      },
      (err) => send(res, 500, { error: err.message }),
    );
  });
  server.keepAliveTimeout = 65000;
  server.listen(PORT, () => console.log(`${ROLE} listening on ${PORT}`));
}

function send(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(data) });
  res.end(data);
}

// ---- consumer -----------------------------------------------------------------------------------
async function runConsumer() {
  const consumer = kafka.consumer({ groupId: "bench-writers" });
  await consumer.connect();
  await consumer.subscribe({ topic: TOPIC, fromBeginning: false });
  await consumer.run({
    partitionsConsumedConcurrently: CONSUMERS,
    eachMessage: async ({ message }) => {
      const t = performance.now();
      const io = { ms: 0 };
      const body = JSON.parse(message.value.toString());
      await timed("db_insert", () => pool.query("INSERT INTO events (item_id, produced_at) VALUES ($1, to_timestamp($2 / 1000.0))", [body.id, body.at]), io);
      const ms = performance.now() - t;
      record("consume", ms);
      record("consume_own", ms - io.ms);
      record(`end_to_end:${body.phase || "none"}`, Date.now() - body.at);
      count(`consumed:${body.phase || "none"}`);
      consumed++;
    },
  });
}

async function main() {
  await connectRedis();
  await connectKafka();
  if (ROLE === "consumer") {
    serve(async () => null);
    await runConsumer();
  } else {
    serve(route);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

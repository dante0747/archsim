/**
 * Starts the ArchSim API for the end-to-end tests (playwright.config.ts runs it as a webServer).
 *
 * Each start gets a fresh folder under apps/web/.e2e/ holding a throwaway SQLite database and a
 * copy of examples/. The copies are shortened to E2E_RUN_SECONDS of simulated time (default 20;
 * 0 keeps the templates as they are), with every timed event (bursts, faults, scenario overrides)
 * moved to the same fraction of the shorter run, so each simulation takes a few seconds.
 *
 * Environment: E2E_API_PORT (default 8002), E2E_RUN_SECONDS. Needs `uv` on PATH.
 */
import { spawn } from "node:child_process";
import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const port = process.env.E2E_API_PORT ?? "8002";
const runSeconds = Number(process.env.E2E_RUN_SECONDS ?? "20");

// Old folders are removed here rather than on exit: Playwright may kill this process outright.
const root = path.join(here, "..", ".e2e");
mkdirSync(root, { recursive: true });
for (const old of readdirSync(root)) {
  try {
    rmSync(path.join(root, old), { recursive: true, force: true });
  } catch {
    /* still held by a server that has not exited yet */
  }
}
const dir = path.join(root, `api-${port}-${Date.now()}`);
const examples = path.join(dir, "examples");
mkdirSync(examples, { recursive: true });

const round = (v) => Math.round(v * 1000) / 1000;

/** Scale the start and length of a pattern or fault (or a list of faults) by k. */
function scaleTimed(value, k) {
  for (const item of Array.isArray(value) ? value : [value]) {
    if (!item || typeof item !== "object") continue;
    for (const key of ["startS", "durationS"]) if (typeof item[key] === "number") item[key] = round(item[key] * k);
  }
}

function shorten(doc, seconds) {
  const w = doc.workload;
  if (!seconds || !w || w.durationS <= seconds) return doc;
  const k = seconds / w.durationS;
  w.durationS = seconds;
  w.warmupS = round(w.warmupS * k);
  scaleTimed(w.pattern, k);
  scaleTimed(w.faults, k);
  for (const sc of doc.scenarios ?? []) {
    for (const o of sc.overrides ?? []) {
      if (o.target?.kind !== "workload") continue;
      if (o.path === "pattern" || o.path === "faults") scaleTimed(o.value, k);
      else if (o.path === "durationS" || o.path === "warmupS") o.value = round(o.value * k);
    }
  }
  return doc;
}

for (const name of readdirSync(path.join(repo, "examples")).filter((f) => f.endsWith(".json"))) {
  const doc = JSON.parse(readFileSync(path.join(repo, "examples", name), "utf8"));
  writeFileSync(path.join(examples, name), JSON.stringify(shorten(doc, runSeconds), null, 2));
}

const dbFile = path.join(dir, "e2e.db").replaceAll("\\", "/");
const child = spawn("uv", ["run", "archsim-api", "serve", "--host", "127.0.0.1", "--port", port], {
  cwd: repo,
  stdio: ["ignore", "pipe", "pipe"],
  env: {
    ...process.env,
    ARCHSIM_DATABASE_URL: `sqlite:///${dbFile}`,
    ARCHSIM_EXAMPLES_DIR: examples,
  },
});
// Pass the server's log through without one line per request.
const ACCESS = /"(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) \S+ HTTP\/[\d.]+" \d{3}/;
function passThrough(from, to) {
  let rest = "";
  from.setEncoding("utf8").on("data", (chunk) => {
    const lines = (rest + chunk).split("\n");
    rest = lines.pop() ?? "";
    for (const line of lines) if (!ACCESS.test(line)) to.write(`${line}\n`);
  });
}
passThrough(child.stdout, process.stdout);
passThrough(child.stderr, process.stderr);
child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => child.kill(sig));

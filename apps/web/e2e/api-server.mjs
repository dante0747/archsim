/**
 * Starts the ArchSim API for the end-to-end tests (playwright.config.ts runs it as a webServer):
 * the Java API (archsim-app) and the Java worker that simulates the runs it queues
 * (`archsim-app worker`, ADR-0053), on a fresh PostgreSQL database.
 *
 * Each start gets a fresh folder under apps/web/.e2e/ with a copy of examples/, shortened to
 * E2E_RUN_SECONDS of simulated time (default 20; 0 keeps the templates as they are), with every
 * timed event (bursts, faults, scenario overrides) moved to the same fraction of the shorter
 * run, so each simulation takes a few seconds. The database E2E_DATABASE (default
 * archsim_e2e_<port>) is dropped and made again on the PostgreSQL server of E2E_POSTGRES_URL.
 *
 * Environment:
 * - E2E_API_PORT (default 8002);
 * - E2E_RUN_SECONDS;
 * - E2E_POSTGRES_URL, a database to connect to as a user who may make databases (default
 *   postgresql://archsim:archsim@127.0.0.1:5432/archsim, the compose stack's:
 *   docker compose up -d postgres);
 * - E2E_DATABASE;
 * - JAVA_HOME, else `java` on PATH.
 *
 * Needs the API's jar (./gradlew :archsim-app:bootJar) and a JDK 25. The database is made with
 * the PostgreSQL driver in that jar, which Spring Boot's jar mode extracts, and a short Java
 * program (FreshDatabase.java), so no other database client is needed.
 */
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const port = Number(process.env.E2E_API_PORT ?? "8002");
const runSeconds = Number(process.env.E2E_RUN_SECONDS ?? "20");
const postgres = new URL(process.env.E2E_POSTGRES_URL ?? "postgresql://archsim:archsim@127.0.0.1:5432/archsim");
const database = process.env.E2E_DATABASE ?? `archsim_e2e_${port}`;
const jar = path.join(repo, "archsim-app", "build", "libs", "archsim-app.jar");

if (!/^[a-z0-9_]+$/.test(database)) throw new Error(`E2E_DATABASE is not a plain database name: ${database}`);
if (!existsSync(jar)) throw new Error(`no ${jar}: build it with ./gradlew :archsim-app:bootJar`);

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

const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", "java") : "java";

/** Runs `java` to its end; if it does not succeed, throws `failure`, with why. */
function runJava(failure, args, options = {}) {
  const result = spawnSync(java, args, { stdio: "inherit", ...options });
  if (result.error) throw new Error(`${failure}: cannot run ${java}: ${result.error.message}`);
  if (result.status !== 0) throw new Error(`${failure}: ${result.signal ?? `exit code ${result.status}`}`);
}

/** A URL's percent-encoded part, decoded; kept whole when it is not valid percent-encoding. */
function decoded(part) {
  try {
    return decodeURIComponent(part);
  } catch {
    return part;
  }
}

// A fresh database, through the PostgreSQL driver in the API's own jar: Spring Boot's jar mode
// extracts the jar's libraries, and FreshDatabase.java drops and makes the database with it. The
// user and password go in PGUSER and PGPASSWORD, as the URL gives them, not on the command line.
const libraries = path.join(dir, "jar");
runJava(`cannot extract the libraries of ${jar}`, ["-Djarmode=tools", "-jar", jar, "extract", "--destination", libraries]);
const driver = readdirSync(path.join(libraries, "lib")).find((name) => /^postgresql-.*\.jar$/.test(name));
if (!driver) throw new Error(`no PostgreSQL driver in ${jar}`);
const admin = `jdbc:postgresql://${postgres.host}${postgres.pathname || "/"}${postgres.search}`;
const credentials = {};
if (postgres.username) credentials.PGUSER = decoded(postgres.username);
if (postgres.password) credentials.PGPASSWORD = decoded(postgres.password);
runJava(
  `cannot make database ${database} on ${postgres.host} (is PostgreSQL running? docker compose up -d postgres)`,
  ["-cp", path.join(libraries, "lib", driver), path.join(here, "FreshDatabase.java"), admin, database],
  { env: { ...process.env, ...credentials } },
);

// E2E_POSTGRES_URL with the fresh database and SQLAlchemy's driver name, its user, host and query kept
const databaseUrl = new URL(postgres);
databaseUrl.protocol = "postgresql+psycopg:";
databaseUrl.pathname = `/${database}`;
const children = [];

/**
 * How long the servers have to answer their health checks: inside the webServer timeout in
 * playwright.config.ts, so a server that does not start is named here rather than timed out there.
 */
const STARTUP_MS = 100_000;
const startupDeadline = Date.now() + STARTUP_MS;

/** Starts a process with these ARCHSIM_* settings; if it cannot start or it ends, so does the stack. */
function start(name, command, args, settings) {
  const child = spawn(command, args, {
    cwd: repo,
    stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, ARCHSIM_DATABASE_URL: databaseUrl.toString(), ...settings },
  });
  passThrough(name, child.stdout, process.stdout);
  passThrough(name, child.stderr, process.stderr);
  child.on("error", (error) => {
    console.error(`[${name}] cannot run ${command}: ${error.message}`);
    stop(1);
  });
  // "close", not "exit": by then its last lines (a stack trace) have been passed through
  child.on("close", (code, signal) => {
    console.error(`[${name}] ended (${signal ?? `exit code ${code}`})`);
    stop(code || 1);
  });
  children.push(child);
  return child;
}

/** Waits until a process logs a line holding `text`; one that does not in time stops the stack. */
function waitForLine(name, child, text) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      console.error(`[${name}] did not log "${text}" within ${STARTUP_MS / 1000} s`);
      stop(1);
    }, Math.max(0, startupDeadline - Date.now()));
    let seen = "";
    child.stdout.on("data", (chunk) => {
      seen = (seen + chunk).slice(-4096);
      if (seen.includes(text)) {
        clearTimeout(timer);
        resolve();
      }
    });
  });
}

/** Waits until a server answers its health check; one that does not in time stops the stack. */
async function waitUntilHealthy(name, url) {
  while (Date.now() < startupDeadline) {
    try {
      if ((await fetch(url, { signal: AbortSignal.timeout(2_000) })).ok) return;
    } catch {
      /* not listening yet, or not answering */
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  console.error(`[${name}] did not answer ${url} within ${STARTUP_MS / 1000} s`);
  stop(1);
}

// Pass the servers' logs through, without one line per request.
const ACCESS = /"(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) \S+ HTTP\/[\d.]+" \d{3}/;
function passThrough(name, from, to) {
  let rest = "";
  from.setEncoding("utf8").on("data", (chunk) => {
    const lines = (rest + chunk).split("\n");
    rest = lines.pop() ?? "";
    for (const line of lines) if (!ACCESS.test(line)) to.write(`[${name}] ${line}\n`);
  });
  from.on("end", () => {
    if (rest && !ACCESS.test(rest)) to.write(`[${name}] ${rest}\n`);
  });
}

let stopping = false;
function stop(code) {
  if (stopping) return;
  stopping = true;
  for (const child of children) child.kill();
  process.exit(code);
}
for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => stop(0));

// As compose does: the database is migrated first (the worker never migrates), then the worker
// starts and the API only once the worker takes runs, so an API that answers its health check
// has a worker behind it (ADR-0053). The worker's heap holds the three runs it takes at once
// (archsim-app's README), and it exits when the heap runs out, so its runs are requeued.
runJava(`cannot migrate database ${database}`, ["-jar", jar, "migrate"], {
  cwd: repo,
  env: { ...process.env, ARCHSIM_DATABASE_URL: databaseUrl.toString() },
});
const worker = start("worker", java, ["-Xmx2g", "-XX:+ExitOnOutOfMemoryError", "-jar", jar, "worker"], {
  ARCHSIM_MAX_CONCURRENT_RUNS: "3",
});
await waitForLine("worker", worker, "run(s) at once");
start("api", java, ["-jar", jar, "api", "--server.address=127.0.0.1", `--server.port=${port}`], {
  ARCHSIM_EXAMPLES_DIR: examples,
});
await waitUntilHealthy("api", `http://127.0.0.1:${port}/api/v1/health`);

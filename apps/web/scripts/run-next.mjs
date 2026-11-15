/**
 * Runs the Next.js CLI with telemetry disabled by default (no phone-home without opt-in).
 * Set NEXT_TELEMETRY_DISABLED to an empty string to opt in. A first argument `--site` runs it on
 * the public website's routes instead of the app's (ARCHSIM_SITE=1, ADR-0078): `npm run dev:site`.
 */
import { spawn } from "node:child_process";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
process.env.NEXT_TELEMETRY_DISABLED ??= "1";
const args = process.argv.slice(2);
if (args[0] === "--site") {
  process.env.ARCHSIM_SITE = "1";
  args.shift();
}
const bin = require.resolve("next/dist/bin/next");
const child = spawn(process.execPath, [bin, ...args], { stdio: "inherit", env: process.env });
child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));

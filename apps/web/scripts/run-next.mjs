/**
 * Runs the Next.js CLI with telemetry disabled by default (no phone-home without opt-in).
 * Set NEXT_TELEMETRY_DISABLED to an empty string to opt in.
 */
import { spawn } from "node:child_process";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
process.env.NEXT_TELEMETRY_DISABLED ??= "1";
const bin = require.resolve("next/dist/bin/next");
const child = spawn(process.execPath, [bin, ...process.argv.slice(2)], { stdio: "inherit", env: process.env });
child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));

import type { Run } from "./types";

type Reproduced = Pick<Run, "engineVersion" | "resultDigest">;

/**
 * What a re-run says about the run it reproduces. A result digest names a result within one
 * engine: the Python and the Java engine agree on the figures but need not share a digest, so a
 * re-run on another engine version is named for what it is.
 */
export function reproduction(original: Reproduced, run: Reproduced): { exact: boolean; description: string } {
  if (original.resultDigest && original.resultDigest === run.resultDigest) {
    return {
      exact: true,
      description: `Same input and seed gave an identical result digest (${run.resultDigest.slice(0, 10)}).`,
    };
  }
  if (original.engineVersion !== run.engineVersion) {
    return {
      exact: false,
      description: `Results differ from the original run, simulated by engine ${original.engineVersion} (this one: ${run.engineVersion}).`,
    };
  }
  return {
    exact: false,
    description: "Results differ from the original run (a different engine, engine build or platform?).",
  };
}

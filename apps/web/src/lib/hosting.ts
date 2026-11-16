/**
 * What the app may truthfully say about where ArchSim runs. A local install (no sign-in) runs on
 * your machine with no account; a hosted server (sign-in) is someone else's machine, where you
 * try ArchSim and from which you can go on to run your own. The server says which in
 * GET /auth/config.
 */
import type { AuthConfig } from "./auth-types";

export interface WhereItRuns {
  /** The line under the home page's buttons. */
  tagline: string;
  /** The home page footer's line. */
  footer: string;
  /** The last of the home page's figures. */
  stat: { value: string; label: string; detail: string };
}

const LOCAL: WhereItRuns = {
  tagline: "Open source · runs on your machine · no account, no telemetry",
  footer: "Runs on your machine · no account, no telemetry",
  stat: { value: "0", label: "bytes leave your machine", detail: "local, open source, no account" },
};

/** Where this server runs ArchSim, in words; null until the server has said. */
export function whereItRuns(config: AuthConfig | null): WhereItRuns | null {
  if (!config) return null;
  if (config.mode === "none") return LOCAL;
  const here = config.sandboxes ? "try it here, no account needed," : "try it here,";
  return {
    tagline: `Open source · ${here} or run it on your own machine with one command`,
    footer: "Run your own copy on your machine with one command",
    stat: { value: "1", label: "command to run your own", detail: "docker compose up, on your machine" },
  };
}

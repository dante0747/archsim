import { describe, expect, it } from "vitest";
import type { AuthConfig } from "./auth-types";
import { whereItRuns } from "./hosting";

const config = (mode: AuthConfig["mode"], sandboxes = false): AuthConfig => ({ mode, providers: mode === "oauth" ? ["github"] : [], sandboxes, csrfToken: null });

describe("what the home page says about where ArchSim runs", () => {
  it("keeps saying it runs on your machine, with no account, on a local install", () => {
    const where = whereItRuns(config("none"));

    expect(where?.tagline).toBe("Open source · runs on your machine · no account, no telemetry");
    expect(where?.footer).toBe("Runs on your machine · no account, no telemetry");
    expect(where?.stat).toEqual({ value: "0", label: "bytes leave your machine", detail: "local, open source, no account" });
  });

  it("offers trying it here, or running it yourself, on a hosted server", () => {
    const where = whereItRuns(config("oauth"));

    expect(where?.tagline).toBe("Open source · try it here, or run it on your own machine with one command");
    expect(JSON.stringify(where)).not.toMatch(/no account|runs on your machine|leave your machine|telemetry/);
  });

  it("says no account is needed only where the server offers sandboxes", () => {
    const where = whereItRuns(config("oauth", true));

    expect(where?.tagline).toBe("Open source · try it here, no account needed, or run it on your own machine with one command");
  });

  it("says nothing before the server has said how it runs", () => {
    expect(whereItRuns(null)).toBeNull();
  });
});

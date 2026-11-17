import { describe, expect, it } from "vitest";
import { evaluateSlo, formatSloValue, objectiveKeys, sloChecks } from "./slo";
import type { Report } from "./types";

/** A run's summary as the checks read it. */
const run = (over: Record<string, unknown> = {}) =>
  ({ summary: { latencyMs: { p95: 90 }, successRate: 1, throughputRps: 100, ...over } }) as unknown as Pick<Report, "summary">;

describe("objectives against a run", () => {
  it("judges availability by the requests that succeeded and whose messages were not lost", () => {
    const [check] = sloChecks({ availability: 0.999 }, run({ availability: 0.99, lostMessages: 46 }));
    expect(check).toMatchObject({ key: "availability", actual: 0.99, pass: false });
  });

  it("judges availability by the success rate in a run without queues", () => {
    const [check] = sloChecks({ availability: 0.999 }, run());
    expect(check).toMatchObject({ key: "availability", actual: 1, pass: true });
  });

  it("judges the age of a message at the slowest queue against its target", () => {
    const result = evaluateSlo({ messageAgeP95Ms: 5000 }, run({ lostMessages: 0, messageAgeP95Ms: 21040 }));
    expect(result.checks).toMatchObject([{ key: "messageAge", label: "Message age p95", actual: 21040, pass: false }]);
    expect(result.pass).toBe(false);
  });

  it("leaves the message age out for a run without a queue, judging the rest", () => {
    const result = evaluateSlo({ latencyP95Ms: 300, messageAgeP95Ms: 5000 }, run());
    expect(result.checks.map((c) => c.key)).toEqual(["p95"]);
    expect(result.pass).toBe(true);
  });

  it("writes a message's age in seconds from a second", () => {
    expect(formatSloValue("messageAge", 21040)).toBe("21.0 s");
    expect(formatSloValue("messageAge", 850)).toBe("850 ms");
  });

  it("offers the message age objective for a design with a queue, or one that sets it", () => {
    expect(objectiveKeys(false, {})).toEqual(["p95", "p99", "availability", "throughput"]);
    expect(objectiveKeys(true, {})).toEqual(["p95", "p99", "availability", "throughput", "messageAge"]);
    expect(objectiveKeys(false, { messageAgeP95Ms: 5000 })).toContain("messageAge");
  });
});

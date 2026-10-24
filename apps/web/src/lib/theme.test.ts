import { describe, expect, it } from "vitest";
import { verdictOf } from "@/components/editor/workflow";
import { kindOf, shortName } from "./theme";
import type { Finding, Report } from "./types";

const report = (findings: Partial<Finding>[]) => ({ findings }) as unknown as Report;

describe("theme helpers", () => {
  it("maps component types and categories to kinds", () => {
    expect(kindOf("traffic_source")).toBe("traffic");
    expect(kindOf("load_balancer")).toBe("network");
    expect(kindOf("cache")).toBe("cache");
    expect(kindOf("message_queue", "storage")).toBe("database");
    expect(kindOf("mystery")).toBe("other");
  });

  it("derives short rail names", () => {
    expect(shortName("load_balancer", "Load Balancer")).toBe("Balancer");
    expect(shortName("api_gateway", "API Gateway (Kong)")).toBe("API");
  });
});

describe("verdict", () => {
  it("prefers the primary bottleneck, then hot components, else healthy", () => {
    expect(verdictOf(report([{ kind: "cache", title: "x" }, { kind: "bottleneck", title: "API is the primary bottleneck" }]))).toEqual({
      title: "API is the primary bottleneck",
      tone: "bad",
    });
    expect(verdictOf(report([{ kind: "hot", title: "DB is running hot" }])).tone).toBe("warn");
    expect(verdictOf(report([{ kind: "healthy", title: "ok" }])).tone).toBe("ok");
  });
});

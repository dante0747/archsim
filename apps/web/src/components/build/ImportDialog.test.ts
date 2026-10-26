import { describe, expect, it } from "vitest";
import { guessFormat } from "./ImportDialog";

describe("import format detection", () => {
  it("recognises compose files, Kubernetes manifests and Terraform JSON", () => {
    expect(guessFormat("docker-compose.yml", "name: shop\nservices:\n  api:\n    image: x\n")).toBe("compose");
    expect(guessFormat("app.yaml", "apiVersion: apps/v1\nkind: Deployment\nmetadata: {name: api}\n")).toBe("k8s");
    expect(guessFormat("plan.json", '{"format_version": "1.2"}')).toBe("terraform");
    expect(guessFormat("plan.txt", '  {"planned_values": {}}')).toBe("terraform");
  });

  it("leaves the choice to the user when it cannot tell", () => {
    expect(guessFormat("notes.yaml", "hello: world\n")).toBeNull();
  });
});

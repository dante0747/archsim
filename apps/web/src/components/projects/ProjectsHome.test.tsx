import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthConfig } from "@/lib/auth-types";

const auth = vi.hoisted(() => ({ state: { status: "signed-out", config: null, me: null, workspaceId: null, workspace: null } as Record<string, unknown> }));
vi.mock("@/store/auth", () => ({ useAuthState: () => auth.state, authSettled: (s: string) => s === "ready" || s === "error" }));
vi.mock("@/lib/api", () => ({
  api: { components: () => new Promise(() => {}), examples: () => new Promise(() => {}), listProjects: () => new Promise(() => {}) },
}));
vi.mock("@/components/projects/actions", () => ({ useProjectActions: () => ({ busy: null, create: vi.fn(), openTemplate: vi.fn(), importFile: vi.fn() }) }));
vi.mock("@/components/auth/UserMenu", () => ({ UserMenu: () => null }));
vi.mock("@/components/landing/HeroShot", () => ({ HeroShot: () => null }));
vi.mock("@/components/landing/HowItWorks", () => ({ HowItWorks: () => null }));
vi.mock("@/components/landing/FeatureBento", () => ({ FeatureBento: () => null }));

import { ProjectsHome } from "./ProjectsHome";

const config = (mode: AuthConfig["mode"], sandboxes = false): AuthConfig => ({ mode, providers: mode === "oauth" ? ["github"] : [], sandboxes, csrfToken: null });

describe("the app's home page", () => {
  beforeEach(() => {
    vi.stubGlobal("matchMedia", (query: string) => ({ matches: query.includes("reduce"), addEventListener() {}, removeEventListener() {} }));
    const Observer = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
    vi.stubGlobal("IntersectionObserver", Observer);
    vi.stubGlobal("ResizeObserver", Observer);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("says it runs on your machine, with no account, on a local install", () => {
    auth.state = { ...auth.state, status: "ready", config: config("none") };

    render(<ProjectsHome privacyUrl={null} />);

    expect(screen.getByText("Open source · runs on your machine · no account, no telemetry")).toBeInTheDocument();
    expect(screen.getByText("bytes leave your machine")).toBeInTheDocument();
    expect(within(screen.getByRole("contentinfo")).getByText("Runs on your machine · no account, no telemetry")).toBeInTheDocument();
  });

  it("never says it runs on your machine on a hosted server", () => {
    auth.state = { ...auth.state, status: "signed-out", config: config("oauth", true) };

    const { container } = render(<ProjectsHome privacyUrl={null} />);

    expect(screen.getByText("Open source · try it here, no account needed, or run it on your own machine with one command")).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/runs on your machine|leave your machine|no telemetry/i);
  });

  it("presents what a run finds as hypotheses to test, never a straight yes or no", () => {
    auth.state = { ...auth.state, status: "ready", config: config("none") };

    const { container } = render(<ProjectsHome privacyUrl={null} />);

    expect(container).toHaveTextContent("ArchSim is an architecture experimentation tool for distributed systems.");
    expect(container).toHaveTextContent("hypotheses to test");
    expect(container.textContent).not.toMatch(/yes or no|straight answer|ship the one/i);
  });

  it("links to the server's privacy notice in the footer when the server names one", () => {
    auth.state = { ...auth.state, status: "signed-out", config: config("oauth") };

    render(<ProjectsHome privacyUrl="https://archsim.io/privacy/" />);

    expect(within(screen.getByRole("contentinfo")).getByRole("link", { name: "Privacy" })).toHaveAttribute("href", "https://archsim.io/privacy/");
  });

  it("shows no privacy link when the server names no notice", () => {
    auth.state = { ...auth.state, status: "ready", config: config("none") };

    render(<ProjectsHome privacyUrl={null} />);

    expect(screen.queryByRole("link", { name: "Privacy" })).toBeNull();
  });
});

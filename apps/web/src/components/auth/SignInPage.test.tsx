import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { AuthConfig } from "@/lib/auth-types";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }), usePathname: () => "/signin" }));
const config: AuthConfig = { mode: "oauth", providers: ["github"], sandboxes: true, csrfToken: "t" };
vi.mock("@/store/auth", () => ({
  useAuthState: () => ({ status: "signed-out", config, me: null, workspaceId: null, workspace: null }),
  useAuth: (pick: (s: { startSandbox: () => Promise<void> }) => unknown) => pick({ startSandbox: vi.fn() }),
}));

import { SignInPage, safeNext } from "./SignInPage";

describe("the sign-in page's privacy notice", () => {
  it("is linked beside what the server stores, where the server names one", () => {
    render(<SignInPage next={null} error={null} privacyUrl="https://archsim.io/privacy/" />);

    const link = screen.getByRole("link", { name: "Privacy" });
    expect(link).toHaveAttribute("href", "https://archsim.io/privacy/");
    expect(link.closest("p")).toHaveTextContent(/reads your name and verified email/);
  });

  it("is not linked where the server names none", () => {
    render(<SignInPage next={null} error={null} privacyUrl={null} />);

    expect(screen.queryByRole("link", { name: "Privacy" })).toBeNull();
  });
});

describe("safeNext, the path to come back to after signing in", () => {
  it.each(["/projects", "/projects/p1?run=r2#traces", "/settings/tokens"])("keeps %j, a path on this site", (next) => {
    expect(safeNext(next)).toBe(next);
  });

  it.each([
    ["no path", null],
    ["an empty path", ""],
    ["another site", "https://evil.example/"],
    ["a scheme-relative address", "//evil.example"],
    ["a backslash after the slash", "/\\evil.example"],
    ["a tab, which address parsing drops", "/\t/evil.example"],
    ["a line feed, which address parsing drops", "/\n/evil.example"],
    ["a carriage return, which address parsing drops", "/\r/evil.example"],
    ["a relative path", "projects"],
    ["an address that cannot be parsed", "//["],
    ["an address that cannot be parsed after a backslash", "/\\["],
  ])("sends %s to the projects page", (_what, next) => {
    expect(safeNext(next)).toBe("/projects");
  });
});

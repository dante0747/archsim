import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { AuthConfig } from "@/lib/auth-types";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }), usePathname: () => "/signin" }));
const config: AuthConfig = { mode: "oauth", providers: ["github"], sandboxes: true, csrfToken: "t" };
vi.mock("@/store/auth", () => ({
  useAuthState: () => ({ status: "signed-out", config, me: null, workspaceId: null, workspace: null }),
  useAuth: (pick: (s: { startSandbox: () => Promise<void> }) => unknown) => pick({ startSandbox: vi.fn() }),
}));

import { SignInPage } from "./SignInPage";

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

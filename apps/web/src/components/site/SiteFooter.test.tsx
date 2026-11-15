import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SiteFooter } from "./SiteFooter";

describe("SiteFooter", () => {
  it("links the community, the license and the security policy, and names the copyright holder", () => {
    render(<SiteFooter copyright="Copyright 2026 Someone and the ArchSim contributors" />);

    const footer = screen.getByRole("contentinfo");
    expect(within(footer).getByRole("link", { name: "Discussions" })).toHaveAttribute("href", "https://github.com/dante0747/archsim/discussions");
    expect(within(footer).getByRole("link", { name: "Security policy" })).toHaveAttribute("href", "https://github.com/dante0747/archsim/blob/main/SECURITY.md");
    expect(within(footer).getByRole("link", { name: "AGPL-3.0-only" })).toHaveAttribute("href", "https://github.com/dante0747/archsim/blob/main/LICENSE");
    expect(footer).toHaveTextContent("© 2026 Someone and the ArchSim contributors");
    expect(footer).toHaveTextContent(/no analytics/i);
  });
});

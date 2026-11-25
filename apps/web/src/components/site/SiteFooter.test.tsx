import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SiteFooter } from "./SiteFooter";

const footer = () => screen.getByRole("contentinfo");

describe("SiteFooter", () => {
  beforeEach(() => vi.stubEnv("__NEXT_TRAILING_SLASH", "true")); // as the site's export builds its links
  afterEach(() => vi.unstubAllEnvs());

  it("groups its links into product, documentation, community and legal", () => {
    render(<SiteFooter copyright="Copyright 2026 Someone and the ArchSim contributors" commercialContact="mailto:someone@example.com" />);

    expect(within(footer()).getAllByRole("navigation").map((n) => n.getAttribute("aria-label"))).toEqual(["Product", "Documentation", "Community", "Legal"]);
    expect(within(footer()).getByRole("link", { name: "Capabilities" })).toHaveAttribute("href", "/#features");
    expect(within(footer()).getByRole("link", { name: "Screens" })).toHaveAttribute("href", "/#tour");
  });

  it("links the community, the license, the security policy and the privacy notice, and names the copyright holder", () => {
    render(<SiteFooter copyright="Copyright 2026 Someone and the ArchSim contributors" commercialContact="mailto:someone@example.com" />);

    expect(within(footer()).getByRole("link", { name: "Discussions" })).toHaveAttribute("href", "https://github.com/dante0747/archsim/discussions");
    expect(within(footer()).getByRole("link", { name: "Security policy" })).toHaveAttribute("href", "https://github.com/dante0747/archsim/blob/main/SECURITY.md");
    expect(within(footer()).getByRole("link", { name: "AGPL-3.0-only" })).toHaveAttribute("href", "https://github.com/dante0747/archsim/blob/main/LICENSE");
    expect(within(footer()).getByRole("link", { name: "Contributor agreement" })).toHaveAttribute("href", "https://github.com/dante0747/archsim/blob/main/CLA.md");
    expect(within(footer()).getByRole("link", { name: "Privacy" })).toHaveAttribute("href", "/privacy/");
    expect(footer()).toHaveTextContent("© 2026 Someone and the ArchSim contributors");
    expect(footer()).toHaveTextContent(/no analytics/i);
  });

  it("says how to ask for a commercial license, as the README does", () => {
    render(<SiteFooter copyright="Copyright 2026 Someone and the ArchSim contributors" commercialContact="mailto:someone@example.com" />);

    expect(within(footer()).getByRole("link", { name: "Ask for a commercial license" })).toHaveAttribute("href", "mailto:someone@example.com");
  });
});

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PrivacyPage } from "./PrivacyPage";

// vitest runs in apps/web; every claim the page makes is checked against the file that makes it true
const repo = (path: string) => readFileSync(resolve(process.cwd(), "../..", path), "utf8");
const app = (path: string) => repo(`archsim-app/src/main/java/io/archsim/app/${path}`);

describe("the demo's privacy notice", () => {
  it("names who runs the demo and how to reach them, as the code of conduct already does", () => {
    render(<PrivacyPage />);

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Privacy");
    expect(screen.getByRole("main")).toHaveTextContent("Majid Abarghooei");
    const contact = screen.getAllByRole("link", { name: "abarghooeimajid@gmail.com" });
    expect(contact[0]).toHaveAttribute("href", "mailto:abarghooeimajid@gmail.com");
    expect(repo("CODE_OF_CONDUCT.md")).toContain("abarghooeimajid@gmail.com");
  });

  it("covers who runs it, where, what it stores, cookies, logs and deletion", () => {
    render(<PrivacyPage />);

    const headings = screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent);
    expect(headings).toEqual(["Who runs it", "Where it runs", "What it stores", "Cookies", "Logs", "Having your data deleted"]);
  });

  it("says how long a sandbox and the backups keep data, as the API and the backup script do", () => {
    const { container } = render(<PrivacyPage />);
    const text = container.textContent ?? "";

    expect(app("signin/Guests.java")).toContain("LIFETIME = Duration.ofHours(24)");
    expect(text).toContain("deleted 24 hours after the sandbox starts");
    expect(repo("deploy/backup.sh")).toContain('KEEP="${ARCHSIM_BACKUP_KEEP:-7}"');
    expect(text).toContain("the newest seven are kept");
    expect(text).toContain("up to seven more days");
    // a pg_dump on a Docker volume: nothing in the kit encrypts it, so the page does not say so
    expect(text).not.toMatch(/encrypt/i);
  });

  it("lists each cookie signing in sets, with its SameSite, as the sign-in code sets them", () => {
    render(<PrivacyPage />);
    const cookie = (name: string) => screen.getByText(name).closest("li");
    const cookies = app("signin/BrowserCookie.java");

    expect(app("access/Principal.java")).toContain('SESSION_COOKIE = "__Host-archsim_session"');
    expect(cookies).toContain('SESSION = new BrowserCookie(Principal.SESSION_COOKIE, "lax", true)');
    expect(cookie("__Host-archsim_session")).toHaveTextContent("HttpOnly, Secure, SameSite=Lax.");
    expect(app("access/BrowserCsrf.java")).toContain('COOKIE = "__Host-archsim_csrf"');
    expect(cookies).toContain('CSRF = new BrowserCookie(BrowserCsrf.COOKIE, "strict", false)');
    expect(cookie("__Host-archsim_csrf")).toHaveTextContent(/the app reads it\. Secure, SameSite=Strict\.$/);
    expect(cookies).toContain('STATE = new BrowserCookie(STATE_NAME, "lax", true)');
    expect(app("signin/StateCookies.java")).toContain("LIFETIME_SECONDS = 600");
    expect(cookie("__Host-archsim_oauth")).toHaveTextContent("for at most ten minutes. HttpOnly, Secure, SameSite=Lax.");
    expect(screen.getByText("No analytics, no tracking, no ads.")).toBeInTheDocument();
  });

  it("says how the server's own logs rotate, as the hosted kit sets them", () => {
    const { container } = render(<PrivacyPage />);

    expect(repo("deploy/compose.yaml")).toMatch(/max-size: 10m\s+max-file: "5"/);
    expect(container.textContent).toContain("five files of 10 MB");
  });
});

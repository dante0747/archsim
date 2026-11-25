/**
 * The website's privacy notice for the hosted demo at app.archsim.io (ADR-0079): who runs it, where,
 * what it keeps and for how long, its cookies and logs, and how to have your data deleted. Every
 * figure here is the repository's: the sandbox's lifetime (the API's Guests), the backups kept
 * (deploy/backup.sh), the cookies (the API's sign-in) and the logs (deploy/compose.yaml), which
 * PrivacyPage.test.tsx checks. The demo's ARCHSIM_PRIVACY_URL points here.
 */
import { ShieldCheck } from "lucide-react";

const OPERATOR = "Majid Abarghooei";
const CONTACT = "abarghooeimajid@gmail.com";

function Contact() {
  return <a href={`mailto:${CONTACT}`}>{CONTACT}</a>;
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section aria-label={title}>
      <h2>{title}</h2>
      {children}
    </section>
  );
}

export function PrivacyPage() {
  return (
    <main id="main" className="mx-auto max-w-3xl px-4 py-14 sm:px-6 sm:py-20">
      <p className="inline-flex items-center gap-2 rounded-full border border-line bg-surface px-3 py-1 text-[13px] font-medium text-ink-2">
        <ShieldCheck className="size-3.5 text-accent" aria-hidden /> The hosted demo
      </p>
      <h1 className="mt-5 font-display text-[40px] font-semibold leading-[1.05] tracking-[-0.04em] text-ink sm:text-[52px]">Privacy</h1>
      <p className="mt-5 text-[17px] leading-relaxed text-ink-2">
        What the demo at <a href="https://app.archsim.io" className="font-medium text-ink underline underline-offset-2">app.archsim.io</a> keeps about
        you, where, and for how long. This website keeps nothing: it is static pages, served at archsim.io by GitHub Pages and at
        archsim.coderator.dev by Cloudflare Pages, with no analytics, no cookies and no third-party scripts.
      </p>

      <div className="doc-prose mt-10">
        <Section title="Who runs it">
          <p>
            {OPERATOR}, who wrote ArchSim, runs the demo. Write to <Contact /> about anything on this page.
          </p>
        </Section>

        <Section title="Where it runs">
          <p>
            On one server in Amsterdam, rented from Vultr. Cloudflare stands in front of it: it serves HTTPS and passes each request on to the
            server.
          </p>
        </Section>

        <Section title="What it stores">
          <ul>
            <li>
              <strong>If you try it without an account:</strong> a sandbox with the designs and runs you make in it, its session (the IP address and
              browser it was started from) and its history of changes, which records the IP address of each. All of it is deleted 24 hours after
              the sandbox starts. The database is backed up every night and the newest seven are kept, so a copy can remain in the backups for up
              to seven more days.
            </li>
            <li>
              <strong>If you sign in with GitHub:</strong> the name, login, profile picture address and verified email GitHub gives, and the
              workspaces, designs, runs and comments you make, until they or your account are deleted. Each session records the IP address and
              browser it was started from, and a workspace&apos;s history records the IP address of each change, which its owners can see.
            </li>
            <li>
              <strong>Share links you make:</strong> a frozen copy of a run&apos;s results, which anyone with the link can open until it expires or
              you revoke it. It counts how often it was opened, not by whom.
            </li>
            <li>
              <strong>Rate limits:</strong> counters of recent requests, by IP address (starting a sandbox, opening a share link) or by account
              (runs, comments). The API deletes each counter once its time window has ended.
            </li>
            <li>
              <strong>In your browser:</strong> your theme, panel layout and last workspace, in its own storage. They are not stored on the server.
            </li>
          </ul>
          <p>Keep secrets and personal data out of your designs: the demo is for trying ArchSim.</p>
        </Section>

        <Section title="Cookies">
          <p>
            Only the ones signing in needs, each a <code>__Host-</code> cookie, so it is sent to app.archsim.io alone and only over HTTPS:
          </p>
          <ul>
            <li>
              <code>__Host-archsim_session</code>: who you are signed in as, or which sandbox is yours, for as long as the session lasts. HttpOnly,
              Secure, SameSite=Lax.
            </li>
            <li>
              <code>__Host-archsim_csrf</code>: a token that shows a request came from the app, not from another site; the app reads it. Secure,
              SameSite=Strict.
            </li>
            <li>
              <code>__Host-archsim_oauth</code>: while you sign in with GitHub, for at most ten minutes. HttpOnly, Secure, SameSite=Lax.
            </li>
          </ul>
          <p>No analytics, no tracking, no ads.</p>
        </Section>

        <Section title="Logs">
          <p>
            Cloudflare, as the proxy in front of the server, sees every request: your IP address, the address you asked for and your browser,
            under <a href="https://www.cloudflare.com/privacypolicy/">Cloudflare&apos;s privacy policy</a>. When you sign in with GitHub, GitHub
            knows you signed in to ArchSim. On the server, each service keeps its own log, rotated at five files of 10 MB.
          </p>
        </Section>

        <Section title="Having your data deleted">
          <p>
            A sandbox deletes itself after 24 hours. Signed in, you can delete your designs and runs, and the workspaces you own, in the app at
            any time. To have your account and everything only it holds deleted, write to <Contact /> from the email address your GitHub account
            has verified, or name your GitHub login. Copies in the backups go within seven days after that.
          </p>
        </Section>
      </div>
    </main>
  );
}

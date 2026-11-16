"use client";

/**
 * /signin: the providers this server offers, why the last attempt failed, if it did, and where the
 * server offers sandboxes, trying ArchSim without signing in. A sandbox's guest signs in here too.
 */
import { ArrowRight, CircleAlert, FlaskConical, LoaderCircle } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Logo } from "@/components/brand";
import { Button } from "@/components/ui/button";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { PROVIDER_NAMES, providerLoginHref } from "@/lib/auth-api";
import { isGuest } from "@/lib/sandbox";
import { useAuth, useAuthState } from "@/store/auth";

const ERRORS: Record<string, string> = {
  state: "Sign-in took too long, or it started in another browser. Please try again.",
  denied: "Sign-in was cancelled.",
  provider: "The provider did not confirm who you are. Please try again in a moment.",
  disabled: "This account is turned off on this server. Ask one of its owners.",
};

/** A path on this site to come back to; anything else goes to the projects page. */
export function safeNext(next: string | null | undefined): string {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) return "/projects";
  return next;
}

function ProviderIcon({ provider }: { provider: string }) {
  if (provider === "github") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden className="size-[18px] fill-current">
        <path d="M12 .5a11.5 11.5 0 0 0-3.64 22.41c.58.1.79-.25.79-.56v-2c-3.2.7-3.88-1.37-3.88-1.37-.52-1.34-1.28-1.69-1.28-1.69-1.05-.72.08-.7.08-.7 1.16.08 1.77 1.2 1.77 1.2 1.03 1.77 2.7 1.26 3.36.96.1-.75.4-1.26.73-1.55-2.55-.29-5.24-1.28-5.24-5.69 0-1.26.45-2.29 1.19-3.1-.12-.29-.52-1.46.11-3.05 0 0 .97-.31 3.17 1.18a11 11 0 0 1 5.77 0c2.2-1.49 3.17-1.18 3.17-1.18.63 1.59.23 2.76.11 3.05.74.81 1.19 1.84 1.19 3.1 0 4.42-2.7 5.4-5.26 5.68.41.36.78 1.06.78 2.14v3.17c0 .31.21.67.8.56A11.5 11.5 0 0 0 12 .5Z" />
      </svg>
    );
  }
  return (
    <svg viewBox="0 0 24 24" aria-hidden className="size-[18px]">
      <path fill="#4285F4" d="M23.5 12.27c0-.85-.08-1.67-.22-2.45H12v4.64h6.45a5.52 5.52 0 0 1-2.4 3.62v3h3.88c2.27-2.09 3.57-5.17 3.57-8.81Z" />
      <path fill="#34A853" d="M12 24c3.24 0 5.96-1.07 7.94-2.92l-3.88-3c-1.08.72-2.45 1.15-4.06 1.15-3.13 0-5.78-2.11-6.72-4.95h-4v3.1A12 12 0 0 0 12 24Z" />
      <path fill="#FBBC05" d="M5.28 14.28a7.2 7.2 0 0 1 0-4.56v-3.1h-4a12 12 0 0 0 0 10.76l4-3.1Z" />
      <path fill="#EA4335" d="M12 4.77c1.76 0 3.34.61 4.59 1.8l3.44-3.44A11.97 11.97 0 0 0 1.28 6.62l4 3.1C6.22 6.88 8.87 4.77 12 4.77Z" />
    </svg>
  );
}

/**
 * "Try it without signing in", where the server offers sandboxes: a sandbox for a day, then on to
 * where signing in would have gone.
 */
function TrySandbox({ target }: { target: string }) {
  const startSandbox = useAuth((s) => s.startSandbox);
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const start = async () => {
    setBusy(true);
    setFailure(null);
    try {
      await startSandbox();
      router.push(target);
    } catch (e) {
      setFailure(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <div className="pt-2">
      <div className="flex items-center gap-3 text-xs text-ink-3" aria-hidden>
        <span className="h-px flex-1 bg-line" /> or <span className="h-px flex-1 bg-line" />
      </div>
      <Button variant="secondary" size="lg" className="mt-3 w-full justify-center" onClick={() => void start()} disabled={busy} data-testid="signin-sandbox">
        {busy ? <LoaderCircle className="animate-spin" /> : <FlaskConical />} Try it without signing in
      </Button>
      <p className="mt-2 text-xs leading-relaxed text-ink-3">
        A sandbox for 24 hours: up to five designs and small runs, no account. Then it and its designs are deleted.
      </p>
      {failure && (
        <p role="alert" className="mt-3 flex items-start gap-2 rounded-lg border border-line-strong bg-critical-soft px-3 py-2 text-[13px] text-ink">
          <CircleAlert className="mt-0.5 size-4 shrink-0 text-danger-ink" />
          {failure}
        </p>
      )}
    </div>
  );
}

export function SignInPage({ next, error }: { next: string | null; error: string | null }) {
  const { status, config, me } = useAuthState();
  const target = safeNext(next);
  const guest = isGuest(me);

  return (
    <div className="flex min-h-full flex-col bg-page">
      <header className="border-b border-line">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-4 px-4 sm:px-6">
          <Logo />
          <div className="ml-auto">
            <ThemeToggle />
          </div>
        </div>
      </header>
      <main className="flex flex-1 items-start justify-center px-4 py-16 sm:py-24">
        <div className="surface-sheen w-full max-w-sm rounded-xl border border-line-strong bg-surface p-6 shadow-float">
          <h1 className="font-display text-[28px] leading-tight text-ink">Sign in to ArchSim</h1>
          <p className="mt-1.5 text-sm text-ink-2">Your designs, runs and team workspaces live on this server.</p>

          {error && (
            <p role="alert" className="mt-4 flex items-start gap-2 rounded-lg border border-line-strong bg-critical-soft px-3 py-2 text-[13px] text-ink">
              <CircleAlert className="mt-0.5 size-4 shrink-0 text-danger-ink" />
              {ERRORS[error] ?? "Sign-in did not work. Please try again."}
            </p>
          )}

          <div className="mt-6 space-y-2.5">
            {status === "idle" || status === "loading" ? (
              <p className="flex items-center gap-2 text-sm text-ink-2" role="status">
                <LoaderCircle className="size-4 animate-spin" /> Checking this server…
              </p>
            ) : status === "error" ? (
              <p className="text-sm text-ink-2">The ArchSim API cannot be reached right now. Try again in a moment.</p>
            ) : config?.mode === "none" ? (
              <>
                <p className="text-sm text-ink-2">This server runs without sign-in: you are its local owner.</p>
                <Button variant="primary" size="lg" className="w-full" asChild>
                  <Link href={target}>
                    Continue <ArrowRight />
                  </Link>
                </Button>
              </>
            ) : me && !guest ? (
              <>
                <p className="text-sm text-ink-2">You are signed in as {me.user.name}.</p>
                <Button variant="primary" size="lg" className="w-full" asChild>
                  <Link href={target}>
                    Continue <ArrowRight />
                  </Link>
                </Button>
              </>
            ) : (
              <>
                {guest && (
                  <p className="text-sm text-ink-2">
                    Signing in starts a workspace of your own. The designs in your sandbox are not moved: they are deleted with it.
                  </p>
                )}
                {(config?.providers ?? []).map((p) => (
                  <Button key={p} size="lg" className="w-full justify-center" asChild>
                    {/* a full-page visit: the API redirects to the provider and back */}
                    <a href={providerLoginHref(p, target)} data-testid={`signin-${p}`}>
                      <ProviderIcon provider={p} /> Continue with {PROVIDER_NAMES[p] ?? p}
                    </a>
                  </Button>
                ))}
                {config?.sandboxes && !guest && <TrySandbox target={target} />}
              </>
            )}
          </div>
          <p className="mt-6 text-xs leading-relaxed text-ink-3">
            ArchSim reads your name and verified email from the provider and nothing else. It never posts anything for you.
          </p>
        </div>
      </main>
    </div>
  );
}

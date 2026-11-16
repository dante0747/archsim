/**
 * A hosted server's privacy notice: `ARCHSIM_PRIVACY_URL`, which the web server reads when a page
 * is asked for (as it reads ARCHSIM_PUBLIC_URL), so one image serves any operator. The home
 * page's footer and the sign-in page link to it; without it they show no link.
 */

/** The notice's address, or null when the setting is unset or not an http(s) address. */
export function privacyUrl(env: Record<string, string | undefined>): string | null {
  const raw = env.ARCHSIM_PRIVACY_URL?.trim();
  if (!raw) return null;
  try {
    const url = new URL(raw);
    return url.protocol === "https:" || url.protocol === "http:" ? url.href : null;
  } catch {
    return null;
  }
}

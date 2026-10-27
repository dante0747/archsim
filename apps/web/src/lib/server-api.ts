/**
 * API calls made by the web server itself (server components and image routes), straight to
 * ARCHSIM_API_URL rather than through the /api proxy. Never cached: a revoked share link must
 * stop working at once. The visitor's address goes along (as the proxy sends it) so the API's
 * per-address limits count visitors, not this server.
 */
import "server-only";
import { headers } from "next/headers";
import { cache } from "react";
import { CLIENT_IP_HEADER, clientIp } from "./proxy";
import type { PublicShare } from "./share-types";

const API_URL = (process.env.ARCHSIM_API_URL ?? "http://127.0.0.1:8000").replace(/\/$/, "");
const TRUSTED_HOPS = Number(process.env.ARCHSIM_TRUSTED_HOPS ?? "0");

export type ShareResult =
  | { kind: "ok"; share: PublicShare }
  | { kind: "not-found" }
  | { kind: "limited"; retryAfter: number | null }
  | { kind: "unavailable"; message: string };

/** The link's slug can only be base62; anything else is not a share (no API call). */
export const SLUG = /^[0-9A-Za-z]{22}$/;

async function forwardedHeaders(): Promise<Record<string, string>> {
  const incoming = await headers();
  const out: Record<string, string> = { accept: "application/json" };
  const ip = clientIp(incoming.get("x-forwarded-for"), TRUSTED_HOPS);
  if (ip) out[CLIENT_IP_HEADER] = ip;
  return out;
}

async function fetchShare(slug: string, view: boolean): Promise<ShareResult> {
  if (!SLUG.test(slug)) return { kind: "not-found" };
  const url = `${API_URL}/api/v1/public/shares/${slug}${view ? "" : "?view=false"}`;
  let res: Response;
  try {
    res = await fetch(url, { headers: await forwardedHeaders(), cache: "no-store" });
  } catch {
    return { kind: "unavailable", message: "The ArchSim API cannot be reached." };
  }
  if (res.status === 404) return { kind: "not-found" };
  if (res.status === 429) {
    const retry = Number(res.headers.get("retry-after"));
    return { kind: "limited", retryAfter: Number.isFinite(retry) && retry > 0 ? retry : null };
  }
  if (!res.ok) return { kind: "unavailable", message: `The ArchSim API answered ${res.status}.` };
  return { kind: "ok", share: (await res.json()) as PublicShare };
}

/** A share for the page: counted as one view, once per request however many parts ask. */
export const getShare = cache((slug: string) => fetchShare(slug, true));

/** A share for its link preview image: not counted as a view. */
export const getSharePreview = cache((slug: string) => fetchShare(slug, false));

/** The address this site is reached at, for absolute links in previews (ARCHSIM_PUBLIC_URL). */
export function publicBase(): URL | null {
  const raw = process.env.ARCHSIM_PUBLIC_URL?.trim();
  if (!raw) return null;
  try {
    const url = new URL(raw);
    return url.protocol === "https:" || url.protocol === "http:" ? url : null;
  } catch {
    return null;
  }
}

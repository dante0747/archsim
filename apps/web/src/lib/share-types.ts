/** Share links, as the API returns them (archsim-app's `shares` package: `ShareView`, `PublicShareView`). */
import type { Objectives, Report } from "./types";

export type ShareStatus = "active" | "expired" | "revoked";

/** A share link of one of your projects (editors make and revoke them, viewers list them). */
export interface Share {
  id: string;
  slug: string;
  /** Where the page is on this site: /r/<slug>. */
  path: string;
  /** The full link, when the API knows the public address. */
  url: string | null;
  title: string;
  projectId: string | null;
  runId: string | null;
  createdBy: string | null;
  createdAt: string;
  expiresAt: string | null;
  revokedAt: string | null;
  viewCount: number;
  resultDigest: string | null;
  reportVersion: string;
  status: ShareStatus;
}

/** A report shared without traces: what /r/<slug> shows. */
export type SharedReport = Omit<Report, "traces">;

/** What anyone with the link reads (GET /public/shares/{slug}). */
export interface PublicShare {
  slug: string;
  title: string;
  projectName: string;
  runLabel: string | null;
  scenarioName: string | null;
  createdAt: string;
  expiresAt: string | null;
  payloadVersion: number;
  reportVersion: string;
  resultDigest: string | null;
  objectives: Objectives;
  report: SharedReport;
  /** "Open a copy" can make a project from it. */
  canCopy: boolean;
}

export const SHARE_EXPIRY_CHOICES: { days: number | null; label: string }[] = [
  { days: 7, label: "7 days" },
  { days: 30, label: "30 days" },
  { days: 90, label: "90 days" },
  { days: null, label: "Never" },
];

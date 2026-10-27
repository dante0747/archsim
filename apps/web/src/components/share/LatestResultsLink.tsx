"use client";

/** On the phone screen of the editor: the project's newest share link, readable on a phone. */
import { useEffect, useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { latestActive, shareApi } from "@/lib/share-api";
import type { Share } from "@/lib/share-types";

export function LatestResultsLink({ projectId }: { projectId: string }) {
  const [latest, setLatest] = useState<Share | null>(null);
  useEffect(() => {
    let cancelled = false;
    shareApi
      .list(projectId)
      .then((shares) => {
        if (!cancelled) setLatest(latestActive(shares));
      })
      .catch(() => {
        /* no link to offer */
      });
    return () => {
      cancelled = true;
    };
  }, [projectId]);
  if (!latest) return null;
  return (
    <Button asChild variant="secondary" size="lg">
      <Link href={latest.path} data-testid="view-latest-results">
        View latest results
      </Link>
    </Button>
  );
}

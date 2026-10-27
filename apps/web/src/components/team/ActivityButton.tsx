"use client";

import { MessageSquare } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tip } from "@/components/ui/misc";
import { useTeam, useTeamSync } from "@/store/team";
import { ActivityDrawer } from "./ActivityDrawer";

/** The top bar's Activity button: open comment threads at a glance, and the drawer with all
 * comments and the history. Also keeps comments and sign-offs current (polling). */
export function ActivityButton() {
  useTeamSync();
  const open = useTeam((s) => s.comments.filter((c) => !c.parentId && !c.resolvedAt).length);
  const openActivity = useTeam((s) => s.openActivity);
  const label = open ? `Activity: ${open} open comment ${open === 1 ? "thread" : "threads"}` : "Activity: comments and history";
  return (
    <>
      <Tip content={label}>
        <Button variant="ghost" size={open ? "sm" : "icon"} aria-label={label} onClick={() => openActivity()} data-testid="activity-button">
          <MessageSquare />
          {open > 0 && <span className="tabular font-mono text-xs">{open}</span>}
        </Button>
      </Tip>
      <ActivityDrawer />
    </>
  );
}

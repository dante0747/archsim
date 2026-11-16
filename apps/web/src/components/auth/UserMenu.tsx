"use client";

/**
 * The person menu at the right of every header: who you are, the workspace switcher (the
 * workspace lists and new projects use), settings and sign out; a sandbox's guest sees when the
 * sandbox is deleted, and signs in from here to keep their work. Signed out on a server with
 * sign-in, it is a "Sign in" button that keeps your page (it opens in a new tab inside the
 * editor, so unsaved edits survive).
 */
import { Check, LogIn, LogOut, Settings, Users } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from "@/components/ui/overlays";
import { initials } from "@/lib/auth-api";
import type { WorkspaceInfo } from "@/lib/auth-types";
import { isGuest, sandboxEndText } from "@/lib/sandbox";
import { signInPage } from "@/lib/session";
import { cn } from "@/lib/utils";
import { useAuth, useAuthState } from "@/store/auth";

const KIND_LABEL: Record<WorkspaceInfo["kind"], string> = { personal: "Personal", team: "Team", sandbox: "Sandbox" };

export function Avatar({ name, className }: { name: string; className?: string }) {
  return (
    <span
      aria-hidden
      className={cn(
        "inline-flex size-7 shrink-0 items-center justify-center rounded-full border border-line-strong bg-surface-2 text-[11px] font-semibold text-ink",
        className,
      )}
    >
      {initials(name)}
    </span>
  );
}

export function UserMenu({ className, showWorkspace = true }: { className?: string; showWorkspace?: boolean }) {
  const { status, me, workspace } = useAuthState();
  const switchWorkspace = useAuth((s) => s.switchWorkspace);
  const signOut = useAuth((s) => s.signOut);
  const router = useRouter();
  const pathname = usePathname();
  const inEditor = pathname.startsWith("/projects/");

  if (status === "signed-out") {
    return (
      <Button variant="secondary" size="sm" asChild className={className}>
        <a href={signInPage()} target={inEditor ? "_blank" : undefined} rel="noreferrer">
          <LogIn /> Sign in
        </a>
      </Button>
    );
  }
  if (status !== "ready" || !me) return null;

  const local = me.kind === "local";
  const guest = isGuest(me);
  const subtitle = local
    ? "No sign-in on this server"
    : guest && me.user.expiresAt
      ? `Sandbox, deleted ${sandboxEndText(me.user.expiresAt)}`
      : (me.user.email ?? "No email shared");
  const onSwitch = (id: string) => {
    if (id === workspace?.id) return;
    switchWorkspace(id);
    const next = me.workspaces.find((w) => w.id === id);
    toast.success(`Switched to ${next?.name ?? "the workspace"}`);
    if (inEditor) router.push("/projects");
  };
  const onSignOut = async (everywhere: boolean) => {
    try {
      await signOut(everywhere);
      router.push("/");
    } catch (e) {
      toast.error("Could not sign out", { description: e instanceof Error ? e.message : String(e) });
    }
  };

  return (
    <Menu>
      <MenuTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          className={cn("h-8 max-w-56 gap-2 px-1.5", className)}
          aria-label={`Account and workspace: ${me.user.name}, ${workspace?.name ?? "no workspace"}`}
          data-testid="user-menu"
        >
          <Avatar name={me.user.name} />
          {showWorkspace && workspace && <span className="hidden min-w-0 truncate text-[12.5px] text-ink-2 lg:inline">{workspace.name}</span>}
        </Button>
      </MenuTrigger>
      <MenuContent align="end" className="w-72">
        <div className="flex items-center gap-2.5 px-2 py-2">
          <Avatar name={me.user.name} className="size-8 text-xs" />
          <div className="min-w-0">
            <p className="truncate text-[13px] font-medium text-ink">{local ? "Local user" : me.user.name}</p>
            <p className="truncate text-xs text-ink-3">{subtitle}</p>
          </div>
        </div>
        <MenuSeparator />
        <MenuLabel>Workspace</MenuLabel>
        {me.workspaces.map((w) => (
          <MenuItem key={w.id} onSelect={() => onSwitch(w.id)} aria-checked={w.id === workspace?.id} role="menuitemradio">
            <Users />
            <span className="min-w-0 flex-1 truncate">{w.name}</span>
            <span className="text-[11px] text-ink-3">
              {KIND_LABEL[w.kind]} · {w.role}
            </span>
            {w.id === workspace?.id ? <Check className="!text-accent" aria-label="current" /> : <span className="size-4" />}
          </MenuItem>
        ))}
        <MenuSeparator />
        <MenuItem asChild>
          <Link href="/settings">
            <Settings /> Settings
          </Link>
        </MenuItem>
        {me.kind === "session" && guest && (
          <>
            <MenuItem asChild>
              <a href={signInPage()}>
                <LogIn /> Sign in to keep your work
              </a>
            </MenuItem>
            <MenuItem onSelect={() => void onSignOut(false)}>
              <LogOut /> Leave the sandbox
            </MenuItem>
          </>
        )}
        {me.kind === "session" && !guest && (
          <>
            <MenuItem onSelect={() => void onSignOut(false)}>
              <LogOut /> Sign out
            </MenuItem>
            <MenuItem onSelect={() => void onSignOut(true)}>
              <LogOut /> Sign out everywhere
            </MenuItem>
          </>
        )}
      </MenuContent>
    </Menu>
  );
}

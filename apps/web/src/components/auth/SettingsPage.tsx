"use client";

/**
 * /settings: the current workspace (name, limits), its members and invitations (owners manage
 * them), new team workspaces, API tokens and signing out. A sandbox's guest sees when the sandbox
 * is deleted and how to sign in instead, as team workspaces and tokens need an account.
 */
import { ArrowLeft, Check, Copy, LoaderCircle, LogIn, LogOut, Plus, Trash2, UserMinus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { TokensSection } from "@/components/auth/TokensSection";
import { Avatar, UserMenu } from "@/components/auth/UserMenu";
import { Logo } from "@/components/brand";
import { Button } from "@/components/ui/button";
import { Input, Label, NativeSelect } from "@/components/ui/input";
import { Badge } from "@/components/ui/misc";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { authApi } from "@/lib/auth-api";
import { ROLE_HELP, ROLES, type Invitation, type Member, type Role, type WorkspaceInfo } from "@/lib/auth-types";
import { isGuest, sandboxEndText } from "@/lib/sandbox";
import { signInPage } from "@/lib/session";
import { useAuth, useAuthState } from "@/store/auth";

const fail = (title: string) => (e: unknown) => toast.error(title, { description: e instanceof Error ? e.message : String(e) });

function Card({ title, description, children }: { title: string; description?: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="rounded-xl border border-line bg-surface p-5 shadow-panel">
      <h2 className="font-display text-[20px] leading-tight text-ink">{title}</h2>
      {description && <p className="mt-1 text-[13px] text-ink-2">{description}</p>}
      <div className="mt-4">{children}</div>
    </section>
  );
}

/** "Limits: 2 runs at once, 60 runs an hour." from the limits that apply (null: none). */
export function limitsText(atOnce: number | null, perHour: number | null): string {
  const parts = [
    ...(atOnce == null ? [] : [`${atOnce} ${atOnce === 1 ? "run" : "runs"} at once`]),
    ...(perHour == null ? [] : [`${perHour} runs an hour`]),
  ];
  return parts.length ? `Limits: ${parts.join(", ")}.` : "No run limits beyond the server's own.";
}

function WorkspaceCard({ workspace }: { workspace: WorkspaceInfo }) {
  const [name, setName] = useState(workspace.name);
  const [busy, setBusy] = useState(false);
  const owner = workspace.role === "owner";
  const router = useRouter();

  const rename = async () => {
    setBusy(true);
    try {
      await authApi.renameWorkspace(workspace.id, name.trim());
      await useAuth.getState().load(true);
      toast.success("Workspace renamed");
    } catch (e) {
      fail("Could not rename")(e);
    } finally {
      setBusy(false);
    }
  };
  const remove = async () => {
    if (!window.confirm(`Delete “${workspace.name}” with all its projects and runs? This cannot be undone.`)) return;
    try {
      await authApi.deleteWorkspace(workspace.id);
      await useAuth.getState().load(true);
      toast.success("Workspace deleted");
      router.push("/projects");
    } catch (e) {
      fail("Could not delete")(e);
    }
  };

  return (
    <Card
      title="Workspace"
      description={
        <>
          Projects and runs belong to a workspace. You are <strong className="font-medium text-ink">{workspace.role}</strong> here:{" "}
          {ROLE_HELP[workspace.role].toLowerCase()}.
        </>
      }
    >
      <div className="flex flex-wrap items-end gap-2">
        <div className="min-w-56 flex-1 space-y-1">
          <Label htmlFor="ws-name">Name</Label>
          <Input id="ws-name" value={name} onChange={(e) => setName(e.target.value)} disabled={!owner} maxLength={120} />
        </div>
        {owner && (
          <Button onClick={() => void rename()} disabled={busy || !name.trim() || name.trim() === workspace.name}>
            {busy ? <LoaderCircle className="animate-spin" /> : <Check />} Save
          </Button>
        )}
      </div>
      <p className="mt-3 flex flex-wrap items-center gap-2 text-xs text-ink-2">
        <Badge>{workspace.kind}</Badge>
        {limitsText(workspace.maxConcurrentRuns, workspace.maxRunsPerHour)}
      </p>
      {owner && workspace.kind === "team" && workspace.id !== "default" && (
        <Button variant="danger" size="sm" className="mt-4" onClick={() => void remove()}>
          <Trash2 /> Delete workspace
        </Button>
      )}
    </Card>
  );
}

function MembersCard({ workspace, meId }: { workspace: WorkspaceInfo; meId: string }) {
  const [members, setMembers] = useState<Member[] | null>(null);
  const owner = workspace.role === "owner";
  const router = useRouter();
  const refresh = useCallback(() => {
    authApi.members(workspace.id).then(setMembers, fail("Could not load members"));
  }, [workspace.id]);
  useEffect(refresh, [refresh]);

  const setRole = async (m: Member, role: Role) => {
    try {
      await authApi.setRole(workspace.id, m.userId, role);
      refresh();
      if (m.userId === meId) await useAuth.getState().load(true);
    } catch (e) {
      fail("Could not change the role")(e);
    }
  };
  const remove = async (m: Member) => {
    const leaving = m.userId === meId;
    if (!window.confirm(leaving ? `Leave “${workspace.name}”?` : `Remove ${m.name} from “${workspace.name}”?`)) return;
    try {
      await authApi.removeMember(workspace.id, m.userId);
      if (leaving) {
        await useAuth.getState().load(true);
        router.push("/projects");
      } else refresh();
    } catch (e) {
      fail(leaving ? "Could not leave" : "Could not remove")(e);
    }
  };

  return (
    <Card title="Members" description={owner ? "Change roles or remove people. A workspace always keeps an owner." : undefined}>
      {members === null ? (
        <LoaderCircle className="size-4 animate-spin text-ink-3" />
      ) : (
        <ul className="divide-y divide-line" aria-label="Members">
          {members.map((m) => (
            <li key={m.userId} className="flex flex-wrap items-center gap-3 py-2.5">
              <Avatar name={m.name} />
              <div className="min-w-0 flex-1">
                <p className="truncate text-[13px] font-medium text-ink">
                  {m.name} {m.userId === meId && <span className="font-normal text-ink-3">(you)</span>}
                </p>
                <p className="truncate text-xs text-ink-3">{m.email ?? "no email"}</p>
              </div>
              {owner && workspace.kind !== "personal" ? (
                <NativeSelect
                  aria-label={`Role of ${m.name}`}
                  value={m.role}
                  onChange={(e) => void setRole(m, e.target.value as Role)}
                  className="w-28"
                >
                  {ROLES.map((r) => (
                    <option key={r} value={r}>
                      {r}
                    </option>
                  ))}
                </NativeSelect>
              ) : (
                <Badge>{m.role}</Badge>
              )}
              {workspace.kind !== "personal" && (owner || m.userId === meId) && (
                <Button variant="ghost" size="icon-sm" aria-label={m.userId === meId ? "Leave workspace" : `Remove ${m.name}`} onClick={() => void remove(m)}>
                  <UserMinus />
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function InvitationsCard({ workspace }: { workspace: WorkspaceInfo }) {
  const [pending, setPending] = useState<Invitation[]>([]);
  const [role, setRole] = useState<Role>("editor");
  const [email, setEmail] = useState("");
  const [link, setLink] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const refresh = useCallback(() => {
    authApi.invitations(workspace.id).then(setPending, fail("Could not load invitations"));
  }, [workspace.id]);
  useEffect(refresh, [refresh]);

  const create = async () => {
    setBusy(true);
    try {
      const inv = await authApi.invite(workspace.id, { role, email: email.trim() || null });
      setLink(`${window.location.origin}/invite/${inv.token}`);
      setEmail("");
      refresh();
    } catch (e) {
      fail("Could not create the invitation")(e);
    } finally {
      setBusy(false);
    }
  };
  const copy = async () => {
    if (!link) return;
    try {
      await navigator.clipboard.writeText(link);
      toast.success("Link copied");
    } catch {
      toast.error("Copy the link by hand: the clipboard is blocked");
    }
  };
  const revoke = async (inv: Invitation) => {
    try {
      await authApi.revokeInvitation(workspace.id, inv.id);
      refresh();
    } catch (e) {
      fail("Could not revoke")(e);
    }
  };

  return (
    <Card title="Invite people" description="A link anyone signed in can use once, within 7 days. Give an email to make it work only for that account.">
      <div className="flex flex-wrap items-end gap-2">
        <div className="space-y-1">
          <Label htmlFor="inv-role">Role</Label>
          <NativeSelect id="inv-role" value={role} onChange={(e) => setRole(e.target.value as Role)} className="w-28">
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="min-w-48 flex-1 space-y-1">
          <Label htmlFor="inv-email">Email (optional)</Label>
          <Input id="inv-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@company.com" />
        </div>
        <Button variant="primary" onClick={() => void create()} disabled={busy}>
          {busy ? <LoaderCircle className="animate-spin" /> : <Plus />} Create link
        </Button>
      </div>
      <p className="mt-1.5 text-xs text-ink-3">{ROLE_HELP[role]}.</p>
      {link && (
        <div className="mt-3 flex items-center gap-2 rounded-lg border border-accent/30 bg-accent-soft p-2">
          <code className="min-w-0 flex-1 truncate font-mono text-xs text-ink" data-testid="invite-link">
            {link}
          </code>
          <Button size="sm" onClick={() => void copy()}>
            <Copy /> Copy
          </Button>
        </div>
      )}
      {link && <p className="mt-1 text-xs text-ink-3">Shown once. Send it to the person you are inviting.</p>}
      {pending.length > 0 && (
        <ul className="mt-4 divide-y divide-line border-t border-line" aria-label="Pending invitations">
          {pending.map((inv) => (
            <li key={inv.id} className="flex items-center gap-3 py-2 text-[13px]">
              <span className="min-w-0 flex-1 truncate text-ink">{inv.email ?? "Anyone with the link"}</span>
              <Badge>{inv.role}</Badge>
              <span className="text-xs text-ink-3">until {new Date(inv.expiresAt).toLocaleDateString()}</span>
              <Button variant="ghost" size="icon-sm" aria-label="Revoke invitation" onClick={() => void revoke(inv)}>
                <Trash2 />
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function NewWorkspaceCard() {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const create = async () => {
    setBusy(true);
    try {
      const ws = await authApi.createWorkspace(name.trim());
      await useAuth.getState().load(true);
      useAuth.getState().switchWorkspace(ws.id);
      setName("");
      toast.success(`Created “${ws.name}”`, { description: "Invite people to it below." });
    } catch (e) {
      fail("Could not create the workspace")(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card title="New team workspace" description="Share projects with your team: you become its owner and can invite people.">
      <form
        className="flex flex-wrap items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (name.trim()) void create();
        }}
      >
        <div className="min-w-56 flex-1 space-y-1">
          <Label htmlFor="new-ws">Name</Label>
          <Input id="new-ws" value={name} onChange={(e) => setName(e.target.value)} placeholder="Platform team" maxLength={120} />
        </div>
        <Button type="submit" disabled={busy || !name.trim()}>
          {busy ? <LoaderCircle className="animate-spin" /> : <Plus />} Create
        </Button>
      </form>
    </Card>
  );
}

export function SettingsPage() {
  const { status, me, workspace } = useAuthState();
  const guest = isGuest(me);
  const signOut = useAuth((s) => s.signOut);
  const router = useRouter();

  useEffect(() => {
    if (status === "signed-out") window.location.replace(signInPage("/settings"));
  }, [status]);

  return (
    <div className="min-h-full bg-page">
      <header className="sticky top-0 z-30 border-b border-line bg-page">
        <div className="mx-auto flex h-14 max-w-4xl items-center gap-4 px-4 sm:px-6">
          <Logo />
          <Button variant="ghost" size="sm" asChild>
            <Link href="/projects">
              <ArrowLeft /> Projects
            </Link>
          </Button>
          <div className="ml-auto flex items-center gap-1.5">
            <ThemeToggle />
            <UserMenu />
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-4xl space-y-5 px-4 py-8 sm:px-6">
        <h1 className="font-display text-[32px] leading-tight text-ink">Settings</h1>
        {status !== "ready" || !me || !workspace ? (
          <p className="flex items-center gap-2 text-sm text-ink-2" role="status">
            <LoaderCircle className="size-4 animate-spin" /> Loading…
          </p>
        ) : (
          <>
            <WorkspaceCard key={`${workspace.id}:${workspace.name}`} workspace={workspace} />
            <MembersCard workspace={workspace} meId={me.user.id} />
            {me.kind !== "local" && workspace.role === "owner" && workspace.kind === "team" && <InvitationsCard workspace={workspace} />}
            {!guest && <NewWorkspaceCard />}
            {me.kind === "session" && !guest && <TokensSection workspaces={me.workspaces} />}
            {me.kind === "local" && (
              <Card title="Sign-in is off on this server">
                <p className="text-[13px] text-ink-2">
                  You are the local owner of every workspace here. Inviting people and API tokens need sign-in: run the API with{" "}
                  <code className="font-mono text-xs">ARCHSIM_AUTH_MODE=oauth</code> (see SECURITY.md).
                </p>
              </Card>
            )}
            {guest && me.user.expiresAt && (
              <Card
                title="Sandbox"
                description={`You are trying ArchSim without an account. This sandbox and its designs are deleted ${sandboxEndText(me.user.expiresAt)}.`}
              >
                <p className="text-[13px] text-ink-2">
                  Team workspaces, invitations, API tokens and share links need an account. Signing in starts a workspace of your own; the
                  sandbox&apos;s designs are not moved to it.
                </p>
                <div className="mt-3 flex flex-wrap gap-2">
                  <Button variant="primary" asChild>
                    <a href={signInPage()}>
                      <LogIn /> Sign in to keep your work
                    </a>
                  </Button>
                  <Button variant="ghost" onClick={() => void signOut(false).then(() => router.push("/"))}>
                    <LogOut /> Leave the sandbox
                  </Button>
                </div>
              </Card>
            )}
            {me.kind === "session" && !guest && (
              <Card title="Account" description={`Signed in as ${me.user.name}${me.user.email ? ` (${me.user.email})` : ""}.`}>
                <div className="flex flex-wrap gap-2">
                  <Button onClick={() => void signOut(false).then(() => router.push("/"))}>
                    <LogOut /> Sign out
                  </Button>
                  <Button variant="ghost" onClick={() => void signOut(true).then(() => router.push("/"))}>
                    Sign out on every device
                  </Button>
                </div>
              </Card>
            )}
          </>
        )}
      </main>
    </div>
  );
}

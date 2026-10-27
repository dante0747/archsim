"use client";

/** Settings: personal API tokens for the CLI, CI and MCP (sent as Authorization: Bearer). */
import { Copy, KeyRound, LoaderCircle, Plus, Trash2 } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input, Label, NativeSelect } from "@/components/ui/input";
import { Badge } from "@/components/ui/misc";
import { authApi } from "@/lib/auth-api";
import type { ApiToken, WorkspaceInfo } from "@/lib/auth-types";
import { fmtRelativeTime } from "@/lib/format";

const EXPIRY = [
  ["30", "30 days"],
  ["90", "90 days"],
  ["365", "1 year"],
  ["", "Never"],
] as const;

export function TokensSection({ workspaces }: { workspaces: WorkspaceInfo[] }) {
  const [tokens, setTokens] = useState<ApiToken[] | null>(null);
  const [name, setName] = useState("");
  const [scope, setScope] = useState<"read" | "write">("write");
  const [workspaceId, setWorkspaceId] = useState("");
  const [days, setDays] = useState("90");
  const [shown, setShown] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const fail = (title: string) => (e: unknown) => toast.error(title, { description: e instanceof Error ? e.message : String(e) });
  const refresh = useCallback(() => {
    authApi.tokens().then(setTokens, (e: unknown) => toast.error("Could not load tokens", { description: String(e) }));
  }, []);
  useEffect(refresh, [refresh]);

  const create = async () => {
    setBusy(true);
    try {
      const t = await authApi.createToken({
        name: name.trim(),
        scope,
        workspaceId: workspaceId || null,
        expiresInDays: days ? Number(days) : null,
      });
      setShown(t.token);
      setName("");
      refresh();
    } catch (e) {
      fail("Could not create the token")(e);
    } finally {
      setBusy(false);
    }
  };
  const revoke = async (t: ApiToken) => {
    if (!window.confirm(`Revoke “${t.name}”? Anything using it stops working at once.`)) return;
    try {
      await authApi.revokeToken(t.id);
      refresh();
    } catch (e) {
      fail("Could not revoke")(e);
    }
  };
  const copy = async () => {
    if (!shown) return;
    try {
      await navigator.clipboard.writeText(shown);
      toast.success("Token copied");
    } catch {
      toast.error("Copy the token by hand: the clipboard is blocked");
    }
  };
  const wsName = (id: string | null) => (id ? (workspaces.find((w) => w.id === id)?.name ?? "a workspace") : "All your workspaces");

  return (
    <section className="rounded-xl border border-line bg-surface p-5 shadow-panel">
      <h2 className="font-display text-[20px] leading-tight text-ink">API tokens</h2>
      <p className="mt-1 text-[13px] text-ink-2">
        For the CLI, CI and MCP: send one as <code className="font-mono text-xs">Authorization: Bearer asim_pat_…</code> to{" "}
        <code className="font-mono text-xs">/api/v1</code> on this site. A token acts as you; a read token only views.
      </p>
      <form
        className="mt-4 grid gap-2 sm:grid-cols-[1fr_auto_auto_auto_auto] sm:items-end"
        onSubmit={(e) => {
          e.preventDefault();
          if (name.trim()) void create();
        }}
      >
        <div className="space-y-1">
          <Label htmlFor="tok-name">Name</Label>
          <Input id="tok-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="GitHub Actions" maxLength={100} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="tok-scope">Access</Label>
          <NativeSelect id="tok-scope" value={scope} onChange={(e) => setScope(e.target.value as "read" | "write")} className="w-24">
            <option value="write">write</option>
            <option value="read">read</option>
          </NativeSelect>
        </div>
        <div className="space-y-1">
          <Label htmlFor="tok-ws">Workspace</Label>
          <NativeSelect id="tok-ws" value={workspaceId} onChange={(e) => setWorkspaceId(e.target.value)} className="w-40">
            <option value="">All of mine</option>
            {workspaces.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1">
          <Label htmlFor="tok-exp">Expires</Label>
          <NativeSelect id="tok-exp" value={days} onChange={(e) => setDays(e.target.value)} className="w-28">
            {EXPIRY.map(([v, label]) => (
              <option key={label} value={v}>
                {label}
              </option>
            ))}
          </NativeSelect>
        </div>
        <Button type="submit" variant="primary" disabled={busy || !name.trim()}>
          {busy ? <LoaderCircle className="animate-spin" /> : <Plus />} Create token
        </Button>
      </form>
      {shown && (
        <div className="mt-3 rounded-lg border border-accent/30 bg-accent-soft p-2">
          <div className="flex items-center gap-2">
            <code className="min-w-0 flex-1 truncate font-mono text-xs text-ink" data-testid="new-token">
              {shown}
            </code>
            <Button size="sm" onClick={() => void copy()}>
              <Copy /> Copy
            </Button>
          </div>
          <p className="mt-1 px-1 text-xs text-ink-2">Shown once: store it in your CI secrets now. Lost tokens are revoked and replaced.</p>
        </div>
      )}
      <div className="mt-4">
        {tokens === null ? (
          <LoaderCircle className="size-4 animate-spin text-ink-3" />
        ) : tokens.length === 0 ? (
          <p className="text-[13px] text-ink-3">No tokens yet.</p>
        ) : (
          <ul className="divide-y divide-line border-t border-line" aria-label="API tokens">
            {tokens.map((t) => (
              <li key={t.id} className="flex flex-wrap items-center gap-3 py-2.5 text-[13px]">
                <KeyRound className="size-4 text-ink-3" aria-hidden />
                <div className="min-w-0 flex-1">
                  <p className="truncate font-medium text-ink">{t.name}</p>
                  <p className="truncate text-xs text-ink-3">
                    <span className="font-mono">{t.prefix}…</span> · {wsName(t.workspaceId)} ·{" "}
                    {t.lastUsedAt ? `used ${fmtRelativeTime(t.lastUsedAt)}` : "never used"} ·{" "}
                    {t.expiresAt ? `until ${new Date(t.expiresAt).toLocaleDateString()}` : "never expires"}
                  </p>
                </div>
                <Badge tone={t.scope === "write" ? "accent" : "neutral"}>{t.scope}</Badge>
                <Button variant="ghost" size="icon-sm" aria-label={`Revoke ${t.name}`} onClick={() => void revoke(t)}>
                  <Trash2 />
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

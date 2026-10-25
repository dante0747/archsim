/**
 * Instant client-side connection checks, derived from the component catalog's port specs.
 * The server remains the source of truth (POST /validate); these rules only give immediate
 * feedback while drawing links.
 */
import { type ArchEdge, type ArchNode, edgeSourcePort, edgeTargetPort, sideOf } from "./document";
import type { Catalog, ComponentType, PortSpec, RequestType } from "./types";

export type ConnectionCheck =
  | { ok: true; sourcePort: PortSpec; targetPort: PortSpec; requestTypes: RequestType[] }
  | { ok: false; reason: string };

export interface ConnectionLike {
  source: string | null;
  target: string | null;
  sourceHandle?: string | null;
  targetHandle?: string | null;
}

export function componentOf(catalog: Catalog | null, type: string): ComponentType | undefined {
  return catalog?.components.find((c) => c.type === type);
}

/** True if adding source -> target would close a directed cycle. */
export function wouldCreateCycle(source: string, target: string, edges: Pick<ArchEdge, "source" | "target">[]): boolean {
  if (source === target) return true;
  const out = new Map<string, string[]>();
  for (const e of edges) {
    const list = out.get(e.source) ?? [];
    list.push(e.target);
    out.set(e.source, list);
  }
  const stack = [target];
  const seen = new Set<string>();
  while (stack.length) {
    const v = stack.pop()!;
    if (v === source) return true;
    if (seen.has(v)) continue;
    seen.add(v);
    for (const w of out.get(v) ?? []) stack.push(w);
  }
  return false;
}

export function checkConnection(
  conn: ConnectionLike,
  nodes: ArchNode[],
  edges: ArchEdge[],
  catalog: Catalog | null,
): ConnectionCheck {
  const { source, target } = conn;
  if (!source || !target) return { ok: false, reason: "Drop the link on a component's input handle." };
  if (source === target) return { ok: false, reason: "A component cannot call itself." };
  const sn = nodes.find((n) => n.id === source);
  const tn = nodes.find((n) => n.id === target);
  if (!sn || !tn) return { ok: false, reason: "Unknown component." };
  const st = componentOf(catalog, sn.data.type);
  const tt = componentOf(catalog, tn.data.type);
  if (!st || !tt) return { ok: false, reason: "Unknown component type." };

  // A side handle ("s-top") means "this component's output"; a port name picks that port.
  const outPorts = st.ports.filter((p) => p.direction === "out");
  const wantOut = sideOf(conn.sourceHandle) ? null : (conn.sourceHandle ?? null);
  const sp = wantOut ? outPorts.find((p) => p.name === wantOut) : outPorts[0];
  if (!sp) return { ok: false, reason: `${sn.data.label} (${st.displayName}) cannot send requests.` };
  const inPorts = tt.ports.filter((p) => p.direction === "in");
  if (inPorts.length === 0) {
    return {
      ok: false,
      reason: `${tn.data.label} (${tt.displayName}) cannot receive requests; it only generates them.`,
    };
  }
  const wantIn = sideOf(conn.targetHandle) ? null : (conn.targetHandle ?? null);
  const tp = inPorts.find((p) => p.name === (wantIn ?? inPorts[0].name)) ?? inPorts[0];

  const common = sp.requestTypes.filter((t) => tp.requestTypes.includes(t));
  if (common.length === 0) {
    return { ok: false, reason: `'${sp.label}' emits ${sp.requestTypes.join("/")} but ${tn.data.label} does not accept them.` };
  }
  if (sp.maxEdges != null) {
    const used = edges.filter((e) => e.source === source && edgeSourcePort(e) === sp.name).length;
    if (used >= sp.maxEdges) {
      return {
        ok: false,
        reason: `'${sp.label}' on ${sn.data.label} allows only ${sp.maxEdges} link. Delete the existing one first${
          st.isTrafficSource ? " (fan out with a load balancer)" : ""
        }.`,
      };
    }
  }
  if (
    edges.some(
      (e) =>
        e.source === source &&
        e.target === target &&
        edgeSourcePort(e) === sp.name &&
        edgeTargetPort(e) === tp.name,
    )
  ) {
    return { ok: false, reason: `${sn.data.label} is already linked to ${tn.data.label}. Edit that link's request types instead.` };
  }
  if (wouldCreateCycle(source, target, edges)) {
    return {
      ok: false,
      reason: `This link would create a loop (${tn.data.label} already reaches ${sn.data.label}). Synchronous call graphs must be acyclic.`,
    };
  }
  return { ok: true, sourcePort: sp, targetPort: tp, requestTypes: common };
}

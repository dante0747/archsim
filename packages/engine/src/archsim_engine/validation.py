"""Document validation: structure, ports, request-type routing and cycles.

Every problem becomes an :class:`~archsim_engine.issues.Issue` with a stable ``code``, the node or
edge it concerns and a hint on how to fix it. Errors block simulation; warnings do not.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from typing import Any

from pydantic import BaseModel, ValidationError

from archsim_engine.issues import Issue, error, warning
from archsim_engine.registry import ComponentRegistry, GraphView
from archsim_engine.schema import ArchitectureDocument, EdgeSpec, NodeSpec


@dataclass
class ValidationResult:
    issues: list[Issue]
    configs: dict[str, BaseModel] = field(default_factory=dict)
    view: GraphView | None = None
    edge_flows: dict[str, set[str]] = field(default_factory=dict)

    @property
    def errors(self) -> list[Issue]:
        return [i for i in self.issues if i.severity == "error"]

    @property
    def warnings(self) -> list[Issue]:
        return [i for i in self.issues if i.severity == "warning"]

    @property
    def valid(self) -> bool:
        return not self.errors

    def to_dict(self) -> dict[str, Any]:
        return {"valid": self.valid, "issues": [i.to_dict() for i in self.issues]}


def pydantic_issues(exc: ValidationError, raw: Any = None, prefix: str = "") -> list[Issue]:
    """Convert a Pydantic error into issues, resolving node/edge ids from list indices."""
    issues = []
    for err in exc.errors():
        loc = list(err["loc"])
        node_id = edge_id = None
        if isinstance(raw, dict) and len(loc) >= 3 and loc[0] == "architecture":
            coll = loc[1]
            idx = loc[2]
            items = (raw.get("architecture") or {}).get(coll)
            if isinstance(idx, int) and isinstance(items, list) and idx < len(items):
                ident = items[idx].get("id") if isinstance(items[idx], dict) else None
                if coll == "nodes":
                    node_id = ident
                elif coll == "edges":
                    edge_id = ident
        path = ".".join(str(p) for p in loc)
        where = f"node '{node_id}'" if node_id else f"link '{edge_id}'" if edge_id else path
        issues.append(
            error(
                "document.schema",
                f"{where}: {err['msg']} (at {prefix}{path})",
                node_id=node_id,
                edge_id=edge_id,
                path=prefix + path,
            )
        )
    return issues


def _name(n: NodeSpec) -> str:
    return n.label or n.id


def _find_cycle(node_ids: list[str], out: dict[str, list[EdgeSpec]]) -> list[str] | None:
    """Return one directed cycle as a list of node ids (first == last), or None."""
    WHITE, GREY, BLACK = 0, 1, 2
    color = dict.fromkeys(node_ids, WHITE)
    parent: dict[str, str] = {}

    for root in node_ids:
        if color[root] != WHITE:
            continue
        stack: list[tuple[str, int]] = [(root, 0)]
        color[root] = GREY
        while stack:
            u, i = stack[-1]
            edges = out.get(u, [])
            if i < len(edges):
                stack[-1] = (u, i + 1)
                v = edges[i].target
                if v not in color:
                    continue
                if color[v] == GREY:
                    cycle = [v]
                    w = u
                    while w != v:
                        cycle.append(w)
                        w = parent[w]
                    cycle.append(v)
                    cycle.reverse()
                    return cycle
                if color[v] == WHITE:
                    color[v] = GREY
                    parent[v] = u
                    stack.append((v, 0))
            else:
                color[u] = BLACK
                stack.pop()
    return None


def _topological(node_ids: list[str], out: dict[str, list[EdgeSpec]]) -> list[str]:
    indeg = dict.fromkeys(node_ids, 0)
    for edges in out.values():
        for e in edges:
            if e.target in indeg:
                indeg[e.target] += 1
    q = deque(n for n in node_ids if indeg[n] == 0)
    order = []
    while q:
        u = q.popleft()
        order.append(u)
        for e in out.get(u, []):
            indeg[e.target] -= 1
            if indeg[e.target] == 0:
                q.append(e.target)
    return order


def _fault_issues(
    doc: ArchitectureDocument,
    node_map: dict[str, NodeSpec],
    edge_ids: set[str],
    registry: ComponentRegistry,
) -> list[Issue]:
    """Faults must target something that exists, of the right kind, inside the run."""
    issues: list[Issue] = []
    duration = doc.workload.duration_s
    for f in doc.workload.faults:
        what = f.label or f"{f.kind} fault '{f.id}'"
        path = f"workload.faults.{f.id}"
        node = node_map.get(f.target)
        if f.kind == "latency":
            if f.target not in edge_ids:
                if node is not None:
                    issues.append(
                        error(
                            "fault_wrong_target",
                            f"{what} targets component '{_name(node)}', but latency faults apply to links.",
                            node_id=node.id,
                            path=path,
                            hint="Pick a link as the target, or use an outage/slowdown fault for a component.",
                        )
                    )
                else:
                    issues.append(
                        error(
                            "fault_unknown_target",
                            f"{what} targets link '{f.target}', which does not exist.",
                            path=path,
                            hint="Pick an existing link or delete the fault.",
                        )
                    )
                continue
        else:
            if node is None:
                if f.target in edge_ids:
                    issues.append(
                        error(
                            "fault_wrong_target",
                            f"{what} targets link '{f.target}', but {f.kind} faults apply to components.",
                            edge_id=f.target,
                            path=path,
                            hint="Pick a component as the target, or use a latency fault for a link.",
                        )
                    )
                else:
                    issues.append(
                        error(
                            "fault_unknown_target",
                            f"{what} targets component '{f.target}', which does not exist.",
                            path=path,
                            hint="Pick an existing component or delete the fault.",
                        )
                    )
                continue
            if node.type in registry and registry.get(node.type).is_traffic_source:
                issues.append(
                    error(
                        "fault_wrong_target",
                        f"{what} targets the traffic source '{_name(node)}'; faults apply to the system, not the clients.",
                        node_id=node.id,
                        path=path,
                        hint="Target a component the clients call, or change the workload instead.",
                    )
                )
                continue
        loc = {"node_id": node.id} if node is not None else {"edge_id": f.target}
        end = f.start_s + f.duration_s
        if f.start_s >= duration:
            issues.append(
                error(
                    "fault_outside_run",
                    f"{what} starts at {f.start_s:g} s, after the run ends ({duration:g} s), so it would never happen.",
                    path=path,
                    hint="Move the fault inside the run or make the run longer.",
                    **loc,
                )
            )
        elif end > duration:
            issues.append(
                warning(
                    "fault_clipped",
                    f"{what} runs until {end:g} s but arrivals stop at {duration:g} s; only the part inside the run has an effect on new requests.",
                    path=path,
                    hint="Shorten the fault or make the run longer.",
                    **loc,
                )
            )
    return issues


DEFAULT_MAX_AMPLIFICATION = 10_000
"""Most component invocations one client request may cause (fan-out x retries); see
``_amplification``. Above a tenth of it, a warning."""


def _amplification(
    sources: list[NodeSpec],
    node_map: dict[str, NodeSpec],
    out: dict[str, list[EdgeSpec]],
    configs: dict[str, BaseModel],
    registry: ComponentRegistry,
) -> tuple[float, list[str]]:
    """Worst-case component invocations one client request can cause, and the path that drives it.

    One call into a part costs 1 plus: a service, cache or client calls every dependency, so the
    sum over its links of (attempts x what one call there costs); a load balancer or queue sends
    each call to one target, so the most expensive link. Retries multiply. Linear in the size of
    the (acyclic) graph, however many paths it has."""
    memo: dict[str, tuple[float, str | None]] = {}

    def attempts(e: EdgeSpec) -> int:
        return max(1, e.config.max_attempts)

    def cost(nid: str) -> tuple[float, str | None]:
        if nid in memo:
            return memo[nid]
        n = node_map[nid]
        category = registry.get(n.type).category if n.type in registry else ""
        links = [
            (attempts(e) * cost(e.target)[0], e.target)
            for e in out.get(nid, [])
            if e.target in node_map
        ]
        if not links:
            result: tuple[float, str | None] = (1.0, None)
        elif category in ("network", "messaging"):
            best = max(links, key=lambda x: x[0])
            result = (1.0 + best[0], best[1])
        else:
            result = (1.0 + sum(x[0] for x in links), max(links, key=lambda x: x[0])[1])
        memo[nid] = result
        return result

    worst = 0.0
    worst_path: list[str] = []
    for s in sources:
        cfg = configs.get(s.id)
        client_attempts = max(1, int(getattr(cfg, "max_attempts", 1) or 1))
        total = client_attempts * cost(s.id)[0]
        if total > worst:
            worst = total
            path = [s.id]
            nxt = cost(s.id)[1]
            while nxt is not None and nxt not in path and len(path) < 50:
                path.append(nxt)
                nxt = cost(nxt)[1]
            worst_path = path
    return worst, worst_path


def validate_document(
    doc: ArchitectureDocument,
    registry: ComponentRegistry,
    max_amplification: float | None = DEFAULT_MAX_AMPLIFICATION,
) -> ValidationResult:
    """Check a document. ``max_amplification`` bounds the component invocations one client
    request may cause (None: no bound)."""
    issues: list[Issue] = []
    nodes = doc.architecture.nodes
    edges = doc.architecture.edges

    # -- identity -----------------------------------------------------------------------------
    node_map: dict[str, NodeSpec] = {}
    for n in nodes:
        if n.id in node_map:
            issues.append(
                error(
                    "node.duplicate_id",
                    f"Two nodes share the id '{n.id}'. Node ids must be unique.",
                    node_id=n.id,
                    hint="Rename one of the nodes.",
                )
            )
        node_map[n.id] = n
    seen_edges: set[str] = set()
    for e in edges:
        if e.id in seen_edges:
            issues.append(
                error("edge.duplicate_id", f"Two links share the id '{e.id}'.", edge_id=e.id)
            )
        seen_edges.add(e.id)

    # -- component types and configuration ---------------------------------------------------
    configs: dict[str, BaseModel] = {}
    for n in nodes:
        if n.type not in registry:
            issues.append(
                error(
                    "node.unknown_type",
                    f"Node '{_name(n)}' has unknown component type '{n.type}'.",
                    node_id=n.id,
                    hint=f"Known types: {', '.join(registry.types())}.",
                )
            )
            continue
        cfg, errs = registry.parse_node_config(n)
        issues.extend(errs)
        if cfg is not None:
            configs[n.id] = cfg

    # -- links ----------------------------------------------------------------------------------
    valid_edges: list[EdgeSpec] = []
    seen_conn: set[tuple[str, str, str, str]] = set()
    for e in edges:
        src = node_map.get(e.source)
        tgt = node_map.get(e.target)
        if src is None or tgt is None:
            missing = e.source if src is None else e.target
            issues.append(
                error(
                    "edge.unknown_node",
                    f"Link '{e.id}' references node '{missing}', which does not exist.",
                    edge_id=e.id,
                    hint="Delete the link or recreate the missing node.",
                )
            )
            continue
        if e.source == e.target:
            issues.append(
                error(
                    "edge.self_loop",
                    f"Link '{e.id}' connects {_name(src)} to itself.",
                    edge_id=e.id,
                    hint="A component cannot call itself; delete the link.",
                )
            )
            continue
        if src.type not in registry or tgt.type not in registry:
            continue
        sm, tm = registry.get(src.type), registry.get(tgt.type)
        sp = sm.port(e.source_port)
        if sp is None or sp.direction != "out":
            outs = ", ".join(p.name for p in sm.out_ports()) or "none"
            issues.append(
                error(
                    "edge.invalid_source_port",
                    f"{_name(src)} has no output port '{e.source_port}' (outputs: {outs}).",
                    edge_id=e.id,
                    node_id=src.id,
                    hint=(
                        f"{sm.display_name} cannot send requests."
                        if not sm.out_ports()
                        else f"Connect from one of: {outs}."
                    ),
                )
            )
            continue
        tp = tm.port(e.target_port)
        if tp is None or tp.direction != "in":
            ins = ", ".join(p.name for p in tm.in_ports())
            issues.append(
                error(
                    "edge.invalid_target_port",
                    (
                        f"{_name(tgt)} ({tm.display_name}) cannot receive requests."
                        if not tm.in_ports()
                        else f"{_name(tgt)} has no input port '{e.target_port}' (inputs: {ins})."
                    ),
                    edge_id=e.id,
                    node_id=tgt.id,
                    hint="Traffic sources only send requests; connect them to an entry point instead."
                    if not tm.in_ports()
                    else None,
                )
            )
            continue
        bad_src = set(e.config.request_types) - set(sp.request_types)
        if bad_src:
            issues.append(
                error(
                    "edge.request_types",
                    f"Link '{e.id}' carries {', '.join(sorted(bad_src))} but port '{sp.name}' of "
                    f"{_name(src)} only emits {', '.join(sp.request_types)}.",
                    edge_id=e.id,
                    hint=f"Limit the link's request types to {', '.join(sp.request_types)}.",
                )
            )
            continue
        bad_tgt = set(e.config.request_types) - set(tp.request_types)
        if bad_tgt:
            issues.append(
                error(
                    "edge.request_types",
                    f"Link '{e.id}' carries {', '.join(sorted(bad_tgt))} but {_name(tgt)} only "
                    f"accepts {', '.join(tp.request_types)} on '{tp.name}'.",
                    edge_id=e.id,
                )
            )
            continue
        key = (e.source, e.source_port, e.target, e.target_port)
        if key in seen_conn:
            issues.append(
                error(
                    "edge.duplicate",
                    f"{_name(src)} is linked to {_name(tgt)} more than once on the same ports.",
                    edge_id=e.id,
                    hint="Merge the links by selecting several request types on one link.",
                )
            )
            continue
        seen_conn.add(key)
        valid_edges.append(e)

    out_edges: dict[str, list[EdgeSpec]] = {}
    in_edges: dict[str, list[EdgeSpec]] = {}
    for e in valid_edges:
        out_edges.setdefault(e.source, []).append(e)
        in_edges.setdefault(e.target, []).append(e)

    # -- port arity -------------------------------------------------------------------------------
    for n in nodes:
        if n.type not in registry:
            continue
        model = registry.get(n.type)
        for p in model.ports:
            pool = out_edges if p.direction == "out" else in_edges
            count = sum(
                1
                for e in pool.get(n.id, [])
                if (e.source_port if p.direction == "out" else e.target_port) == p.name
            )
            if count < p.min_edges:
                issues.append(
                    error(
                        "port.missing_link",
                        f"{_name(n)}: '{p.label}' needs at least {p.min_edges} link(s). {p.description}",
                        node_id=n.id,
                        hint=f"Drag from the '{p.name}' handle of {_name(n)} to a target.",
                    )
                )
            if p.max_edges is not None and count > p.max_edges:
                issues.append(
                    error(
                        "port.too_many_links",
                        f"{_name(n)}: '{p.label}' allows at most {p.max_edges} link(s) but has {count}.",
                        node_id=n.id,
                        hint="Remove the extra links (put a load balancer in between to fan out).",
                    )
                )

    # -- traffic source -------------------------------------------------------------------------
    sources = [n for n in nodes if n.type in registry and registry.get(n.type).is_traffic_source]
    # Several client groups (e.g. mobile app, web browser, API consumers) share the workload by
    # their traffic share.
    if not sources:
        issues.append(
            error(
                "graph.no_source",
                "There is no client, so nothing generates load.",
                hint="Drag a client (Clients, Mobile app, Web browser or API consumers) onto the "
                "canvas and connect it.",
            )
        )

    # -- cycles -------------------------------------------------------------------------------------
    node_ids = list(node_map)
    cycle = _find_cycle(node_ids, out_edges)
    if cycle:
        names = " → ".join(_name(node_map[c]) for c in cycle)
        issues.append(
            error(
                "graph.cycle",
                f"Requests would loop forever: {names}. Synchronous call graphs must be acyclic.",
                node_id=cycle[0],
                hint="Remove one of the links in the loop.",
            )
        )

    # -- amplification: how much work one request can cause ---------------------------------
    if not cycle and sources and max_amplification is not None:
        amp, path = _amplification(sources, node_map, out_edges, configs, registry)
        route = " → ".join(_name(node_map[x]) for x in path)
        if amp > max_amplification:
            issues.append(
                error(
                    "design.amplification",
                    f"One request can cause up to {amp:,.0f} calls between parts (fan-out and retries "
                    f"multiplied along {route}), above the limit of {max_amplification:,.0f}. Simulating "
                    "it would take a very long time per request.",
                    node_id=path[1] if len(path) > 1 else path[0],
                    hint="Call fewer dependencies per service, remove a layer, or lower maxAttempts "
                    "(retry at one layer only).",
                )
            )
        elif amp > max_amplification / 10:
            issues.append(
                warning(
                    "design.amplification",
                    f"One request can cause up to {amp:,.0f} calls between parts (fan-out and retries "
                    f"multiplied along {route}); runs will be slow.",
                    node_id=path[1] if len(path) > 1 else path[0],
                    hint="Call fewer dependencies per service or lower maxAttempts.",
                )
            )

    view = GraphView(
        nodes=node_map,
        configs=configs,
        out_edges=out_edges,
        in_edges=in_edges,
        workload=doc.workload,
    )
    edge_flows: dict[str, set[str]] = {}

    # -- request-type routing --------------------------------------------------------------------
    if not cycle and len(sources) == 1:
        incoming: dict[str, set[str]] = {nid: set() for nid in node_ids}
        reached = {sources[0].id}
        for nid in _topological(node_ids, out_edges):
            n = node_map[nid]
            if n.type not in registry or nid not in configs:
                continue
            model = registry.get(n.type)
            if nid not in reached and not model.is_traffic_source:
                continue
            for e in out_edges.get(nid, []):
                emitted = model.emitted_types(e.source_port, incoming[nid], configs[nid], view)
                flow = emitted & set(e.config.request_types)
                edge_flows[e.id] = flow
                if flow:
                    incoming[e.target] |= flow
                    reached.add(e.target)
        view.incoming_types = incoming
        for n in nodes:
            # parts without ports (a Kubernetes cluster) are not on the request path
            off_path = n.type in registry and not registry.get(n.type).ports
            if n.id not in reached and not off_path:
                issues.append(
                    warning(
                        "node.unreachable",
                        f"{_name(n)} receives no traffic (not reachable from the traffic source).",
                        node_id=n.id,
                        hint="Connect it, or delete it if it is not part of this design.",
                    )
                )
        for e in valid_edges:
            if e.source in reached and not edge_flows.get(e.id):
                issues.append(
                    warning(
                        "edge.unused",
                        f"Link '{e.id}' never carries traffic: it accepts "
                        f"{', '.join(e.config.request_types)} but none of those reach "
                        f"{_name(node_map[e.source])}'s '{e.source_port}' port.",
                        edge_id=e.id,
                    )
                )

    # -- component-specific rules ------------------------------------------------------------------
    for n in nodes:
        cfg = configs.get(n.id)
        if cfg is None:
            continue
        issues.extend(registry.get(n.type).validate(n, cfg, view))

    # -- faults (chaos experiments) ----------------------------------------------------------------
    issues.extend(_fault_issues(doc, node_map, seen_edges, registry))

    # -- workload ---------------------------------------------------------------------------------
    if doc.workload.peak_rate() == 0:
        issues.append(
            warning(
                "workload.no_traffic",
                "The workload offers 0 requests/s, so the run will complete no requests.",
                hint="Set RPS above 0.",
            )
        )

    return ValidationResult(issues=issues, configs=configs, view=view, edge_flows=edge_flows)

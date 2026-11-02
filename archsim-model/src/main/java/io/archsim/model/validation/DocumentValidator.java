package io.archsim.model.validation;

import io.archsim.model.codec.BindingError;
import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.Port;
import io.archsim.model.component.client.ClientConfig;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.Fault;
import io.archsim.model.document.FaultKind;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.Pricing;
import io.archsim.model.document.RequestType;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.pricing.PricingException;
import io.archsim.model.text.PythonFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Checks a design the way the Python engine's {@code validate_document} does, issue for issue
 * and word for word: ids, component types and configs, links and their ports, the call graph
 * (clients, cycles, how much work one request causes), which request types reach which part, each
 * component type's own rules, faults, the workload and prices. Errors stop a simulation; warnings
 * do not.
 */
public final class DocumentValidator {

    /** The most component calls one client request may cause by default; above a tenth of it, a warning. */
    public static final double DEFAULT_MAX_AMPLIFICATION = 10_000;

    private static final String ARROW = " → ";

    private final ComponentRegistry registry;
    private final PriceSnapshots prices;

    public DocumentValidator(ComponentRegistry registry, PriceSnapshots prices) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.prices = Objects.requireNonNull(prices, "prices");
    }

    /** ArchSim's own component types and the price snapshots it ships. */
    public static DocumentValidator standard() {
        return new DocumentValidator(StandardComponents.registry(), PriceSnapshots.shipped());
    }

    /** Checks {@code document}, bounding the calls one request may cause at the default. */
    public ValidationResult validate(ArchitectureDocument document) {
        return validate(document, DEFAULT_MAX_AMPLIFICATION);
    }

    /**
     * Checks {@code document}.
     *
     * @param maxAmplification the most component calls one client request may cause; {@code null}
     *     for no bound
     */
    public ValidationResult validate(ArchitectureDocument document, @Nullable Double maxAmplification) {
        return new Run(document).validate(maxAmplification);
    }

    /** One validation: what it has found so far, step by step. */
    private final class Run {

        private final ArchitectureDocument document;
        private final List<NodeSpec> nodes;
        private final List<Issue> issues = new ArrayList<>();
        private final Map<String, NodeSpec> nodeMap = new LinkedHashMap<>();
        private final Set<String> edgeIds = new HashSet<>();
        private final Map<String, Record> configs = new LinkedHashMap<>();
        private final List<EdgeSpec> validEdges = new ArrayList<>();
        private final Map<String, List<EdgeSpec>> outEdges = new LinkedHashMap<>();
        private final Map<String, List<EdgeSpec>> inEdges = new LinkedHashMap<>();

        Run(ArchitectureDocument document) {
            this.document = document;
            this.nodes = document.architecture().nodes();
        }

        ValidationResult validate(@Nullable Double maxAmplification) {
            checkIdentity();
            checkTypesAndConfigs();
            checkLinks();
            checkCallerSettings();
            checkPortCounts();
            List<NodeSpec> sources =
                    nodes.stream().filter(this::isTrafficSource).toList();
            if (sources.isEmpty()) {
                issues.add(Issue.error("graph.no_source", "There is no client, so nothing generates load.")
                        .withHint("Drag a client (Clients, Mobile app, Web browser or API consumers) onto the canvas"
                                + " and connect it."));
            }
            CallGraph graph = new CallGraph(List.copyOf(nodeMap.keySet()), outEdges);
            Optional<List<String>> cycle = graph.findCycle();
            cycle.ifPresent(this::reportCycle);
            if (cycle.isEmpty() && !sources.isEmpty() && maxAmplification != null) {
                checkAmplification(graph, sources, maxAmplification);
            }
            GraphView view = new GraphView(nodeMap, configs, outEdges, inEdges, document.workload(), Map.of());
            Map<String, Set<RequestType>> edgeFlows = new LinkedHashMap<>();
            if (cycle.isEmpty() && sources.size() == 1) {
                view = route(graph, sources.getFirst(), view, edgeFlows);
            }
            checkComponentRules(view);
            checkFaults();
            if (document.workload().peakRate() == 0) {
                issues.add(Issue.warning(
                                "workload.no_traffic",
                                "The workload offers 0 requests/s, so the run will complete no" + " requests.")
                        .withHint("Set RPS above 0."));
            }
            checkPricing();
            return new ValidationResult(issues, configs, view, edgeFlows);
        }

        private boolean isTrafficSource(NodeSpec node) {
            return registry.find(node.type())
                    .map(ComponentSpec::isTrafficSource)
                    .orElse(false);
        }

        // -- identity ----------------------------------------------------------------------------

        private void checkIdentity() {
            for (NodeSpec n : nodes) {
                if (nodeMap.containsKey(n.id())) {
                    issues.add(Issue.error(
                                    "node.duplicate_id",
                                    "Two nodes share the id '%s'. Node ids must be unique.".formatted(n.id()))
                            .atNode(n.id())
                            .withHint("Rename one of the nodes."));
                }
                nodeMap.put(n.id(), n);
            }
            for (EdgeSpec e : document.architecture().edges()) {
                if (!edgeIds.add(e.id())) {
                    issues.add(Issue.error("edge.duplicate_id", "Two links share the id '%s'.".formatted(e.id()))
                            .atEdge(e.id()));
                }
            }
        }

        // -- component types and configuration --------------------------------------------------

        private void checkTypesAndConfigs() {
            for (NodeSpec n : nodes) {
                Optional<ComponentSpec<?>> spec = registry.find(n.type());
                if (spec.isEmpty()) {
                    issues.add(Issue.error(
                                    "node.unknown_type",
                                    "Node '%s' has unknown component type '%s'.".formatted(n.name(), n.type()))
                            .atNode(n.id())
                            .withHint("Known types: %s.".formatted(String.join(", ", registry.types()))));
                    continue;
                }
                try {
                    configs.put(n.id(), spec.get().parseConfig(n.config()));
                } catch (BindingException invalid) {
                    invalid.errors().forEach(error -> issues.add(configIssue(n, error)));
                }
            }
        }

        private static Issue configIssue(NodeSpec node, BindingError error) {
            String where = error.location().toString();
            return Issue.error(
                            "node.config_invalid",
                            "%s: '%s' %s"
                                    .formatted(
                                            node.name(), where, error.message().toLowerCase(Locale.ROOT)))
                    .atNode(node.id())
                    .atPath(where)
                    .withHint("Fix the highlighted field in the inspector.");
        }

        // -- links ---------------------------------------------------------------------------------

        private void checkLinks() {
            Set<List<String>> connections = new HashSet<>();
            for (EdgeSpec e : document.architecture().edges()) {
                Optional<Issue> problem = linkProblem(e, connections);
                if (problem.isPresent()) {
                    issues.add(problem.get());
                } else if (nodeMap.containsKey(e.source())
                        && registry.contains(
                                Objects.requireNonNull(nodeMap.get(e.source())).type())
                        && registry.contains(
                                Objects.requireNonNull(nodeMap.get(e.target())).type())) {
                    validEdges.add(e);
                    outEdges.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e);
                    inEdges.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e);
                }
            }
        }

        /** What is wrong with a link, if anything; a link between unknown types is skipped silently. */
        private Optional<Issue> linkProblem(EdgeSpec e, Set<List<String>> connections) {
            NodeSpec source = nodeMap.get(e.source());
            NodeSpec target = nodeMap.get(e.target());
            if (source == null || target == null) {
                String missing = source == null ? e.source() : e.target();
                return Optional.of(Issue.error(
                                "edge.unknown_node",
                                "Link '%s' references node '%s', which does not exist.".formatted(e.id(), missing))
                        .atEdge(e.id())
                        .withHint("Delete the link or recreate the missing node."));
            }
            if (e.source().equals(e.target())) {
                return Optional.of(Issue.error(
                                "edge.self_loop", "Link '%s' connects %s to itself.".formatted(e.id(), source.name()))
                        .atEdge(e.id())
                        .withHint("A component cannot call itself; delete the link."));
            }
            Optional<ComponentSpec<?>> sourceSpec = registry.find(source.type());
            Optional<ComponentSpec<?>> targetSpec = registry.find(target.type());
            if (sourceSpec.isEmpty() || targetSpec.isEmpty()) {
                return Optional.empty();
            }
            return portProblem(e, source, sourceSpec.get(), target, targetSpec.get())
                    .or(() -> duplicateProblem(e, source, target, connections));
        }

        private Optional<Issue> portProblem(
                EdgeSpec e, NodeSpec source, ComponentSpec<?> sm, NodeSpec target, ComponentSpec<?> tm) {
            Optional<Port> sp = sm.port(e.sourcePort()).filter(Port::isOut);
            if (sp.isEmpty()) {
                String outs = names(sm.outPorts());
                return Optional.of(Issue.error(
                                "edge.invalid_source_port",
                                "%s has no output port '%s' (outputs: %s)."
                                        .formatted(source.name(), e.sourcePort(), outs.isEmpty() ? "none" : outs))
                        .atEdge(e.id())
                        .atNode(source.id())
                        .withHint(
                                sm.outPorts().isEmpty()
                                        ? "%s cannot send requests.".formatted(sm.displayName())
                                        : "Connect from one of: %s.".formatted(outs)));
            }
            Optional<Port> tp = tm.port(e.targetPort()).filter(Port::isIn);
            if (tp.isEmpty()) {
                boolean receives = !tm.inPorts().isEmpty();
                return Optional.of(Issue.error(
                                "edge.invalid_target_port",
                                receives
                                        ? "%s has no input port '%s' (inputs: %s)."
                                                .formatted(target.name(), e.targetPort(), names(tm.inPorts()))
                                        : "%s (%s) cannot receive requests.".formatted(target.name(), tm.displayName()))
                        .atEdge(e.id())
                        .atNode(target.id())
                        .withHint(
                                receives
                                        ? null
                                        : "Traffic sources only send requests; connect them to an entry point"
                                                + " instead."));
            }
            Set<RequestType> badSource =
                    missing(e.config().requestTypes(), sp.get().requestTypes());
            if (!badSource.isEmpty()) {
                String emits = types(sp.get().requestTypes());
                return Optional.of(Issue.error(
                                "edge.request_types",
                                "Link '%s' carries %s but port '%s' of %s only emits %s."
                                        .formatted(
                                                e.id(),
                                                types(badSource),
                                                sp.get().name(),
                                                source.name(),
                                                emits))
                        .atEdge(e.id())
                        .withHint("Limit the link's request types to %s.".formatted(emits)));
            }
            Set<RequestType> badTarget =
                    missing(e.config().requestTypes(), tp.get().requestTypes());
            if (!badTarget.isEmpty()) {
                return Optional.of(Issue.error(
                                "edge.request_types",
                                "Link '%s' carries %s but %s only accepts %s on '%s'."
                                        .formatted(
                                                e.id(),
                                                types(badTarget),
                                                target.name(),
                                                types(tp.get().requestTypes()),
                                                tp.get().name()))
                        .atEdge(e.id()));
            }
            return Optional.empty();
        }

        private Optional<Issue> duplicateProblem(
                EdgeSpec e, NodeSpec source, NodeSpec target, Set<List<String>> connections) {
            if (connections.add(List.of(e.source(), e.sourcePort(), e.target(), e.targetPort()))) {
                return Optional.empty();
            }
            return Optional.of(Issue.error(
                            "edge.duplicate",
                            "%s is linked to %s more than once on the same ports."
                                    .formatted(source.name(), target.name()))
                    .atEdge(e.id())
                    .withHint("Merge the links by selecting several request types on one link."));
        }

        // -- caller-side link settings (only services call their dependencies one by one) --------

        private void checkCallerSettings() {
            for (EdgeSpec e : validEdges) {
                List<String> flags = new ArrayList<>();
                if (e.config().readsOnMiss()) {
                    flags.add("'Reads only on a cache miss'");
                }
                if (e.config().continueOnFailure()) {
                    flags.add("'Carry on if this call fails'");
                }
                NodeSpec source = Objects.requireNonNull(nodeMap.get(e.source()));
                if (!flags.isEmpty() && !source.type().equals(ServiceSpec.TYPE)) {
                    issues.add(Issue.warning(
                                    "edge.caller_setting_ignored",
                                    "Link '%s' starts at %s, which is not a service, so %s %s ignored."
                                            .formatted(
                                                    e.name(),
                                                    source.name(),
                                                    String.join(" and ", flags),
                                                    flags.size() == 1 ? "is" : "are"))
                            .atEdge(e.id())
                            .withHint("Only a service's links use these settings."));
                }
            }
        }

        // -- port arity ----------------------------------------------------------------------------

        private void checkPortCounts() {
            for (NodeSpec n : nodes) {
                Optional<ComponentSpec<?>> spec = registry.find(n.type());
                if (spec.isEmpty()) {
                    continue;
                }
                for (Port p : spec.get().ports()) {
                    List<EdgeSpec> pool = (p.isOut() ? outEdges : inEdges).getOrDefault(n.id(), List.of());
                    long count = pool.stream()
                            .filter(e -> (p.isOut() ? e.sourcePort() : e.targetPort()).equals(p.name()))
                            .count();
                    if (count < p.minEdges()) {
                        issues.add(Issue.error(
                                        "port.missing_link",
                                        "%s: '%s' needs at least %d link(s). %s"
                                                .formatted(n.name(), p.label(), p.minEdges(), p.description()))
                                .atNode(n.id())
                                .withHint(
                                        "Drag from the '%s' handle of %s to a target.".formatted(p.name(), n.name())));
                    }
                    if (p.maxEdges() != null && count > p.maxEdges()) {
                        issues.add(Issue.error(
                                        "port.too_many_links",
                                        "%s: '%s' allows at most %d link(s) but has %d."
                                                .formatted(n.name(), p.label(), p.maxEdges(), count))
                                .atNode(n.id())
                                .withHint("Remove the extra links (put a load balancer in between to fan out)."));
                    }
                }
            }
        }

        // -- the call graph -------------------------------------------------------------------------

        private void reportCycle(List<String> cycle) {
            String names = cycle.stream().map(this::nameOf).collect(Collectors.joining(ARROW));
            issues.add(Issue.error(
                            "graph.cycle",
                            "Requests would loop forever: %s. Synchronous call graphs must be acyclic."
                                    .formatted(names))
                    .atNode(cycle.getFirst())
                    .withHint("Remove one of the links in the loop."));
        }

        private String nameOf(String nodeId) {
            return Objects.requireNonNull(nodeMap.get(nodeId)).name();
        }

        private void checkAmplification(CallGraph graph, List<NodeSpec> sources, double max) {
            CallGraph.Amplification amplification = graph.amplification(
                    sources.stream().map(NodeSpec::id).toList(),
                    source ->
                            configs.get(source) instanceof ClientConfig client ? Math.max(1, client.maxAttempts()) : 1,
                    node -> registry.find(
                                    Objects.requireNonNull(nodeMap.get(node)).type())
                            .map(spec -> spec.category() == Category.NETWORK || spec.category() == Category.MESSAGING)
                            .orElse(false));
            List<String> path = amplification.path();
            String route = path.stream().map(this::nameOf).collect(Collectors.joining(ARROW));
            String calls = PythonFormat.format(amplification.calls(), ",.0f");
            String at = path.size() > 1 ? path.get(1) : path.getFirst();
            if (amplification.calls() > max) {
                issues.add(Issue.error(
                                "design.amplification",
                                ("One request can cause up to %s calls between parts (fan-out and retries multiplied"
                                                + " along %s), above the limit of %s. Simulating it would take a very"
                                                + " long time per request.")
                                        .formatted(calls, route, PythonFormat.format(max, ",.0f")))
                        .atNode(at)
                        .withHint("Call fewer dependencies per service, remove a layer, or lower maxAttempts (retry at"
                                + " one layer only)."));
            } else if (amplification.calls() > max / 10) {
                issues.add(Issue.warning(
                                "design.amplification",
                                ("One request can cause up to %s calls between parts (fan-out and retries multiplied"
                                                + " along %s); runs will be slow.")
                                        .formatted(calls, route))
                        .atNode(at)
                        .withHint("Call fewer dependencies per service or lower maxAttempts."));
            }
        }

        // -- request-type routing ----------------------------------------------------------------

        /**
         * Follows the request types from the one client down the graph in topological order,
         * recording what each link carries, and warns of parts and links no traffic reaches.
         */
        private GraphView route(
                CallGraph graph, NodeSpec source, GraphView view, Map<String, Set<RequestType>> edgeFlows) {
            Map<String, Set<RequestType>> incoming = new LinkedHashMap<>();
            nodeMap.keySet().forEach(id -> incoming.put(id, EnumSet.noneOf(RequestType.class)));
            Set<String> reached = new LinkedHashSet<>(List.of(source.id()));
            for (String id : graph.topologicalOrder()) {
                NodeSpec n = Objects.requireNonNull(nodeMap.get(id));
                Optional<ComponentSpec<?>> spec = registry.find(n.type());
                Record config = configs.get(id);
                if (spec.isEmpty() || config == null) {
                    continue;
                }
                if (!reached.contains(id) && !spec.get().isTrafficSource()) {
                    continue;
                }
                for (EdgeSpec e : outEdges.getOrDefault(id, List.of())) {
                    Set<RequestType> flow = EnumSet.noneOf(RequestType.class);
                    flow.addAll(emitted(
                            spec.get(), e.sourcePort(), Objects.requireNonNull(incoming.get(id)), config, view));
                    flow.retainAll(e.config().requestTypes());
                    edgeFlows.put(e.id(), flow);
                    if (!flow.isEmpty()) {
                        Objects.requireNonNull(incoming.get(e.target())).addAll(flow);
                        reached.add(e.target());
                    }
                }
            }
            for (NodeSpec n : nodes) {
                // parts without ports (a Kubernetes cluster) are not on the request path
                boolean offPath =
                        registry.find(n.type()).map(s -> s.ports().isEmpty()).orElse(false);
                if (!reached.contains(n.id()) && !offPath) {
                    issues.add(Issue.warning(
                                    "node.unreachable",
                                    "%s receives no traffic (not reachable from the traffic source)."
                                            .formatted(n.name()))
                            .atNode(n.id())
                            .withHint("Connect it, or delete it if it is not part of this design."));
                }
            }
            for (EdgeSpec e : validEdges) {
                Set<RequestType> flow = edgeFlows.get(e.id());
                if (reached.contains(e.source()) && (flow == null || flow.isEmpty())) {
                    issues.add(Issue.warning(
                                    "edge.unused",
                                    "Link '%s' never carries traffic: it accepts %s but none of those reach %s's '%s' port."
                                            .formatted(
                                                    e.id(),
                                                    types(e.config().requestTypes()),
                                                    nameOf(e.source()),
                                                    e.sourcePort()))
                            .atEdge(e.id()));
                }
            }
            return view.withIncomingTypes(incoming);
        }

        // -- component-specific rules -------------------------------------------------------------

        private void checkComponentRules(GraphView view) {
            for (NodeSpec n : nodes) {
                Record config = configs.get(n.id());
                Optional<ComponentSpec<?>> spec = registry.find(n.type());
                if (config != null && spec.isPresent()) {
                    issues.addAll(rules(spec.get(), n, config, view));
                }
            }
        }

        // -- faults (chaos experiments) -----------------------------------------------------------

        private void checkFaults() {
            double duration = document.workload().durationS();
            for (Fault f : document.workload().faults()) {
                String what = f.label() != null && !f.label().isEmpty()
                        ? f.label()
                        : "%s fault '%s'".formatted(f.kind().json(), f.id());
                String path = "workload.faults." + f.id();
                NodeSpec node = nodeMap.get(f.target());
                Optional<Issue> wrong = f.kind() == FaultKind.LATENCY
                        ? latencyTargetProblem(f, what, path, node)
                        : componentTargetProblem(f, what, path, node);
                if (wrong.isPresent()) {
                    issues.add(wrong.get());
                    continue;
                }
                double end = f.startS() + f.durationS();
                Issue timing = null;
                if (f.startS() >= duration) {
                    timing = Issue.error(
                                    "fault_outside_run",
                                    "%s starts at %s s, after the run ends (%s s), so it would never happen."
                                            .formatted(what, g(f.startS()), g(duration)))
                            .withHint("Move the fault inside the run or make the run longer.");
                } else if (end > duration) {
                    timing = Issue.warning(
                                    "fault_clipped",
                                    ("%s runs until %s s but arrivals stop at %s s; only the part inside the run has an"
                                                    + " effect on new requests.")
                                            .formatted(what, g(end), g(duration)))
                            .withHint("Shorten the fault or make the run longer.");
                }
                if (timing != null) {
                    issues.add(
                            node != null
                                    ? timing.atNode(node.id()).atPath(path)
                                    : timing.atEdge(f.target()).atPath(path));
                }
            }
        }

        private Optional<Issue> latencyTargetProblem(Fault f, String what, String path, @Nullable NodeSpec node) {
            if (edgeIds.contains(f.target())) {
                return Optional.empty();
            }
            if (node != null) {
                return Optional.of(Issue.error(
                                "fault_wrong_target",
                                "%s targets component '%s', but latency faults apply to links."
                                        .formatted(what, node.name()))
                        .atNode(node.id())
                        .atPath(path)
                        .withHint("Pick a link as the target, or use an outage/slowdown fault for a component."));
            }
            return Optional.of(Issue.error(
                            "fault_unknown_target",
                            "%s targets link '%s', which does not exist.".formatted(what, f.target()))
                    .atPath(path)
                    .withHint("Pick an existing link or delete the fault."));
        }

        private Optional<Issue> componentTargetProblem(Fault f, String what, String path, @Nullable NodeSpec node) {
            if (node == null) {
                if (edgeIds.contains(f.target())) {
                    return Optional.of(Issue.error(
                                    "fault_wrong_target",
                                    "%s targets link '%s', but %s faults apply to components."
                                            .formatted(
                                                    what, f.target(), f.kind().json()))
                            .atEdge(f.target())
                            .atPath(path)
                            .withHint("Pick a component as the target, or use a latency fault for a link."));
                }
                return Optional.of(Issue.error(
                                "fault_unknown_target",
                                "%s targets component '%s', which does not exist.".formatted(what, f.target()))
                        .atPath(path)
                        .withHint("Pick an existing component or delete the fault."));
            }
            if (isTrafficSource(node)) {
                return Optional.of(Issue.error(
                                "fault_wrong_target",
                                "%s targets the traffic source '%s'; faults apply to the system, not the clients."
                                        .formatted(what, node.name()))
                        .atNode(node.id())
                        .atPath(path)
                        .withHint("Target a component the clients call, or change the workload instead."));
            }
            return Optional.empty();
        }

        // -- pricing --------------------------------------------------------------------------------

        private void checkPricing() {
            Pricing pricing = document.pricing();
            if (pricing.isGeneric()) {
                return;
            }
            PriceSnapshot snapshot;
            try {
                snapshot = prices.forPricing(RecordCodec.write(pricing));
            } catch (PricingException missing) {
                boolean pinned = pricing.snapshotDate() != null
                        || (pricing.snapshotSha256() != null
                                && !pricing.snapshotSha256().isEmpty());
                issues.add(Issue.warning(
                                "pricing.snapshot_missing",
                                "%s Sizes are not snapped and costs use generic illustrative rates."
                                        .formatted(missing.getMessage()))
                        .withHint(
                                pinned
                                        ? "Add that snapshot file, or choose another snapshot (clearing snapshotDate"
                                                + " uses the newest)."
                                        : "Add a price snapshot for that provider on this machine, or use generic"
                                                + " rates."));
                return;
            }
            List<String> unknown = snapshot.unknownFamilies(pricing.families());
            if (!unknown.isEmpty()) {
                List<String> known = List.copyOf(snapshot.families());
                String some = String.join(", ", known.subList(0, Math.min(20, known.size())));
                issues.add(Issue.warning(
                                "pricing.unknown_family",
                                "%s: no instance family of that name in %s, so it restricts nothing."
                                        .formatted(String.join(", ", unknown), snapshot.label()))
                        .atPath("pricing.families")
                        .withHint("Families in it: %s.".formatted(some.isEmpty() ? "none" : some)));
            }
            if (snapshot.hasShapes()) {
                Set<String> listed = snapshot.instanceTypes();
                for (NodeSpec n : nodes) {
                    JsonNode named = n.config().get("instanceType");
                    if (named != null
                            && named.isString()
                            && !named.stringValue().isEmpty()
                            && !listed.contains(named.stringValue())) {
                        issues.add(Issue.warning(
                                        "pricing.unknown_instance_type",
                                        ("%s: no instance type %s in %s, so it runs on the cheapest type that covers"
                                                        + " its CPU and memory.")
                                                .formatted(n.name(), named.stringValue(), snapshot.label()))
                                .atNode(n.id())
                                .atPath("instanceType")
                                .withHint("Choose a type from the snapshot, or clear it."));
                    }
                }
            }
        }
    }

    /** A component type's rules on a node, when the node's config is of the type's config. */
    private static <C extends Record> List<Issue> rules(
            ComponentSpec<C> spec, NodeSpec node, Record config, GraphView view) {
        return spec.configType().isInstance(config)
                ? spec.validate(node, spec.configType().cast(config), view)
                : List.of();
    }

    /** The request types that leave a node through a port, when its config is of its type's. */
    private static <C extends Record> Set<RequestType> emitted(
            ComponentSpec<C> spec, String port, Set<RequestType> incoming, Record config, GraphView view) {
        return spec.configType().isInstance(config)
                ? spec.emittedTypes(port, incoming, spec.configType().cast(config), view)
                : Set.of();
    }

    private static Set<RequestType> missing(List<RequestType> carried, List<RequestType> allowed) {
        Set<RequestType> extra = EnumSet.noneOf(RequestType.class);
        extra.addAll(carried);
        allowed.forEach(extra::remove);
        return extra;
    }

    private static String types(Collection<RequestType> types) {
        return types.stream().map(RequestType::json).collect(Collectors.joining(", "));
    }

    private static String names(List<Port> ports) {
        return ports.stream().map(Port::name).collect(Collectors.joining(", "));
    }

    private static String g(double value) {
        return PythonFormat.format(value, "g");
    }
}

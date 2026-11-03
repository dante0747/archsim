package io.archsim.model.validation;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.issue.Issue;

/**
 * Follows the request types from the design's one client down an acyclic call graph,
 * recording what each link carries and what reaches each node, and warns of parts and
 * links no traffic reaches. Each instance routes once.
 */
final class RequestTypeFlow {

	private final ValidationContext context;

	private final CallGraph graph;

	private final GraphView view;

	private final Map<String, Set<RequestType>> incoming = new LinkedHashMap<>();

	private final Set<String> reached = new LinkedHashSet<>();

	private final Map<String, Set<RequestType>> edgeFlows = new LinkedHashMap<>();

	/**
	 * A routing of an acyclic graph.
	 * @param context the validation so far
	 * @param graph the design's call graph, which has no cycle
	 */
	RequestTypeFlow(ValidationContext context, CallGraph graph) {
		this.context = context;
		this.graph = graph;
		this.view = context.graphView();
	}

	/**
	 * Follows the request types from the client, node by node in topological order, then
	 * warns of the nodes and links they do not reach.
	 * @param source the design's one client
	 * @return the view with the request types that reach each node, and the request types
	 * each link carries
	 */
	RequestFlow route(NodeSpec source) {
		this.context.nodeIds().forEach((id) -> this.incoming.put(id, EnumSet.noneOf(RequestType.class)));
		this.reached.add(source.id());
		for (String id : this.graph.topologicalOrder()) {
			followFrom(id);
		}
		warnOfUnreachedNodes();
		warnOfUnusedEdges();
		return new RequestFlow(this.view.withIncomingTypes(this.incoming), this.edgeFlows);
	}

	/**
	 * Sends what reaches a node on along its links, when traffic reaches it or it is a
	 * client; a node of an unknown type or with an invalid config sends nothing.
	 * @param id the node's id
	 */
	private void followFrom(String id) {
		NodeSpec node = this.context.requireNode(id);
		Optional<ComponentSpec<?>> spec = this.context.spec(node);
		Record config = this.context.config(id);
		if (spec.isEmpty() || config == null) {
			return;
		}
		if (!this.reached.contains(id) && !spec.get().isTrafficSource()) {
			return;
		}
		for (EdgeSpec edge : this.context.outEdges(id)) {
			Set<RequestType> flow = EnumSet.noneOf(RequestType.class);
			flow.addAll(emitted(spec.get(), edge.sourcePort(), incomingAt(id), config));
			flow.retainAll(edge.config().requestTypes());
			this.edgeFlows.put(edge.id(), flow);
			if (!flow.isEmpty()) {
				incomingAt(edge.target()).addAll(flow);
				this.reached.add(edge.target());
			}
		}
	}

	private Set<RequestType> incomingAt(String nodeId) {
		return Objects.requireNonNull(this.incoming.get(nodeId));
	}

	/**
	 * The request types that leave a node through a port, when its config is of its
	 * type's.
	 * @param <C> the type's config
	 * @param spec the node's component type
	 * @param port the port they leave through
	 * @param incoming the request types that reach the node
	 * @param config the node's parsed config
	 * @return the request types it sends on; none when the config is of another type
	 */
	private <C extends Record> Set<RequestType> emitted(ComponentSpec<C> spec, String port, Set<RequestType> incoming,
			Record config) {
		return spec.configType().isInstance(config)
				? spec.emittedTypes(port, incoming, spec.configType().cast(config), this.view) : Set.of();
	}

	private void warnOfUnreachedNodes() {
		for (NodeSpec node : this.context.declaredNodes()) {
			// parts without ports (a Kubernetes cluster) are not on the request path
			boolean offPath = this.context.spec(node).map((spec) -> spec.ports().isEmpty()).orElse(false);
			if (!this.reached.contains(node.id()) && !offPath) {
				this.context
					.report(Issue
						.warning("node.unreachable",
								"%s receives no traffic (not reachable from the traffic source)."
									.formatted(node.name()))
						.atNode(node.id())
						.withHint("Connect it, or delete it if it is not part of this design."));
			}
		}
	}

	private void warnOfUnusedEdges() {
		for (EdgeSpec edge : this.context.validEdges()) {
			Set<RequestType> flow = this.edgeFlows.get(edge.id());
			if (this.reached.contains(edge.source()) && (flow == null || flow.isEmpty())) {
				this.context.report(Issue.warning("edge.unused",
						"Link '%s' never carries traffic: it accepts %s but none of those reach %s's '%s' port."
							.formatted(edge.id(), IssueText.requestTypes(edge.config().requestTypes()),
									this.context.nameOf(edge.source()), edge.sourcePort()))
					.atEdge(edge.id()));
			}
		}
	}

}

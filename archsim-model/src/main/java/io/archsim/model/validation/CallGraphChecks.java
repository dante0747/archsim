package io.archsim.model.validation;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.archsim.model.component.Category;
import io.archsim.model.component.client.ClientConfig;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.issue.Issue;
import io.archsim.model.text.PythonFormat;

/**
 * Checks the call graph: that a client generates load, that requests cannot loop, and
 * that one request does not cause too many calls between parts; and says whether the
 * request types can be followed down it, which needs one client and no loop.
 */
final class CallGraphChecks {

	/** How messages join the names of the nodes along a path. */
	private static final String ARROW = " → ";

	/** How messages write a number of calls: thousands separated, no decimals. */
	private static final String WHOLE_NUMBER = ",.0f";

	/** Above the limit divided by this, the calls one request causes draw a warning. */
	private static final int WARNING_DIVISOR = 10;

	/**
	 * The start of both amplification messages: the calls, and the path that drives them.
	 */
	private static final String CALLS_ALONG_PATH = "One request can cause up to %s calls between parts"
			+ " (fan-out and retries multiplied along %s)";

	private final ValidationContext context;

	private final List<NodeSpec> sources;

	private final CallGraph graph;

	/**
	 * A loop in the graph, as node ids with the first repeated at the end; {@code null}
	 * when it has none.
	 */
	private final @Nullable List<String> cycle;

	/**
	 * Checks of the design's call graph, once the link checks have recorded its valid
	 * links.
	 * @param context the validation so far
	 * @param graph the design's nodes and valid links
	 */
	CallGraphChecks(ValidationContext context, CallGraph graph) {
		this.context = context;
		this.sources = context.trafficSources();
		this.graph = graph;
		this.cycle = graph.findCycle().orElse(null);
	}

	/**
	 * Reports a design with no client, a loop, or a request that causes too many calls.
	 * @param maxAmplification the most component calls one client request may cause;
	 * {@code null} for no bound
	 */
	void check(@Nullable Double maxAmplification) {
		if (this.sources.isEmpty()) {
			this.context.report(Issue.error("graph.no_source", "There is no client, so nothing generates load.")
				.withHint("Drag a client (Clients, Mobile app, Web browser or API consumers) onto the canvas and"
						+ " connect it."));
		}
		if (this.cycle != null) {
			reportCycle(this.cycle);
		}
		else if (!this.sources.isEmpty() && maxAmplification != null) {
			checkAmplification(maxAmplification);
		}
	}

	/**
	 * The client to follow the request types from: the design's one client, when the
	 * graph has no loop.
	 * @return the client; empty when the graph has a loop or not exactly one client
	 */
	Optional<NodeSpec> routableSource() {
		if (this.cycle != null || this.sources.size() != 1) {
			return Optional.empty();
		}
		return Optional.of(this.sources.getFirst());
	}

	private void reportCycle(List<String> cycle) {
		this.context
			.report(Issue
				.error("graph.cycle",
						"Requests would loop forever: %s. Synchronous call graphs must be acyclic."
							.formatted(names(cycle)))
				.atNode(cycle.getFirst())
				.withHint("Remove one of the links in the loop."));
	}

	/**
	 * Reports a request that causes more calls than the limit as an error, and one that
	 * causes more than a tenth of it as a warning.
	 * @param max the most component calls one client request may cause
	 */
	private void checkAmplification(double max) {
		List<String> sourceIds = this.sources.stream().map(NodeSpec::id).toList();
		CallGraph.Amplification amplification = this.graph.amplification(sourceIds, this::clientAttempts,
				this::sendsToOneTarget);
		String calls = PythonFormat.format(amplification.calls(), WHOLE_NUMBER);
		String route = names(amplification.path());
		String at = worstNode(amplification.path());
		if (amplification.calls() > max) {
			this.context.report(Issue
				.error("design.amplification",
						(CALLS_ALONG_PATH + ", above the limit of %s. Simulating it would take a very long time"
								+ " per request.")
							.formatted(calls, route, PythonFormat.format(max, WHOLE_NUMBER)))
				.atNode(at)
				.withHint("Call fewer dependencies per service, remove a layer, or lower maxAttempts (retry at one"
						+ " layer only)."));
		}
		else if (amplification.calls() > max / WARNING_DIVISOR) {
			this.context.report(Issue
				.warning("design.amplification", (CALLS_ALONG_PATH + "; runs will be slow.").formatted(calls, route))
				.atNode(at)
				.withHint("Call fewer dependencies per service or lower maxAttempts."));
		}
	}

	/**
	 * The attempts a client makes per request: its {@code maxAttempts}, at least one.
	 * @param sourceId the client's node id
	 * @return the attempts; one for a client whose config is not a client's
	 */
	private int clientAttempts(String sourceId) {
		return (this.context.config(sourceId) instanceof ClientConfig client) ? Math.max(1, client.maxAttempts()) : 1;
	}

	/**
	 * Whether a node sends each call to one of its links rather than to all of them.
	 * @param nodeId the node's id
	 * @return {@code true} for a network part (a load balancer) or a messaging one (a
	 * queue)
	 */
	private boolean sendsToOneTarget(String nodeId) {
		return this.context.spec(this.context.requireNode(nodeId))
			.map((spec) -> spec.category() == Category.NETWORK || spec.category() == Category.MESSAGING)
			.orElse(false);
	}

	/**
	 * Where an amplification issue points: the first node the client calls on the
	 * costliest path, or the client when it calls none.
	 * @param path the costliest path, starting at the client
	 * @return the id of the node to point at
	 */
	private static String worstNode(List<String> path) {
		return (path.size() > 1) ? path.get(1) : path.getFirst();
	}

	private String names(List<String> nodeIds) {
		return nodeIds.stream().map(this.context::nameOf).collect(Collectors.joining(ARROW));
	}

}

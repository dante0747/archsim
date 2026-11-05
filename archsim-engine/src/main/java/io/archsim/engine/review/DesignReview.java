package io.archsim.engine.review;

import java.util.List;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;

/**
 * The design review: an architecture linter over a design as a run simulates it. Static,
 * with no simulation: it flags single points of failure, missing timeouts, stacked
 * retries, timeout inversion, pool exhaustion, unbounded queues and more, each with the
 * design's numbers, most severe first.
 */
public final class DesignReview {

	private final ComponentRegistry registry;

	private final LoadEstimator loads;

	/**
	 * Reviews designs made of a registry's types.
	 * @param registry the component types
	 */
	public DesignReview(ComponentRegistry registry) {
		this.registry = registry;
		this.loads = new LoadEstimator(registry);
	}

	/**
	 * The findings of a design's review. A check a part's specs give nothing to judge is
	 * skipped: CPU for a part without cores above zero, CPU work above zero or threads
	 * that run, and memory for a data store without hot data or a cache of items without
	 * size.
	 * @param document the design as a run simulates it: resolved and auto-sized
	 * @return its findings, by severity, rule and title
	 */
	public List<Finding> review(ArchitectureDocument document) {
		ReviewGraph graph = new ReviewGraph(document.architecture(), this.registry);
		Findings findings = new Findings();
		PartRules parts = new PartRules(graph, findings);
		StoreRules stores = new StoreRules(graph, findings, document.workload());
		for (NodeSpec node : document.architecture().nodes()) {
			parts.check(node);
			stores.check(node);
		}
		CallRules calls = new CallRules(graph, findings);
		calls.missingTimeouts();
		calls.retriesWithoutBackoff();
		new RetryPaths(graph, findings).check();
		calls.timeoutInversions();
		new CapacityRules(graph, findings, document, this.loads).check();
		edgeRateLimit(graph, findings);
		return findings.sorted();
	}

	/**
	 * Nothing in front of the system caps its traffic: no load balancer or gateway a
	 * request reaches has a rate limit.
	 * @param graph the design
	 * @param findings the review's findings
	 */
	private static void edgeRateLimit(ReviewGraph graph, Findings findings) {
		if (graph.sources().isEmpty()) {
			return;
		}
		boolean limited = graph.architecture()
			.nodes()
			.stream()
			.map(NodeSpec::id)
			.anyMatch((id) -> graph.onPath(id) && graph.settings(id).nonZero("rateLimitRps").orElse(0) > 0
					&& graph.family(id) == ComponentFamily.NETWORK);
		if (limited) {
			return;
		}
		List<String> firstHops = graph.sources()
			.stream()
			.flatMap((source) -> graph.outs(source.id()).stream())
			.map(EdgeSpec::target)
			.toList();
		findings.add(ReviewRule.EDGE_RATE_LIMIT, Severity.LOW, new Involved(firstHops, List.of()), new Wording(
				"No rate limit at the edge",
				"Nothing in front of the system caps traffic, so a spike, a retry storm or a bot flood reaches every "
						+ "tier at full force.",
				"Add a rate limit on the gateway or load balancer sized to the capacity you have measured."));
	}

}

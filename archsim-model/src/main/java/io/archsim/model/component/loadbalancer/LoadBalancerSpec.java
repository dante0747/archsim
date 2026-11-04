package io.archsim.model.component.loadbalancer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentIdentity;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.Port;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.issue.Issue;
import io.archsim.model.text.PythonFormat;

/**
 * A load balancer: it spreads requests across the replicas of the targets its links lead
 * to, and links can filter by request type.
 */
public final class LoadBalancerSpec extends ComponentSpec<LoadBalancerConfig> {

	/** The component type's name in documents. */
	public static final String TYPE = "load_balancer";

	/** Creates the load balancer type: requests come in, and go out to its targets. */
	public LoadBalancerSpec() {
		super(new ComponentIdentity(TYPE, "Load Balancer", Category.NETWORK), LoadBalancerConfig.class,
				requestsAndTargets());
	}

	@Override
	public Map<String, TechnologyProfile> technologies() {
		return LoadBalancerConfig.PROFILES.byName();
	}

	@Override
	public List<Issue> validate(NodeSpec node, LoadBalancerConfig config, GraphView view) {
		List<Issue> issues = new ArrayList<>();
		List<EdgeSpec> outs = view.outEdges(node.id());
		if (outs.isEmpty()) {
			// the missing link is reported for every port alike
			return issues;
		}
		issues.addAll(timeoutsAboveUpstream(node, config, outs));
		issues.addAll(unroutableTypes(node, view, outs));
		return issues;
	}

	private static List<Issue> timeoutsAboveUpstream(NodeSpec node, LoadBalancerConfig config, List<EdgeSpec> outs) {
		Optional<Double> upstream = config.effectiveUpstreamTimeoutMs();
		if (upstream.isEmpty()) {
			return List.of();
		}
		List<Issue> issues = new ArrayList<>();
		for (EdgeSpec edge : outs) {
			Double timeout = edge.config().timeoutMs();
			if (timeout != null && timeout > upstream.get()) {
				issues.add(Issue
					.warning("lb.timeout_above_upstream",
							("Link '%s' allows %s ms but %s gives up after %s ms (its upstream timeout),"
									+ " so the longer link timeout never applies.")
								.formatted(edge.id(), PythonFormat.format(timeout, "g"), node.name(),
										PythonFormat.format(upstream.get(), "g")))
					.atNode(node.id())
					.atEdge(edge.id())
					.atPath("upstreamTimeoutMs"));
			}
		}
		return issues;
	}

	private static List<Issue> unroutableTypes(NodeSpec node, GraphView view, List<EdgeSpec> outs) {
		Set<RequestType> incoming = EnumSet.noneOf(RequestType.class);
		incoming.addAll(view.incomingTypes().getOrDefault(node.id(), Set.of()));
		List<Issue> issues = new ArrayList<>();
		for (RequestType type : incoming) {
			if (outs.stream().noneMatch((edge) -> edge.config().carries(type))) {
				issues.add(Issue
					.error("lb.unroutable_type",
							"%s receives %s requests but none of its outgoing links carry %ss.".formatted(node.name(),
									type.json(), type.json()))
					.atNode(node.id())
					.withHint("Add '%s' to the request types of one of its outgoing links.".formatted(type.json())));
			}
		}
		return issues;
	}

	/**
	 * Requests come in through one port and go out to the targets it balances across.
	 * @return the load balancer's ports
	 */
	private static List<Port> requestsAndTargets() {
		Port in = Port.in("in", "Requests", "Incoming requests.");
		Port targets = Port
			.out("out", "Targets",
					"Instances to balance across. A retry (link max attempts > 1) avoids the instance "
							+ "that just failed.")
			.withMinEdges(1);
		return List.of(in, targets);
	}

}

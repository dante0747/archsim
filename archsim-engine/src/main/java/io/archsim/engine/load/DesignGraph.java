package io.archsim.engine.load;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;

/**
 * A design's parts and the links between them, as the load estimate walks them. Each
 * part's settings are read once, leniently (ADR-0038). Of two parts with one id, the
 * later counts, as in a map keyed by id; a link to or from a part the design does not
 * have carries nothing.
 */
final class DesignGraph {

	/** A cache's out port for the reads it missed. */
	static final String MISS_PORT = "miss";

	private final Map<String, NodeSpec> nodes = new LinkedHashMap<>();

	private final Map<String, LenientSettings> settings = new LinkedHashMap<>();

	private final Map<String, ComponentFamily> families = new LinkedHashMap<>();

	private final Map<String, List<EdgeSpec>> outs = new LinkedHashMap<>();

	private final Map<String, Integer> indegrees = new LinkedHashMap<>();

	DesignGraph(Architecture architecture, ComponentRegistry registry) {
		for (NodeSpec node : architecture.nodes()) {
			this.nodes.put(node.id(), node);
			this.settings.put(node.id(), LenientSettings.ofNode(registry.find(node.type()), node.config()));
			this.families.put(node.id(), ComponentFamily.of(node.type(), registry));
			this.indegrees.put(node.id(), 0);
		}
		for (EdgeSpec edge : architecture.edges()) {
			if (this.nodes.containsKey(edge.source()) && this.nodes.containsKey(edge.target())) {
				this.outs.computeIfAbsent(edge.source(), (source) -> new ArrayList<>()).add(edge);
				this.indegrees.merge(edge.target(), 1, Integer::sum);
			}
		}
	}

	/**
	 * How many links lead into each part, for Kahn's order.
	 * @return a fresh copy to count down, by part id
	 */
	Map<String, Integer> indegrees() {
		return new LinkedHashMap<>(this.indegrees);
	}

	/**
	 * The links out of a part.
	 * @param id the part's id
	 * @return its links, in the design's order
	 */
	List<EdgeSpec> outs(String id) {
		return this.outs.getOrDefault(id, List.of());
	}

	/**
	 * The family of a part.
	 * @param id the part's id
	 * @return its family
	 */
	ComponentFamily family(String id) {
		return Objects.requireNonNull(this.families.get(id), id);
	}

	/**
	 * The settings of a part.
	 * @param id the part's id
	 * @return its settings
	 */
	LenientSettings settings(String id) {
		return Objects.requireNonNull(this.settings.get(id), id);
	}

	/**
	 * The replicas a load balancer weighs a target by: a service's replicas (its most
	 * when it autoscales), one for anything else.
	 * @param id the target's id
	 * @return its weight
	 */
	double weight(String id) {
		if (!type(id).equals(ServiceSpec.TYPE)) {
			return 1.0;
		}
		LenientSettings service = settings(id);
		double replicas = service.nonZero("replicas").orElse(1.0);
		if (service.isTrue("autoscaling")) {
			replicas = Math.max(service.nonZero("maxReplicas").orElse(replicas), replicas);
		}
		return Math.max(1.0, replicas);
	}

	/**
	 * The weights of links' targets, added up as Python's {@code sum()} adds them.
	 * @param edges the links
	 * @return the sum of their targets' weights
	 */
	double weights(List<EdgeSpec> edges) {
		return FloatSum.of(edges.stream().mapToDouble((edge) -> weight(edge.target())));
	}

	/**
	 * A service's read links to caches that leave the misses to it (cache-aside), when it
	 * has a link that reads the data store on a miss.
	 * @param outs the service's links
	 * @return the lookups; none without a link that reads on a miss
	 */
	List<EdgeSpec> asideLookups(List<EdgeSpec> outs) {
		boolean readsOnMiss = outs.stream()
			.anyMatch((edge) -> edge.config().readsOnMiss() && edge.config().carries(RequestType.READ));
		if (!readsOnMiss) {
			return List.of();
		}
		return outs.stream().filter(this::isAsideLookup).toList();
	}

	private boolean isAsideLookup(EdgeSpec edge) {
		return edge.config().carries(RequestType.READ) && !edge.config().readsOnMiss()
				&& type(edge.target()).equals(CacheSpec.TYPE)
				&& outs(edge.target()).stream().noneMatch((out) -> out.sourcePort().equals(MISS_PORT));
	}

	private String type(String id) {
		return Objects.requireNonNull(this.nodes.get(id), id).type();
	}

}

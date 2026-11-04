package io.archsim.engine.sizing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.archsim.model.codec.BindingException;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.kubernetes.ClusterConfig;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.document.NodeSpec;

/**
 * A pod can never be bigger than a node: a Kubernetes service's size, capped at what one
 * node allocates.
 */
final class ClusterFit {

	private static final String CLUSTER = "kubernetes_cluster";

	private final Map<String, Node> clusters;

	private ClusterFit(Map<String, Node> clusters) {
		this.clusters = clusters;
	}

	/**
	 * The design's Kubernetes clusters and what one node of each allocates to a pod.
	 * @param nodes the design's parts
	 * @param registry the component types
	 * @return the clusters; one whose config is not valid is left out
	 */
	static ClusterFit of(Iterable<NodeSpec> nodes, ComponentRegistry registry) {
		Map<String, Node> clusters = new LinkedHashMap<>();
		Optional<ComponentSpec<?>> type = registry.find(CLUSTER);
		for (NodeSpec node : nodes) {
			if (node.type().equals(CLUSTER) && type.isPresent()) {
				allocatable(node, type.get()).ifPresent((cluster) -> clusters.put(node.id(), cluster));
			}
		}
		return new ClusterFit(clusters);
	}

	/**
	 * A service's size capped at what one node of its cluster allocates to a pod.
	 * @param size the size worked out for the service
	 * @param service the service's settings
	 * @return the size, capped; as it is for a service on no cluster
	 */
	PartSize fit(PartSize size, ServiceConfig service) {
		String clusterId = service.clusterIn(new ArrayList<>(this.clusters.keySet()));
		if (clusterId == null) {
			return size;
		}
		Node node = Objects.requireNonNull(this.clusters.get(clusterId), clusterId);
		PartSize fitted = size;
		if (size.cpuCores() != null && size.cpuCores() > node.cpu()) {
			fitted = fitted.with(Spec.CPU_CORES, node.cpu(), "; capped at %s, what one node of %s can allocate to a pod"
				.formatted(SizingText.brief(node.cpu()), node.name()));
		}
		if (size.memoryGib() != null && size.memoryGib() > node.memoryGib()) {
			fitted = fitted.with(Spec.MEMORY_GIB, node.memoryGib(),
					"; capped at %s GiB, what one node of %s can allocate to a pod"
						.formatted(SizingText.brief(node.memoryGib()), node.name()));
		}
		return fitted;
	}

	private static Optional<Node> allocatable(NodeSpec node, ComponentSpec<?> type) {
		try {
			if (type.parseConfig(node.config()) instanceof ClusterConfig cluster) {
				return Optional.of(new Node(node.name(), cluster.allocatableCpu(), cluster.allocatableMemoryGib()));
			}
			return Optional.empty();
		}
		catch (BindingException ex) {
			// a config that is not valid is reported by validation
			return Optional.empty();
		}
	}

	/**
	 * What one node of a cluster can allocate to a pod.
	 *
	 * @param name how the cluster is called in explanations
	 * @param cpu the vCPU
	 * @param memoryGib the memory, in GiB
	 */
	private record Node(String name, double cpu, double memoryGib) {
	}

}

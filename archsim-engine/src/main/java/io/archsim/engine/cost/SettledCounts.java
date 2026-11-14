package io.archsim.engine.cost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.archsim.model.codec.BindingException;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.kubernetes.ClusterConfig;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.component.kubernetes.PodRequest;
import io.archsim.model.component.service.Platform;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;

/**
 * The count each part that scales settles at under a load, once the load holds
 * (ADR-0074): an autoscaled service the replicas its autoscaler wants for its work there,
 * and a Kubernetes cluster the nodes its pods there need (with its autoscaler, within its
 * bounds; without, its nodes).
 */
final class SettledCounts {

	private SettledCounts() {
	}

	/**
	 * The count of each autoscaled service and each cluster at a load.
	 * @param document the design as simulated
	 * @param registry the component types, to read the parts' settings
	 * @param load the load, with the replicas each part keeps busy there
	 * @return the counts by part id; a part whose settings are not valid is left out
	 */
	static Map<String, InstanceCount> of(ArchitectureDocument document, ComponentRegistry registry, LevelLoad load) {
		Map<String, ServiceConfig> services = configs(document, registry, ServiceSpec.TYPE, ServiceConfig.class);
		Map<String, Integer> replicas = replicasAt(services, load);
		Map<String, InstanceCount> counts = serviceCounts(services, replicas, load.level());
		Map<String, ClusterConfig> clusters = configs(document, registry, KubernetesClusterSpec.TYPE,
				ClusterConfig.class);
		Map<String, List<PodRequest>> pods = podsByCluster(services, replicas, clusters,
				KubernetesLines.clusterIds(document));
		clusters
			.forEach((id, cluster) -> counts.put(id, nodes(cluster, pods.getOrDefault(id, List.of()), load.level())));
		return counts;
	}

	/**
	 * The replicas each service runs at a load: an autoscaled one's wanted replicas for
	 * its work there, any other's the replicas it starts with.
	 * @param services each service's settings, by id
	 * @param load the load, with the replicas each part keeps busy there
	 * @return the replicas, by id
	 */
	private static Map<String, Integer> replicasAt(Map<String, ServiceConfig> services, LevelLoad load) {
		Map<String, Integer> replicas = new HashMap<>();
		for (Map.Entry<String, ServiceConfig> service : services.entrySet()) {
			ServiceConfig config = service.getValue();
			double busy = load.busyReplicas().getOrDefault(service.getKey(), 0.0);
			replicas.put(service.getKey(), autoscales(config) ? config.wantedReplicas(busy) : config.initialReplicas());
		}
		return replicas;
	}

	private static Map<String, InstanceCount> serviceCounts(Map<String, ServiceConfig> services,
			Map<String, Integer> replicas, LoadLevel level) {
		Map<String, InstanceCount> counts = new HashMap<>();
		for (Map.Entry<String, ServiceConfig> service : services.entrySet()) {
			ServiceConfig config = service.getValue();
			int count = replicas.getOrDefault(service.getKey(), 0);
			if (autoscales(config)) {
				counts.put(service.getKey(), new InstanceCount(count,
						CostText.plural(count, "replica") + atLoad(level, config.minReplicas(), config.maxReplicas())));
			}
		}
		return counts;
	}

	/**
	 * What each cluster's pods request at a load, from every service on it.
	 * @param services each service's settings, by id
	 * @param replicas the replicas each service runs at the load, by id
	 * @param clusters the settings of each cluster whose settings are valid, by id
	 * @param clusterIds every cluster of the design, as a service finds its own among
	 * them
	 * @return the pods by cluster id
	 */
	private static Map<String, List<PodRequest>> podsByCluster(Map<String, ServiceConfig> services,
			Map<String, Integer> replicas, Map<String, ClusterConfig> clusters, List<String> clusterIds) {
		Map<String, List<PodRequest>> pods = new HashMap<>();
		for (Map.Entry<String, ServiceConfig> service : services.entrySet()) {
			String clusterId = service.getValue().clusterIn(clusterIds);
			ClusterConfig cluster = (clusterId != null) ? clusters.get(clusterId) : null;
			if (cluster != null) {
				pods.computeIfAbsent(clusterId, (id) -> new ArrayList<>())
					.addAll(Collections.nCopies(replicas.getOrDefault(service.getKey(), 0),
							PodRequest.of(service.getValue(), cluster)));
			}
		}
		return pods;
	}

	private static boolean autoscales(ServiceConfig service) {
		return service.autoscaling() && service.platform() != Platform.SERVERLESS;
	}

	private static InstanceCount nodes(ClusterConfig cluster, List<PodRequest> pods, LoadLevel level) {
		int nodes = cluster.nodesFor(pods);
		String basis = CostText.plural(nodes, "node");
		return new InstanceCount(nodes,
				cluster.clusterAutoscaler() ? basis + atLoad(level, cluster.minNodes(), cluster.maxNodes()) : basis);
	}

	private static String atLoad(LoadLevel level, int fewest, int most) {
		return " at the " + level.words() + " (autoscaling " + fewest + "–" + most + ")";
	}

	/**
	 * The settings of each part of a type, in the design's order.
	 * @param <C> the type's settings
	 * @param document the design
	 * @param registry the component types
	 * @param type the component type
	 * @param kind the type's settings record
	 * @return the settings by part id; a part whose settings are not valid is left out
	 */
	private static <C extends Record> Map<String, C> configs(ArchitectureDocument document, ComponentRegistry registry,
			String type, Class<C> kind) {
		Map<String, C> configs = new LinkedHashMap<>();
		Optional<ComponentSpec<?>> spec = registry.find(type);
		for (NodeSpec node : document.architecture().nodes()) {
			if (node.type().equals(type) && spec.isPresent()) {
				parsed(spec.get(), node, kind).ifPresent((config) -> configs.put(node.id(), config));
			}
		}
		return configs;
	}

	private static <C extends Record> Optional<C> parsed(ComponentSpec<?> spec, NodeSpec node, Class<C> kind) {
		try {
			Record config = spec.parseConfig(node.config());
			return kind.isInstance(config) ? Optional.of(kind.cast(config)) : Optional.empty();
		}
		catch (BindingException ex) {
			// a config that is not valid is reported by validation
			return Optional.empty();
		}
	}

}

package io.archsim.model.component.kubernetes;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.issue.Issue;
import io.archsim.model.text.PythonFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * A Kubernetes cluster: a pool of nodes for the services on the Kubernetes platform. It
 * is not on the request path, so it has no ports; each replica of its services is a pod
 * scheduled by its CPU and memory requests.
 */
public final class KubernetesClusterSpec extends ComponentSpec<ClusterConfig> {

	/** The type name, which services' rules look for. */
	public static final String TYPE = "kubernetes_cluster";

	public KubernetesClusterSpec() {
		super(TYPE, "Kubernetes cluster", Category.COMPUTE, ClusterConfig.class, List.of());
	}

	@Override
	public List<Issue> validate(NodeSpec node, ClusterConfig config, GraphView view) {
		List<Issue> issues = new ArrayList<>();
		String name = node.name();
		if (config.clusterAutoscaler()
				&& !(config.minNodes() <= config.nodes() && config.nodes() <= config.maxNodes())) {
			issues
				.add(Issue
					.warning("kubernetes.nodes_outside_range",
							"%s starts with %d nodes, outside its autoscaling range %d-%d.".formatted(name,
									config.nodes(), config.minNodes(), config.maxNodes()))
					.atNode(node.id())
					.atPath("nodes"));
		}
		List<String> clusters = view.idsOfType(TYPE);
		double allocCpu = config.allocatableCpu();
		double allocMem = config.allocatableMemoryGib();
		double requestedCpu = 0.0;
		double requestedMem = 0.0;
		int members = 0;
		for (NodeSpec other : view.nodes().values()) {
			if (!other.type().equals(ServiceSpec.TYPE)
					|| !(view.configs().get(other.id()) instanceof ServiceConfig service)
					|| !node.id().equals(service.clusterIn(clusters))) {
				continue;
			}
			members++;
			double cpu = service.cpuCores() != null ? service.cpuCores() : config.defaultCpuRequest();
			double mem = service.memoryGib() != null ? service.memoryGib() : config.defaultMemoryRequestGib();
			int pods = service.autoscaling()
					? Math.min(Math.max(service.replicas(), service.minReplicas()), service.maxReplicas())
					: service.replicas();
			requestedCpu += cpu * pods;
			requestedMem += mem * pods;
			if (cpu > allocCpu + 1e-9 || mem > allocMem + 1e-9) {
				issues.add(Issue
					.error("kubernetes.pod_too_big",
							("Pods of %s request %s vCPU and %s GiB, more than one node of %s can offer (%s"
									+ " vCPU, %s GiB allocatable), so they can never be scheduled.")
								.formatted(other.name(), g(cpu), g(mem), name, g(allocCpu), g(allocMem)))
					.atNode(other.id())
					.atPath("cpuCores")
					.withHint("Use bigger nodes or smaller pod requests."));
			}
		}
		if (members > 0 && (requestedCpu > allocCpu * config.nodes() + 1e-9
				|| requestedMem > allocMem * config.nodes() + 1e-9)) {
			issues.add(Issue
				.warning("kubernetes.starts_pending",
						("The starting pods on %s request %s vCPU and %s GiB but its %d nodes offer %s vCPU and"
								+ " %s GiB: some pods start Pending.")
							.formatted(name, g(requestedCpu), g(requestedMem), config.nodes(),
									g(allocCpu * config.nodes()), g(allocMem * config.nodes())))
				.atNode(node.id())
				.atPath("nodes"));
		}
		if (members == 0) {
			issues.add(
					Issue
						.warning("kubernetes.no_services",
								"No service runs on %s. Set a service's platform to Kubernetes%s".formatted(name,
										clusters.size() > 1 ? " and pick this cluster." : "."))
						.atNode(node.id()));
		}
		return issues;
	}

	private static String g(double value) {
		return PythonFormat.format(value, "g");
	}

}

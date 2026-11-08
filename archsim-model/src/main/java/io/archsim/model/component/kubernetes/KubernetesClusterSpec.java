package io.archsim.model.component.kubernetes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentIdentity;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.issue.Issue;
import io.archsim.model.text.PythonFormat;

/**
 * A Kubernetes cluster: a pool of nodes for the services on the Kubernetes platform. It
 * is not on the request path, so it has no ports; each replica of its services is a pod
 * scheduled by its CPU and memory requests.
 */
public final class KubernetesClusterSpec extends ComponentSpec<ClusterConfig> {

	/** The type name, which services' rules look for. */
	public static final String TYPE = "kubernetes_cluster";

	/**
	 * How far a request may exceed what nodes offer and still fit, so floating-point
	 * error does not leave a pod unscheduled.
	 */
	private static final double ROUNDING_TOLERANCE = 1e-9;

	/** Creates the Kubernetes cluster type, which has no ports. */
	public KubernetesClusterSpec() {
		super(new ComponentIdentity(TYPE, "Kubernetes cluster", Category.COMPUTE), ClusterConfig.class, List.of());
	}

	@Override
	public Map<String, TechnologyProfile> technologies() {
		return ClusterConfig.PROFILES.byName();
	}

	@Override
	public List<Issue> validate(NodeSpec node, ClusterConfig config, GraphView view) {
		List<Issue> issues = new ArrayList<>();
		nodesOutsideRange(node, config).ifPresent(issues::add);
		List<String> clusters = view.idsOfType(TYPE);
		List<ServicePods> members = servicesOn(node, config, view, clusters);
		for (ServicePods pods : members) {
			podTooBig(node, config, pods).ifPresent(issues::add);
		}
		if (members.isEmpty()) {
			issues.add(noServices(node, clusters));
		}
		else {
			startsPending(node, config, members).ifPresent(issues::add);
		}
		return issues;
	}

	private static Optional<Issue> nodesOutsideRange(NodeSpec node, ClusterConfig config) {
		boolean inRange = config.minNodes() <= config.nodes() && config.nodes() <= config.maxNodes();
		if (!config.clusterAutoscaler() || inRange) {
			return Optional.empty();
		}
		return Optional
			.of(Issue
				.warning("kubernetes.nodes_outside_range",
						"%s starts with %d nodes, outside its autoscaling range %d-%d.".formatted(node.name(),
								config.nodes(), config.minNodes(), config.maxNodes()))
				.atNode(node.id())
				.atPath("nodes"));
	}

	/**
	 * The pods of each service that runs on the cluster.
	 * @param cluster the cluster's node
	 * @param config the cluster's config, for the requests of pods whose service sets
	 * none
	 * @param view the design the cluster is in
	 * @param clusters the ids of the design's clusters
	 * @return the pods of each service on the cluster, in the document's order
	 */
	private static List<ServicePods> servicesOn(NodeSpec cluster, ClusterConfig config, GraphView view,
			List<String> clusters) {
		List<ServicePods> members = new ArrayList<>();
		for (NodeSpec other : view.nodes().values()) {
			if (other.type().equals(ServiceSpec.TYPE) && view.configs().get(other.id()) instanceof ServiceConfig service
					&& cluster.id().equals(service.clusterIn(clusters))) {
				members.add(ServicePods.of(other, service, config));
			}
		}
		return members;
	}

	private static Optional<Issue> podTooBig(NodeSpec node, ClusterConfig config, ServicePods pods) {
		double allocatableCpu = config.allocatableCpu();
		double allocatableMemoryGib = config.allocatableMemoryGib();
		if (!exceeds(pods.request().cpu(), allocatableCpu)
				&& !exceeds(pods.request().memoryGib(), allocatableMemoryGib)) {
			return Optional.empty();
		}
		return Optional.of(Issue
			.error("kubernetes.pod_too_big",
					("Pods of %s request %s vCPU and %s GiB, more than one node of %s can offer (%s"
							+ " vCPU, %s GiB allocatable), so they can never be scheduled.")
						.formatted(pods.service().name(), format(pods.request().cpu()),
								format(pods.request().memoryGib()), node.name(), format(allocatableCpu),
								format(allocatableMemoryGib)))
			.atNode(pods.service().id())
			.atPath("cpuCores")
			.withHint("Use bigger nodes or smaller pod requests."));
	}

	private static Optional<Issue> startsPending(NodeSpec node, ClusterConfig config, List<ServicePods> members) {
		double requestedCpu = 0.0;
		double requestedMemoryGib = 0.0;
		for (ServicePods pods : members) {
			requestedCpu += pods.request().cpu() * pods.count();
			requestedMemoryGib += pods.request().memoryGib() * pods.count();
		}
		double offeredCpu = config.allocatableCpu() * config.nodes();
		double offeredMemoryGib = config.allocatableMemoryGib() * config.nodes();
		if (!exceeds(requestedCpu, offeredCpu) && !exceeds(requestedMemoryGib, offeredMemoryGib)) {
			return Optional.empty();
		}
		return Optional.of(
				Issue
					.warning("kubernetes.starts_pending",
							("The starting pods on %s request %s vCPU and %s GiB but its %d nodes offer %s vCPU and"
									+ " %s GiB: some pods start Pending.")
								.formatted(node.name(), format(requestedCpu), format(requestedMemoryGib),
										config.nodes(), format(offeredCpu), format(offeredMemoryGib)))
					.atNode(node.id())
					.atPath("nodes"));
	}

	private static Issue noServices(NodeSpec node, List<String> clusters) {
		return Issue
			.warning("kubernetes.no_services",
					"No service runs on %s. Set a service's platform to Kubernetes%s".formatted(node.name(),
							(clusters.size() > 1) ? " and pick this cluster." : "."))
			.atNode(node.id());
	}

	private static boolean exceeds(double requested, double offered) {
		return requested > offered + ROUNDING_TOLERANCE;
	}

	private static String format(double value) {
		return PythonFormat.format(value, "g");
	}

	/**
	 * The pods of one service on the cluster, and what each of them requests.
	 *
	 * @param service the service's node
	 * @param request what each pod requests
	 * @param count the pods the service starts with
	 */
	private record ServicePods(NodeSpec service, PodRequest request, int count) {

		/**
		 * The pods of a service: one per replica it starts with.
		 * @param node the service's node
		 * @param service the service's config
		 * @param cluster the cluster's config
		 * @return the service's pods
		 */
		static ServicePods of(NodeSpec node, ServiceConfig service, ClusterConfig cluster) {
			return new ServicePods(node, PodRequest.of(service, cluster), service.initialReplicas());
		}

	}

}

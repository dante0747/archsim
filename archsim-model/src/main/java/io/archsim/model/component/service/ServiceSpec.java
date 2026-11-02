package io.archsim.model.component.service;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.Port;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.issue.Issue;
import io.archsim.model.text.PythonFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * A service: stateless application servers, each replica with a worker pool and a bounded
 * queue. It calls its outgoing links in order for the request types they carry.
 */
public final class ServiceSpec extends ComponentSpec<ServiceConfig> {

	/** The type name, which other types' rules look for. */
	public static final String TYPE = "service";

	/** The type of the Kubernetes clusters a service may run on. */
	private static final String CLUSTER = "kubernetes_cluster";

	public ServiceSpec() {
		super(TYPE, "Service / API Servers", Category.COMPUTE, ServiceConfig.class,
				List.of(Port.in("in", "Requests", "Incoming requests."),
						Port.out("out", "Dependencies",
								"Downstream calls made while handling a request. Each link's request types decide"
										+ " which requests use it.")));
	}

	@Override
	public List<Issue> validate(NodeSpec node, ServiceConfig config, GraphView view) {
		List<Issue> issues = new ArrayList<>();
		issues.addAll(clusterIssues(node, config, view));
		issues.addAll(scalingIssues(node, config));
		issues.addAll(memoryIssues(node, config));
		issues.addAll(cacheAsideIssues(node, view));
		// Python also checks min replicas against max; the config's own check refuses
		// that first
		return issues;
	}

	private static List<Issue> clusterIssues(NodeSpec node, ServiceConfig config, GraphView view) {
		List<String> clusters = view.idsOfType(CLUSTER);
		String named = config.namedCluster();
		if (named != null && !clusters.contains(named)) {
			return List.of(Issue
				.error("service.cluster_missing",
						"%s runs on cluster '%s', which is not a Kubernetes cluster in this design."
							.formatted(node.name(), named))
				.atNode(node.id())
				.atPath("cluster")
				.withHint("Pick one of the design's clusters, or clear the setting."));
		}
		if (named != null && !config.onKubernetes()) {
			return List.of(
					Issue
						.warning("service.cluster_ignored",
								"%s names a cluster but runs on %s, so the cluster is ignored.".formatted(node.name(),
										config.platform().json()))
						.atNode(node.id())
						.atPath("cluster"));
		}
		if (config.onKubernetes() && named == null && clusters.size() > 1) {
			return List.of(Issue
				.warning("service.cluster_ambiguous",
						("%s runs on Kubernetes but the design has %d clusters; pick one, or its pods are not"
								+ " scheduled on any of them.")
							.formatted(node.name(), clusters.size()))
				.atNode(node.id())
				.atPath("cluster"));
		}
		return List.of();
	}

	private static List<Issue> scalingIssues(NodeSpec node, ServiceConfig config) {
		List<Issue> issues = new ArrayList<>();
		boolean serverless = config.platform() == Platform.SERVERLESS;
		if (serverless && config.autoscaling()) {
			issues.add(Issue
				.warning("service.autoscaling_ignored",
						("%s runs serverless, so the autoscaling settings are ignored (serverless scales per"
								+ " request up to its concurrency limit).")
							.formatted(node.name()))
				.atNode(node.id())
				.withHint("Turn autoscaling off, or pick a non-serverless platform."));
		}
		if (config.autoscaling() && !serverless
				&& !(config.minReplicas() <= config.replicas() && config.replicas() <= config.maxReplicas())) {
			issues.add(Issue
				.warning("service.replicas_outside_range",
						("%s starts with %d replicas, outside its autoscaling range %d-%d; it will start at"
								+ " the nearest bound.")
							.formatted(node.name(), config.replicas(), config.minReplicas(), config.maxReplicas()))
				.atNode(node.id()));
		}
		return issues;
	}

	private static List<Issue> memoryIssues(NodeSpec node, ServiceConfig config) {
		OptionalInt held = config.memorySlots();
		Double memoryGib = config.memoryGib();
		if (held.isEmpty() || memoryGib == null) {
			return List.of();
		}
		int slots = held.getAsInt();
		String memory = PythonFormat.format(memoryGib, "g");
		if (slots < 1) {
			return List
				.of(Issue
					.error("service.memory_too_small",
							("%s has %s GiB per replica, less than it needs at idle plus one request (%s + %s"
									+ " MiB).")
								.formatted(node.name(), memory, PythonFormat.format(config.memoryBaselineMb(), "g"),
										PythonFormat.format(config.memoryPerRequestMb(), "g")))
					.atNode(node.id())
					.withHint("Give it more memory, or lower the memory at idle or per request."));
		}
		if (config.platform() == Platform.SERVERLESS) {
			return List.of();
		}
		int workers = config.workersPerReplica();
		int queued = workers + config.queueCapacity();
		if (slots < workers) {
			return List.of(Issue
				.warning("service.memory_below_workers",
						("%s can hold only %d requests per replica in %s GiB, fewer than its %d workers; the"
								+ " rest are refused as out of memory.")
							.formatted(node.name(), slots, memory, workers))
				.atNode(node.id())
				.withHint("Add memory or lower the workers per replica."));
		}
		if (slots < queued && config.blockingDownstream()) {
			return List.of(
					Issue
						.warning("service.memory_below_queue",
								("%s can hold %d requests per replica in %s GiB but its workers and queue take up to"
										+ " %d; under load requests are refused as out of memory.")
									.formatted(node.name(), slots, memory, queued))
						.atNode(node.id()));
		}
		return List.of();
	}

	/**
	 * Links set to 'Reads only on a cache miss': the service needs a cache lookup that
	 * can answer "miss", which a read-through cache (one with its own miss link) never
	 * does.
	 */
	private static List<Issue> cacheAsideIssues(NodeSpec node, GraphView view) {
		List<EdgeSpec> outs = view.outEdges(node.id());
		List<EdgeSpec> fetch = outs.stream()
			.filter(e -> e.config().readsOnMiss() && e.config().carries(RequestType.READ))
			.toList();
		if (fetch.isEmpty()) {
			return List.of();
		}
		EdgeSpec first = fetch.getFirst();
		List<EdgeSpec> lookups = outs.stream()
			.filter(e -> !e.config().readsOnMiss() && e.config().carries(RequestType.READ)
					&& view.node(e.target()).map(n -> n.type().equals("cache")).orElse(false))
			.toList();
		if (lookups.isEmpty()) {
			return List.of(Issue
				.error("edge.reads_on_miss_no_cache",
						("%s sends reads to %s only on a cache miss, but %s has no link that reads from a"
								+ " cache, so those reads never happen.")
							.formatted(node.name(), view.name(first.target()), node.name()))
				.atNode(node.id())
				.atEdge(first.id())
				.withHint("Link the service to a cache for reads, or turn off 'Reads only on a cache miss' on"
						+ " this link."));
		}
		List<EdgeSpec> readThrough = lookups.stream()
			.filter(e -> view.outEdges(e.target()).stream().anyMatch(x -> x.sourcePort().equals("miss"))
					&& !e.config().continueOnFailure())
			.toList();
		if (readThrough.size() != lookups.size()) {
			return List.of();
		}
		String cache = view.name(readThrough.getFirst().target());
		return List.of(Issue
			.warning("edge.reads_on_miss_unused",
					("%s reads the data store itself on a miss (its 'miss' port is connected), so %s's link to"
							+ " %s set to 'Reads only on a cache miss' never gets reads.")
						.formatted(cache, node.name(), view.name(first.target())))
			.atNode(node.id())
			.atEdge(first.id())
			.withHint(("For cache-aside, remove %s's miss link; for read-through, turn off 'Reads only on a"
					+ " cache miss'.")
				.formatted(cache)));
	}

}

package io.archsim.engine.review;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;

/**
 * The review's rules about a part on its own: a single point of failure, reads that fail
 * with their cache, requests that may wait forever, and an autoscaling range that cannot
 * scale out or scales late.
 */
final class PartRules {

	/** Managed load balancers, run with failover by their provider. */
	private static final Set<String> MANAGED_LOAD_BALANCERS = Set.of("aws_alb", "api_gateway");

	/** Managed queues, run on several brokers by their provider. */
	private static final Set<String> MANAGED_QUEUES = Set.of("sqs", "pubsub", "aws_sqs", "google_pubsub");

	/** A target utilization above this leaves little headroom while replicas start. */
	private static final double LATE_TARGET = 0.85;

	private final ReviewGraph graph;

	private final Findings findings;

	PartRules(ReviewGraph graph, Findings findings) {
		this.graph = graph;
		this.findings = findings;
	}

	/**
	 * Checks a part against each rule.
	 * @param node the part
	 */
	void check(NodeSpec node) {
		String id = node.id();
		ComponentFamily family = this.graph.family(id);
		switch (family) {
			case COMPUTE -> compute(node);
			case DATABASE -> {
				noReplica(id);
				unboundedWait(id, family);
			}
			case NETWORK -> singleLoadBalancer(id);
			case MESSAGING -> singleBroker(id);
			default -> {
				// no rule about a part on its own for the other families
			}
		}
	}

	private void compute(NodeSpec node) {
		String id = node.id();
		if (ServiceSpec.TYPE.equals(node.type())) {
			singleReplica(id);
		}
		cacheDependency(id);
		unboundedWait(id, ComponentFamily.COMPUTE);
		autoscaling(id);
	}

	private void singleReplica(String id) {
		LenientSettings settings = this.graph.settings(id);
		double replicas = settings.number("replicas").orElse(1.0);
		boolean autoscaling = settings.isTrue("autoscaling");
		double least = autoscaling ? settings.nonZero("minReplicas").orElse(replicas) : replicas;
		if (this.graph.serverless(id) || least > 1) {
			return;
		}
		String name = this.graph.label(id);
		this.findings.add(ReviewRule.SPOF, this.graph.onPath(id) ? Severity.HIGH : Severity.MEDIUM, Involved.node(id),
				new Wording(name + " runs a single replica",
						"One crash, deploy or host failure takes down every request that passes through " + name
								+ (autoscaling ? " (autoscaling minimum is 1)" : "") + ".",
						"Run at least 2 replicas (3 across zones for critical paths) behind the load balancer."));
	}

	private void noReplica(String id) {
		LenientSettings settings = this.graph.settings(id);
		double replicas = settings.nonZero("readReplicas").orElse(0.0);
		double factor = settings.number("replicationFactor").orElse(1.0);
		double shards = ReviewNumbers.larger(1, ReviewNumbers.whole(settings.nonZero("shards").orElse(1)));
		if (replicas != 0 || factor > 1 || !this.graph.onPath(id)) {
			return;
		}
		String each = (shards > 1) ? " (each of the " + NumberText.plain(shards) + " shards has a single copy)" : "";
		this.findings.add(ReviewRule.SPOF, Severity.MEDIUM, Involved.node(id), new Wording(
				this.graph.label(id) + " has no replica",
				"A primary failure is an outage for every read and write that reaches it" + each + ".",
				"Add a standby or read replicas (relational), a replica set (document stores) or replication factor 3 "
						+ "(wide-column)."));
	}

	/**
	 * A self-hosted load balancer that requests reach.
	 * @param id the load balancer
	 */
	private void singleLoadBalancer(String id) {
		String technology = this.graph.settings(id).text("technology").orElse("");
		if (!this.graph.onPath(id) || MANAGED_LOAD_BALANCERS.contains(technology)) {
			return;
		}
		String name = this.graph.label(id);
		this.findings.add(ReviewRule.SPOF, Severity.LOW, Involved.node(id),
				new Wording(name + " is a single instance",
						name + " is modelled as one self-hosted instance; if it stops, nothing behind it is reachable.",
						"Run two instances with failover (floating IP, DNS) or use a managed load balancer."));
	}

	/**
	 * A self-hosted queue on one broker that requests reach.
	 * @param id the queue
	 */
	private void singleBroker(String id) {
		LenientSettings settings = this.graph.settings(id);
		String technology = settings.text("technology").orElse("");
		if (!this.graph.onPath(id) || MANAGED_QUEUES.contains(technology)
				|| settings.number("brokers").orElse(1.0) > 1) {
			return;
		}
		String name = this.graph.label(id);
		this.findings.add(ReviewRule.SPOF, Severity.MEDIUM, Involved.node(id),
				new Wording(name + " runs on one broker",
						"Producers cannot publish while the only broker of " + name
								+ " is down, so the asynchronous path stops with it.",
						"Run 3 brokers with replicated partitions/queues, or use a managed queue."));
	}

	/**
	 * Reads that go through a cache which fails them when it is down: no link to the data
	 * store for a miss, or a lookup whose failure fails the read.
	 * @param id the part that reads
	 */
	private void cacheDependency(String id) {
		List<EdgeSpec> reads = this.graph.outs(id)
			.stream()
			.filter((edge) -> edge.config().carries(RequestType.READ))
			.toList();
		List<EdgeSpec> viaCache = reads.stream()
			.filter((edge) -> this.graph.family(edge.target()) == ComponentFamily.CACHE && !edge.config().readsOnMiss())
			.toList();
		List<EdgeSpec> fetch = reads.stream().filter((edge) -> edge.config().readsOnMiss()).toList();
		List<EdgeSpec> hard = viaCache.stream().filter((edge) -> !edge.config().continueOnFailure()).toList();
		boolean missesFallBack = hard.isEmpty() && !fetch.isEmpty();
		if (viaCache.isEmpty() || missesFallBack) {
			return;
		}
		readsFailWithCache(id, hard.isEmpty() ? viaCache : hard, fetch.stream().findFirst());
	}

	/**
	 * Reads that fail when their cache does.
	 * @param id the part that reads
	 * @param failing the links to the cache whose failure fails a read
	 * @param fetch the link to the data store on a miss, if there is one
	 */
	private void readsFailWithCache(String id, List<EdgeSpec> failing, Optional<EdgeSpec> fetch) {
		String cache = failing.get(0).target();
		String name = this.graph.label(id);
		String cacheName = this.graph.label(cache);
		Optional<String> store = fetch.map((edge) -> this.graph.label(edge.target()));
		String detail = store
			.map((storeName) -> name + " reads " + storeName + " on a cache miss, but a failed lookup on " + cacheName
					+ " fails the read instead of counting as a miss.")
			.orElse("Every read from " + name + " goes through " + cacheName
					+ "; if the cache itself is unavailable, reads have no other path.");
		boolean carriesOn = failing.stream().allMatch((edge) -> edge.config().continueOnFailure());
		this.findings.add(ReviewRule.CACHE_DEPENDENCY, Severity.MEDIUM,
				new Involved(List.of(id, cache), failing.stream().map(EdgeSpec::id).toList()),
				new Wording("Reads fail when " + cacheName + " is down", detail,
						fallbackAdvice(new Reader(name, cacheName, carriesOn), store)));
	}

	/**
	 * The advice to a part whose reads fail with its cache: only the part of the fallback
	 * it lacks, carrying on when a lookup fails or a link to read on a miss (ADR-0082).
	 * @param reader the part and its cache
	 * @param store the data store it reads on a miss; empty when it has no such link
	 * @return the advice
	 */
	private static String fallbackAdvice(Reader reader, Optional<String> store) {
		String replicas = ", or run the cache with replicas.";
		if (store.isPresent()) {
			return "Turn on 'Carry on if this call fails' on the link to " + reader.cache() + ", so a failed lookup "
					+ "counts as a miss and " + reader.name() + " reads " + store.get() + " instead (check it can take "
					+ "every read)" + replicas;
		}
		if (reader.carriesOn()) {
			return "Give " + reader.name() + " a link to the data store set to 'Reads only on a cache miss', so reads "
					+ "fall back to the data store when " + reader.cache() + " fails (check it can take every read)"
					+ replicas;
		}
		return "Turn on 'Carry on if this call fails' on the link to " + reader.cache() + " and give " + reader.name()
				+ " a link to the data store set to 'Reads only on a cache miss', so reads fall back to the data store "
				+ "(check it can take every read)" + replicas;
	}

	/**
	 * A queue, or a data store's wait for a connection, with no timeout.
	 * @param id the part
	 * @param family its family: compute or database
	 */
	private void unboundedWait(String id, ComponentFamily family) {
		LenientSettings settings = this.graph.settings(id);
		double capacity = settings.number("queueCapacity").orElse(1.0);
		if (this.graph.serverless(id) || !settings.isNull("queueTimeoutMs") || capacity <= 0) {
			return;
		}
		boolean database = family == ComponentFamily.DATABASE;
		String name = this.graph.label(id);
		this.findings.add(ReviewRule.UNBOUNDED_WAIT, Severity.LOW, Involved.node(id),
				new Wording(name + " lets requests wait forever",
						name + " has no " + (database ? "connection acquire" : "queue") + " timeout, so up to "
								+ HalfUp.grouped(settings.nonZero("queueCapacity").orElse(0)) + " requests"
								+ (database ? "" : " per replica")
								+ " can wait with no limit while clients have long given up.",
						"Set a queue timeout below the caller's timeout so stale work is shed early."));
	}

	private void autoscaling(String id) {
		LenientSettings settings = this.graph.settings(id);
		if (!settings.isTrue("autoscaling") || this.graph.serverless(id)) {
			return;
		}
		double least = settings.number("minReplicas").orElse(1.0);
		double most = settings.number("maxReplicas").orElse(least);
		String name = this.graph.label(id);
		if (most <= least) {
			this.findings.add(ReviewRule.AUTOSCALING_RANGE, Severity.MEDIUM, Involved.node(id), new Wording(
					name + " cannot scale out",
					"Autoscaling is on but the maximum (" + NumberText.plain(most) + ") is not above the minimum ("
							+ NumberText.plain(least) + "), so no capacity can be added under load.",
					"Raise maxReplicas to cover the expected peak (for example 3× the baseline)."));
			return;
		}
		OptionalDouble target = settings.number("targetUtilization");
		if (target.isPresent() && target.getAsDouble() > LATE_TARGET) {
			this.findings.add(ReviewRule.AUTOSCALING_RANGE, Severity.LOW, Involved.node(id),
					new Wording(name + " scales late",
							"Target utilization " + ReviewText.percent(target.getAsDouble())
									+ " leaves little headroom while new replicas start ("
									+ ReviewText.plain(settings.number("startupS"))
									+ " s); a spike saturates the existing ones first.",
							"Target 50–70% utilization for latency-sensitive services, or keep warm spare capacity."));
		}
	}

	/**
	 * A part that reads through a cache.
	 *
	 * @param name its label
	 * @param cache the cache's label
	 * @param carriesOn whether its links to the cache carry on when a lookup fails
	 */
	private record Reader(String name, String cache, boolean carriesOn) {
	}

}

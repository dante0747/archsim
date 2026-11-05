package io.archsim.engine.review;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.Workload;

/**
 * The review's rules about where requests and data go: a data store's hot shard,
 * consistency ALL and the connections its callers may need, a cache's optimistic hit
 * ratio, a queue's backlog, consumers and output, and a third party's errors passed on.
 */
final class StoreRules {

	/** From this Zipf exponent on, popular keys crowd onto a few shards. */
	private static final double HOT_SKEW = 0.8;

	/** A fixed hit ratio above this is rare outside static content. */
	private static final double OPTIMISTIC_HIT_RATIO = 0.98;

	/** A third party failing this share of calls or more needs a retry or a fallback. */
	private static final double NOTABLE_ERROR_RATE = 0.01;

	private static final double PERCENT = 100;

	/** What a data store accepts when its config does not say. */
	private static final double DEFAULT_CONNECTIONS = 10;

	/** What a serverless function may run at once when its config does not say. */
	private static final double DEFAULT_CONCURRENCY = 1000;

	/** A service's workers per replica when its config does not say. */
	private static final double DEFAULT_WORKERS = 4;

	private final ReviewGraph graph;

	private final Findings findings;

	private final Workload workload;

	StoreRules(ReviewGraph graph, Findings findings, Workload workload) {
		this.graph = graph;
		this.findings = findings;
		this.workload = workload;
	}

	/**
	 * Checks a part against each rule.
	 * @param node the part
	 */
	void check(NodeSpec node) {
		String id = node.id();
		switch (this.graph.family(id)) {
			case DATABASE -> {
				hotPartition(id);
				consistencyAll(id);
				connections(id);
			}
			case CACHE -> optimisticCache(id);
			case MESSAGING -> queue(id);
			case EXTERNAL -> externalErrors(id);
			default -> {
				// no rule about where requests and data go for the other families
			}
		}
	}

	private void hotPartition(String id) {
		double shards = ReviewNumbers.larger(1,
				ReviewNumbers.whole(this.graph.settings(id).nonZero("shards").orElse(1)));
		double skew = this.workload.keySkew();
		if (shards <= 1 || skew < HOT_SKEW) {
			return;
		}
		String name = this.graph.label(id);
		this.findings.add(ReviewRule.HOT_PARTITION, Severity.MEDIUM, Involved.node(id),
				new Wording(name + " may have a hot shard", "Key popularity follows Zipf s="
						+ NumberText.plain(skew) + ": the most popular keys land on a few of the "
						+ NumberText.plain(shards) + " shards, so the hottest shard can get several times its fair "
						+ HalfUp.fixed(PERCENT / shards, 1) + "% share. Simulate to measure it.",
						"Pick a higher-cardinality partition key, cache hot keys, or split hot keys across "
								+ "partitions."));
	}

	private void consistencyAll(String id) {
		LenientSettings settings = this.graph.settings(id);
		OptionalDouble factor = settings.number("replicationFactor");
		if (!settings.is("consistency", "all") || factor.orElse(1.0) <= 1) {
			return;
		}
		String name = this.graph.label(id);
		this.findings.add(ReviewRule.CONSISTENCY_ALL, Severity.LOW, Involved.node(id),
				new Wording(name + " uses consistency ALL", "Every operation waits for all " + ReviewText.plain(factor)
						+ " replicas: latency follows the slowest one and a single replica outage fails the request.",
						"Use QUORUM for both reads and writes to keep strong consistency with one replica down."));
	}

	/**
	 * The connections a data store's callers may need: a serverless function's instances
	 * each open one, a blocking service's workers each hold one.
	 * @param id the data store
	 */
	private void connections(String id) {
		double connections = accepted(this.graph.settings(id));
		double workers = 0;
		List<String> blockers = new ArrayList<>();
		for (EdgeSpec edge : this.graph.ins(id)) {
			String caller = edge.source();
			LenientSettings settings = this.graph.settings(caller);
			if (this.graph.family(caller) != ComponentFamily.COMPUTE) {
				continue;
			}
			if (this.graph.serverless(caller)) {
				connectionStorm(edge, settings.number("concurrencyLimit").orElse(DEFAULT_CONCURRENCY), connections);
			}
			else if (!settings.isFalse("blockingDownstream")) {
				workers += workers(settings);
				blockers.add(caller);
			}
		}
		if (workers > connections) {
			poolExhaustion(id, workers, connections, blockers);
		}
	}

	private void connectionStorm(EdgeSpec edge, double limit, double connections) {
		if (limit <= connections) {
			return;
		}
		String caller = this.graph.label(edge.source());
		String name = this.graph.label(edge.target());
		this.findings.add(ReviewRule.CONNECTION_STORM, Severity.HIGH,
				new Involved(List.of(edge.source(), edge.target()), List.of(edge.id())),
				new Wording(caller + " can open " + HalfUp.grouped(limit) + " connections to " + name,
						"Up to " + HalfUp.grouped(limit)
								+ " concurrent function instances may each hold a connection, but " + name + " accepts "
								+ HalfUp.grouped(connections)
								+ ". A traffic spike turns into connection errors and lock contention.",
						"Put a connection pooler or proxy in front (PgBouncer, RDS Proxy-style), or cap function "
								+ "concurrency."));
	}

	/**
	 * More blocking workers than the data store has connections.
	 * @param id the data store
	 * @param workers the most workers its blocking callers run
	 * @param connections the connections it accepts
	 * @param blockers its blocking callers, once per link
	 */
	private void poolExhaustion(String id, double workers, double connections, List<String> blockers) {
		String name = this.graph.label(id);
		OptionalDouble timeout = this.graph.settings(id).number("queueTimeoutMs");
		String fail = timeout.isPresent() ? " and fail after " + ReviewText.milliseconds(timeout.getAsDouble()) : "";
		List<String> nodeIds = new ArrayList<>(blockers);
		nodeIds.add(id);
		List<String> edgeIds = this.graph.ins(id)
			.stream()
			.filter((edge) -> blockers.contains(edge.source()))
			.map(EdgeSpec::id)
			.toList();
		this.findings.add(ReviewRule.POOL_EXHAUSTION, (workers > 2 * connections) ? Severity.MEDIUM : Severity.LOW,
				new Involved(nodeIds, edgeIds),
				new Wording(name + " has fewer connections than callers",
						"Up to " + HalfUp.grouped(workers) + " blocking workers ("
								+ String.join(", ", blockers.stream().map(this.graph::label).toList())
								+ ") can wait on " + name + ", which accepts " + HalfUp.grouped(connections)
								+ " connections. When it slows down, " + HalfUp.grouped(workers - connections)
								+ " requests queue for a connection" + fail + ".",
						"Size application pools to the database's real concurrency, add a pooler, or cap service "
								+ "concurrency."));
	}

	private void optimisticCache(String id) {
		LenientSettings settings = this.graph.settings(id);
		OptionalDouble ratio = settings.number("hitRatio");
		if (!this.graph.hitModelIs(id, ReviewGraph.FIXED_RATIO) || ratio.isEmpty()
				|| ratio.getAsDouble() <= OPTIMISTIC_HIT_RATIO) {
			return;
		}
		this.findings.add(ReviewRule.OPTIMISTIC_CACHE, Severity.LOW, Involved.node(id), new Wording(
				this.graph.label(id) + " assumes a " + HalfUp.fixed(ratio.getAsDouble() * PERCENT, 1) + "% hit ratio",
				"Hit ratios this high are rare outside static content; the data store behind it is sized for the "
						+ "misses you assume.",
				"Use the measured hit ratio, or the TTL + keyspace model, and test a cold-cache scenario."));
	}

	private void queue(String id) {
		LenientSettings settings = this.graph.settings(id);
		String name = this.graph.label(id);
		if (settings.isNull("maxDepth")) {
			this.findings.add(ReviewRule.QUEUE_UNBOUNDED, Severity.MEDIUM, Involved.node(id), new Wording(
					name + " has an unbounded backlog",
					"If consumers fall behind or a downstream dependency is down, " + name
							+ " keeps accepting messages without limit (memory, disk and recovery time grow).",
					"Set a maximum depth or retention and alert on consumer lag; decide what producers do when it is "
							+ "full."));
		}
		if (this.graph.outs(id).isEmpty()) {
			this.findings.add(ReviewRule.QUEUE_SINK, Severity.LOW, Involved.node(id), new Wording(
					name + " has no downstream link",
					"Consumers process messages but call nothing, so the asynchronous work is not modelled beyond the "
							+ "queue.",
					"Link the queue's output to the service or store its consumers write to."));
		}
		idleConsumers(id, settings.number("partitions"), settings.number("consumers"));
	}

	private void idleConsumers(String id, OptionalDouble partitions, OptionalDouble consumers) {
		if (partitions.isEmpty() || consumers.isEmpty() || consumers.getAsDouble() <= partitions.getAsDouble()) {
			return;
		}
		String active = NumberText.plain(ReviewNumbers.whole(partitions.getAsDouble()));
		this.findings.add(ReviewRule.QUEUE_PARTITIONS, Severity.LOW, Involved.node(id),
				new Wording(
						NumberText.plain(ReviewNumbers.whole(consumers.getAsDouble() - partitions.getAsDouble()))
								+ " consumers of " + this.graph.label(id) + " sit idle",
						"Only one consumer per partition is active: " + active + " partitions cap parallelism at "
								+ active + " although " + NumberText.plain(ReviewNumbers.whole(consumers.getAsDouble()))
								+ " consumers run.",
						"Add partitions or reduce consumers."));
	}

	private void externalErrors(String id) {
		double errorRate = this.graph.settings(id).nonZero("errorRate").orElse(0);
		List<EdgeSpec> callers = this.graph.ins(id)
			.stream()
			.filter((edge) -> edge.config().maxAttempts() <= 1)
			.toList();
		if (errorRate < NOTABLE_ERROR_RATE || callers.isEmpty()) {
			return;
		}
		String name = this.graph.label(id);
		this.findings.add(ReviewRule.EXTERNAL_ERRORS, Severity.LOW,
				new Involved(List.of(id), callers.stream().map(EdgeSpec::id).toList()),
				new Wording(HalfUp.fixed(errorRate * PERCENT, 1) + "% of calls to " + name + " fail",
						String.join(", ", callers.stream().map((edge) -> this.graph.label(edge.source())).toList())
								+ " call " + name + " once with no retry, so every transient upstream error becomes a "
								+ "user-visible failure.",
						"Add one retry with backoff and jitter for idempotent calls, or a fallback/queue for "
								+ "non-critical calls."));
	}

	/**
	 * The connections a data store accepts over its shards and copies.
	 * @param settings its settings
	 * @return the connections
	 */
	private static double accepted(LenientSettings settings) {
		double each = settings.number("maxConnections").orElse(DEFAULT_CONNECTIONS);
		double shards = ReviewNumbers.larger(1.0, settings.nonZero("shards").orElse(1.0));
		double copies = ReviewNumbers.larger(1 + settings.nonZero("readReplicas").orElse(0.0),
				settings.number("replicationFactor").orElse(1.0));
		return each * shards * copies;
	}

	/**
	 * The most workers a service runs at once, at its largest scale.
	 * @param settings its settings
	 * @return the workers
	 */
	private static double workers(LenientSettings settings) {
		double perReplica = settings.number("workersPerReplica").orElse(DEFAULT_WORKERS);
		double replicas = settings.number("replicas").orElse(1.0);
		if (settings.isTrue("autoscaling")) {
			replicas = ReviewNumbers.larger(settings.nonZero("maxReplicas").orElse(0.0), replicas);
		}
		return perReplica * replicas;
	}

}

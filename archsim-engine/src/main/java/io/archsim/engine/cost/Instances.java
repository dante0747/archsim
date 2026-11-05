package io.archsim.engine.cost;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.text.HalfUp;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.external.ExternalApiSpec;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.component.loadbalancer.LoadBalancerSpec;
import io.archsim.model.component.queue.MessageQueueSpec;
import io.archsim.model.component.service.ServiceSpec;

/** The billable instances of each kind of part, before a run or in one. */
final class Instances {

	/** A cluster that does not say has this many nodes. */
	private static final double DEFAULT_CLUSTER_NODES = 3.0;

	/** How far from one a run's scale may be and still leave its counts as they were. */
	private static final double UNSCALED = 1e-9;

	private Instances() {
	}

	/**
	 * The billable instances of a part.
	 * @param part the part
	 * @param registry the component types, for the part's family
	 * @return its instances and how they were counted
	 */
	static InstanceCount of(CostPart part, ComponentRegistry registry) {
		if (ComponentFamily.of(part.type(), registry) == ComponentFamily.TRAFFIC) {
			return new InstanceCount(0.0, "not billed");
		}
		return switch (part.type()) {
			case ExternalApiSpec.TYPE -> new InstanceCount(0.0, "per call");
			case ServiceSpec.TYPE -> counted(atLeastOne(part.valueNumber("replicas")), "replica");
			case KubernetesClusterSpec.TYPE -> cluster(part);
			case DatabaseSpec.TYPE -> database(part);
			case MessageQueueSpec.TYPE -> counted(atLeastOne(part.valueNumber("brokers")), "broker");
			case CacheSpec.TYPE, LoadBalancerSpec.TYPE -> counted(atLeastOne(part.valueNumber("nodes")), "node");
			default -> other(part);
		};
	}

	/**
	 * The billable instances of a part in a run: an autoscaled service's or cluster's
	 * average active instances times the scale, at least its minimum; any other part's as
	 * before a run.
	 * @param part the part
	 * @param registry the component types, for the part's family
	 * @param measured the part in the run's report; {@code null} when the report does not
	 * have it
	 * @param scale the base load ÷ the run's offered load
	 * @return its instances and how they were counted
	 */
	static InstanceCount of(CostPart part, ComponentRegistry registry, @Nullable ReportNode measured, double scale) {
		if (measured != null) {
			Optional<InstanceCount> averaged = switch (part.type()) {
				case ServiceSpec.TYPE ->
					averaged(measured.series("replicas"), part.valueNumber("replicas"), scale, "active instance");
				case KubernetesClusterSpec.TYPE ->
					averaged(measured.series("nodes"), part.valueNumber("minNodes"), scale, "node");
				default -> Optional.empty();
			};
			if (averaged.isPresent()) {
				return averaged.get();
			}
		}
		return of(part, registry);
	}

	/**
	 * A part's average active instances in a run, scaled to the base load.
	 * @param series its active instances per time bucket
	 * @param minimum the fewest it runs; {@code null} when unset
	 * @param scale the base load ÷ the run's offered load
	 * @param word what an instance is called, such as {@code node}
	 * @return the instances; empty when the run measured none
	 */
	private static Optional<InstanceCount> averaged(List<@Nullable Double> series, @Nullable Double minimum,
			double scale, String word) {
		List<Double> measured = series.stream().filter(Objects::nonNull).toList();
		if (measured.isEmpty()) {
			return Optional.empty();
		}
		double count = FloatSum.of(measured.stream().mapToDouble(Double::doubleValue)) / measured.size();
		String atBase = "";
		if (Math.abs(scale - 1.0) > UNSCALED) {
			count = Math.max(atLeastOne(minimum), count * scale);
			atBase = " at the base load";
		}
		return Optional.of(new InstanceCount(count, "avg " + CostText.plural(HalfUp.round(count, 1), word) + atBase));
	}

	private static InstanceCount cluster(CostPart part) {
		Double nodes = part.valueNumber("nodes");
		return counted(Math.max(0.0, (nodes != null) ? nodes : DEFAULT_CLUSTER_NODES), "node");
	}

	/**
	 * A database: per shard, the larger of its replicas plus one and its replication
	 * factor.
	 * @param part the database
	 * @return its nodes
	 */
	private static InstanceCount database(CostPart part) {
		double shards = atLeastOne(part.valueNumber("shards"));
		double factor = atLeastOne(part.valueNumber("replicationFactor"));
		Double replicas = part.valueNumber("readReplicas");
		double perShard = Math.max(factor, 1 + Math.max(0.0, orZero(replicas)));
		String basis = (shards > 1) ? CostText.plural(shards, "shard") + " × " + CostText.plural(perShard, "node")
				: CostText.plural(perShard, "node");
		return new InstanceCount(shards * perShard, basis);
	}

	private static InstanceCount other(CostPart part) {
		Double replicas = part.valueNumber("replicas");
		double count = Math.max(1.0, (replicas != null) ? replicas : orOne(part.valueNumber("instances")));
		return counted(count, "instance");
	}

	private static InstanceCount counted(double count, String word) {
		return new InstanceCount(count, CostText.plural(count, word));
	}

	/**
	 * Python's {@code max(1.0, x or 1.0)}: an unset or zero count is one.
	 * @param value the count
	 * @return at least one
	 */
	private static double atLeastOne(@Nullable Double value) {
		return Math.max(1.0, orOne(value));
	}

	/**
	 * Python's {@code x or 1.0}: an unset or zero count is one; a negative one stays.
	 * @param value the count
	 * @return the count, or one
	 */
	private static double orOne(@Nullable Double value) {
		return (value != null && value != 0) ? value : 1.0;
	}

	private static double orZero(@Nullable Double value) {
		return (value != null) ? value : 0.0;
	}

}

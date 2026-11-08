package io.archsim.engine.components.queue;

import java.util.OptionalDouble;
import java.util.function.DoubleSupplier;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.SimContext;
import io.archsim.engine.runtime.Timing;
import io.archsim.model.component.queue.MessageQueueConfig;
import io.archsim.model.component.queue.Partitioning;
import io.archsim.model.distribution.Distribution;

/**
 * What a queue's publishers and consumers share during a run.
 *
 * @param simulation the run
 * @param nodeId the queue
 * @param metrics its metrics
 * @param config its settings
 * @param backlog its backlog
 * @param deliveries what its consumers do with each message
 * @param partitions its partitions; {@code null} for a shared queue
 */
record QueueState(Simulation simulation, String nodeId, NodeMetrics metrics, MessageQueueConfig config, Backlog backlog,
		Deliveries deliveries, @Nullable Partitions partitions) {

	private static final double MS_PER_SECOND = 1000.0;

	/**
	 * A queue at the start of a run, its consumers started.
	 * @param context the run
	 * @param config the queue's settings
	 * @param owner the part it is
	 * @return its state
	 */
	static QueueState of(SimContext context, MessageQueueConfig config, Owner owner) {
		PythonRandom consumerStream = context.stream("node:" + owner.nodeId() + ":consumer");
		Holding holding = holding(context, config);
		Backlog backlog = new Backlog(context.simulation(), owner.metrics(), holding, config.flowThreshold());
		Sampler consumerTime = Sampler.of(config.consumerTimeMs());
		OptionalDouble visibilityMs = config.visibilityTimeoutSInEffect().isPresent()
				? OptionalDouble.of(config.visibilityTimeoutSInEffect().getAsDouble() * MS_PER_SECOND)
				: OptionalDouble.empty();
		Deliveries deliveries = new Deliveries(context, new Deliveries.Owner(owner.nodeId(), owner.metrics()), backlog,
				new Deliveries.Rules(() -> owner.timing().sample(consumerTime, consumerStream), config.maxDeliveries(),
						visibilityMs));
		holding.startConsumers(new Consuming(context.simulation(), deliveries, config.concurrencyPerConsumer(),
				config.fetchesInCycles(), fetchOverhead(context, config, owner)));
		Partitions partitions = (holding instanceof Partitions partitioned) ? partitioned : null;
		return new QueueState(context.simulation(), owner.nodeId(), owner.metrics(), config, backlog, deliveries,
				partitions);
	}

	// partitions, a shared queue its consumers poll, or one they take from one by one
	private static Holding holding(SimContext context, MessageQueueConfig config) {
		int consumers = effectiveConsumers(config);
		if (config.partitioned()) {
			return new Partitions(context.simulation(), context.timeline(), new Partitions.Layout(config.partitions(),
					config.partitioningInEffect() == Partitioning.KEY, consumers));
		}
		if (config.fetchesInCycles()) {
			return new SharedQueue(context.simulation(), consumers);
		}
		return new SharedStore(context.simulation(), consumers);
	}

	/**
	 * The consumers that have work: no more than there are partitions.
	 * @param config the queue's settings
	 * @return {@code min(consumers, partitions)}
	 */
	static int effectiveConsumers(MessageQueueConfig config) {
		return Math.min(config.consumers(), config.partitions());
	}

	// draws a poll's fetch-and-commit time, when consumers poll and have one
	private static @Nullable DoubleSupplier fetchOverhead(SimContext context, MessageQueueConfig config, Owner owner) {
		Distribution fetchOverheadMs = config.fetchOverheadMs();
		if (fetchOverheadMs == null) {
			return null;
		}
		Sampler overhead = Sampler.of(fetchOverheadMs);
		PythonRandom fetchStream = context.stream("node:" + owner.nodeId() + ":fetch");
		return () -> owner.timing().sample(overhead, fetchStream);
	}

	/**
	 * The part a queue's state belongs to.
	 *
	 * @param nodeId its id
	 * @param metrics its metrics
	 * @param timing how it draws its times
	 */
	record Owner(String nodeId, NodeMetrics metrics, Timing timing) {
	}

}

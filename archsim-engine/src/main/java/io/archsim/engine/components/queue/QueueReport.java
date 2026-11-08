package io.archsim.engine.components.queue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.SequencedMap;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.MeanAccumulator;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.queue.MessageQueueConfig;
import io.archsim.model.component.queue.MessageQueueSpec;

/**
 * What a queue adds to its part of a run's report: its consumers, its backlog at the end,
 * its messages' age, the series of its lag, partitions, redeliveries, drops and blocked
 * publishers, and the behaviours its technology brings that are in effect.
 */
final class QueueReport {

	private static final Map<String, List<TechnologyTrait>> TRAITS = ComponentCatalog
		.technologyTraits(MessageQueueSpec.TYPE);

	private static final int DIGITS = 4;

	private static final double P95 = 95;

	private QueueReport() {
	}

	/**
	 * The queue's own summary figures: its effective consumers, its consumer slots when a
	 * consumer handles more than one message at once, its backlog at the end, its
	 * messages' mean and 95th-percentile age, and its longest partition at the end.
	 * @param state the queue
	 * @return the figures, in the report's order
	 */
	static SequencedMap<String, @Nullable Number> extraSummary(QueueState state) {
		SequencedMap<String, @Nullable Number> figures = new LinkedHashMap<>();
		MessageQueueConfig config = state.config();
		figures.put("effectiveConsumers", QueueState.effectiveConsumers(config));
		if (config.concurrencyPerConsumer() > 1) {
			figures.put("consumerSlots", config.consumerSlots());
		}
		figures.put("backlogAtEnd", state.backlog().depth());
		figures.put("meanMessageAgeMs", rounded(state.deliveries().ages().windowMean()));
		figures.put("p95MessageAgeMs", rounded(state.deliveries().windowAges().percentile(P95)));
		Partitions partitions = state.partitions();
		if (partitions != null) {
			figures.put("deepestPartitionAtEnd", partitions.longestNow());
		}
		return figures;
	}

	/**
	 * The queue's own series: its lag, its longest partition, its redeliveries, its drops
	 * and the time publishers were blocked, each when it is modelled.
	 * @param state the queue
	 * @return the series over the whole timeline, in the report's order
	 */
	static SequencedMap<String, List<@Nullable Double>> extraTimeseries(QueueState state) {
		SequencedMap<String, List<@Nullable Double>> series = new LinkedHashMap<>();
		NodeMetrics metrics = state.metrics();
		series.put("lag", RuntimeComponent.series(metrics.queue().meanSeries()));
		Partitions partitions = state.partitions();
		if (partitions != null) {
			series.put("partitionLagMax",
					RuntimeComponent.series(Arrays.stream(partitions.longestPerBucket()).asDoubleStream().toArray()));
		}
		putRate(series, "redeliveryRps", metrics.counters().get(Deliveries.REDELIVERIES));
		putRate(series, "droppedRps", metrics.counters().get(Publishing.DROPPED));
		MeanAccumulator blocked = metrics.means().get(Publishing.PUBLISH_BLOCKED);
		if (blocked != null) {
			series.put("publishBlockedMs", blocked.series());
		}
		return series;
	}

	/**
	 * The behaviours the queue's technology brings that are in effect.
	 * @param state the queue
	 * @return the traits in effect, in the technology's order
	 */
	static List<TechnologyTrait> behaviour(QueueState state) {
		MessageQueueConfig config = state.config();
		boolean visibility = config.visibilityTimeoutSInEffect().isPresent();
		Map<String, Boolean> active = Map.of("partitions", state.partitions() != null, "acks_all",
				config.acknowledgedByAll(), "linger", config.lingerMsInEffect() > 0, "memory_alarm",
				config.flowThreshold().isPresent(), "visibility_timeout", visibility, "ack_deadline", visibility,
				"fifo_limit", config.limitsFifoPublishes());
		return TRAITS.getOrDefault(config.technology().json(), List.of())
			.stream()
			.filter((trait) -> active.getOrDefault(trait.id(), true))
			.toList();
	}

	private static void putRate(SequencedMap<String, List<@Nullable Double>> series, String name,
			@Nullable Counter counter) {
		if (counter != null) {
			series.put(name, RuntimeComponent.series(counter.rateSeries()));
		}
	}

	private static @Nullable Double rounded(OptionalDouble value) {
		return value.isPresent() ? PythonRound.round(value.getAsDouble(), DIGITS) : null;
	}

}

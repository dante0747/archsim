package io.archsim.engine.components.queue;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.queueing.Zipf;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.text.NumberText;
import io.archsim.model.component.queue.MessageQueueConfig;
import io.archsim.model.component.queue.Partitioning;
import io.archsim.model.document.Workload;
import io.archsim.model.text.PythonFormat;

/**
 * The calculations behind a queue's own figures, each when it applies: its effective
 * consumers and their slots, how fast they drain the backlog, its hottest partition, its
 * memory alarm and its visibility timeout.
 */
final class QueueExplain {

	private static final double MS_PER_SECOND = 1000.0;

	private static final int TWO_DIGITS = 2;

	private QueueExplain() {
	}

	/**
	 * A queue's formulas, in the report's order.
	 * @param state the queue
	 * @param workload the run's workload, its keyspace
	 * @param summary the queue's summary in the report
	 * @return the formulas that apply
	 */
	static List<Formula> of(QueueState state, Workload workload, NodeSummary summary) {
		MessageQueueConfig config = state.config();
		List<Formula> formulas = new ArrayList<>();
		formulas.add(consumers(config));
		boolean partitioned = state.partitions() != null;
		if (config.concurrencyPerConsumer() > 1) {
			formulas.add(consumerSlots(config, partitioned));
		}
		Double meanHoldMs = summary.meanHoldMs();
		if (meanHoldMs != null && meanHoldMs != 0) {
			formulas.add(drainRate(config, meanHoldMs, summary.arrivalRps()));
		}
		if (partitioned && config.partitioningInEffect() == Partitioning.KEY) {
			formulas.add(hotPartition(config, workload));
		}
		config.flowThreshold().ifPresent((threshold) -> formulas.add(memoryAlarm(config, threshold)));
		config.visibilityTimeoutSInEffect().ifPresent((seconds) -> formulas.add(visibility(seconds)));
		return formulas;
	}

	private static Formula consumers(MessageQueueConfig config) {
		return Formula
			.of("consumers", "Effective consumers", "effective consumers = min(consumers, partitions)",
					"min(" + config.consumers() + ", " + config.partitions() + ")")
			.withResult((double) QueueState.effectiveConsumers(config), "consumers")
			.withMetric("utilization");
	}

	private static Formula consumerSlots(MessageQueueConfig config, boolean partitioned) {
		return Formula
			.of("consumer_slots", "Consumer slots",
					partitioned ? "slots = Σ over consumers of min(concurrency, partitions it owns)"
							: "slots = effective consumers × concurrency",
					QueueState.effectiveConsumers(config) + " consumers × up to " + config.concurrencyPerConsumer()
							+ " messages at once")
			.withResult((double) config.consumerSlots(), "slots")
			.withNote("A partition's messages go one at a time, so a consumer uses at most one slot per partition "
					+ "it owns.")
			.withMetric("utilization");
	}

	private static Formula drainRate(MessageQueueConfig config, double meanHoldMs, @Nullable Double arrivalRps) {
		int slots = config.consumerSlots();
		String what = (config.concurrencyPerConsumer() > 1) ? "consumer slots" : "effective consumers";
		String note = "Publishes: " + NumberText.formula(arrivalRps, TWO_DIGITS)
				+ "/s. When they exceed the consumer throughput the backlog (lag) grows.";
		if (config.fetchesInCycles()) {
			note += " Consumers poll in cycles: each poll also costs the fetch overhead, spread over more messages "
					+ "per poll as the backlog grows, so the throughput approaches this limit only under load; "
					+ "below it, a message waits for the next poll.";
		}
		return Formula
			.of("drain_rate", "Consumer throughput", "messages/s = " + what + " × 1000 ÷ mean processing time",
					slots + " × 1000 ÷ " + NumberText.formula(meanHoldMs, TWO_DIGITS) + " ms")
			.withResult(slots * MS_PER_SECOND / meanHoldMs, "msg/s")
			.withNote(note)
			.withMetric("lag");
	}

	private static Formula hotPartition(MessageQueueConfig config, Workload workload) {
		double share = Zipf.share(workload.keyCount(), workload.keySkew(), 1);
		return Formula
			.of("hot_partition", "Hottest partition", "hot partition share ≥ hottest key's share = 1 ÷ Σ k^−s",
					PythonFormat.format(workload.keyCount(), ",") + " keys, Zipf s = "
							+ PythonFormat.format(workload.keySkew(), "g") + "; fair share 1 ÷ " + config.partitions())
			.withResult(share, "")
			.withNote("The partition holding the hottest key gets at least " + NumberText.percent(share)
					+ " of messages against a fair " + NumberText.percent(1.0 / config.partitions())
					+ "; its one consumer has to keep up with that alone.")
			.withMetric("partitionLagMax");
	}

	private static Formula memoryAlarm(MessageQueueConfig config, double threshold) {
		Double memoryGib = config.memoryGib();
		return Formula.of("memory_alarm", "Memory alarm threshold", "backlog limit = watermark × memory ÷ message size",
				NumberText.formula(config.memoryHighWatermark(), TWO_DIGITS) + " × " + NumberText.formula(memoryGib, 1)
						+ " GiB ÷ " + NumberText.formula(config.messageSizeKb(), 1) + " KiB")
			.withResult(threshold, "messages")
			.withNote("Past it, publishers block until the backlog falls below 90% of the limit.")
			.withMetric("publishBlockedMs");
	}

	private static Formula visibility(double seconds) {
		return Formula
			.of("visibility", "Visibility timeout", "redelivered if processing time > visibility timeout",
					"timeout " + NumberText.formula(seconds, 1) + " s")
			.withResult(seconds, "s")
			.withNote("Processing (consumer time plus downstream calls) longer than this makes the message visible "
					+ "again: a second consumer repeats the work.")
			.withMetric("redeliveryRps");
	}

}

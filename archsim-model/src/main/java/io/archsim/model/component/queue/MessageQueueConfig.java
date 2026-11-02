package io.archsim.model.component.queue;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.DefaultFrom;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.component.Resources;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.TechnologyProfiles;
import io.archsim.model.component.Toggle;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.distribution.LogNormal;

/**
 * A message queue or stream: its partitions and consumers, publish and consumer times,
 * delivery, flow control and brokers.
 *
 * @param technology the messaging product
 * @param partitions partitions (ordered logs) of a partitioned queue
 * @param consumers consumer processes
 * @param maxDepth messages it holds before refusing publishes; {@code null}: no limit
 * @param publishTimeMs the time a publish takes to be acknowledged
 * @param consumerTimeMs the time a consumer works on a message, calls excluded
 * @param consumerConcurrency messages one consumer handles at once (schema 1.6);
 * {@code null}: one
 * @param fetchOverheadMs a consumer's fetch-and-commit time per poll (schema 1.6);
 * {@code null}: no fetch cycles
 * @param maxDeliveries deliveries of a failing message before it is dead-lettered
 * @param partitioning how messages are spread over partitions; auto follows the
 * technology
 * @param acks what a publish waits for; auto follows the technology
 * @param replicationMs the time a publish takes to reach replicas
 * @param lingerMs how long a producer batches messages; {@code null}: the technology's
 * @param delivery whether a failed message is delivered again; auto follows the
 * technology
 * @param visibilityTimeoutS how long a message stays hidden while it is processed;
 * {@code null}: the technology's
 * @param fifo whether the queue keeps first-in, first-out order (with its throughput
 * limit)
 * @param fifoLimitRps the messages per second a FIFO queue takes
 * @param flowControl whether a memory alarm blocks publishers; auto follows the
 * technology
 * @param memoryHighWatermark the share of memory at which the alarm fires
 * @param messageSizeKb the size of one message
 * @param brokers broker instances
 * @param cpuCores vCPU per broker (for the design and prices only)
 * @param memoryGib memory per broker
 * @param storageGib disk per broker
 * @param instanceType the instance type in the design's price snapshot (schema 1.4)
 * @param costPerHour the price of one broker per hour, in USD
 */
public record MessageQueueConfig(@Default("generic") QueueTechnology technology,
		@Default("8") @Ge(1) @Le(10_000) int partitions, @Default("4") @Ge(1) @Le(10_000) int consumers,
		@Default("100000") @Ge(1) @Le(100_000_000) @Nullable Integer maxDepth,

		@DefaultFrom("DEFAULT_PUBLISH_TIME") Distribution publishTimeMs,

		@DefaultFrom("DEFAULT_CONSUMER_TIME") Distribution consumerTimeMs,

		@Ge(1) @Le(10_000) @Nullable Integer consumerConcurrency, @Nullable Distribution fetchOverheadMs,
		@Default("3") @Ge(1) @Le(20) int maxDeliveries, @Default("auto") Partitioning partitioning,
		@Default("auto") Acks acks,

		@DefaultFrom("DEFAULT_REPLICATION") Distribution replicationMs,

		@Ge(0) @Le(10_000) @Nullable Double lingerMs, @Default("auto") Delivery delivery,
		@Gt(0) @Le(43_200) @Nullable Double visibilityTimeoutS, @Default("false") boolean fifo,
		@Default("300.0") @Gt(0) @Le(1_000_000) double fifoLimitRps, @Default("auto") Toggle flowControl,
		@Default("0.6") @Gt(0) @Le(1) double memoryHighWatermark,
		@Default("1.0") @Gt(0) @Le(1_048_576) double messageSizeKb, @Default("1") @Ge(1) @Le(1000) int brokers,
		@Gt(0) @Le(Resources.MAX_CPU_CORES) @Nullable Double cpuCores,
		@Gt(0) @Le(Resources.MAX_MEMORY_GIB) @Nullable Double memoryGib,
		@Gt(0) @Le(Resources.MAX_STORAGE_GIB) @Nullable Double storageGib,
		@Matches(Resources.INSTANCE_TYPE) @Nullable String instanceType,
		@Default("0.15") @Ge(0) @Le(10_000) double costPerHour) {

	/** The default time a publish takes to be acknowledged: 2 ms median, 8 ms p95. */
	static final Distribution DEFAULT_PUBLISH_TIME = new LogNormal(2.0, 8.0);

	/** The default time a consumer works on a message: 5 ms median, 20 ms p95. */
	static final Distribution DEFAULT_CONSUMER_TIME = new LogNormal(5.0, 20.0);

	/** The default time a publish takes to reach the replicas: 2 ms median, 8 ms p95. */
	static final Distribution DEFAULT_REPLICATION = new LogNormal(2.0, 8.0);

	/**
	 * What each technology does by default (technology.py's {@code QUEUE_PROFILES}). A
	 * visibility timeout Python lists as {@code None} is left out: the setting's fallback
	 * is none too.
	 */
	static final TechnologyProfiles<QueueTechnology> PROFILES = new TechnologyProfiles<>(QueueTechnology.class,
			Map.of(QueueTechnology.KAFKA,
					TechnologyProfile.core()
						.with("partitioning", "key")
						.with("acks", "all")
						.with("lingerMs", 5.0)
						.with("flowControl", "off")
						.with("delivery", "at_least_once"),
					QueueTechnology.RABBITMQ,
					unpartitioned(TechnologyProfile.experimental(), "on").with("delivery", "at_least_once"),
					QueueTechnology.SQS,
					unpartitioned(TechnologyProfile.core(), "off").with("delivery", "at_least_once")
						.with("visibilityTimeoutS", 30.0),
					QueueTechnology.PUBSUB,
					unpartitioned(TechnologyProfile.experimental(), "off").with("delivery", "at_least_once")
						.with("visibilityTimeoutS", 10.0),
					QueueTechnology.NATS,
					unpartitioned(TechnologyProfile.experimental(), "off").with("delivery", "at_least_once"),
					QueueTechnology.REDIS_STREAMS, unpartitioned(TechnologyProfile.experimental(), "off"),
					QueueTechnology.GENERIC, unpartitioned(TechnologyProfile.core(), "off")));

	/**
	 * The profile of one shared queue: no partitions, publishes acknowledged by the
	 * leader, no batching.
	 * @param tier the profile to fill in
	 * @param flowControl whether a memory alarm blocks publishers ({@code on} or
	 * {@code off})
	 * @return the filled-in profile
	 */
	private static TechnologyProfile unpartitioned(TechnologyProfile tier, String flowControl) {
		return tier.with("partitioning", "none")
			.with("acks", "leader")
			.with("lingerMs", 0.0)
			.with("flowControl", flowControl);
	}

	public TechnologyProfile profile() {
		return PROFILES.of(this.technology);
	}

	/**
	 * Whether messages go to partitions (by key or in turn), after the technology's
	 * default.
	 * @return {@code true} when the queue is partitioned
	 */
	public boolean partitioned() {
		Partitioning effective = profile().choice("partitioning", this.partitioning, Partitioning.AUTO,
				Partitioning.NONE);
		return effective == Partitioning.KEY || effective == Partitioning.ROUND_ROBIN;
	}

	/**
	 * Whether a memory alarm blocks publishers, after the technology's default.
	 * @return {@code true} when publishers wait while memory is short
	 */
	public boolean blocksOnMemoryAlarm() {
		return profile().choice("flowControl", this.flowControl, Toggle.AUTO, Toggle.OFF) == Toggle.ON;
	}

	/**
	 * Messages one consumer handles at once.
	 * @return the configured concurrency, or one when it is not set
	 */
	public int concurrencyPerConsumer() {
		return (this.consumerConcurrency != null) ? this.consumerConcurrency : 1;
	}

}

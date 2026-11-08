package io.archsim.model.component.queue;

import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.DefaultFrom;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.Ui;
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
public record MessageQueueConfig(
		@Default("generic") @Description(Descriptions.TECHNOLOGY) @Ui(
				group = Descriptions.TECHNOLOGY_GROUP) QueueTechnology technology,
		@Default("8") @Description(Descriptions.PARTITIONS) @Ui(group = Descriptions.CAPACITY_GROUP,
				headline = true) @Ge(1) @Le(10_000) int partitions,
		@Default("4") @Description(Descriptions.CONSUMERS) @Ui(group = Descriptions.CAPACITY_GROUP,
				headline = true) @Ge(1) @Le(10_000) int consumers,
		@Default("100000") @Description(Descriptions.MAX_DEPTH) @Ui(label = "Max backlog", unit = "msgs",
				group = Descriptions.CAPACITY_GROUP) @Ge(1) @Le(100_000_000) @Nullable Integer maxDepth,

		@DefaultFrom("DEFAULT_PUBLISH_TIME") @Description(Descriptions.PUBLISH_TIME_MS) @Ui(
				label = "Publish (ack) time", unit = "ms", group = Descriptions.PERFORMANCE_GROUP, assumption = true,
				headline = true) Distribution publishTimeMs,

		@DefaultFrom("DEFAULT_CONSUMER_TIME") @Description(Descriptions.CONSUMER_TIME_MS) @Ui(label = "Consumer time",
				unit = "ms", group = Descriptions.PERFORMANCE_GROUP, assumption = true) Distribution consumerTimeMs,

		@Description(Descriptions.CONSUMER_CONCURRENCY) @Ui(
				group = Descriptions.CAPACITY_GROUP) @Ge(1) @Le(10_000) @Nullable Integer consumerConcurrency,
		@Description(Descriptions.FETCH_OVERHEAD_MS) @Ui(label = "Fetch overhead", unit = "ms",
				group = Descriptions.PERFORMANCE_GROUP) @Nullable Distribution fetchOverheadMs,
		@Default("3") @Description(Descriptions.MAX_DELIVERIES) @Ui(
				group = Descriptions.RELIABILITY_GROUP) @Ge(1) @Le(20) int maxDeliveries,
		@Default("auto") @Description(Descriptions.PARTITIONING) @Ui(
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) Partitioning partitioning,
		@Default("auto") @Description(Descriptions.ACKS) @Ui(label = "Publish acknowledgement",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) Acks acks,

		@DefaultFrom("DEFAULT_REPLICATION") @Description(Descriptions.REPLICATION_MS) @Ui(label = "Replication wait",
				unit = "ms", group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				assumption = true) Distribution replicationMs,

		@Description(Descriptions.LINGER_MS) @Ui(label = "Batching linger", unit = "ms",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) @Ge(0) @Le(10_000) @Nullable Double lingerMs,
		@Default("auto") @Description(Descriptions.DELIVERY) @Ui(label = "Delivery guarantee",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) Delivery delivery,
		@Description(Descriptions.VISIBILITY_TIMEOUT_S) @Ui(label = "Visibility timeout", unit = "s",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) @Gt(0) @Le(43_200) @Nullable Double visibilityTimeoutS,
		@Default("false") @Description(Descriptions.FIFO) @Ui(label = "FIFO queue",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP, visibleWhen = "technology=sqs") boolean fifo,
		@Default("300.0") @Description(Descriptions.FIFO_LIMIT_RPS) @Ui(label = "FIFO publish limit", unit = "msg/s",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				visibleWhen = "technology=sqs") @Gt(0) @Le(1_000_000) double fifoLimitRps,
		@Default("auto") @Description(Descriptions.FLOW_CONTROL) @Ui(label = "Memory alarm blocks publishers",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) Toggle flowControl,
		@Default("0.6") @Description(Descriptions.MEMORY_HIGH_WATERMARK) @Ui(
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP, step = 0.05) @Gt(0) @Le(1) double memoryHighWatermark,
		@Default("1.0") @Description(Descriptions.MESSAGE_SIZE_KB) @Ui(label = "Message size", unit = "KiB",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				assumption = true) @Gt(0) @Le(1_048_576) double messageSizeKb,
		@Default("1") @Description(Descriptions.BROKERS) @Ui(
				group = Descriptions.COST_GROUP) @Ge(1) @Le(1000) int brokers,
		@Description(Descriptions.CPU_CORES) @Ui(label = "CPU", unit = "vCPU", group = Descriptions.RESOURCES_GROUP,
				step = 0.5) @Gt(0) @Le(Resources.MAX_CPU_CORES) @Nullable Double cpuCores,
		@Description(Descriptions.MEMORY_GIB) @Ui(label = "Memory", unit = "GiB", group = Descriptions.RESOURCES_GROUP,
				step = 0.5) @Gt(0) @Le(Resources.MAX_MEMORY_GIB) @Nullable Double memoryGib,
		@Description(Descriptions.STORAGE_GIB) @Ui(label = "Storage", unit = "GiB",
				group = Descriptions.RESOURCES_GROUP) @Gt(0) @Le(Resources.MAX_STORAGE_GIB) @Nullable Double storageGib,
		@Description(Descriptions.INSTANCE_TYPE) @Ui(
				group = Descriptions.RESOURCES_GROUP) @Matches(Resources.INSTANCE_TYPE) @Nullable String instanceType,
		@Default("0.15") @Description(Descriptions.COST_PER_HOUR) @Ui(label = "Cost per broker-hour", unit = "$/h",
				group = Descriptions.COST_GROUP, assumption = true) @Ge(0) @Le(10_000) double costPerHour) {

	/** The default time a publish takes to be acknowledged: 2 ms median, 8 ms p95. */
	static final Distribution DEFAULT_PUBLISH_TIME = new LogNormal(2.0, 8.0);

	/** The default time a consumer works on a message: 5 ms median, 20 ms p95. */
	static final Distribution DEFAULT_CONSUMER_TIME = new LogNormal(5.0, 20.0);

	/** The default time a publish takes to reach the replicas: 2 ms median, 8 ms p95. */
	static final Distribution DEFAULT_REPLICATION = new LogNormal(2.0, 8.0);

	/**
	 * What each technology does by default (technology.py's {@code QUEUE_PROFILES}).
	 */
	static final TechnologyProfiles<QueueTechnology> PROFILES = TechnologyProfiles.builder(QueueTechnology.class)
		.with(QueueTechnology.KAFKA,
				TechnologyProfile.core()
					.with("partitioning", "key")
					.with("acks", "all")
					.with("lingerMs", 5.0)
					.with("delivery", "at_least_once")
					.withoutDefault("visibilityTimeoutS")
					.with("flowControl", "off"))
		.with(QueueTechnology.RABBITMQ,
				unpartitioned(TechnologyProfile.experimental()).with("delivery", "at_least_once")
					.withoutDefault("visibilityTimeoutS")
					.with("flowControl", "on"))
		.with(QueueTechnology.SQS,
				unpartitioned(TechnologyProfile.core()).with("delivery", "at_least_once")
					.with("visibilityTimeoutS", 30.0)
					.with("flowControl", "off"))
		.with(QueueTechnology.PUBSUB,
				unpartitioned(TechnologyProfile.experimental()).with("delivery", "at_least_once")
					.with("visibilityTimeoutS", 10.0)
					.with("flowControl", "off"))
		.with(QueueTechnology.NATS,
				unpartitioned(TechnologyProfile.experimental()).with("delivery", "at_least_once")
					.withoutDefault("visibilityTimeoutS")
					.with("flowControl", "off"))
		.with(QueueTechnology.REDIS_STREAMS, unpartitioned(TechnologyProfile.experimental()).with("flowControl", "off"))
		.with(QueueTechnology.GENERIC, unpartitioned(TechnologyProfile.core()).with("flowControl", "off"))
		.build();

	/** The KiB in a GiB, to count the messages that fit in memory. */
	private static final double KIB_PER_GIB = 1024.0 * 1024.0;

	/**
	 * The profile of one shared queue: no partitions, publishes acknowledged by the
	 * leader, no batching.
	 * @param tier the profile to fill in
	 * @return the filled-in profile
	 */
	private static TechnologyProfile unpartitioned(TechnologyProfile tier) {
		return tier.with("partitioning", "none").with("acks", "leader").with("lingerMs", 0.0);
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
	 * Messages all consumers handle at once: each of the first {@code min(consumers,
	 * partitions)} consumers has its concurrency, and on a partitioned queue no more than
	 * the partitions it owns (consumer {@code i} owns the partitions {@code p} with
	 * {@code p mod consumers = i}).
	 * @return the consumer slots
	 */
	public int consumerSlots() {
		int active = Math.min(this.consumers, this.partitions);
		int each = concurrencyPerConsumer();
		if (each == 1) {
			return active;
		}
		if (!partitioned()) {
			return active * each;
		}
		int slots = 0;
		for (int consumer = 0; consumer < active; consumer++) {
			int owned = (this.partitions - consumer + active - 1) / active;
			slots += Math.min(each, owned);
		}
		return slots;
	}

	/**
	 * How long a producer batches messages, after the technology's default.
	 * @return the linger, in milliseconds; zero when neither sets one
	 */
	public double lingerMsInEffect() {
		return profile().number("lingerMs", this.lingerMs).orElse(0.0);
	}

	/**
	 * Whether a publish waits for every replica, after the technology's default.
	 * @return {@code true} when a publish is acknowledged by all replicas
	 */
	public boolean acknowledgedByAll() {
		return profile().choice("acks", this.acks, Acks.AUTO, Acks.LEADER) == Acks.ALL;
	}

	/**
	 * How messages are spread over partitions, after the technology's default.
	 * @return by key, in turn, or none
	 */
	public Partitioning partitioningInEffect() {
		return profile().choice("partitioning", this.partitioning, Partitioning.AUTO, Partitioning.NONE);
	}

	/**
	 * Whether a message is delivered at most once, so a full backlog drops it, after the
	 * technology's default.
	 * @return {@code true} at most once; {@code false} at least once, the default
	 */
	public boolean deliversAtMostOnce() {
		Delivery effective = profile().choice("delivery", this.delivery, Delivery.AUTO, Delivery.AT_LEAST_ONCE);
		return effective == Delivery.AT_MOST_ONCE;
	}

	/**
	 * How long a message stays hidden while a consumer holds it, after the technology's
	 * default.
	 * @return seconds; empty when a message is never delivered again for taking long
	 */
	public OptionalDouble visibilityTimeoutSInEffect() {
		return profile().number("visibilityTimeoutS", this.visibilityTimeoutS)
			.map(OptionalDouble::of)
			.orElse(OptionalDouble.empty());
	}

	/**
	 * Whether consumers poll in cycles (a fetch overhead, or more than one message at
	 * once) rather than taking each message as soon as they are free.
	 * @return {@code true} when they poll in cycles
	 */
	public boolean fetchesInCycles() {
		return this.fetchOverheadMs != null || concurrencyPerConsumer() > 1;
	}

	/**
	 * The backlog at which the memory alarm blocks publishers: the high watermark's share
	 * of memory, in messages.
	 * @return messages; empty without flow control or a memory size
	 */
	public OptionalDouble flowThreshold() {
		if (!blocksOnMemoryAlarm() || this.memoryGib == null) {
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(this.memoryHighWatermark * this.memoryGib * KIB_PER_GIB / this.messageSizeKb);
	}

	/**
	 * Whether publishes are limited to a FIFO queue's throughput: an SQS FIFO queue's.
	 * @return {@code true} when its publish limit applies
	 */
	public boolean limitsFifoPublishes() {
		return this.fifo && this.technology == QueueTechnology.SQS;
	}

	/**
	 * Messages one consumer handles at once.
	 * @return the configured concurrency, or one when it is not set
	 */
	public int concurrencyPerConsumer() {
		return (this.consumerConcurrency != null) ? this.consumerConcurrency : 1;
	}

}

package io.archsim.model.component.queue;

import static io.archsim.model.component.Resources.INSTANCE_TYPE;
import static io.archsim.model.component.Resources.MAX_CPU_CORES;
import static io.archsim.model.component.Resources.MAX_MEMORY_GIB;
import static io.archsim.model.component.Resources.MAX_STORAGE_GIB;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.TechnologyProfiles;
import io.archsim.model.component.Toggle;
import io.archsim.model.distribution.Distribution;
import java.util.Map;
import org.jspecify.annotations.Nullable;

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

		@Default("{\"kind\": \"lognormal\", \"median\": 2.0, \"p95\": 8.0}") Distribution publishTimeMs,

		@Default("{\"kind\": \"lognormal\", \"median\": 5.0, \"p95\": 20.0}") Distribution consumerTimeMs,

		@Ge(1) @Le(10_000) @Nullable Integer consumerConcurrency, @Nullable Distribution fetchOverheadMs,
		@Default("3") @Ge(1) @Le(20) int maxDeliveries, @Default("auto") Partitioning partitioning,
		@Default("auto") Acks acks,

		@Default("{\"kind\": \"lognormal\", \"median\": 2.0, \"p95\": 8.0}") Distribution replicationMs,

		@Ge(0) @Le(10_000) @Nullable Double lingerMs, @Default("auto") Delivery delivery,
		@Gt(0) @Le(43_200) @Nullable Double visibilityTimeoutS, @Default("false") boolean fifo,
		@Default("300.0") @Gt(0) @Le(1_000_000) double fifoLimitRps, @Default("auto") Toggle flowControl,
		@Default("0.6") @Gt(0) @Le(1) double memoryHighWatermark,
		@Default("1.0") @Gt(0) @Le(1_048_576) double messageSizeKb, @Default("1") @Ge(1) @Le(1000) int brokers,
		@Gt(0) @Le(MAX_CPU_CORES) @Nullable Double cpuCores, @Gt(0) @Le(MAX_MEMORY_GIB) @Nullable Double memoryGib,
		@Gt(0) @Le(MAX_STORAGE_GIB) @Nullable Double storageGib, @Matches(INSTANCE_TYPE) @Nullable String instanceType,
		@Default("0.15") @Ge(0) @Le(10_000) double costPerHour) {

	/**
	 * What each technology does by default (technology.py's {@code QUEUE_PROFILES}). A
	 * visibility timeout Python lists as {@code None} is left out: the setting's fallback
	 * is none too.
	 */
	static final TechnologyProfiles<QueueTechnology> PROFILES = new TechnologyProfiles<>(QueueTechnology.class, Map.of(
			QueueTechnology.KAFKA,
			queue(TechnologyProfile.core(), "key", "all", 5.0, "off").with("delivery", "at_least_once"),
			QueueTechnology.RABBITMQ,
			queue(TechnologyProfile.experimental(), "none", "leader", 0.0, "on").with("delivery", "at_least_once"),
			QueueTechnology.SQS,
			queue(TechnologyProfile.core(), "none", "leader", 0.0, "off").with("delivery", "at_least_once")
				.with("visibilityTimeoutS", 30.0),
			QueueTechnology.PUBSUB,
			queue(TechnologyProfile.experimental(), "none", "leader", 0.0, "off").with("delivery", "at_least_once")
				.with("visibilityTimeoutS", 10.0),
			QueueTechnology.NATS,
			queue(TechnologyProfile.experimental(), "none", "leader", 0.0, "off").with("delivery", "at_least_once"),
			QueueTechnology.REDIS_STREAMS, queue(TechnologyProfile.experimental(), "none", "leader", 0.0, "off"),
			QueueTechnology.GENERIC, queue(TechnologyProfile.core(), "none", "leader", 0.0, "off")));

	private static TechnologyProfile queue(TechnologyProfile tier, String partitioning, String acks, double lingerMs,
			String flowControl) {
		return tier.with("partitioning", partitioning)
			.with("acks", acks)
			.with("lingerMs", lingerMs)
			.with("flowControl", flowControl);
	}

	public TechnologyProfile profile() {
		return PROFILES.of(technology);
	}

	/**
	 * Whether messages go to partitions (by key or in turn), after the technology's
	 * default.
	 */
	public boolean partitioned() {
		Partitioning effective = profile().choice("partitioning", partitioning, Partitioning.AUTO, Partitioning.NONE);
		return effective == Partitioning.KEY || effective == Partitioning.ROUND_ROBIN;
	}

	/** Whether a memory alarm blocks publishers, after the technology's default. */
	public boolean blocksOnMemoryAlarm() {
		return profile().choice("flowControl", flowControl, Toggle.AUTO, Toggle.OFF) == Toggle.ON;
	}

	/** Messages one consumer handles at once. */
	public int concurrencyPerConsumer() {
		return consumerConcurrency == null ? 1 : consumerConcurrency;
	}
}

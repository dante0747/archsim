package io.archsim.engine.sizing;

import java.util.List;
import java.util.Set;

/**
 * The rules auto-sizing follows: the utilization it aims at, the common sizes it rounds
 * to, and the illustrative ratios it uses where the model has no measured basis (their
 * explanations say "illustrative").
 */
public final class Sizing {

	/**
	 * The CPU utilization a part is sized for at the peak: its work as a share of what
	 * its vCPUs can do.
	 */
	public static final double TARGET_CPU = 0.6;

	/**
	 * The size below the target's is kept when it runs the peak at up to this: rounding
	 * up to the next common size would otherwise double the cores for a few points of
	 * utilization.
	 */
	public static final double CPU_STEP_SLACK = 0.65;

	/** Auto memory is what the engine's memory model needs, plus 25%. */
	public static final double MEMORY_HEADROOM = 1.25;

	/** Auto storage keeps the data under 70% full. */
	public static final double STORAGE_FILL = 0.7;

	/** The smallest auto disk, in GiB. */
	public static final double MIN_STORAGE_GIB = 10;

	/**
	 * Illustrative: the memory the operating system of a VM uses besides the part's
	 * process.
	 */
	public static final double OS_MEMORY_GIB = 0.25;

	/** Illustrative: requests per second an L7 proxy handles comfortably on one vCPU. */
	public static final double PROXY_RPS_PER_VCPU = 5000;

	/** Illustrative: load balancer memory per vCPU (a proxy keeps little state). */
	public static final int PROXY_GIB_PER_VCPU = 1;

	/** Illustrative: messages per second a broker handles comfortably on one vCPU. */
	public static final double BROKER_MSGS_PER_VCPU = 10000;

	/** Illustrative: broker memory per vCPU for buffers and page cache. */
	public static final int BROKER_GIB_PER_VCPU = 2;

	/**
	 * Measured: a Kafka broker's fixed cores, the intercept of a least-squares fit of its
	 * busy cores to the messages it handled a second over the steps of ArchSim's Kafka
	 * experiment that it kept up with (no backlog at the end: 522, 1,044 and 1,566
	 * messages a second; {@code benchmarks/results/kafka/measured.json}, ADR-0085). One
	 * combined KRaft broker and controller, replication factor 1, messages of about 50
	 * bytes sent one a request, on one physical core. A rough figure: three points fit it
	 * loosely (R² 0.42), and the broker was busier at 1,044 messages a second (0.48
	 * cores) than at 1,566 (0.45).
	 */
	public static final double KAFKA_FIXED_CORES = 0.39;

	/**
	 * Measured: a Kafka broker's CPU per message, in milliseconds, the same fit's slope.
	 */
	public static final double KAFKA_CPU_MS_PER_MESSAGE = 0.053;

	/**
	 * Measured: a Kafka broker's memory, the largest working set over the same steps (973
	 * MiB), in GiB.
	 */
	public static final double KAFKA_WORKING_SET_GIB = 0.95;

	/** Kafka's default retention ({@code log.retention.hours = 168}), in seconds. */
	public static final double LOG_RETENTION_S = 7 * 24 * 3600;

	/**
	 * A broker that deletes on ack keeps room for an hour of peak publishes, in seconds.
	 */
	public static final double BACKLOG_S = 3600;

	/** The largest auto memory of one service replica, in GiB. */
	public static final int MAX_SERVICE_MEMORY_GIB = 64;

	/** Illustrative: the smallest auto memory of a database instance, in GiB. */
	public static final double DB_MIN_MEMORY_GIB = 1;

	/**
	 * Memory per connection of a database instance: RDS for PostgreSQL sets
	 * {@code max_connections} to its memory / 9,531,392 bytes by default.
	 */
	public static final long DB_BYTES_PER_CONNECTION = 9_531_392;

	/**
	 * Illustrative: the share of a database's data read often, when no working set is
	 * set.
	 */
	public static final double DB_HOT_SHARE = 0.25;

	/** AWS Lambda gives a function one vCPU per this many MB of memory. */
	public static final int LAMBDA_MB_PER_VCPU = 1769;

	/** The common vCPU counts. */
	static final List<Double> CPU_STEPS = List.of(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 48.0, 64.0, 96.0, 128.0, 192.0, 256.0,
			384.0, 512.0);

	/** The common memory sizes, in GiB. */
	static final List<Double> MEMORY_STEPS = List.of(0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 384.0,
			512.0, 768.0, 1024.0, 1536.0, 2048.0);

	/** The common disk sizes, in GiB. */
	static final List<Double> DISK_STEPS = List.of(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0,
			10000.0, 20000.0, 50000.0, 100000.0);

	/** The most vCPU an auto size has. */
	static final double MAX_CPU = 512;

	/** The most memory an auto size has, in GiB. */
	static final double MAX_MEMORY_GIB = 24576;

	/** The most disk an auto size has, in GiB. */
	static final double MAX_STORAGE_GIB = 1_000_000;

	/** The brokers that keep a log for its retention rather than deleting on ack. */
	static final Set<String> LOG_BROKERS = Set.of("kafka", "redis_streams");

	/**
	 * Standard shapes without a price snapshot: GiB per vCPU (compute, general, memory).
	 */
	static final List<Double> GIB_PER_VCPU = List.of(2.0, 4.0, 8.0);

	private Sizing() {
	}

}

package io.archsim.engine.components.database;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Signal;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.kernel.Store;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.SimContext;

/**
 * A database's replication: each write shipped to its shard's replicas after a network
 * delay, then replayed on each by a few workers; a write that waits for replicas is
 * acknowledged once enough have replayed it. The replicas' lag is sampled through the
 * run, the highest in each bucket.
 */
final class ReplicationPipeline {

	private static final double MIN_SAMPLE_MS = 50.0;

	private static final double SAMPLES_PER_BUCKET = 4.0;

	private static final int LAG_DIGITS = 3;

	private final Simulation simulation;

	private final Timeline timeline;

	private final DatabaseSettings settings;

	private final DatabaseShards shards;

	private final NodeMetrics metrics;

	private final PythonRandom stream;

	private final Sampler delay;

	/** Each shard's last write's sequence number. */
	private final int[] sequences;

	/** Each shard's keys, with the sequence number of the last write to each. */
	private final List<Map<Integer, Integer>> lastWrites = new ArrayList<>();

	private final double[] lagMax;

	private final boolean[] lagSeen;

	/**
	 * A database's replication at the start of a run.
	 * @param context the run
	 * @param settings the database's settings in effect
	 * @param shards its shards, whose replicas have their logs
	 * @param accounts where it counts and what it draws from
	 */
	ReplicationPipeline(SimContext context, DatabaseSettings settings, DatabaseShards shards, Accounts accounts) {
		this.simulation = context.simulation();
		this.timeline = context.timeline();
		this.settings = settings;
		this.shards = shards;
		this.metrics = accounts.metrics();
		this.stream = accounts.stream();
		this.delay = Sampler.of(settings.config().replicationDelayMs());
		this.sequences = new int[settings.partitions()];
		for (int shard = 0; shard < settings.partitions(); shard++) {
			this.lastWrites.add(new HashMap<>());
		}
		this.lagMax = new double[this.timeline.buckets()];
		this.lagSeen = new boolean[this.timeline.buckets()];
	}

	/**
	 * Starts the replicas' workers, then the lag's sampling.
	 */
	void start() {
		for (int shard = 0; shard < this.settings.partitions(); shard++) {
			this.shards.replicas(shard).forEach(this::startWorkers);
		}
		double stepMs = Math.max(MIN_SAMPLE_MS, this.timeline.bucketMs() / SAMPLES_PER_BUCKET);
		this.simulation.start((finish) -> sampleLag(stepMs));
	}

	/**
	 * Ships a committed write to its shard's replicas.
	 * @param shard the shard
	 * @param key the key it wrote
	 * @param writeMs the write's time on the primary, which its replay takes a share of
	 * @return the write to replay
	 */
	ApplyJob replicate(int shard, int key, double writeMs) {
		int sequence = ++this.sequences[shard];
		this.lastWrites.get(shard).put(key, sequence);
		int acksNeeded = this.settings.replicaAcks();
		Signal acknowledged = (acksNeeded > 0) ? this.simulation.event() : null;
		ApplyJob job = new ApplyJob(sequence, writeMs * this.settings.config().replicaApplyFactor(), acksNeeded,
				acknowledged);
		double now = this.simulation.now();
		List<DatabaseInstance> replicas = this.shards.replicas(shard);
		replicas.forEach((replica) -> logOf(replica).expect(sequence, now));
		this.simulation.start((finish) -> this.simulation.after(this.delay.sample(this.stream), () -> {
			replicas.forEach((replica) -> logOf(replica).arrived().put(job));
			finish.run();
		}));
		return job;
	}

	/**
	 * Whether a read a replica served missed the latest write to its key.
	 * @param shard the shard
	 * @param key the key
	 * @param instance the instance that served it
	 * @return {@code true} when a replica had not replayed that write yet
	 */
	boolean stale(int shard, int key, DatabaseInstance instance) {
		ReplicaLog log = instance.log();
		Integer lastWrite = this.lastWrites.get(shard).get(key);
		return log != null && lastWrite != null && lastWrite > log.applied();
	}

	/**
	 * Waits for the replicas a write waits for, then for the network's delay back to the
	 * primary.
	 * @param acknowledged the moment enough replicas replayed the write
	 * @param next what to do once the primary knows
	 */
	void afterAcknowledged(Signal acknowledged, Runnable next) {
		acknowledged.then(() -> this.simulation.after(this.delay.sample(this.stream), next));
	}

	/**
	 * Whether any replica still has writes to replay.
	 * @return {@code true} while one is behind its primary
	 */
	boolean behind() {
		return this.shards.all().stream().map(DatabaseInstance::log).anyMatch((log) -> log != null && log.behind());
	}

	/**
	 * The replicas' highest lag in each bucket.
	 * @return milliseconds per bucket of the whole timeline; {@code null} where none was
	 * sampled
	 */
	List<@Nullable Double> lagSeries() {
		List<@Nullable Double> series = new ArrayList<>(this.lagMax.length);
		for (int bucket = 0; bucket < this.lagMax.length; bucket++) {
			if (this.lagSeen[bucket]) {
				series.add(this.lagMax[bucket]);
			}
			else {
				series.add(null);
			}
		}
		return series;
	}

	/**
	 * The replicas' highest lag sampled in the measurement window's whole buckets.
	 * @return milliseconds, rounded; empty when none was sampled
	 */
	Optional<Double> maxLagMs() {
		int last = Math.min(this.timeline.windowBucketsEnd(), this.lagMax.length);
		Optional<Double> max = Optional.empty();
		for (int bucket = this.timeline.firstWindowBucket(); bucket < last; bucket++) {
			if (this.lagSeen[bucket] && (max.isEmpty() || this.lagMax[bucket] > max.get())) {
				max = Optional.of(this.lagMax[bucket]);
			}
		}
		return max.map((lagMs) -> PythonRound.round(lagMs, LAG_DIGITS));
	}

	private void startWorkers(DatabaseInstance replica) {
		ReplicaLog log = logOf(replica);
		for (int worker = 0; worker < this.settings.applyWorkers(); worker++) {
			this.simulation.start((finish) -> replayNext(replica, log));
		}
	}

	// a worker replays the writes that reach its replica, one after another, forever
	private void replayNext(DatabaseInstance replica, ReplicaLog log) {
		Store.Get<ApplyJob> next = log.arrived().get();
		next.then(() -> {
			ApplyJob job = next.value();
			this.shards.apply(replica, job.costMs(), () -> {
				log.replayed(job.sequence());
				this.metrics.count(DatabaseCounters.REPLAYED, this.simulation.now());
				job.acknowledge();
				replayNext(replica, log);
			});
		});
	}

	private void sampleLag(double stepMs) {
		double now = this.simulation.now();
		double lag = 0.0;
		for (DatabaseInstance instance : this.shards.all()) {
			ReplicaLog log = instance.log();
			if (log != null) {
				lag = Math.max(lag, log.lag(now));
			}
		}
		int bucket = this.timeline.index(now);
		this.lagMax[bucket] = Math.max(this.lagMax[bucket], lag);
		this.lagSeen[bucket] = true;
		this.simulation.after(stepMs, () -> sampleLag(stepMs));
	}

	private static ReplicaLog logOf(DatabaseInstance replica) {
		ReplicaLog log = replica.log();
		if (log == null) {
			throw new IllegalStateException("a replicating database's replica has a log");
		}
		return log;
	}

	/**
	 * Where replication counts and what it draws from.
	 *
	 * @param metrics the database's metrics
	 * @param stream the stream its network delays are drawn from
	 */
	record Accounts(NodeMetrics metrics, PythonRandom stream) {
	}

}

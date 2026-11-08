package io.archsim.engine.components.database;

import java.util.List;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.runtime.TokenBucket;

/**
 * What each shard of a database controls its writes with, when the database has it: row
 * locks (which readers wait on too, with locking reads), a log flushed with group commit,
 * and a throughput limit.
 */
final class ShardControls {

	private final @Nullable List<LockTable> locks;

	private final @Nullable List<LogFlusher> flushers;

	private final @Nullable List<TokenBucket> limits;

	/**
	 * A database's shards' controls at the start of a run.
	 * @param simulation the run
	 * @param settings the database's settings in effect
	 * @param flushMs draws a log flush's time, in milliseconds
	 */
	ShardControls(Simulation simulation, DatabaseSettings settings, DoubleSupplier flushMs) {
		int shards = settings.partitions();
		boolean locked = settings.locksRows() || settings.readersWaitForWriters();
		this.locks = locked ? each(shards, () -> new LockTable(simulation)) : null;
		this.flushers = settings.config().flushesCommits() ? each(shards, () -> new LogFlusher(simulation, flushMs))
				: null;
		Double limit = settings.config().throughputLimitRps();
		this.limits = (limit != null) ? each(shards, () -> TokenBucket.ofRate(limit)) : null;
	}

	/**
	 * A shard's row locks.
	 * @param shard the shard
	 * @return its locks; empty when the database locks no rows
	 */
	Optional<LockTable> locks(int shard) {
		return (this.locks != null) ? Optional.of(this.locks.get(shard)) : Optional.empty();
	}

	/**
	 * A shard's log, when a write waits for it to be flushed.
	 * @param shard the shard
	 * @return its log; empty when writes do not wait for a flush
	 */
	Optional<LogFlusher> flusher(int shard) {
		return (this.flushers != null) ? Optional.of(this.flushers.get(shard)) : Optional.empty();
	}

	/**
	 * Every shard's log.
	 * @return the logs; empty when writes do not wait for a flush
	 */
	Optional<List<LogFlusher>> flushers() {
		return Optional.ofNullable(this.flushers);
	}

	/**
	 * Takes an operation from a shard's throughput limit.
	 * @param shard the shard
	 * @param now the time
	 * @return {@code false} when the limit refuses it
	 */
	boolean admits(int shard, double now) {
		return this.limits == null || this.limits.get(shard).tryTake(now, 1.0);
	}

	private static <T> List<T> each(int shards, Supplier<T> make) {
		return IntStream.range(0, shards).mapToObj((shard) -> make.get()).toList();
	}

}

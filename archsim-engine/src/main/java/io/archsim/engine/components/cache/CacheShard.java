package io.archsim.engine.components.cache;

import io.archsim.engine.runtime.Cores;
import io.archsim.engine.runtime.WorkerPool;

/**
 * One node of a cache: its command executors with their queue, its CPU, its keys, and
 * until when a snapshot's fork stalls its commands.
 */
final class CacheShard {

	private final WorkerPool pool;

	private final Cores cores;

	private final ShardKeys keys;

	private double pausedUntil;

	/**
	 * A shard at the start of a run.
	 * @param pool its command executors and their queue
	 * @param cores its CPU
	 * @param keys the keys it holds
	 */
	CacheShard(WorkerPool pool, Cores cores, ShardKeys keys) {
		this.pool = pool;
		this.cores = cores;
		this.keys = keys;
	}

	WorkerPool pool() {
		return this.pool;
	}

	Cores cores() {
		return this.cores;
	}

	ShardKeys keys() {
		return this.keys;
	}

	/**
	 * Until when a snapshot's fork stalls the shard's commands.
	 * @return the time; past when no fork stalls them
	 */
	double pausedUntil() {
		return this.pausedUntil;
	}

	/**
	 * Stalls the shard's commands until a time, or longer when a fork already does.
	 * @param time when the stall ends
	 */
	void pauseUntil(double time) {
		this.pausedUntil = Math.max(this.pausedUntil, time);
	}

}

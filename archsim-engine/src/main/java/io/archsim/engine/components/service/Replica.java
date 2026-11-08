package io.archsim.engine.components.service;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.runtime.Cores;
import io.archsim.engine.runtime.WorkerPool;

/**
 * One replica of a service during a run: its handlers and their queue, its CPU, the
 * requests it holds, its arrival rate and its garbage collector's pauses.
 */
final class Replica {

	private final WorkerPool pool;

	private final Cores cores;

	private final @Nullable RateMeter meter;

	private int load;

	private long served;

	private double pausedUntil;

	private double pauseStart = -1.0;

	private double pauseEnd = -1.0;

	/**
	 * A replica present at the start of the run.
	 * @param pool its handlers and their queue
	 * @param cores its CPU
	 * @param meter its arrival rate, when its service time follows its load
	 */
	Replica(WorkerPool pool, Cores cores, @Nullable RateMeter meter) {
		this.pool = pool;
		this.cores = cores;
		this.meter = meter;
	}

	WorkerPool pool() {
		return this.pool;
	}

	Cores cores() {
		return this.cores;
	}

	@Nullable RateMeter meter() {
		return this.meter;
	}

	/**
	 * The requests the replica holds, queued or served.
	 * @return the count
	 */
	int load() {
		return this.load;
	}

	void hold() {
		this.load++;
	}

	void letGo() {
		this.load--;
	}

	/**
	 * Counts a request served.
	 * @return the requests served so far
	 */
	long countServed() {
		this.served++;
		return this.served;
	}

	double pausedUntil() {
		return this.pausedUntil;
	}

	double pauseStart() {
		return this.pauseStart;
	}

	double pauseEnd() {
		return this.pauseEnd;
	}

	/**
	 * Schedules a stop-the-world pause.
	 * @param start when it starts: now, or when the pause under way ends
	 * @param durationMs how long it lasts
	 */
	void pause(double start, double durationMs) {
		this.pausedUntil = start + durationMs;
		this.pauseStart = start;
		this.pauseEnd = start + durationMs;
	}

}

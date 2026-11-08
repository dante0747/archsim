package io.archsim.engine.runtime;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Resource;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Gauge;

/**
 * The CPU of one instance of a part. Work runs on executors: {@code floor(cores)} of
 * them, at least one, each at {@code cores ÷ executors} speed, so below one vCPU work
 * runs proportionally slower and between whole numbers above it faster (2.5 vCPU: two
 * executors at 1.25). Without a CPU size, or with a whole number of cores and at least
 * one per handler that could run at once, work simply takes its time; otherwise it queues
 * for a free executor. Each instance's own hyperthreads and the design's shared host come
 * with their slice of the port, and the simulator refuses them until then.
 */
public final class Cores {

	/**
	 * Absorbs floating-point error: added before rounding the cores down, so it does not
	 * cost an executor, and allowed between a speed and 1 for them to count as equal.
	 */
	private static final double ROUNDING_TOLERANCE = 1e-9;

	private final Simulation simulation;

	private final @Nullable Gauge busy;

	private final int executors;

	private final double speed;

	private final @Nullable Resource pool;

	/**
	 * An instance's CPU.
	 * @param simulation the run
	 * @param cores its vCPU; {@code null} when its CPU is not modelled
	 * @param concurrency the handlers that could run work at once
	 * @param busy the part's gauge of busy executors, over all its instances;
	 * {@code null} when it counts none
	 */
	public Cores(Simulation simulation, @Nullable Double cores, int concurrency, @Nullable Gauge busy) {
		this.simulation = simulation;
		this.busy = busy;
		if (cores == null) {
			this.executors = concurrency;
			this.speed = 1.0;
			this.pool = null;
			return;
		}
		this.executors = Math.max(1, (int) Math.floor(cores + ROUNDING_TOLERANCE));
		this.speed = cores / this.executors;
		boolean contended = this.executors < concurrency || Math.abs(this.speed - 1.0) > ROUNDING_TOLERANCE;
		this.pool = contended ? new Resource(simulation, this.executors) : null;
	}

	/**
	 * How many executors the instance has.
	 * @return its whole cores, at least one; its handlers without a CPU size
	 */
	public int executors() {
		return this.executors;
	}

	/**
	 * How fast each executor runs.
	 * @return the cores per executor: 1 for a whole number of cores or none set, less
	 * below one vCPU, more between whole numbers above it
	 */
	public double speed() {
		return this.speed;
	}

	/**
	 * Runs CPU work, waiting for a free executor when they are all busy.
	 * @param workMs the work at full speed, in milliseconds
	 * @param done what to do when it is done
	 */
	public void run(double workMs, Runnable done) {
		Resource executorPool = this.pool;
		if (executorPool == null) {
			count(1);
			this.simulation.after(workMs, () -> {
				count(-1);
				done.run();
			});
			return;
		}
		Resource.Request executor = executorPool.request();
		executor.then(() -> {
			count(1);
			this.simulation.after(workMs / this.speed, () -> {
				count(-1);
				executorPool.release(executor);
				done.run();
			});
		});
	}

	private void count(long delta) {
		if (this.busy != null) {
			this.busy.add(this.simulation.now(), delta);
		}
	}

}

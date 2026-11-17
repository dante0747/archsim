package io.archsim.engine.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Resource;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.numeric.PythonRound;

/**
 * The CPU of one instance of a part. Work runs on executors: {@code floor(cores)} of
 * them, at least one, each at {@code cores ÷ executors} speed, so below one vCPU work
 * runs proportionally slower and between whole numbers above it faster (2.5 vCPU: two
 * executors at 1.25). Without a CPU size, or with a whole number of cores and at least
 * one per handler that could run at once, work simply takes its time; otherwise it queues
 * for a free executor. On a machine (the design's host, or the instance's own
 * hyperthreads) work runs at the speed of its threads when it starts.
 */
public final class Cores {

	/**
	 * Absorbs floating-point error: added before rounding the cores down, so it does not
	 * cost an executor, and allowed between a speed and 1 for them to count as equal.
	 */
	private static final double ROUNDING_TOLERANCE = 1e-9;

	private static final int UTILIZATION_DIGITS = 4;

	private final Simulation simulation;

	private final @Nullable Gauge busy;

	private final @Nullable Gauge instanceBusy;

	private final CpuMachine machine;

	private final int executors;

	private final double speed;

	private final @Nullable Resource pool;

	/**
	 * An instance's CPU.
	 * @param simulation the run
	 * @param cores its vCPU; {@code null} when its CPU is not modelled
	 * @param concurrency the handlers that could run work at once
	 * @param accounts where its work is counted: the part's busy executors, the
	 * instance's own, and the machine whose threads it runs on
	 */
	public Cores(Simulation simulation, @Nullable Double cores, int concurrency, Accounts accounts) {
		this.simulation = simulation;
		this.busy = accounts.busy();
		this.instanceBusy = accounts.instanceBusy();
		this.machine = accounts.machine();
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
	 * The machine the instance's work runs on.
	 * @return the design's host, the instance's own hyperthreads, or full cores
	 */
	public CpuMachine machine() {
		return this.machine;
	}

	/**
	 * The mean share of a part's executors busy inside the measurement window.
	 * @param busy the part's busy executors, over all its instances
	 * @param executors its executors, over all its instances
	 * @return the share, rounded to 4 decimals; empty without executors
	 */
	public static Optional<Double> utilization(Gauge busy, double executors) {
		return (executors > 0) ? Optional.of(PythonRound.round(busy.windowMean() / executors, UTILIZATION_DIGITS))
				: Optional.empty();
	}

	/**
	 * The CPU cores a part's busy executors kept busy in each bucket: the executors times
	 * their speed (a fraction of a core below one vCPU).
	 * @param busy the part's busy executors, over all its instances
	 * @param speed an executor's speed, as {@link #speed()} gives it
	 * @return the cores, one value per bucket of the whole timeline
	 */
	public static List<@Nullable Double> busyCores(Gauge busy, double speed) {
		return RuntimeComponent
			.series(Arrays.stream(busy.meanSeries()).map((executors) -> executors * speed).toArray());
	}

	/**
	 * Runs CPU work, waiting for a free executor when they are all busy, at the speed of
	 * its machine's threads when it starts.
	 * @param workMs the work at full speed, in milliseconds
	 * @param done what to do when it is done
	 */
	public void run(double workMs, Runnable done) {
		Resource executorPool = this.pool;
		if (executorPool == null) {
			count(1);
			this.simulation.after(this.machine.start(workMs), () -> {
				this.machine.stop();
				count(-1);
				done.run();
			});
			return;
		}
		Resource.Request executor = executorPool.request();
		executor.then(() -> {
			count(1);
			this.simulation.after(this.machine.start(workMs / this.speed), () -> {
				this.machine.stop();
				count(-1);
				executorPool.release(executor);
				done.run();
			});
		});
	}

	private void count(long delta) {
		double now = this.simulation.now();
		if (this.busy != null) {
			this.busy.add(now, delta);
		}
		if (this.instanceBusy != null) {
			this.instanceBusy.add(now, delta);
		}
	}

	/**
	 * Where an instance's CPU work is counted.
	 *
	 * @param busy the part's gauge of busy executors, over all its instances;
	 * {@code null} when it counts none
	 * @param instanceBusy the instance's own gauge of busy executors, for a part that
	 * tells its instances apart (ADR-0083); {@code null} when it does not
	 * @param machine the machine whose threads the work runs on
	 */
	public record Accounts(@Nullable Gauge busy, @Nullable Gauge instanceBusy, CpuMachine machine) {

		/**
		 * Work counted over the part's instances alone.
		 * @param busy the part's gauge of busy executors; {@code null} when it counts
		 * none
		 * @param machine the machine whose threads the work runs on
		 * @return the accounts
		 */
		public static Accounts ofPart(@Nullable Gauge busy, CpuMachine machine) {
			return new Accounts(busy, null, machine);
		}

	}

}

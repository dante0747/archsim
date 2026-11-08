package io.archsim.engine.sweep;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.simulation.SimulationLimits;

/**
 * What a breaking-point sweep runs: the design or one of its scenarios at evenly spaced
 * constant loads, each step a run of its own.
 *
 * @param scenarioId the scenario swept; {@code null} for the design as drawn
 * @param range the loads swept; {@code null} for the range around the design's load
 * ({@link SweepRange#suggested})
 * @param steps how many loads: fewer than 2 run 2, more than {@link SweepRange#MAX_STEPS}
 * run that many
 * @param durationS how long each step runs, in seconds: a shorter step runs two seconds
 * ({@link #stepSeconds})
 * @param warmupS each step's warm-up, in seconds: a longer one is cut to a second short
 * of the step ({@link #warmupSeconds})
 * @param limits the limits each step's run is held to
 */
public record SweepRequest(@Nullable String scenarioId, @Nullable SweepRange range, int steps, double durationS,
		double warmupS, SimulationLimits limits) {

	/** How many loads a sweep runs unless asked for another number. */
	public static final int STEPS = 8;

	/** How long a step runs unless asked, in seconds. */
	public static final double DURATION_S = 20.0;

	/** A step's warm-up unless asked, in seconds. */
	public static final double WARMUP_S = 5.0;

	/**
	 * A sweep of a design or one of its scenarios around its load, with eight steps of
	 * twenty seconds each, within the engine's limits.
	 * @param scenarioId the scenario; {@code null} for the design as drawn
	 * @return the request
	 */
	public static SweepRequest of(@Nullable String scenarioId) {
		return new SweepRequest(scenarioId, null, STEPS, DURATION_S, WARMUP_S, SimulationLimits.DEFAULT);
	}

	/**
	 * How long each step runs: as asked, at least two seconds.
	 * @return seconds
	 */
	public double stepSeconds() {
		return Math.max(SweepRange.SHORTEST_SWEEP_STEP_S, this.durationS);
	}

	/**
	 * Each step's warm-up: as asked, at most a second short of the step and never below
	 * zero.
	 * @return seconds
	 */
	public double warmupSeconds() {
		return Math.max(0.0, Math.min(this.warmupS, stepSeconds() - 1));
	}

	/**
	 * This sweep over other loads.
	 * @param loads the range swept
	 * @return the request
	 */
	public SweepRequest withRange(SweepRange loads) {
		return new SweepRequest(this.scenarioId, loads, this.steps, this.durationS, this.warmupS, this.limits);
	}

	/**
	 * This sweep with another number of loads.
	 * @param count how many
	 * @return the request
	 */
	public SweepRequest withSteps(int count) {
		return new SweepRequest(this.scenarioId, this.range, count, this.durationS, this.warmupS, this.limits);
	}

	/**
	 * This sweep with steps of another length.
	 * @param durationSeconds how long each step runs
	 * @param warmupSeconds each step's warm-up
	 * @return the request
	 */
	public SweepRequest withTiming(double durationSeconds, double warmupSeconds) {
		return new SweepRequest(this.scenarioId, this.range, this.steps, durationSeconds, warmupSeconds, this.limits);
	}

	/**
	 * This sweep within other limits.
	 * @param bounds the limits each step's run is held to
	 * @return the request
	 */
	public SweepRequest withLimits(SimulationLimits bounds) {
		return new SweepRequest(this.scenarioId, this.range, this.steps, this.durationS, this.warmupS, bounds);
	}

}

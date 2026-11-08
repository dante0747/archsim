package io.archsim.engine.simulator;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.simulation.SimulationLimits;

/**
 * What a run simulates of a design, and how.
 *
 * @param scenarioId the scenario to simulate; {@code null} for the design as drawn
 * @param seed the seed that replaces the workload's once the scenario is applied, as a
 * seed group's runs do; {@code null} for the workload's own
 * @param mode whether the run is simulated whole or may be answered from a sample
 * @param limits the most requests and wall-clock time the run may take
 */
public record RunRequest(@Nullable String scenarioId, @Nullable Long seed, SimulationMode mode,
		SimulationLimits limits) {

	/**
	 * The whole run of a design or one of its scenarios, with the workload's seed, within
	 * the engine's limits.
	 * @param scenarioId the scenario; {@code null} for the design as drawn
	 * @return the request
	 */
	public static RunRequest of(@Nullable String scenarioId) {
		return new RunRequest(scenarioId, null, SimulationMode.FULL, SimulationLimits.DEFAULT);
	}

	/**
	 * This run with another seed.
	 * @param replacement the seed that replaces the workload's
	 * @return the request
	 */
	public RunRequest withSeed(long replacement) {
		return new RunRequest(this.scenarioId, replacement, this.mode, this.limits);
	}

	/**
	 * This run in another mode.
	 * @param simulation whether it is simulated whole or may be answered from a sample
	 * @return the request
	 */
	public RunRequest withMode(SimulationMode simulation) {
		return new RunRequest(this.scenarioId, this.seed, simulation, this.limits);
	}

	/**
	 * This run within other limits.
	 * @param bounds the most requests and wall-clock time it may take
	 * @return the request
	 */
	public RunRequest withLimits(SimulationLimits bounds) {
		return new RunRequest(this.scenarioId, this.seed, this.mode, bounds);
	}

}

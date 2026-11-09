package io.archsim.app.worker;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.SimulationMode;

/**
 * A run a worker claimed, with what its simulation needs, read from its row.
 *
 * @param id the run
 * @param document its design, as the API queued it: upgraded, not resolved
 * @param scenarioId the scenario to simulate, or {@code null} for the design itself
 * @param attempts how many times it has been claimed, this time included
 * @param maxRequests its workspace's limit on requests when it was queued, or
 * {@code null} for the worker's
 * @param maxWallSeconds its workspace's limit on wall-clock seconds, or {@code null} for
 * the worker's
 * @param seed the seed to simulate with
 * @param mode a whole run, or a quick check
 */
record ClaimedRun(String id, JsonNode document, @Nullable String scenarioId, int attempts, @Nullable Long maxRequests,
		@Nullable Double maxWallSeconds, long seed, SimulationMode mode) {

	/**
	 * The limits the run simulates under: the worker's, tightened by the run's own.
	 * @param worker the worker's limits
	 * @return the tighter of each
	 */
	SimulationLimits limits(SimulationLimits worker) {
		return new SimulationLimits(
				(this.maxRequests != null) ? Math.min(worker.maxRequests(), this.maxRequests) : worker.maxRequests(),
				(this.maxWallSeconds != null) ? Math.min(worker.maxWallSeconds(), this.maxWallSeconds)
						: worker.maxWallSeconds());
	}

	/**
	 * What the simulator is asked: the run's scenario, seed and mode, under limits.
	 * @param limits the limits it simulates under
	 * @return the request
	 */
	RunRequest request(SimulationLimits limits) {
		return RunRequest.of(this.scenarioId).withSeed(this.seed).withMode(this.mode).withLimits(limits);
	}

}

package io.archsim.app.worker;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

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

}

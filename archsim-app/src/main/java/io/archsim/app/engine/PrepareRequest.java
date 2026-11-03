package io.archsim.app.engine;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A run to prepare ({@code prepare_run}): a design, its scenario, the run's limits and
 * the seed that replaces the design's.
 *
 * @param document the design
 * @param scenarioId the scenario to simulate, or {@code null} for the design itself
 * @param maxRequests the most requests the run may simulate
 * @param maxWallSeconds the most wall-clock time it may take
 * @param seed the seed to simulate with, or {@code null} for the design's
 */
public record PrepareRequest(JsonNode document, @Nullable String scenarioId, long maxRequests, double maxWallSeconds,
		@Nullable Long seed) {

}

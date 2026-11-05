package io.archsim.app.runs;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.run.QuickCheck;

/**
 * A run planned to be queued: the design to store with it, the scenario it simulates and
 * the seed it runs with, and whether a quick check can answer it.
 *
 * @param scenarioId the scenario, if any
 * @param scenarioName its name
 * @param seed the seed the run simulates with
 * @param document the design to store, upgraded to the current schema
 * @param quickCheck whether sampled mode can answer the run
 */
record PlannedRun(@Nullable String scenarioId, @Nullable String scenarioName, long seed, ObjectNode document,
		QuickCheck quickCheck) {

}

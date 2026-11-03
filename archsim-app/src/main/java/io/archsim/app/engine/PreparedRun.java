package io.archsim.app.engine;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * A run the engine prepared to be queued ({@code prepare_run}): the design to store with
 * it, the scenario it simulates and the seed it runs with, and whether a quick check can
 * answer it. Read from the engine service's answer by the model's codec, so an answer
 * missing a field fails at once.
 *
 * @param scenarioId the scenario, if any
 * @param scenarioName its name
 * @param seed the seed the run simulates with
 * @param document the design to store, upgraded to the current schema
 * @param quickCheck whether sampled mode can answer the run
 */
public record PreparedRun(@Nullable String scenarioId, @Nullable String scenarioName, long seed, ObjectNode document,
		QuickCheck quickCheck) {

	/**
	 * Whether a quick check (sampled mode) can answer a run ({@code quick_check}).
	 *
	 * @param eligible whether it can
	 * @param reason why not, or what it will simulate
	 * @param requestedS the run's length
	 * @param simulatedS how much a quick check simulates, when it can
	 * @param sampleS the steady sample it measures, when it can
	 */
	public record QuickCheck(boolean eligible, String reason, double requestedS, @Nullable Double simulatedS,
			@Nullable Double sampleS) {

	}

}

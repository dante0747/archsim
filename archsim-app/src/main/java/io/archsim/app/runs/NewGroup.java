package io.archsim.app.runs;

import org.jspecify.annotations.Nullable;

/**
 * A seed group to start: one run per seed, from a base seed on.
 *
 * @param destination its project and workspace
 * @param baseSeed the first run's seed
 * @param size how many runs
 * @param mode {@code full} or {@code sampled}
 * @param scenarioId the scenario its runs simulate, if any
 * @param label what it is called
 */
record NewGroup(Destination destination, long baseSeed, int size, RunMode mode, @Nullable String scenarioId,
		String label) {

}

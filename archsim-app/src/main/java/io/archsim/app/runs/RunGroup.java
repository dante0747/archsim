package io.archsim.app.runs;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Runs of one input started together: one per seed, from a base seed on.
 *
 * @param id the group's id
 * @param workspaceId its project's workspace
 * @param projectId its project
 * @param kind {@code seeds}
 * @param baseSeed the first run's seed
 * @param size how many runs
 * @param mode {@code full} or {@code sampled}
 * @param scenarioId the scenario its runs simulate, if any
 * @param label what it is called
 * @param createdBy who started it
 * @param createdAt when
 */
record RunGroup(String id, String workspaceId, String projectId, String kind, long baseSeed, int size, String mode,
		@Nullable String scenarioId, @Nullable String label, @Nullable String createdBy, Instant createdAt) {

}

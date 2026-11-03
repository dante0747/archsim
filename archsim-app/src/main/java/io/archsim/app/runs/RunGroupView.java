package io.archsim.app.runs;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A seed group as people read it: its runs and how they agree.
 *
 * @param id the group's id
 * @param projectId its project
 * @param kind {@code seeds} or {@code sweep}
 * @param baseSeed the first run's seed
 * @param size how many runs
 * @param mode {@code full} or {@code sampled}
 * @param scenarioId the scenario its runs simulate, if any
 * @param label what it is called
 * @param createdAt when it started
 * @param status {@code running} while a run is queued or running; then {@code succeeded}
 * when every run did, {@code failed} when none did, else {@code partial}
 * @param runs its runs, in seed order
 * @param summary {@code summarize_seeds} of the finished runs
 */
record RunGroupView(String id, String projectId, String kind, long baseSeed, int size, String mode,
		@Nullable String scenarioId, @Nullable String label, Instant createdAt, String status, List<RunView> runs,
		JsonNode summary) {

}

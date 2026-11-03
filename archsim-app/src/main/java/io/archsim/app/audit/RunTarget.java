package io.archsim.app.audit;

import org.jspecify.annotations.Nullable;

/**
 * A run a change was made to, as the history names it.
 *
 * @param id the run's id
 * @param workspaceId its workspace
 * @param projectId its project
 * @param label what it is called
 * @param scenarioName the scenario it simulates, if any
 */
public record RunTarget(String id, String workspaceId, String projectId, @Nullable String label,
		@Nullable String scenarioName) {

}

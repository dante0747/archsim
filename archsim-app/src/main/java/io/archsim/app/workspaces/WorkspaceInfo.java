package io.archsim.app.workspaces;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A workspace as stored: its name, kind and own limits.
 *
 * @param id the workspace's id
 * @param name its name
 * @param kind {@code personal}, {@code team} or {@code sandbox}
 * @param maxConcurrentRuns its own limit of runs at once, if it has one
 * @param maxRunsPerHour its own limit of runs per hour, if it has one
 * @param createdAt when it was made
 */
public record WorkspaceInfo(String id, String name, String kind, @Nullable Integer maxConcurrentRuns,
		@Nullable Integer maxRunsPerHour, Instant createdAt) {

}

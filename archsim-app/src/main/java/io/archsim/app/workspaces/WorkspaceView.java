package io.archsim.app.workspaces;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A workspace as its members see it.
 *
 * @param id the workspace's id
 * @param name its name
 * @param kind {@code personal}, {@code team} or {@code sandbox}
 * @param role the asker's role in it
 * @param maxConcurrentRuns runs simulated at once: its own limit, else its kind's
 * ({@code null}: no limit)
 * @param maxRunsPerHour runs it may start per hour, the same way ({@code null}: no limit)
 * @param createdAt when it was made
 */
public record WorkspaceView(String id, String name, String kind, String role, @Nullable Integer maxConcurrentRuns,
		@Nullable Integer maxRunsPerHour, Instant createdAt) {

}

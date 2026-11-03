package io.archsim.app.workspaces;

import org.jspecify.annotations.Nullable;

/**
 * A workspace's name, kind and own limits.
 *
 * @param id the workspace's id
 * @param name its name
 * @param kind {@code personal}, {@code team} or {@code sandbox}
 * @param maxRunsPerHour its own limit of runs per hour, if it has one
 */
public record WorkspaceInfo(String id, String name, String kind, @Nullable Integer maxRunsPerHour) {

}

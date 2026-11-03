package io.archsim.app.runs;

import io.archsim.app.limits.WorkspaceLimits;

/**
 * Where a run is queued, and the limits it is queued with.
 *
 * @param projectId its project
 * @param workspaceId the project's workspace
 * @param limits the run's own limits, its workspace's when it is queued
 */
record Destination(String projectId, String workspaceId, WorkspaceLimits.RunLimits limits) {

}

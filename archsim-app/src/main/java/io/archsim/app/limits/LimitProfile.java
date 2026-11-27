package io.archsim.app.limits;

import org.jspecify.annotations.Nullable;

/**
 * What a kind of workspace may use. An empty limit is none, or the server's own setting
 * where it has one.
 *
 * @param maxConcurrentRuns runs simulated at once (empty: as many as the workers have
 * slots)
 * @param runsPerHour runs started per hour
 * @param maxRequests expected requests in one run (empty: {@code ARCHSIM_MAX_REQUESTS})
 * @param maxWallSeconds wall-clock seconds one run may take (empty:
 * {@code ARCHSIM_MAX_WALL_SECONDS})
 * @param calibrateWallSeconds wall-clock seconds of the replay inside
 * {@code POST /calibrate}, which answers while the request waits
 * @param maxProjects projects the workspace holds
 * @param maxStoredBytes bytes the workspace stores: its projects' designs, its runs'
 * designs and reports, and its share links' copies (ADR-0109)
 * @param maxSeeds runs in one seed group
 */
public record LimitProfile(@Nullable Integer maxConcurrentRuns, @Nullable Integer runsPerHour,
		@Nullable Long maxRequests, @Nullable Double maxWallSeconds, @Nullable Double calibrateWallSeconds,
		@Nullable Integer maxProjects, @Nullable Long maxStoredBytes, int maxSeeds) {

}

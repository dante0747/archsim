package io.archsim.app.runs;

import org.springframework.stereotype.Component;

import io.archsim.app.access.Principal;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.app.web.ApiException;
import io.archsim.app.workspaces.WorkspaceInfo;

/**
 * Counts new runs against the hourly limits: each person's, and each workspace's (its
 * own, else its kind's).
 */
@Component
class RunCharges {

	private final RateLimits rateLimits;

	private final WorkspaceLimits workspaceLimits;

	RunCharges(RateLimits rateLimits, WorkspaceLimits workspaceLimits) {
		this.rateLimits = rateLimits;
		this.workspaceLimits = workspaceLimits;
	}

	/**
	 * Counts runs about to start, all or none.
	 * @param workspace where they start
	 * @param principal who starts them
	 * @param runs how many
	 * @throws ApiException {@code 429} when they do not fit in this hour
	 */
	void charge(WorkspaceInfo workspace, Principal principal, int runs) {
		RateLimit perUser = RateLimit.RUNS_PER_USER;
		this.rateLimits.enforce("user:" + principal.userId() + ":runs", perUser,
				"You have started " + perUser.limit() + " runs this hour, the most one person may.", runs);
		this.workspaceLimits.runsPerHour(workspace.kind(), workspace.maxRunsPerHour())
			.ifPresent((perWorkspace) -> this.rateLimits.enforce("ws:" + workspace.id() + ":runs",
					new RateLimit(perWorkspace, RateLimit.HOUR),
					workspace.name() + " has started its " + perWorkspace + " runs for this hour.", runs));
	}

}

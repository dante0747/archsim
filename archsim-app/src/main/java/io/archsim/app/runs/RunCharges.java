package io.archsim.app.runs;

import java.util.List;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;

import io.archsim.app.access.Principal;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.limits.RateLimits.Charge;
import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.app.web.ApiException;
import io.archsim.app.workspaces.WorkspaceInfo;

/**
 * Counts new runs against the hourly limits: each person's, and each workspace's (its
 * own, else its kind's), together, so runs one limit refuses cost the other nothing.
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
		OptionalInt perWorkspace = this.workspaceLimits.runsPerHour(workspace.kind(), workspace.maxRunsPerHour());
		Charge person = personsCharge(principal);
		// the person's counter always first: run charges lock counters in one order
		List<Charge> charges = perWorkspace.isPresent()
				? List.of(person, workspacesCharge(workspace, perWorkspace.getAsInt())) : List.of(person);
		this.rateLimits.enforceAll(charges, runs);
	}

	private static Charge personsCharge(Principal principal) {
		RateLimit perUser = RateLimit.RUNS_PER_USER;
		return new Charge("user:" + principal.userId() + ":runs", perUser,
				"You have started " + perUser.limit() + " runs this hour, the most one person may.");
	}

	private static Charge workspacesCharge(WorkspaceInfo workspace, int perWorkspace) {
		return new Charge("ws:" + workspace.id() + ":runs", new RateLimit(perWorkspace, RateLimit.HOUR),
				workspace.name() + " has started its " + perWorkspace + " runs for this hour.");
	}

}

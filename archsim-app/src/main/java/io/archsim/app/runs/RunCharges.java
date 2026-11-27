package io.archsim.app.runs;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.archsim.app.access.Principal;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.limits.RateLimits.Charge;
import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.app.web.ApiException;
import io.archsim.app.workspaces.WorkspaceInfo;

/**
 * Counts new runs against the hourly limits: each person's; in a sandbox, its address's
 * and all sandboxes' together (ADR-0106); and each workspace's, its own else its kind's.
 * They are counted together, so runs one limit refuses cost the others nothing.
 */
@Component
class RunCharges {

	/** All sandboxes' runs together: one counter, whatever address they come from. */
	private static final Charge SANDBOXES = new Charge("sandboxes:runs", RateLimit.SANDBOX_RUNS,
			"Sandboxes have started " + RateLimit.SANDBOX_RUNS.limit()
					+ " runs this hour, the most they may together: sign in to run without waiting.");

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
		this.rateLimits.enforceAll(charges(workspace, principal), runs);
	}

	/**
	 * The counters a run counts against, in the order of kinds every charge locks them in
	 * (see {@link RateLimits#enforceAll}): the person's; in a sandbox, its address's,
	 * then all sandboxes'; then the workspace's, when it has a limit.
	 * @param workspace where the run starts
	 * @param principal who starts it
	 * @return the charges, at most one of each kind
	 */
	List<Charge> charges(WorkspaceInfo workspace, Principal principal) {
		List<Charge> charges = new ArrayList<>();
		charges.add(personsCharge(principal));
		if (WorkspaceLimits.isSandbox(workspace.kind())) {
			charges.add(addressCharge(principal));
			charges.add(SANDBOXES);
		}
		this.workspaceLimits.runsPerHour(workspace.kind(), workspace.maxRunsPerHour())
			.ifPresent((limit) -> charges.add(workspacesCharge(workspace, limit)));
		return List.copyOf(charges);
	}

	private static Charge personsCharge(Principal principal) {
		RateLimit perUser = RateLimit.RUNS_PER_USER;
		return new Charge("user:" + principal.userId() + ":runs", perUser,
				"You have started " + perUser.limit() + " runs this hour, the most one person may.");
	}

	/**
	 * What a sandbox's runs cost their address, counted as every limit per address counts
	 * it, however many sandboxes it holds.
	 * @param principal who starts them
	 * @return the charge
	 */
	private static Charge addressCharge(Principal principal) {
		RateLimit perAddress = RateLimit.SANDBOX_RUNS_PER_IP;
		String refusal = "Sandboxes from your address have started " + perAddress.limit()
				+ " runs this hour, the most one address may.";
		return new Charge("ip:" + principal.countedAddress() + ":sandbox-runs", perAddress, refusal);
	}

	private static Charge workspacesCharge(WorkspaceInfo workspace, int perWorkspace) {
		return new Charge("ws:" + workspace.id() + ":runs", new RateLimit(perWorkspace, RateLimit.HOUR),
				workspace.name() + " has started its " + perWorkspace + " runs for this hour.");
	}

}

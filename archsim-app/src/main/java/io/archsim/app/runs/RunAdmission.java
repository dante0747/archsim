package io.archsim.app.runs;

import java.util.List;

import org.springframework.stereotype.Component;

import io.archsim.app.access.Principal;
import io.archsim.app.web.ApiException;
import io.archsim.app.workspaces.WorkspaceInfo;

/**
 * Admits prepared runs: counts them against the hourly limits, then queues them. Only
 * runs that were prepared and checked get here, so a refused run is never counted.
 */
@Component
class RunAdmission {

	private final RunCharges charges;

	private final RunQueue queue;

	RunAdmission(RunCharges charges, RunQueue queue) {
		this.charges = charges;
		this.queue = queue;
	}

	/**
	 * Counts and queues runs, all or none.
	 * @param workspace where they start
	 * @param principal who starts them
	 * @param runs the runs
	 * @return the queued runs, in order
	 * @throws ApiException {@code 429} when they do not fit in this hour
	 */
	List<RunView> admit(WorkspaceInfo workspace, Principal principal, List<NewRun> runs) {
		this.charges.charge(workspace, principal, runs.size());
		return this.queue.enqueue(runs, principal);
	}

	/**
	 * Counts and queues a seed group's runs with the group, all or none.
	 * @param workspace where they start
	 * @param principal who starts them
	 * @param group the group
	 * @param runs its runs, in seed order
	 * @return the queued runs, in seed order
	 * @throws ApiException {@code 429} when they do not fit in this hour
	 */
	List<RunView> admitGroup(WorkspaceInfo workspace, Principal principal, NewGroup group, List<NewRun> runs) {
		this.charges.charge(workspace, principal, runs.size());
		return this.queue.enqueueGroup(group, runs, principal);
	}

}

package io.archsim.app.runs;

import java.util.List;

import org.springframework.stereotype.Component;

import io.archsim.app.access.Principal;
import io.archsim.app.limits.WorkspaceStorage;
import io.archsim.app.web.ApiException;
import io.archsim.app.workspaces.WorkspaceInfo;

/**
 * Admits prepared runs: refuses them in a workspace that stores its quota or more (each
 * run stores its report, and its design unless a run of it did, ADR-0109), counts them
 * against the hourly limits, then queues them. Only runs that were prepared and checked
 * get here, so a run refused before it is counted never is. The runs are queued in a
 * transaction that checks the quota again with the workspace locked
 * ({@link WorkspaceStorage#writeInTurn}), so they see what writes made at once stored;
 * runs that second check refuses have been counted.
 */
@Component
class RunAdmission {

	private final WorkspaceStorage storage;

	private final RunCharges charges;

	private final RunQueue queue;

	RunAdmission(WorkspaceStorage storage, RunCharges charges, RunQueue queue) {
		this.storage = storage;
		this.charges = charges;
		this.queue = queue;
	}

	/**
	 * Counts and queues runs, all or none.
	 * @param workspace where they start
	 * @param principal who starts them
	 * @param runs the runs
	 * @return the queued runs, in order
	 * @throws ApiException {@code 409} when the workspace stores its quota or more,
	 * {@code 429} when they do not fit in this hour
	 */
	List<RunView> admit(WorkspaceInfo workspace, Principal principal, List<NewRun> runs) {
		this.storage.requireRoom(workspace.id(), workspace.kind());
		this.charges.charge(workspace, principal, runs.size());
		return this.storage.writeInTurn(workspace.id(), () -> this.queue.enqueue(runs, principal));
	}

	/**
	 * Counts and queues a seed group's runs with the group, all or none.
	 * @param workspace where they start
	 * @param principal who starts them
	 * @param group the group
	 * @param runs its runs, in seed order
	 * @return the queued runs, in seed order
	 * @throws ApiException {@code 409} when the workspace stores its quota or more,
	 * {@code 429} when they do not fit in this hour
	 */
	List<RunView> admitGroup(WorkspaceInfo workspace, Principal principal, NewGroup group, List<NewRun> runs) {
		this.storage.requireRoom(workspace.id(), workspace.kind());
		this.charges.charge(workspace, principal, runs.size());
		return this.storage.writeInTurn(workspace.id(), () -> this.queue.enqueueGroup(group, runs, principal));
	}

}

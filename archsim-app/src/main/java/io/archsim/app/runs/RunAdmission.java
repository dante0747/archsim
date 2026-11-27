package io.archsim.app.runs;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import io.archsim.app.access.Principal;
import io.archsim.app.limits.WorkspaceStorage;
import io.archsim.app.web.ApiException;
import io.archsim.app.workspaces.WorkspaceInfo;

/**
 * Admits prepared runs: refuses them in a workspace that stores its quota or more (each
 * run stores its design and its report, ADR-0109), counts them against the hourly limits,
 * then queues them. Only runs that were prepared and checked get here, so a run refused
 * before it is counted never is. The runs are queued in a transaction that checks the
 * quota again with the workspace locked, so they see what writes made at once stored;
 * runs that second check refuses have been counted.
 */
@Component
class RunAdmission {

	private final WorkspaceStorage storage;

	private final RunCharges charges;

	private final RunQueue queue;

	private final TransactionOperations transactions;

	RunAdmission(WorkspaceStorage storage, RunCharges charges, RunQueue queue, TransactionOperations transactions) {
		this.storage = storage;
		this.charges = charges;
		this.queue = queue;
		this.transactions = transactions;
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
		return queueInTurn(workspace, () -> this.queue.enqueue(runs, principal));
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
		return queueInTurn(workspace, () -> this.queue.enqueueGroup(group, runs, principal));
	}

	/**
	 * Queues runs after checking the workspace's quota again, with the workspace locked.
	 * @param workspace where they start
	 * @param queueing what queues them
	 * @return the queued runs
	 * @throws ApiException {@code 409} when the workspace stores its quota or more
	 */
	private List<RunView> queueInTurn(WorkspaceInfo workspace, Supplier<List<RunView>> queueing) {
		// one transaction, written out: the workspace's lock it holds until it ends
		// lets the check see what writes made at once stored before it
		return Objects.requireNonNull(this.transactions.execute((status) -> {
			this.storage.requireRoomInTurn(workspace.id());
			return queueing.get();
		}));
	}

}

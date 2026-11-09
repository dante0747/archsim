package io.archsim.app.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import io.archsim.app.limits.WorkspaceLimits;

/**
 * Claims on the run queue, as the Python worker made them (ADR-0053): the oldest runs
 * first, but every workspace's oldest before anyone's second, each workspace under its
 * cap of runs at once counted on every worker, the caps those the API admits runs by.
 * Claims take turns on an advisory lock, in one transaction each, so two never take one
 * run or count past a cap.
 */
@Component
@Profile("worker")
class RunClaims {

	private final ClaimRepository repository;

	private final WorkspaceLimits limits;

	private final TransactionOperations transactions;

	private final Duration lease;

	RunClaims(ClaimRepository repository, WorkspaceLimits limits, TransactionOperations transactions,
			WorkerProperties settings) {
		this.repository = repository;
		this.limits = limits;
		this.transactions = transactions;
		this.lease = settings.lease();
	}

	/**
	 * Takes queued runs for a worker: they become running under its lease, started now,
	 * one more attempt counted.
	 * @param request the worker, its free slots and the runs it must not take
	 * @param now the time
	 * @return the runs, with what their simulations need; none when nothing is free
	 */
	List<ClaimedRun> claim(ClaimRequest request, Instant now) {
		if (request.limit() <= 0) {
			return List.of();
		}
		// one transaction, written out: the advisory lock it holds until it ends
		// keeps claims correct
		return Objects.requireNonNull(this.transactions.execute((status) -> claimInTurn(request, now)));
	}

	private List<ClaimedRun> claimInTurn(ClaimRequest request, Instant now) {
		this.repository.takeTurn();
		List<Candidate> candidates = this.repository.candidates(request.limit(), request.exclude());
		if (candidates.isEmpty()) {
			return List.of();
		}
		Set<String> workspaces = candidates.stream().map(Candidate::workspaceId).collect(Collectors.toSet());
		List<String> picked = FairPick.pick(candidates, caps(workspaces), this.repository.running(workspaces),
				request.limit());
		if (picked.isEmpty()) {
			return List.of();
		}
		this.repository.lease(picked, request.workerId(), now, now.plus(this.lease));
		return this.repository.claimed(picked, request.workerId());
	}

	// the workspaces that have a cap of runs at once, with their caps
	private Map<String, Integer> caps(Set<String> workspaces) {
		Map<String, Integer> caps = new HashMap<>();
		for (ClaimRepository.WorkspaceCap workspace : this.repository.caps(workspaces)) {
			this.limits.maxConcurrentRuns(workspace.kind(), workspace.maxConcurrentRuns())
				.ifPresent((cap) -> caps.put(workspace.id(), cap));
		}
		return caps;
	}

}

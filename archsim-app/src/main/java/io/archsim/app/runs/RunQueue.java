package io.archsim.app.runs;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Principal;
import io.archsim.app.audit.AuditLog;
import io.archsim.app.audit.RunTarget;
import io.archsim.app.store.Ids;
import io.archsim.app.store.Times;

/**
 * The run queue, as the API uses it: runs are queued and asked to stop through the
 * database, so any worker takes them and any instance can stop them. Each change is
 * written with its entry in the history, all or nothing.
 */
@Component
class RunQueue {

	private final RunRepository runs;

	private final RunGroupRepository groups;

	private final AuditLog audit;

	private final Clock clock;

	RunQueue(RunRepository runs, RunGroupRepository groups, AuditLog audit, Clock clock) {
		this.runs = runs;
		this.groups = groups;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Queues runs, all or none.
	 * @param queued the runs
	 * @param principal who starts them
	 * @return the queued runs, in order
	 */
	@Transactional
	List<RunView> enqueue(List<NewRun> queued, Principal principal) {
		return queued.stream().map((run) -> add(run, principal, null)).toList();
	}

	/**
	 * Queues a seed group with its runs, all or none.
	 * @param group the group
	 * @param queued its runs, in seed order
	 * @param principal who starts them
	 * @return the queued runs, in seed order
	 */
	@Transactional
	List<RunView> enqueueGroup(NewGroup group, List<NewRun> queued, Principal principal) {
		String groupId = Ids.newId();
		Destination destination = group.destination();
		this.groups
			.add(new RunGroup(groupId, destination.workspaceId(), destination.projectId(), "seeds", group.baseSeed(),
					group.size(), group.mode().json(), group.scenarioId(), group.label(), principal.userId(), now()));
		return IntStream.range(0, queued.size())
			.mapToObj((index) -> add(queued.get(index), principal, new GroupPlace(groupId, index)))
			.toList();
	}

	/**
	 * Asks a run to stop, if a worker still has it to do.
	 * @param run the run
	 * @param principal who asks
	 * @return the run as it is now
	 */
	@Transactional
	RunView cancel(StoredRun run, Principal principal) {
		if (run.view().active()) {
			this.runs.requestCancel(run.id(), now());
			this.audit.run("run.cancel", target(run.workspaceId(), run.view()), principal);
		}
		return this.runs.find(run.id()).map(StoredRun::view).orElse(run.view());
	}

	/**
	 * Deletes a run, stopping it first if it is active.
	 * @param run the run
	 * @param principal who deletes it
	 */
	@Transactional
	void delete(StoredRun run, Principal principal) {
		if (run.view().active()) {
			this.runs.requestCancel(run.id(), now());
		}
		this.audit.run("run.delete", target(run.workspaceId(), run.view()), principal);
		this.runs.delete(run.id());
	}

	private RunView add(NewRun run, Principal principal, @Nullable GroupPlace group) {
		String id = Ids.newId();
		this.runs.add(new QueuedRun(id, run, principal.userId(), group, now()));
		RunView queued = this.runs.find(id).orElseThrow().view();
		this.audit.run("run.create", target(run.destination().workspaceId(), queued), principal);
		return queued;
	}

	private static RunTarget target(String workspaceId, RunView run) {
		return new RunTarget(run.id(), workspaceId, run.projectId(), run.label(), run.scenarioName());
	}

	private Instant now() {
		return Times.now(this.clock);
	}

}

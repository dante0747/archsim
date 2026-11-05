package io.archsim.app.runs;

import java.util.List;

import org.springframework.stereotype.Service;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.web.ApiException;
import io.archsim.engine.seeds.SeedRun;
import io.archsim.engine.seeds.SeedSummary;
import io.archsim.engine.slo.StoredObjectives;
import io.archsim.model.document.Objectives;

/**
 * Seed groups: the runs of one input started together, read back with the engine's
 * summary of the finished ones against the objectives of the design they ran: the verdict
 * split, latency percentiles, success and throughput ranges, and whether the runs
 * disagree. A group belongs to its project's workspace.
 */
@Service
class RunGroups {

	private final RunGroupRepository groups;

	private final RunRepository runs;

	private final Access access;

	RunGroups(RunGroupRepository groups, RunRepository runs, Access access) {
		this.groups = groups;
		this.runs = runs;
		this.access = access;
	}

	/**
	 * A group with its runs and their summary.
	 * @param principal who asks
	 * @param groupId the group
	 * @return the group
	 * @throws ApiException {@code 404} without a role in its workspace
	 */
	RunGroupView get(Principal principal, String groupId) {
		String what = "Run group '" + groupId + "'";
		RunGroup group = this.groups.find(groupId).orElseThrow(() -> Access.notFound(what));
		this.access.require(principal, group.workspaceId(), WorkspaceRole.VIEWER, what);
		List<RunView> members = this.runs.ofGroup(group.id());
		SeedSummary summary = SeedSummary.of(members.stream()
			.map((run) -> SeedRun.read(run.seed(), run.succeeded() ? run.summary() : null))
			.toList(), objectives(members));
		return new RunGroupView(group.id(), group.projectId(), "sweep".equals(group.kind()) ? "sweep" : "seeds",
				group.baseSeed(), group.size(), "sampled".equals(group.mode()) ? "sampled" : "full", group.scenarioId(),
				group.label(), group.createdAt(), status(members), members, summary.toJson());
	}

	/**
	 * The objectives of the design the group's runs simulated.
	 * @param members the group's runs
	 * @return the objectives as the design stored with its first run sets them; none
	 * without runs
	 */
	private Objectives objectives(List<RunView> members) {
		if (members.isEmpty()) {
			return StoredObjectives.read(null);
		}
		return StoredObjectives.read(this.runs.objectives(members.getFirst().id()).orElse(null));
	}

	private static String status(List<RunView> runs) {
		if (runs.stream().anyMatch(RunView::active)) {
			return "running";
		}
		long succeeded = runs.stream().filter(RunView::succeeded).count();
		if (!runs.isEmpty() && succeeded == runs.size()) {
			return "succeeded";
		}
		return (succeeded > 0) ? "partial" : "failed";
	}

}

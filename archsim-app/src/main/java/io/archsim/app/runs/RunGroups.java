package io.archsim.app.runs;

import java.util.List;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.engine.EngineService;
import io.archsim.app.web.ApiException;

/**
 * Seed groups: the runs of one input started together, read back with the engine's
 * summary of the finished ones against the objectives of the design they ran: the verdict
 * split, latency percentiles, success and throughput ranges, and whether the runs
 * disagree. A group belongs to its project's workspace.
 */
@Service
class RunGroups {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final RunGroupRepository groups;

	private final RunRepository runs;

	private final Access access;

	private final EngineService engine;

	RunGroups(RunGroupRepository groups, RunRepository runs, Access access, EngineService engine) {
		this.groups = groups;
		this.runs = runs;
		this.access = access;
		this.engine = engine;
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
		JsonNode objectives = members.isEmpty() ? NODES.objectNode()
				: this.runs.objectives(members.getFirst().id()).orElseGet(NODES::objectNode);
		ArrayNode finished = NODES.arrayNode();
		members.forEach((run) -> finished.addObject()
			.put("seed", run.seed())
			.set("summary", run.succeeded() ? run.summary() : null));
		return new RunGroupView(group.id(), group.projectId(), "sweep".equals(group.kind()) ? "sweep" : "seeds",
				group.baseSeed(), group.size(), "sampled".equals(group.mode()) ? "sampled" : "full", group.scenarioId(),
				group.label(), group.createdAt(), status(members), members,
				this.engine.summarizeSeeds(finished, objectives));
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

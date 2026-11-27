package io.archsim.app.runs;

import java.util.List;

import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.projects.ProjectDesign;
import io.archsim.app.projects.ProjectService;
import io.archsim.app.web.ApiException;

/**
 * Starts a run of a project's design, or of a design the request carries; with
 * {@code seeds} above 1, a seed group. Everything is prepared and checked before anything
 * is counted against the hourly limits or queued.
 */
@Service
class RunStarts {

	private final ProjectService projects;

	private final RunPlanner planner;

	private final SeedGroups seedGroups;

	private final RunAdmission admission;

	RunStarts(ProjectService projects, RunPlanner planner, SeedGroups seedGroups, RunAdmission admission) {
		this.projects = projects;
		this.planner = planner;
		this.seedGroups = seedGroups;
		this.admission = admission;
	}

	/**
	 * Starts a run, or a seed group.
	 * @param principal who starts it
	 * @param projectId the project
	 * @param request what to run
	 * @return the run, or the group's first run
	 * @throws ApiException {@code 409} when the workspace stores its quota or more,
	 * {@code 422} when the design cannot be simulated or the group is too big,
	 * {@code 429} over the hourly limits
	 */
	RunView create(Principal principal, String projectId, RunCreate request) {
		RunStart start = prepare(principal, projectId, request);
		if (request.seeds() > 1) {
			return this.seedGroups.start(principal, start, request);
		}
		String label = RunLabels.of(request.label(), start.first(), request.mode());
		NewRun run = new NewRun(start.destination(), start.first(), label, request.mode());
		return this.admission.admit(start.plan().workspace(), principal, List.of(run)).getFirst();
	}

	private RunStart prepare(Principal principal, String projectId, RunCreate request) {
		ProjectDesign project = this.projects.design(principal, projectId, WorkspaceRole.EDITOR);
		RunPlanner.Plan plan = this.planner.plan(project.workspaceId());
		ObjectNode document = (request.document() != null) ? request.document() : project.document();
		PlannedRun first = this.planner.prepare(plan.request(document, request.scenarioId(), null));
		if (request.mode() == RunMode.SAMPLED) {
			RunPlanner.requireQuickCheck(first);
		}
		return new RunStart(plan, new Destination(project.id(), project.workspaceId(), plan.limits()), document, first);
	}

}

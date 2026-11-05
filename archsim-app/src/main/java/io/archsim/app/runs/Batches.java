package io.archsim.app.runs;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.projects.ProjectDesign;
import io.archsim.app.projects.ProjectService;
import io.archsim.app.web.ApiException;

/**
 * Batches: several scenarios of one design started together. Every scenario is prepared
 * before any is counted or queued, and they are queued in one transaction.
 */
@Service
class Batches {

	private final ProjectService projects;

	private final RunPlanner planner;

	private final RunAdmission admission;

	Batches(ProjectService projects, RunPlanner planner, RunAdmission admission) {
		this.projects = projects;
		this.planner = planner;
		this.admission = admission;
	}

	/**
	 * Starts several scenarios of one design.
	 * @param principal who starts them
	 * @param projectId the project
	 * @param request the scenarios, and the design if not the saved one
	 * @return the runs, in the scenarios' order
	 * @throws ApiException {@code 422} naming a scenario that cannot be simulated, before
	 * any is queued
	 */
	List<RunView> start(Principal principal, String projectId, BatchRunCreate request) {
		ProjectDesign project = this.projects.design(principal, projectId, WorkspaceRole.EDITOR);
		RunPlanner.Plan plan = this.planner.plan(project.workspaceId());
		ObjectNode document = (request.document() != null) ? request.document() : project.document();
		Destination destination = new Destination(project.id(), project.workspaceId(), plan.limits());
		List<NewRun> runs = new ArrayList<>();
		for (@Nullable
		String scenario : request.scenarioIds()) {
			PlannedRun prepared = this.planner.prepareScenario(plan.request(document, scenario, null));
			runs.add(new NewRun(destination, prepared, RunLabels.of(null, prepared, RunMode.FULL), RunMode.FULL));
		}
		return this.admission.admit(plan.workspace(), principal, runs);
	}

}
